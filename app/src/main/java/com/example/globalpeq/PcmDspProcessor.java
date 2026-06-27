package com.example.globalpeq;

import java.util.ArrayList;
import java.util.List;

final class PcmDspProcessor {
    private final List<Biquad> filters = new ArrayList<>();
    private int sampleRate = 48000;
    private int channelCount = 2;
    private float pregain = 1f;
    private PsychoacousticBassProcessor psychoacousticBass = new PsychoacousticBassProcessor(48000, 2);
    private AlgorithmicReverb reverb = new AlgorithmicReverb(48000, 2);
    private LookaheadLimiter limiter = new LookaheadLimiter(48000, 2);

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
            if (preset.mode == EqMode.GEQ) {
                int bandCount = Math.min(Preset.GEQ_FREQUENCIES.length, preset.geqGainsMb.length);
                for (int i = 0; i < bandCount; i++) {
                    int gainMb = preset.geqGainsMb[i];
                    if (gainMb != 0) {
                        filters.add(Biquad.fromBand(
                                new ParametricBand(FilterType.PEAK, true, Preset.GEQ_FREQUENCIES[i], gainMb, 110),
                                sampleRate,
                                channelCount));
                    }
                }
            } else {
                for (ParametricBand band : preset.bands) {
                    if (band.enabled && band.gainMb != 0) {
                        filters.add(Biquad.fromBand(band, sampleRate, channelCount));
                    }
                }
            }
            psychoacousticBass = new PsychoacousticBassProcessor(sampleRate, channelCount);
            psychoacousticBass.configure(
                    preset.extraBassCutoffHz,
                    preset.extraBassEnabled ? preset.extraBassAmountPercent : 0,
                    preset.virtualBassCutoffHz,
                    enableDspBass ? preset.virtualBassAmountPercent : 0);
            reverb = new AlgorithmicReverb(sampleRate, channelCount);
            reverb.configure(preset.reverbType,
                    preset.reverbDecayPercent,
                    preset.reverbPredelayMs,
                    preset.reverbSizePercent,
                    preset.reverbMixPercent,
                    safeConfig.wetMixPercent);
            limiter = new LookaheadLimiter(sampleRate, channelCount);
            limiter.configure(safeConfig.lookaheadMs, safeConfig.latencyMs);
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
        psychoacousticBass.process(samples, sampleCount, channelCount);
        for (Biquad filter : filters) {
            filter.process(samples, sampleCount, channelCount);
        }
        reverb.process(samples, sampleCount, channelCount);
        limiter.process(samples, sampleCount, channelCount);
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

    private static final class PsychoacousticBassProcessor {
        private final int sampleRate;
        private final int channelCount;
        private Biquad sourceHighPass;
        private Biquad sourceLowPass;
        private Biquad harmonicHighPass;
        private Biquad harmonicLowPass;
        private Biquad octaveHighPass;
        private Biquad octaveLowPass;
        private float[] lowBand = new float[0];
        private float[] harmonicBand = new float[0];
        private float[] octaveBand = new float[0];
        private final float[] envelope;
        private float harmonicMix;
        private float octaveMix;
        private float lowBandReduce;
        private float drive;
        private float envelopeAttack;
        private float envelopeRelease;

        PsychoacousticBassProcessor(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.envelope = new float[channelCount];
            configure(140, 0, 95, 0);
        }

        void configure(int virtualCutoffHz,
                       int virtualAmountPercent,
                       int dspCutoffHz,
                       int dspAmountPercent) {
            float virtualAmount = clamp01(virtualAmountPercent / 100f);
            float dspAmount = clamp01(dspAmountPercent / 100f);
            float totalAmount = clamp01(virtualAmount + dspAmount * 0.35f);
            int blendedCutoff = clamp(
                    Math.round(virtualCutoffHz * 0.7f + dspCutoffHz * 0.3f),
                    60,
                    220);

            sourceHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, 28, 0, 70),
                    sampleRate,
                    channelCount);
            sourceLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, blendedCutoff, 0, 72),
                    sampleRate,
                    channelCount);
            harmonicHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(110, Math.round(blendedCutoff * 1.3f)), 0, 78),
                    sampleRate,
                    channelCount);
            harmonicLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.round(blendedCutoff * 4.4f), sampleRate / 3), 0, 82),
                    sampleRate,
                    channelCount);
            octaveHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(95, Math.round(blendedCutoff * 1.15f)), 0, 80),
                    sampleRate,
                    channelCount);
            octaveLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.round(blendedCutoff * 3.0f), sampleRate / 4), 0, 88),
                    sampleRate,
                    channelCount);

            harmonicMix = totalAmount * (0.34f + virtualAmount * 0.22f);
            octaveMix = totalAmount * (0.18f + virtualAmount * 0.14f + dspAmount * 0.28f);
            lowBandReduce = dspAmount * 0.58f + virtualAmount * 0.18f;
            drive = 1.2f + totalAmount * 5.6f + dspAmount * 1.5f;
            envelopeAttack = 0.012f + totalAmount * 0.02f;
            envelopeRelease = 0.002f + dspAmount * 0.006f;
            for (int i = 0; i < envelope.length; i++) {
                envelope[i] = 0f;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (harmonicMix <= 0f && octaveMix <= 0f && lowBandReduce <= 0f) {
                return;
            }

            if (lowBand.length < sampleCount) {
                lowBand = new float[sampleCount];
                harmonicBand = new float[sampleCount];
                octaveBand = new float[sampleCount];
            }

            System.arraycopy(samples, 0, lowBand, 0, sampleCount);
            sourceHighPass.process(lowBand, sampleCount, channelCount);
            sourceLowPass.process(lowBand, sampleCount, channelCount);
            System.arraycopy(lowBand, 0, harmonicBand, 0, sampleCount);
            System.arraycopy(lowBand, 0, octaveBand, 0, sampleCount);

            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float low = lowBand[i];
                float absLow = Math.abs(low);
                float coeff = absLow > envelope[channel] ? envelopeAttack : envelopeRelease;
                envelope[channel] += (absLow - envelope[channel]) * coeff;
                float normalized = low / (0.05f + envelope[channel]);
                float shaped = (float) Math.tanh(normalized * drive);
                float third = shaped * shaped * shaped;
                float second = low * low;
                float dynamics = 0.3f + Math.min(1f, envelope[channel] * 8f) * 0.7f;
                harmonicBand[i] = (third * 0.72f + shaped * 0.18f) * dynamics;
                octaveBand[i] = second * (0.85f + Math.abs(shaped) * 0.35f) * dynamics;
            }

            harmonicHighPass.process(harmonicBand, sampleCount, channelCount);
            harmonicLowPass.process(harmonicBand, sampleCount, channelCount);
            octaveHighPass.process(octaveBand, sampleCount, channelCount);
            octaveLowPass.process(octaveBand, sampleCount, channelCount);

            for (int i = 0; i < sampleCount; i++) {
                samples[i] += harmonicBand[i] * harmonicMix;
                samples[i] += octaveBand[i] * octaveMix;
                samples[i] -= lowBand[i] * lowBandReduce;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }
    }

    private static final class AlgorithmicReverb {
        private final int sampleRate;
        private final int channelCount;
        private final float[][] preDelayBuffers;
        private final int[] preDelayIndices;
        private final PlateFeedbackNetwork network;
        private final float[] wetFrame = new float[2];
        private float wetMix;
        private float stereoWidth = 1f;
        private int preDelayLength;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.preDelayBuffers = new float[Math.max(1, channelCount)][Math.max(1, sampleRate / 2)];
            this.preDelayIndices = new int[Math.max(1, channelCount)];
            this.network = new PlateFeedbackNetwork(sampleRate);
            configure("Default", 0, 0, 0, 0, 100);
        }

        void configure(String type,
                       int decayPercent,
                       int preDelayMs,
                       int sizePercent,
                       int mixPercent,
                       int globalWetPercent) {
            float size = clamp01(sizePercent / 100f);
            float decay = clamp01(decayPercent / 100f);
            float mix = clamp01(mixPercent / 100f);
            float globalWet = clamp01(globalWetPercent / 100f);
            wetMix = "Default".equals(type) ? 0f : mix * globalWet;
            preDelayLength = Math.max(0, Math.min(preDelayBuffers[0].length - 1, preDelayMs * sampleRate / 1000));

            ModeProfile profile = ModeProfile.forType(type);
            stereoWidth = profile.baseWidth + size * 0.2f;
            network.configure(profile, size, decay);

            for (int channel = 0; channel < preDelayIndices.length; channel++) {
                preDelayIndices[channel] = 0;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (wetMix <= 0f) {
                return;
            }

            int frames = sampleCount / channelCount;
            for (int frame = 0; frame < frames; frame++) {
                int frameOffset = frame * channelCount;
                float leftDry = samples[frameOffset];
                float rightDry = channelCount > 1 ? samples[frameOffset + 1] : leftDry;
                float leftIn = preDelayProcess(leftDry, 0);
                float rightIn = preDelayProcess(rightDry, Math.min(1, preDelayIndices.length - 1));
                float monoIn = channelCount > 1 ? 0.5f * (leftIn + rightIn) : leftIn;
                float sideIn = channelCount > 1 ? 0.5f * (leftIn - rightIn) : 0f;
                network.process(monoIn, sideIn, wetFrame);

                float wetLeft = wetFrame[0];
                float wetRight = channelCount > 1 ? wetFrame[1] : 0.5f * (wetFrame[0] + wetFrame[1]);
                if (channelCount > 1) {
                    float mid = 0.5f * (wetLeft + wetRight);
                    float side = 0.5f * (wetLeft - wetRight) * stereoWidth;
                    wetLeft = mid + side;
                    wetRight = mid - side;
                }

                samples[frameOffset] = clampSample(leftDry * (1f - wetMix) + wetLeft * wetMix);
                if (channelCount > 1) {
                    samples[frameOffset + 1] = clampSample(rightDry * (1f - wetMix) + wetRight * wetMix);
                }
                for (int channel = 2; channel < channelCount; channel++) {
                    float dry = samples[frameOffset + channel];
                    float wet = 0.5f * (wetLeft + wetRight);
                    samples[frameOffset + channel] = clampSample(dry * (1f - wetMix) + wet * wetMix);
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
    }

    private static final class LookaheadLimiter {
        private static final float CEILING = 0.985f;

        private final int sampleRate;
        private final int channelCount;
        private float[] delayBuffer;
        private int writeFrame;
        private int delayFrames;
        private int primedFrames;
        private float envelope;
        private float gain = 1f;
        private float attackCoeff = 0.5f;
        private float releaseCoeff = 0.995f;

        LookaheadLimiter(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            configure(0, 20);
        }

        void configure(int lookaheadMs, int latencyMs) {
            delayFrames = Math.max(0, Math.min(sampleRate / 8, lookaheadMs * sampleRate / 1000));
            int latencyFrames = Math.max(delayFrames + 1, Math.min(sampleRate / 2, latencyMs * sampleRate / 1000));
            int bufferFrames = Math.max(delayFrames + 1, latencyFrames + 8);
            delayBuffer = new float[Math.max(channelCount, bufferFrames * channelCount)];
            writeFrame = 0;
            primedFrames = 0;
            envelope = 0f;
            gain = 1f;
            float attackSeconds = Math.max(0.0015f, Math.max(0.001f, lookaheadMs / 1000f) * 0.35f);
            float releaseSeconds = Math.max(0.045f, Math.max(0.02f, latencyMs / 1000f) * 0.65f);
            attackCoeff = (float) Math.exp(-1.0 / (sampleRate * attackSeconds));
            releaseCoeff = (float) Math.exp(-1.0 / (sampleRate * releaseSeconds));
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (samples == null || sampleCount <= 0) {
                return;
            }
            if (delayFrames <= 0 || delayBuffer == null) {
                softClip(samples, sampleCount);
                return;
            }

            int frames = sampleCount / channelCount;
            int bufferFrames = Math.max(1, delayBuffer.length / this.channelCount);
            for (int frame = 0; frame < frames; frame++) {
                float peak = 0f;
                int frameOffset = frame * channelCount;
                for (int channel = 0; channel < channelCount; channel++) {
                    peak = Math.max(peak, Math.abs(samples[frameOffset + channel]));
                }

                if (peak > envelope) {
                    envelope = peak + attackCoeff * (envelope - peak);
                } else {
                    envelope = peak + releaseCoeff * (envelope - peak);
                }
                float targetGain = envelope > CEILING ? CEILING / Math.max(CEILING, envelope) : 1f;
                gain += 0.35f * (targetGain - gain);

                int readFrame = writeFrame - delayFrames;
                if (readFrame < 0) {
                    readFrame += bufferFrames;
                }
                for (int channel = 0; channel < channelCount; channel++) {
                    int writeIndex = writeFrame * this.channelCount + channel;
                    float dry = samples[frameOffset + channel];
                    float delayed = primedFrames >= delayFrames
                            ? delayBuffer[readFrame * this.channelCount + channel]
                            : dry;
                    delayBuffer[writeIndex] = dry;
                    samples[frameOffset + channel] = clampSample(delayed * gain);
                }

                writeFrame++;
                primedFrames++;
                if (writeFrame >= bufferFrames) {
                    writeFrame = 0;
                }
            }
        }

        private void softClip(float[] samples, int sampleCount) {
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = clampSample((float) Math.tanh(samples[i] * 0.92f));
            }
        }

        private float clampSample(float sample) {
            return Math.max(-1f, Math.min(1f, sample));
        }
    }

    private static final class PlateFeedbackNetwork {
        private static final float[] BASE_DELAY_MS = {9.7f, 11.3f, 13.1f, 15.1f, 17.8f, 20.9f, 24.2f, 28.7f};
        private static final float[] INPUT_SIGNS = {1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f};
        private static final float[] SIDE_SIGNS = {1f, 1f, -1f, -1f, 1f, -1f, -1f, 1f};
        private static final float[] LEFT_TAPS = {0.34f, -0.26f, 0.29f, -0.18f, 0.31f, -0.23f, 0.19f, -0.17f};
        private static final float[] RIGHT_TAPS = {-0.21f, 0.33f, -0.17f, 0.28f, -0.19f, 0.30f, -0.24f, 0.27f};

        private final ModulatedDelayLine[] lines;
        private final float[] feedbackState;
        private final float[] lowpassState;
        private final float[] delayed;
        private float feedbackGain;
        private float damping;
        private float inputScatter;
        private float sideScatter;
        private float outputTrim;

        PlateFeedbackNetwork(int sampleRate) {
            lines = new ModulatedDelayLine[BASE_DELAY_MS.length];
            feedbackState = new float[BASE_DELAY_MS.length];
            lowpassState = new float[BASE_DELAY_MS.length];
            delayed = new float[BASE_DELAY_MS.length];
            int maxDelaySamples = Math.max(256, Math.round(sampleRate * 0.08f));
            for (int i = 0; i < lines.length; i++) {
                lines[i] = new ModulatedDelayLine(maxDelaySamples, sampleRate);
            }
        }

        void configure(ModeProfile profile, float size, float decay) {
            float sizeScale = 0.72f + size * 0.9f;
            feedbackGain = clamp(0.58f + decay * 0.34f + profile.feedbackBias, 0.35f, 0.965f);
            damping = clamp(0.06f + profile.brightness * 0.2f + (1f - decay) * 0.05f, 0.05f, 0.32f);
            inputScatter = 0.22f + profile.attack * 0.18f + size * 0.06f;
            sideScatter = 0.08f + profile.stereoSpread * 0.16f;
            outputTrim = 0.42f - size * 0.08f;

            for (int i = 0; i < lines.length; i++) {
                float delaySamples = BASE_DELAY_MS[i] * sizeScale * lines[i].sampleRate / 1000f;
                float modDepthSamples = (0.15f + profile.modDepth * 0.55f + size * 0.35f) * lines[i].sampleRate / 1000f;
                float modRateHz = 0.14f + profile.modRate * 0.28f + i * 0.017f;
                lines[i].configure(delaySamples, modDepthSamples, modRateHz, (float) (i * 0.61));
                feedbackState[i] = 0f;
                lowpassState[i] = 0f;
            }
        }

        void process(float monoIn, float sideIn, float[] wetFrame) {
            float sum = 0f;
            for (int i = 0; i < lines.length; i++) {
                delayed[i] = lines[i].read();
                lowpassState[i] += damping * (delayed[i] - lowpassState[i]);
                feedbackState[i] = lowpassState[i];
                sum += feedbackState[i];
            }

            float mean = (2f / lines.length) * sum;
            for (int i = 0; i < lines.length; i++) {
                float mixed = mean - feedbackState[i];
                float excitation = monoIn * INPUT_SIGNS[i] * inputScatter
                        + sideIn * SIDE_SIGNS[i] * sideScatter;
                lines[i].write(excitation + mixed * feedbackGain);
                lines[i].advance();
            }

            float left = 0f;
            float right = 0f;
            for (int i = 0; i < lines.length; i++) {
                left += delayed[i] * LEFT_TAPS[i];
                right += delayed[i] * RIGHT_TAPS[i];
            }
            wetFrame[0] = left * outputTrim;
            wetFrame[1] = right * outputTrim;
        }
    }

    private static final class ModulatedDelayLine {
        private final float[] buffer;
        private final int sampleRate;
        private int writeIndex;
        private float delaySamples;
        private float modDepthSamples;
        private float lfoPhase;
        private float lfoIncrement;

        ModulatedDelayLine(int maxDelaySamples, int sampleRate) {
            buffer = new float[Math.max(32, maxDelaySamples + 8)];
            this.sampleRate = Math.max(8000, sampleRate);
        }

        void configure(float delaySamples, float modDepthSamples, float modRateHz, float phaseOffset) {
            this.delaySamples = clamp(delaySamples, 4f, buffer.length - 4f);
            this.modDepthSamples = clamp(modDepthSamples, 0f, Math.min(12f, this.delaySamples * 0.35f));
            this.lfoPhase = phaseOffset;
            this.lfoIncrement = (float) (2.0 * Math.PI * modRateHz / sampleRate);
        }

        float read() {
            float modulation = (float) Math.sin(lfoPhase) * modDepthSamples;
            float readPos = writeIndex - delaySamples - modulation;
            while (readPos < 0f) {
                readPos += buffer.length;
            }
            int indexA = (int) readPos;
            int indexB = (indexA + 1) % buffer.length;
            float frac = readPos - indexA;
            return buffer[indexA] + (buffer[indexB] - buffer[indexA]) * frac;
        }

        void write(float value) {
            buffer[writeIndex] = value;
        }

        void advance() {
            writeIndex++;
            if (writeIndex >= buffer.length) {
                writeIndex = 0;
            }
            lfoPhase += lfoIncrement;
            if (lfoPhase > Math.PI * 2.0f) {
                lfoPhase -= (float) (Math.PI * 2.0);
            }
        }
    }

    private static final class ModeProfile {
        final float brightness;
        final float feedbackBias;
        final float baseWidth;
        final float modDepth;
        final float modRate;
        final float attack;
        final float stereoSpread;

        ModeProfile(float brightness,
                    float feedbackBias,
                    float baseWidth,
                    float modDepth,
                    float modRate,
                    float attack,
                    float stereoSpread) {
            this.brightness = brightness;
            this.feedbackBias = feedbackBias;
            this.baseWidth = baseWidth;
            this.modDepth = modDepth;
            this.modRate = modRate;
            this.attack = attack;
            this.stereoSpread = stereoSpread;
        }

        static ModeProfile forType(String type) {
            if ("Plate".equals(type)) return new ModeProfile(0.82f, 0.03f, 1.12f, 0.22f, 0.75f, 0.95f, 0.8f);
            if ("Hall".equals(type)) return new ModeProfile(0.6f, 0.06f, 1.18f, 0.28f, 0.52f, 0.72f, 0.9f);
            if ("Chamber".equals(type)) return new ModeProfile(0.68f, 0.02f, 1.04f, 0.2f, 0.6f, 0.86f, 0.66f);
            if ("Room".equals(type)) return new ModeProfile(0.54f, -0.02f, 0.96f, 0.16f, 0.45f, 0.68f, 0.45f);
            if ("Studio".equals(type)) return new ModeProfile(0.46f, -0.04f, 0.88f, 0.1f, 0.35f, 0.62f, 0.3f);
            return new ModeProfile(0.65f, 0f, 1f, 0.18f, 0.5f, 0.8f, 0.55f);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clampSample(float value) {
        return clamp(value, -1f, 1f);
    }
}
