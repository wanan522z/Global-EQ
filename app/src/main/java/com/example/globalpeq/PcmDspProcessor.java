package com.example.globalpeq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PcmDspProcessor {
    private static final float EFFECT_HEADROOM_DB = -12f;
    private final List<Biquad> filters = new ArrayList<>();
    private int sampleRate = 48000;
    private int channelCount = 2;
    private float pregain = 1f;
    private float effectHeadroom = 1f;
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
        effectHeadroom = preset != null && preset.enabled
                ? dbToLinear(EFFECT_HEADROOM_DB)
                : 1f;
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
            int effectiveReverbWetPercent = effectiveReverbWetPercent(preset, safeConfig);
            reverb.configure(preset.reverbType,
                    preset.reverbDecayPercent,
                    preset.reverbPredelayMs,
                    preset.reverbSizePercent,
                    preset.reverbMixPercent,
                    effectiveReverbWetPercent);
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
            samples[i] *= pregain * effectHeadroom;
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

    private static int effectiveReverbWetPercent(Preset preset, AdvancedModeConfig config) {
        if (preset == null || "Default".equals(preset.reverbType) || preset.reverbMixPercent <= 0) {
            return 0;
        }
        int configuredWet = config == null ? AdvancedModeConfig.DEFAULT.wetMixPercent : config.wetMixPercent;
        if (configuredWet <= 0) {
            return 100;
        }
        return Math.max(0, Math.min(100, configuredWet));
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
        private Biquad sustainLowPass;
        private Biquad sustainHighPass;
        private Biquad harmonicHighPass;
        private Biquad harmonicLowPass;
        private Biquad upperHarmonicHighPass;
        private Biquad upperHarmonicLowPass;
        private Biquad octaveHighPass;
        private Biquad octaveLowPass;
        private Biquad subBodyLowPass;
        private float[] lowBand = new float[0];
        private float[] sustainBand = new float[0];
        private float[] harmonicBand = new float[0];
        private float[] upperHarmonicBand = new float[0];
        private float[] octaveBand = new float[0];
        private float[] subBodyBand = new float[0];
        private final float[] envelope;
        private float harmonicMix;
        private float upperHarmonicMix;
        private float octaveMix;
        private float sustainMix;
        private float subBodyMix;
        private float lowBandLift;
        private float lowBandTrim;
        private float drive;
        private float asymmetry;
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
            float totalAmount = clamp01(virtualAmount * 0.9f + dspAmount * 0.7f);
            int blendedCutoff = clamp(
                    Math.round(virtualCutoffHz * 0.55f + dspCutoffHz * 0.45f),
                    50,
                    210);

            sourceHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, 24, 0, 68),
                    sampleRate,
                    channelCount);
            sourceLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, blendedCutoff, 0, 72),
                    sampleRate,
                    channelCount);
            sustainHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(24, Math.round(blendedCutoff * 0.42f)), 0, 84),
                    sampleRate,
                    channelCount);
            sustainLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.max(85, Math.round(blendedCutoff * 1.18f)), 0, 82),
                    sampleRate,
                    channelCount);
            harmonicHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(95, Math.round(blendedCutoff * 1.08f)), 0, 76),
                    sampleRate,
                    channelCount);
            harmonicLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.round(blendedCutoff * 4.9f), sampleRate / 3), 0, 84),
                    sampleRate,
                    channelCount);
            upperHarmonicHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(160, Math.round(blendedCutoff * 1.8f)), 0, 80),
                    sampleRate,
                    channelCount);
            upperHarmonicLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.round(blendedCutoff * 6.4f), sampleRate / 2), 0, 86),
                    sampleRate,
                    channelCount);
            octaveHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(85, Math.round(blendedCutoff * 0.96f)), 0, 82),
                    sampleRate,
                    channelCount);
            octaveLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.round(blendedCutoff * 3.6f), sampleRate / 3), 0, 88),
                    sampleRate,
                    channelCount);
            subBodyLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.max(70, Math.round(blendedCutoff * 1.28f)), 0, 92),
                    sampleRate,
                    channelCount);

            harmonicMix = totalAmount * (0.42f + virtualAmount * 0.28f + dspAmount * 0.14f);
            upperHarmonicMix = totalAmount * (0.18f + virtualAmount * 0.14f + dspAmount * 0.2f);
            octaveMix = totalAmount * (0.26f + virtualAmount * 0.12f + dspAmount * 0.34f);
            sustainMix = totalAmount * (0.22f + dspAmount * 0.32f);
            subBodyMix = totalAmount * (0.15f + dspAmount * 0.26f + virtualAmount * 0.08f);
            lowBandLift = totalAmount * (0.08f + dspAmount * 0.18f + virtualAmount * 0.06f);
            lowBandTrim = dspAmount * 0.14f + virtualAmount * 0.04f;
            drive = 1.8f + totalAmount * 7.2f + dspAmount * 2.4f;
            asymmetry = 0.18f + virtualAmount * 0.22f + dspAmount * 0.28f;
            envelopeAttack = 0.014f + totalAmount * 0.026f;
            envelopeRelease = 0.0025f + totalAmount * 0.008f;
            for (int i = 0; i < envelope.length; i++) {
                envelope[i] = 0f;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (harmonicMix <= 0f && upperHarmonicMix <= 0f && octaveMix <= 0f && sustainMix <= 0f && subBodyMix <= 0f && lowBandLift <= 0f && lowBandTrim <= 0f) {
                return;
            }

            if (lowBand.length < sampleCount) {
                lowBand = new float[sampleCount];
                sustainBand = new float[sampleCount];
                harmonicBand = new float[sampleCount];
                upperHarmonicBand = new float[sampleCount];
                octaveBand = new float[sampleCount];
                subBodyBand = new float[sampleCount];
            }

            System.arraycopy(samples, 0, lowBand, 0, sampleCount);
            sourceHighPass.process(lowBand, sampleCount, channelCount);
            sourceLowPass.process(lowBand, sampleCount, channelCount);
            System.arraycopy(lowBand, 0, sustainBand, 0, sampleCount);
            System.arraycopy(lowBand, 0, harmonicBand, 0, sampleCount);
            System.arraycopy(lowBand, 0, upperHarmonicBand, 0, sampleCount);
            System.arraycopy(lowBand, 0, octaveBand, 0, sampleCount);
            System.arraycopy(lowBand, 0, subBodyBand, 0, sampleCount);

            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float low = lowBand[i];
                float absLow = Math.abs(low);
                float coeff = absLow > envelope[channel] ? envelopeAttack : envelopeRelease;
                envelope[channel] += (absLow - envelope[channel]) * coeff;
                float normalized = low / (0.028f + envelope[channel] * 0.82f);
                float sign = Math.signum(normalized);
                float shaped = (float) Math.tanh(normalized * drive);
                float evenDriver = (float) Math.tanh((normalized + normalized * Math.abs(normalized) * asymmetry) * (drive * 0.82f));
                float oddDriver = shaped * shaped * shaped;
                float fifthDriver = oddDriver * shaped * shaped;
                float rectified = Math.max(0f, Math.abs(evenDriver) - 0.22f) * sign;
                float dynamics = 0.36f + Math.min(1f, envelope[channel] * 9.5f) * 0.64f;
                float sustain = (float) Math.tanh(low * (1.45f + envelope[channel] * 8f + drive * 0.16f));
                float body = (float) Math.tanh(low * (2.2f + drive * 0.12f));

                sustainBand[i] = sustain * dynamics;
                harmonicBand[i] = (oddDriver * 0.74f + evenDriver * 0.23f + shaped * 0.16f) * dynamics;
                upperHarmonicBand[i] = (fifthDriver * 0.58f + rectified * 0.44f) * dynamics;
                octaveBand[i] = (Math.abs(shaped) * sign) * (0.72f + Math.abs(evenDriver) * 0.48f) * dynamics;
                subBodyBand[i] = body * (0.42f + dynamics * 0.58f);
            }

            sustainHighPass.process(sustainBand, sampleCount, channelCount);
            sustainLowPass.process(sustainBand, sampleCount, channelCount);
            harmonicHighPass.process(harmonicBand, sampleCount, channelCount);
            harmonicLowPass.process(harmonicBand, sampleCount, channelCount);
            upperHarmonicHighPass.process(upperHarmonicBand, sampleCount, channelCount);
            upperHarmonicLowPass.process(upperHarmonicBand, sampleCount, channelCount);
            octaveHighPass.process(octaveBand, sampleCount, channelCount);
            octaveLowPass.process(octaveBand, sampleCount, channelCount);
            subBodyLowPass.process(subBodyBand, sampleCount, channelCount);

            for (int i = 0; i < sampleCount; i++) {
                samples[i] += lowBand[i] * lowBandLift;
                samples[i] += sustainBand[i] * sustainMix;
                samples[i] += harmonicBand[i] * harmonicMix;
                samples[i] += upperHarmonicBand[i] * upperHarmonicMix;
                samples[i] += octaveBand[i] * octaveMix;
                samples[i] += subBodyBand[i] * subBodyMix;
                samples[i] -= lowBand[i] * lowBandTrim;
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
        private final ReverbEngine network;
        private final float[] wetFrame = new float[2];
        private float wetMix;
        private float stereoWidth = 1f;
        private int preDelayLength;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.preDelayBuffers = new float[Math.max(1, channelCount)][Math.max(1, sampleRate / 2)];
            this.preDelayIndices = new int[Math.max(1, channelCount)];
            this.network = new ReverbEngine(sampleRate);
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
                Arrays.fill(preDelayBuffers[channel], 0f);
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

    private static final class ReverbEngine {
        private static final float[] INPUT_SIGNS = {1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f};
        private static final float[] SIDE_SIGNS = {1f, 1f, -1f, -1f, 1f, -1f, -1f, 1f};

        private final DiffuserChain earlyDiffuser;
        private final ModulatedDelayLine[] lines;
        private final float[] feedbackState;
        private final float[] lowpassState;
        private final float[] highpassState;
        private final float[] delayed;
        private final float[] mixBuffer;
        private final int sampleRate;
        private float feedbackGain;
        private float highDamping;
        private float lowDamping;
        private float inputScatter;
        private float sideScatter;
        private float outputTrim;
        private float earlyGain;
        private float lateGain;
        private float buildup;
        private float toneTilt;
        private float outputLowpassL;
        private float outputLowpassR;

        ReverbEngine(int sampleRate) {
            this.sampleRate = sampleRate;
            this.earlyDiffuser = new DiffuserChain(sampleRate);
            this.lines = new ModulatedDelayLine[8];
            this.feedbackState = new float[8];
            this.lowpassState = new float[8];
            this.highpassState = new float[8];
            this.delayed = new float[8];
            this.mixBuffer = new float[8];
            int maxDelaySamples = Math.max(512, Math.round(sampleRate * 0.12f));
            for (int i = 0; i < lines.length; i++) {
                lines[i] = new ModulatedDelayLine(maxDelaySamples, sampleRate);
            }
        }

        void configure(ModeProfile profile, float size, float decay) {
            float sizeScale = profile.minSizeScale + size * profile.sizeRange;
            feedbackGain = clamp(profile.baseFeedback + decay * profile.decayRange + profile.feedbackBias, 0.3f, 0.985f);
            highDamping = clamp(profile.highDamping + (1f - decay) * 0.06f, 0.03f, 0.42f);
            lowDamping = clamp(profile.lowDamping + size * 0.04f, 0.01f, 0.35f);
            inputScatter = profile.inputScatter + size * 0.05f;
            sideScatter = profile.sideScatter + size * 0.04f;
            earlyGain = profile.earlyGain;
            lateGain = profile.lateGain;
            buildup = profile.buildup;
            toneTilt = profile.toneTilt;
            outputTrim = profile.outputTrim - size * 0.05f;
            outputLowpassL = 0f;
            outputLowpassR = 0f;
            earlyDiffuser.configure(profile, size);

            for (int i = 0; i < lines.length; i++) {
                float delaySamples = profile.baseDelayMs[i] * sizeScale * sampleRate / 1000f;
                float modDepthSamples = profile.modDepthMs * (0.7f + 0.12f * i + size * 0.45f) * sampleRate / 1000f;
                float modRateHz = profile.modRateHz + i * profile.modSpreadHz;
                lines[i].configure(delaySamples, modDepthSamples, modRateHz, (float) (i * 0.57));
                lines[i].clear();
                feedbackState[i] = 0f;
                lowpassState[i] = 0f;
                highpassState[i] = 0f;
            }
        }

        void process(float monoIn, float sideIn, float[] wetFrame) {
            float diffuseIn = earlyDiffuser.process(monoIn);
            float injectedMono = monoIn * (1f - earlyGain) + diffuseIn * earlyGain;
            float sum = 0f;
            for (int i = 0; i < lines.length; i++) {
                delayed[i] = lines[i].read();
                lowpassState[i] += highDamping * (delayed[i] - lowpassState[i]);
                float lowReduced = delayed[i] - lowpassState[i];
                highpassState[i] += lowDamping * (lowReduced - highpassState[i]);
                feedbackState[i] = lowpassState[i] - highpassState[i] * toneTilt;
                sum += feedbackState[i];
            }

            float mean = sum / lines.length;
            for (int i = 0; i < lines.length; i++) {
                mixBuffer[i] = (mean - feedbackState[i]) * 2f;
            }
            for (int i = 0; i < lines.length; i++) {
                int next = (i + 3) & 7;
                int cross = (i + 5) & 7;
                float recirculated = mixBuffer[i]
                        + mixBuffer[next] * buildup
                        - mixBuffer[cross] * (0.16f + buildup * 0.12f);
                float excitation = injectedMono * INPUT_SIGNS[i] * inputScatter
                        + sideIn * SIDE_SIGNS[i] * sideScatter;
                lines[i].write(excitation + recirculated * feedbackGain);
                lines[i].advance();
            }

            float leftEarly = earlyDiffuser.tapLeft();
            float rightEarly = earlyDiffuser.tapRight();
            float leftLate = 0f;
            float rightLate = 0f;
            for (int i = 0; i < lines.length; i++) {
                leftLate += delayed[i] * ModeProfile.LEFT_TAPS[i];
                rightLate += delayed[i] * ModeProfile.RIGHT_TAPS[i];
            }
            float left = leftEarly * earlyGain + leftLate * lateGain;
            float right = rightEarly * earlyGain + rightLate * lateGain;
            outputLowpassL += 0.11f * (left - outputLowpassL);
            outputLowpassR += 0.11f * (right - outputLowpassR);
            wetFrame[0] = (left * 0.72f + outputLowpassL * 0.28f) * outputTrim;
            wetFrame[1] = (right * 0.72f + outputLowpassR * 0.28f) * outputTrim;
        }
    }

    private static final class DiffuserChain {
        private final DiffusionStage[] stages;
        private final StereoTapDelay tapDelay;
        private float lastLeft;
        private float lastRight;

        DiffuserChain(int sampleRate) {
            stages = new DiffusionStage[] {
                    new DiffusionStage(sampleRate, 7.3f),
                    new DiffusionStage(sampleRate, 11.1f),
                    new DiffusionStage(sampleRate, 16.7f),
                    new DiffusionStage(sampleRate, 23.9f)
            };
            tapDelay = new StereoTapDelay(sampleRate, 38f);
        }

        void configure(ModeProfile profile, float size) {
            for (int i = 0; i < stages.length; i++) {
                float delayMs = profile.diffusionDelayMs[i] * (0.82f + size * 0.55f);
                float feedback = clamp(profile.diffusionFeedback + i * 0.04f, 0.35f, 0.82f);
                stages[i].configure(delayMs, feedback);
                stages[i].clear();
            }
            tapDelay.configure(
                    profile.earlyTapMs * (0.85f + size * 0.35f),
                    (profile.earlyTapMs + profile.earlySpreadMs) * (0.85f + size * 0.35f));
            tapDelay.clear();
            lastLeft = 0f;
            lastRight = 0f;
        }

        float process(float input) {
            float sample = input;
            for (DiffusionStage stage : stages) {
                sample = stage.process(sample);
            }
            tapDelay.process(sample);
            lastLeft = tapDelay.left();
            lastRight = tapDelay.right();
            return sample;
        }

        float tapLeft() {
            return lastLeft;
        }

        float tapRight() {
            return lastRight;
        }
    }

    private static final class DiffusionStage {
        private final float[] buffer;
        private final int sampleRate;
        private int delaySamples;
        private int index;
        private float feedback;

        DiffusionStage(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            this.buffer = new float[Math.max(32, Math.round(maxDelayMs * sampleRate / 1000f) + 16)];
            this.delaySamples = Math.min(buffer.length - 1, 8);
        }

        void configure(float delayMs, float feedback) {
            this.delaySamples = clampInt(Math.round(delayMs * sampleRate / 1000f), 4, buffer.length - 2);
            this.feedback = feedback;
            index = 0;
        }

        float process(float input) {
            int readIndex = index - delaySamples;
            if (readIndex < 0) {
                readIndex += buffer.length;
            }
            float delayed = buffer[readIndex];
            float output = delayed - input * feedback;
            buffer[index] = input + delayed * feedback;
            index++;
            if (index >= buffer.length) {
                index = 0;
            }
            return output;
        }

        void clear() {
            Arrays.fill(buffer, 0f);
            index = 0;
        }
    }

    private static final class StereoTapDelay {
        private final float[] buffer;
        private final int sampleRate;
        private int leftDelay;
        private int rightDelay;
        private int index;
        private float left;
        private float right;

        StereoTapDelay(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            this.buffer = new float[Math.max(64, Math.round(maxDelayMs * sampleRate / 1000f) + 32)];
        }

        void configure(float leftDelayMs, float rightDelayMs) {
            leftDelay = clampInt(Math.round(leftDelayMs * sampleRate / 1000f), 1, buffer.length - 2);
            rightDelay = clampInt(Math.round(rightDelayMs * sampleRate / 1000f), 1, buffer.length - 2);
            index = 0;
            left = 0f;
            right = 0f;
        }

        void process(float input) {
            buffer[index] = input;
            int leftRead = index - leftDelay;
            if (leftRead < 0) {
                leftRead += buffer.length;
            }
            int rightRead = index - rightDelay;
            if (rightRead < 0) {
                rightRead += buffer.length;
            }
            left = buffer[leftRead];
            right = buffer[rightRead];
            index++;
            if (index >= buffer.length) {
                index = 0;
            }
        }

        float left() {
            return left;
        }

        float right() {
            return right;
        }

        void clear() {
            Arrays.fill(buffer, 0f);
            index = 0;
            left = 0f;
            right = 0f;
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

        void clear() {
            Arrays.fill(buffer, 0f);
            writeIndex = 0;
        }
    }

    private static final class ModeProfile {
        static final float[] LEFT_TAPS = {0.34f, -0.26f, 0.29f, -0.18f, 0.31f, -0.23f, 0.19f, -0.17f};
        static final float[] RIGHT_TAPS = {-0.21f, 0.33f, -0.17f, 0.28f, -0.19f, 0.30f, -0.24f, 0.27f};

        final float brightness;
        final float feedbackBias;
        final float baseWidth;
        final float modDepth;
        final float modRate;
        final float attack;
        final float stereoSpread;
        final float[] baseDelayMs;
        final float[] diffusionDelayMs;
        final float minSizeScale;
        final float sizeRange;
        final float baseFeedback;
        final float decayRange;
        final float highDamping;
        final float lowDamping;
        final float inputScatter;
        final float sideScatter;
        final float outputTrim;
        final float earlyGain;
        final float lateGain;
        final float buildup;
        final float toneTilt;
        final float modDepthMs;
        final float modRateHz;
        final float modSpreadHz;
        final float earlyTapMs;
        final float earlySpreadMs;
        final float diffusionFeedback;

        ModeProfile(float brightness,
                    float feedbackBias,
                    float baseWidth,
                    float modDepth,
                    float modRate,
                    float attack,
                    float stereoSpread,
                    float[] baseDelayMs,
                    float[] diffusionDelayMs,
                    float minSizeScale,
                    float sizeRange,
                    float baseFeedback,
                    float decayRange,
                    float highDamping,
                    float lowDamping,
                    float inputScatter,
                    float sideScatter,
                    float outputTrim,
                    float earlyGain,
                    float lateGain,
                    float buildup,
                    float toneTilt,
                    float modDepthMs,
                    float modRateHz,
                    float modSpreadHz,
                    float earlyTapMs,
                    float earlySpreadMs,
                    float diffusionFeedback) {
            this.brightness = brightness;
            this.feedbackBias = feedbackBias;
            this.baseWidth = baseWidth;
            this.modDepth = modDepth;
            this.modRate = modRate;
            this.attack = attack;
            this.stereoSpread = stereoSpread;
            this.baseDelayMs = baseDelayMs;
            this.diffusionDelayMs = diffusionDelayMs;
            this.minSizeScale = minSizeScale;
            this.sizeRange = sizeRange;
            this.baseFeedback = baseFeedback;
            this.decayRange = decayRange;
            this.highDamping = highDamping;
            this.lowDamping = lowDamping;
            this.inputScatter = inputScatter;
            this.sideScatter = sideScatter;
            this.outputTrim = outputTrim;
            this.earlyGain = earlyGain;
            this.lateGain = lateGain;
            this.buildup = buildup;
            this.toneTilt = toneTilt;
            this.modDepthMs = modDepthMs;
            this.modRateHz = modRateHz;
            this.modSpreadHz = modSpreadHz;
            this.earlyTapMs = earlyTapMs;
            this.earlySpreadMs = earlySpreadMs;
            this.diffusionFeedback = diffusionFeedback;
        }

        static ModeProfile forType(String type) {
            if ("Plate".equals(type)) return new ModeProfile(
                    0.82f, 0.03f, 1.12f, 0.22f, 0.75f, 0.95f, 0.8f,
                    new float[] {9.7f, 11.3f, 13.1f, 15.1f, 17.8f, 20.9f, 24.2f, 28.7f},
                    new float[] {3.1f, 4.8f, 6.7f, 8.4f},
                    0.72f, 0.9f, 0.59f, 0.31f, 0.19f, 0.05f, 0.36f, 0.19f, 0.39f, 0.22f, 0.88f, 0.29f, 0.12f,
                    0.18f, 0.35f, 0.017f, 7.5f, 5.4f, 0.56f);
            if ("Hall".equals(type)) return new ModeProfile(
                    0.6f, 0.06f, 1.18f, 0.28f, 0.52f, 0.72f, 0.9f,
                    new float[] {18.4f, 22.1f, 26.7f, 31.3f, 37.6f, 44.8f, 53.9f, 64.2f},
                    new float[] {5.0f, 7.3f, 10.9f, 14.6f},
                    0.86f, 1.12f, 0.61f, 0.33f, 0.16f, 0.08f, 0.28f, 0.21f, 0.36f, 0.3f, 0.82f, 0.34f, 0.05f,
                    0.32f, 0.24f, 0.014f, 12.8f, 9.5f, 0.52f);
            if ("Chamber".equals(type)) return new ModeProfile(
                    0.68f, 0.02f, 1.04f, 0.2f, 0.6f, 0.86f, 0.66f,
                    new float[] {12.3f, 14.8f, 17.6f, 20.9f, 24.6f, 29.4f, 34.7f, 41.2f},
                    new float[] {4.1f, 5.9f, 8.2f, 11.1f},
                    0.78f, 0.96f, 0.57f, 0.28f, 0.18f, 0.06f, 0.31f, 0.17f, 0.4f, 0.26f, 0.85f, 0.32f, 0.08f,
                    0.22f, 0.3f, 0.016f, 9.2f, 6.3f, 0.54f);
            if ("Room".equals(type)) return new ModeProfile(
                    0.54f, -0.02f, 0.96f, 0.16f, 0.45f, 0.68f, 0.45f,
                    new float[] {7.4f, 8.8f, 10.7f, 12.1f, 14.3f, 16.8f, 19.4f, 22.9f},
                    new float[] {2.7f, 3.8f, 5.1f, 6.6f},
                    0.62f, 0.72f, 0.53f, 0.22f, 0.21f, 0.09f, 0.27f, 0.13f, 0.42f, 0.42f, 0.72f, 0.25f, 0.1f,
                    0.12f, 0.44f, 0.013f, 5.3f, 3.7f, 0.5f);
            if ("Studio".equals(type)) return new ModeProfile(
                    0.46f, -0.04f, 0.88f, 0.1f, 0.35f, 0.62f, 0.3f,
                    new float[] {5.7f, 6.8f, 8.1f, 9.5f, 11.2f, 13.4f, 15.1f, 18.2f},
                    new float[] {1.9f, 2.8f, 3.9f, 5.2f},
                    0.58f, 0.56f, 0.48f, 0.18f, 0.23f, 0.1f, 0.24f, 0.1f, 0.46f, 0.48f, 0.64f, 0.21f, 0.14f,
                    0.08f, 0.52f, 0.011f, 3.8f, 2.6f, 0.47f);
            return new ModeProfile(
                    0.65f, 0f, 1f, 0.18f, 0.5f, 0.8f, 0.55f,
                    new float[] {10.2f, 12.1f, 14.4f, 16.8f, 20.1f, 23.4f, 27.2f, 31.9f},
                    new float[] {3.8f, 5.4f, 7.6f, 10.1f},
                    0.74f, 0.84f, 0.56f, 0.29f, 0.18f, 0.07f, 0.3f, 0.16f, 0.4f, 0.28f, 0.8f, 0.29f, 0.08f,
                    0.16f, 0.31f, 0.015f, 8.1f, 5.8f, 0.52f);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp01(float value) {
        return clamp(value, 0f, 1f);
    }

    private static float clampSample(float value) {
        return clamp(value, -1f, 1f);
    }
}
