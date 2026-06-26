package com.example.globalpeq;

import java.util.ArrayList;
import java.util.List;

final class PcmDspProcessor {
    private final List<Biquad> filters = new ArrayList<>();
    private int sampleRate = 48000;
    private int channelCount = 2;
    private float pregain = 1f;
    private VirtualBass virtualBass = new VirtualBass(48000, 2);
    private HarmonicBassEnhancer harmonicBass = new HarmonicBassEnhancer(48000, 2);
    private AlgorithmicReverb reverb = new AlgorithmicReverb(48000, 2);

    void configure(Preset preset, int nextSampleRate, int nextChannelCount) {
        configure(preset, nextSampleRate, nextChannelCount, false, AdvancedModeConfig.DEFAULT);
    }

    void configure(Preset preset,
                   int nextSampleRate,
                   int nextChannelCount,
                   boolean enableDspBass,
                   AdvancedModeConfig config) {
        sampleRate = Math.max(8000, nextSampleRate);
        channelCount = Math.max(1, nextChannelCount);
        pregain = preset == null ? 1f : dbToLinear(preset.pregainMb / 100f);
        filters.clear();
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;

        if (preset != null) {
            for (ParametricBand band : preset.bands) {
                if (band.enabled && band.gainMb != 0) {
                    filters.add(Biquad.fromBand(band, sampleRate, channelCount));
                }
            }
            virtualBass = new VirtualBass(sampleRate, channelCount);
            virtualBass.configure(preset.virtualBassCutoffHz,
                    preset.virtualBassEnabled ? preset.virtualBassAmountPercent : 0);
            harmonicBass = new HarmonicBassEnhancer(sampleRate, channelCount);
            harmonicBass.configure(preset.dspBassCutoffHz,
                    enableDspBass ? preset.systemBassBoostPercent : 0);
            reverb = new AlgorithmicReverb(sampleRate, channelCount);
            reverb.configure(preset.reverbType,
                    preset.reverbDecayPercent,
                    preset.reverbPredelayMs,
                    preset.reverbSizePercent,
                    preset.reverbMixPercent,
                    safeConfig.wetMixPercent);
        }
    }

    void processInterleaved(float[] samples, int frameCount) {
        if (samples == null || frameCount <= 0) {
            return;
        }

        int sampleCount = Math.min(samples.length, frameCount * channelCount);
        for (int i = 0; i < sampleCount; i++) {
            samples[i] *= pregain;
        }
        virtualBass.process(samples, sampleCount, channelCount);
        for (Biquad filter : filters) {
            filter.process(samples, sampleCount, channelCount);
        }
        harmonicBass.process(samples, sampleCount, channelCount);
        reverb.process(samples, sampleCount, channelCount);
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    private static final class Biquad {
        private final float[] z1;
        private final float[] z2;
        private float b0;
        private float b1;
        private float b2;
        private float a1;
        private float a2;

        private Biquad(int channelCount) {
            z1 = new float[channelCount];
            z2 = new float[channelCount];
        }

        static Biquad fromBand(ParametricBand band, int sampleRate, int channelCount) {
            Biquad biquad = new Biquad(channelCount);
            double frequency = Math.max(20.0, Math.min(sampleRate * 0.45, band.frequencyHz));
            double omega = 2.0 * Math.PI * frequency / sampleRate;
            double sin = Math.sin(omega);
            double cos = Math.cos(omega);
            double q = Math.max(0.2, band.qHundred / 100.0);
            double gain = Math.pow(10.0, band.gainMb / 4000.0);
            double alpha = sin / (2.0 * q);
            double beta = Math.sqrt(gain) / q;
            double b0;
            double b1;
            double b2;
            double a0;
            double a1;
            double a2;

            switch (band.type) {
                case LOW_SHELF:
                    b0 = gain * ((gain + 1.0) - (gain - 1.0) * cos + beta * sin);
                    b1 = 2.0 * gain * ((gain - 1.0) - (gain + 1.0) * cos);
                    b2 = gain * ((gain + 1.0) - (gain - 1.0) * cos - beta * sin);
                    a0 = (gain + 1.0) + (gain - 1.0) * cos + beta * sin;
                    a1 = -2.0 * ((gain - 1.0) + (gain + 1.0) * cos);
                    a2 = (gain + 1.0) + (gain - 1.0) * cos - beta * sin;
                    break;
                case HIGH_SHELF:
                    b0 = gain * ((gain + 1.0) + (gain - 1.0) * cos + beta * sin);
                    b1 = -2.0 * gain * ((gain - 1.0) + (gain + 1.0) * cos);
                    b2 = gain * ((gain + 1.0) + (gain - 1.0) * cos - beta * sin);
                    a0 = (gain + 1.0) - (gain - 1.0) * cos + beta * sin;
                    a1 = 2.0 * ((gain - 1.0) - (gain + 1.0) * cos);
                    a2 = (gain + 1.0) - (gain - 1.0) * cos - beta * sin;
                    break;
                case LOW_PASS:
                    b0 = (1.0 - cos) * 0.5;
                    b1 = 1.0 - cos;
                    b2 = (1.0 - cos) * 0.5;
                    a0 = 1.0 + alpha;
                    a1 = -2.0 * cos;
                    a2 = 1.0 - alpha;
                    break;
                case HIGH_PASS:
                    b0 = (1.0 + cos) * 0.5;
                    b1 = -(1.0 + cos);
                    b2 = (1.0 + cos) * 0.5;
                    a0 = 1.0 + alpha;
                    a1 = -2.0 * cos;
                    a2 = 1.0 - alpha;
                    break;
                case PEAK:
                default:
                    b0 = 1.0 + alpha * gain;
                    b1 = -2.0 * cos;
                    b2 = 1.0 - alpha * gain;
                    a0 = 1.0 + alpha / gain;
                    a1 = -2.0 * cos;
                    a2 = 1.0 - alpha / gain;
                    break;
            }

            biquad.b0 = (float) (b0 / a0);
            biquad.b1 = (float) (b1 / a0);
            biquad.b2 = (float) (b2 / a0);
            biquad.a1 = (float) (a1 / a0);
            biquad.a2 = (float) (a2 / a0);
            return biquad;
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float input = samples[i];
                float output = b0 * input + z1[channel];
                z1[channel] = b1 * input - a1 * output + z2[channel];
                z2[channel] = b2 * input - a2 * output;
                samples[i] = output;
            }
        }
    }

    private static final class VirtualBass {
        private final int sampleRate;
        private final int channelCount;
        private Biquad lowPass;
        private Biquad bandPass;
        private float drive = 0f;
        private float mix = 0f;

        VirtualBass(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            configure(140, 0);
        }

        void configure(int cutoffHz, int amountPercent) {
            float amount = Math.max(0f, Math.min(1f, amountPercent / 100f));
            int cutoff = Math.max(60, Math.min(250, cutoffHz));
            ParametricBand low = new ParametricBand(FilterType.LOW_PASS, true, cutoff, 0, 70);
            ParametricBand band = new ParametricBand(FilterType.PEAK, true, Math.min(900, cutoff * 2), 1200, 85);
            lowPass = Biquad.fromBand(low, sampleRate, channelCount);
            bandPass = Biquad.fromBand(band, sampleRate, channelCount);
            drive = 1.2f + amount * 7.5f;
            mix = amount * 0.85f;
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (mix <= 0f) {
                return;
            }

            float[] generated = new float[sampleCount];
            System.arraycopy(samples, 0, generated, 0, sampleCount);
            lowPass.process(generated, sampleCount, channelCount);
            for (int i = 0; i < sampleCount; i++) {
                float low = generated[i];
                float even = low * low * Math.signum(low);
                float odd = low * low * low;
                generated[i] = (float) Math.tanh((low + even * 0.95f + odd * 0.55f) * drive);
            }
            bandPass.process(generated, sampleCount, channelCount);
            for (int i = 0; i < sampleCount; i++) {
                samples[i] += generated[i] * mix;
            }
        }
    }

    private static final class HarmonicBassEnhancer {
        private final int sampleRate;
        private final int channelCount;
        private Biquad lowPass;
        private Biquad highPass;
        private Biquad secondPeak;
        private Biquad thirdPeak;
        private final float[] dcEstimate;
        private float secondMix;
        private float thirdMix;
        private float drive;

        HarmonicBassEnhancer(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.dcEstimate = new float[channelCount];
            configure(95, 0);
        }

        void configure(int cutoffHz, int amountPercent) {
            int cutoff = Math.max(45, Math.min(220, cutoffHz));
            float amount = Math.max(0f, Math.min(1f, amountPercent / 100f));
            lowPass = Biquad.fromBand(new ParametricBand(FilterType.LOW_PASS, true, cutoff, 0, 65), sampleRate, channelCount);
            highPass = Biquad.fromBand(new ParametricBand(FilterType.HIGH_PASS, true, Math.min(700, Math.max(120, Math.round(cutoff * 1.35f))), 0, 70), sampleRate, channelCount);
            secondPeak = Biquad.fromBand(new ParametricBand(FilterType.PEAK, true, Math.min(sampleRate / 3, Math.round(cutoff * 2.05f)), 900, 95), sampleRate, channelCount);
            thirdPeak = Biquad.fromBand(new ParametricBand(FilterType.PEAK, true, Math.min(sampleRate / 3, Math.round(cutoff * 3.1f)), 700, 105), sampleRate, channelCount);
            secondMix = amount * 0.58f;
            thirdMix = amount * 0.36f;
            drive = 1.1f + amount * 4.4f;
            for (int i = 0; i < dcEstimate.length; i++) {
                dcEstimate[i] = 0f;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (secondMix <= 0f && thirdMix <= 0f) {
                return;
            }

            float[] generated = new float[sampleCount];
            System.arraycopy(samples, 0, generated, 0, sampleCount);
            lowPass.process(generated, sampleCount, channelCount);
            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float low = generated[i];
                dcEstimate[channel] += 0.0025f * (Math.abs(low) - dcEstimate[channel]);
                float second = Math.max(0f, Math.abs(low) - dcEstimate[channel]);
                float third = low * low * low;
                generated[i] = (float) Math.tanh((second * secondMix + third * thirdMix) * drive);
            }
            highPass.process(generated, sampleCount, channelCount);
            secondPeak.process(generated, sampleCount, channelCount);
            thirdPeak.process(generated, sampleCount, channelCount);
            for (int i = 0; i < sampleCount; i++) {
                samples[i] += generated[i];
            }
        }
    }

    private static final class AlgorithmicReverb {
        private static final int[] COMB_BASE_DELAYS = {1116, 1188, 1277, 1356};
        private static final int[] ALLPASS_BASE_DELAYS = {225, 556};

        private final int sampleRate;
        private final int channelCount;
        private final CombFilter[][] combs;
        private final AllPassFilter[][] allpasses;
        private final float[][] preDelayBuffers;
        private final int[] preDelayIndices;
        private final float[] outputLowpassState;
        private float wetMix;
        private int preDelayLength;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.combs = new CombFilter[channelCount][COMB_BASE_DELAYS.length];
            this.allpasses = new AllPassFilter[channelCount][ALLPASS_BASE_DELAYS.length];
            this.preDelayBuffers = new float[channelCount][Math.max(1, sampleRate / 2)];
            this.preDelayIndices = new int[channelCount];
            this.outputLowpassState = new float[channelCount];
            for (int channel = 0; channel < channelCount; channel++) {
                for (int i = 0; i < COMB_BASE_DELAYS.length; i++) {
                    combs[channel][i] = new CombFilter(delayForRate(COMB_BASE_DELAYS[i]));
                }
                for (int i = 0; i < ALLPASS_BASE_DELAYS.length; i++) {
                    allpasses[channel][i] = new AllPassFilter(delayForRate(ALLPASS_BASE_DELAYS[i]));
                }
            }
            configure("Default", 0, 0, 0, 0, 100);
        }

        void configure(String type,
                       int decayPercent,
                       int preDelayMs,
                       int sizePercent,
                       int mixPercent,
                       int globalWetPercent) {
            float typeFeedbackBias = reverbTypeFeedbackBias(type);
            float typeDamping = reverbTypeDamping(type);
            float size = Math.max(0f, Math.min(1f, sizePercent / 100f));
            float decay = Math.max(0f, Math.min(1f, decayPercent / 100f));
            float mix = Math.max(0f, Math.min(1f, mixPercent / 100f));
            float globalWet = Math.max(0f, Math.min(1f, globalWetPercent / 100f));
            wetMix = "Default".equals(type) ? 0f : mix * globalWet;
            preDelayLength = Math.max(0, Math.min(preDelayBuffers[0].length - 1, preDelayMs * sampleRate / 1000));

            float feedback = 0.48f + size * 0.16f + decay * 0.26f + typeFeedbackBias;
            feedback = Math.max(0.2f, Math.min(0.94f, feedback));
            float damping = Math.max(0.05f, Math.min(0.82f, typeDamping + (1f - decay) * 0.25f));

            for (int channel = 0; channel < channelCount; channel++) {
                for (CombFilter comb : combs[channel]) {
                    comb.setFeedback(feedback);
                    comb.setDamping(damping);
                }
                for (AllPassFilter allPass : allpasses[channel]) {
                    allPass.setFeedback(0.55f + size * 0.15f);
                }
                preDelayIndices[channel] = 0;
                outputLowpassState[channel] = 0f;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (wetMix <= 0f) {
                return;
            }

            int frames = sampleCount / channelCount;
            for (int frame = 0; frame < frames; frame++) {
                for (int channel = 0; channel < channelCount; channel++) {
                    int index = frame * channelCount + channel;
                    float dry = samples[index];
                    float delayed = preDelayProcess(dry, channel);
                    float wet = 0f;
                    for (CombFilter comb : combs[channel]) {
                        wet += comb.process(delayed);
                    }
                    for (AllPassFilter allPass : allpasses[channel]) {
                        wet = allPass.process(wet);
                    }
                    outputLowpassState[channel] += 0.14f * (wet - outputLowpassState[channel]);
                    float decorrelated = wet * 0.72f + outputLowpassState[channel] * 0.28f;
                    samples[index] = dry * (1f - wetMix) + decorrelated * wetMix;
                }
            }
        }

        private float preDelayProcess(float input, int channel) {
            if (preDelayLength <= 0) {
                return input;
            }
            float[] buffer = preDelayBuffers[channel];
            int writeIndex = preDelayIndices[channel];
            int readIndex = writeIndex - preDelayLength;
            if (readIndex < 0) {
                readIndex += buffer.length;
            }
            float output = buffer[readIndex];
            buffer[writeIndex] = input;
            preDelayIndices[channel] = (writeIndex + 1) % buffer.length;
            return output;
        }

        private int delayForRate(int samplesAt44k1) {
            return Math.max(8, Math.round(samplesAt44k1 * (sampleRate / 44100f)));
        }

        private float reverbTypeFeedbackBias(String type) {
            if ("Hall".equals(type)) return 0.05f;
            if ("Plate".equals(type)) return 0.03f;
            if ("Chamber".equals(type)) return 0.02f;
            if ("Room".equals(type)) return -0.01f;
            if ("Studio".equals(type)) return -0.03f;
            return 0f;
        }

        private float reverbTypeDamping(String type) {
            if ("Hall".equals(type)) return 0.18f;
            if ("Plate".equals(type)) return 0.12f;
            if ("Chamber".equals(type)) return 0.15f;
            if ("Room".equals(type)) return 0.22f;
            if ("Studio".equals(type)) return 0.28f;
            return 0.2f;
        }
    }

    private static final class CombFilter {
        private final float[] buffer;
        private int index;
        private float feedback = 0.7f;
        private float damping = 0.2f;
        private float filterStore;

        CombFilter(int size) {
            buffer = new float[Math.max(8, size)];
        }

        void setFeedback(float feedback) {
            this.feedback = feedback;
        }

        void setDamping(float damping) {
            this.damping = damping;
        }

        float process(float input) {
            float output = buffer[index];
            filterStore += damping * (output - filterStore);
            buffer[index] = input + filterStore * feedback;
            index = (index + 1) % buffer.length;
            return output;
        }
    }

    private static final class AllPassFilter {
        private final float[] buffer;
        private int index;
        private float feedback = 0.5f;

        AllPassFilter(int size) {
            buffer = new float[Math.max(8, size)];
        }

        void setFeedback(float feedback) {
            this.feedback = feedback;
        }

        float process(float input) {
            float buffered = buffer[index];
            float output = -input + buffered;
            buffer[index] = input + buffered * feedback;
            index = (index + 1) % buffer.length;
            return output;
        }
    }
}
