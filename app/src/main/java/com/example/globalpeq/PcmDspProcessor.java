package com.example.globalpeq;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PcmDspProcessor {
    private static final String TAG = "PcmDspProcessor";
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
        effectHeadroom = 1f;
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
                    enableDspBass ? preset.virtualBassAmountPercent : 0,
                    false);
            reverb = new AlgorithmicReverb(sampleRate, channelCount);
            reverb.configure(preset.reverbType,
                    preset.reverbDecayPercent,
                    preset.reverbPredelayMs,
                    preset.reverbSizePercent,
                    preset.reverbMixPercent,
                    preset.reverbMainMb,
                    false);
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
            float input = finiteOrZero(samples[i]);
            samples[i] = clampSample(input * pregain * effectHeadroom);
        }
        psychoacousticBass.process(samples, sampleCount, channelCount);
        for (Biquad filter : filters) {
            filter.process(samples, sampleCount, channelCount);
        }
        try {
            reverb.process(samples, sampleCount, channelCount);
        } catch (RuntimeException e) {
            Log.e(TAG, "Reverb processing failed, resetting reverb state", e);
            reverb = new AlgorithmicReverb(sampleRate, channelCount);
        }
        limiter.process(samples, sampleCount, channelCount);
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = clampSample(finiteOrZero(samples[i]));
        }
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
        private float[] octaveDc = new float[0];
        private float harmonicMix;
        private float octaveMix;
        private float sustainMix;
        private float lowBandLift;
        private float lowBandTrim;
        private float drive;
        private float saturationCeiling;
        private float safeAmount;
        private float harmonicCeiling;
        private float octaveCeiling;
        private float envelopeAttack;
        private float envelopeRelease;

        PsychoacousticBassProcessor(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.envelope = new float[channelCount];
            configure(140, 0, 95, 0, false);
        }

        void configure(int virtualCutoffHz,
                       int virtualAmountPercent,
                       int dspCutoffHz,
                       int dspAmountPercent,
                       boolean lowCpuMode) {
            float virtualAmount = clamp01(virtualAmountPercent / 100f);
            float dspAmount = clamp01(dspAmountPercent / 100f);
            float totalAmount = clamp01(virtualAmount * 0.72f + dspAmount * 0.96f);
            safeAmount = totalAmount <= 0.35f
                    ? totalAmount
                    : 0.35f + (totalAmount - 0.35f) * 0.55f;
            int blendedCutoff = clamp(
                    Math.round(virtualCutoffHz * 0.35f + dspCutoffHz * 0.65f),
                    55,
                    185);

            sourceHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, 24, 0, 68),
                    sampleRate,
                    channelCount);
            sourceLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, blendedCutoff, 0, 72),
                    sampleRate,
                    channelCount);
            harmonicHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(58, Math.round(blendedCutoff * 0.66f)), 0, 82),
                    sampleRate,
                    channelCount);
            harmonicLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(255, Math.max(118, Math.round(blendedCutoff * 1.55f))), 0, 88),
                    sampleRate,
                    channelCount);
            octaveHighPass = Biquad.fromBand(
                    new ParametricBand(FilterType.HIGH_PASS, true, Math.max(72, Math.round(blendedCutoff * 0.92f)), 0, 82),
                    sampleRate,
                    channelCount);
            octaveLowPass = Biquad.fromBand(
                    new ParametricBand(FilterType.LOW_PASS, true, Math.min(Math.max(150, Math.round(blendedCutoff * 2.18f)), 320), 0, 88),
                    sampleRate,
                    channelCount);

            harmonicMix = safeAmount * (0.22f + virtualAmount * 0.1f + dspAmount * 0.12f);
            octaveMix = safeAmount * (0.56f + virtualAmount * 0.18f + dspAmount * 0.22f);
            sustainMix = safeAmount * (0.015f + dspAmount * 0.03f + virtualAmount * 0.02f);
            lowBandLift = safeAmount * (0.09f + dspAmount * 0.08f + virtualAmount * 0.06f);
            lowBandTrim = safeAmount * (0.01f + dspAmount * 0.006f + virtualAmount * 0.004f);
            drive = 1.18f + safeAmount * 2.1f + dspAmount * 0.65f;
            saturationCeiling = 0.56f + (1f - totalAmount) * 0.08f;
            harmonicCeiling = 0.2f + (1f - totalAmount) * 0.05f;
            octaveCeiling = 0.28f + (1f - totalAmount) * 0.06f;
            envelopeAttack = 0.012f + safeAmount * 0.012f;
            envelopeRelease = 0.0028f + safeAmount * 0.0032f;
            for (int i = 0; i < envelope.length; i++) {
                envelope[i] = 0f;
            }
            if (octaveDc.length != channelCount) {
                octaveDc = new float[channelCount];
            }
            for (int i = 0; i < octaveDc.length; i++) {
                octaveDc[i] = 0f;
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (harmonicMix <= 0f && octaveMix <= 0f && sustainMix <= 0f && lowBandLift <= 0f && lowBandTrim <= 0f) {
                return;
            }

            if (lowBand.length < sampleCount) {
                lowBand = new float[sampleCount];
                harmonicBand = new float[sampleCount];
                octaveBand = new float[sampleCount];
            }
            if (octaveDc.length != channelCount) {
                octaveDc = new float[channelCount];
            }

            System.arraycopy(samples, 0, lowBand, 0, sampleCount);
            sourceHighPass.process(lowBand, sampleCount, channelCount);
            sourceLowPass.process(lowBand, sampleCount, channelCount);

            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float low = lowBand[i];
                float absLow = Math.abs(low);
                float coeff = absLow > envelope[channel] ? envelopeAttack : envelopeRelease;
                envelope[channel] += (absLow - envelope[channel]) * coeff;
                float normalized = low / (0.045f + envelope[channel] * 0.92f);
                float shaped = (float) Math.tanh(normalized * drive);
                float envelopeDrive = Math.min(1f, envelope[channel] * 6.0f);
                float dynamics = 0.48f + envelopeDrive * 0.34f;
                harmonicBand[i] = softLimit((shaped - normalized * 0.82f) * dynamics, harmonicCeiling);

                // Full-wave rectification gives us a cheap octave-up component from the sub band.
                float octaveRaw = Math.abs(shaped);
                octaveDc[channel] += (octaveRaw - octaveDc[channel]) * 0.012f;
                octaveBand[i] = softLimit((octaveRaw - octaveDc[channel]) * (0.84f + envelopeDrive * 0.36f), octaveCeiling);
            }

            harmonicHighPass.process(harmonicBand, sampleCount, channelCount);
            harmonicLowPass.process(harmonicBand, sampleCount, channelCount);
            octaveHighPass.process(octaveBand, sampleCount, channelCount);
            octaveLowPass.process(octaveBand, sampleCount, channelCount);

            for (int i = 0; i < sampleCount; i++) {
                samples[i] += softLimit(lowBand[i] * (lowBandLift + sustainMix), saturationCeiling);
                samples[i] += harmonicBand[i] * harmonicMix;
                samples[i] += octaveBand[i] * octaveMix;
                samples[i] -= lowBand[i] * lowBandTrim;
                samples[i] = finiteOrZero(samples[i]);
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        private static float softLimit(float value, float ceiling) {
            float safeCeiling = Math.max(0.05f, ceiling);
            return (float) Math.tanh(value / safeCeiling) * safeCeiling;
        }
    }

    private static final class AlgorithmicReverb {
        private final int sampleRate;
        private final int channelCount;
        private final float[][] preDelayBuffers;
        private final int[] preDelayIndices;
        private final StereoReverbCore reverbCore;
        private final float[] wetFrame = new float[2];
        private boolean lowCpuMode;
        private float wetMix;
        private float dryMix;
        private float wetGain;
        private float blendGain;
        private int preDelayLength;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.preDelayBuffers = new float[Math.max(1, channelCount)][Math.max(1, sampleRate / 2)];
            this.preDelayIndices = new int[Math.max(1, channelCount)];
            this.reverbCore = new StereoReverbCore(sampleRate);
            configure("Default", 0, 0, 0, 0, false);
        }

        void configure(String type,
                       int decayPercent,
                       int preDelayMs,
                       int sizePercent,
                       int mixPercent,
                       boolean lowCpuMode) {
            this.lowCpuMode = lowCpuMode;
            float size = clamp01(sizePercent / 100f);
            float mix = clamp01(mixPercent / 100f);
            ReverbProfile profile = ReverbProfile.forType(type);
            float decaySeconds = clamp(decayPercent / 100f, 0f, 12f);
            float decayShape = clamp01((Math.max(0.35f, decaySeconds) - 0.35f) / 11.65f);
            wetMix = "Default".equals(type) ? 0f : mix;
            dryMix = "Default".equals(type) ? 1f : 1f - wetMix * wetMix;
            wetGain = "Default".equals(type) ? 0f : wetMix;
            blendGain = 1f;
            preDelayLength = Math.max(0, Math.min(preDelayBuffers[0].length - 1, preDelayMs * sampleRate / 1000));
            float immediateEarlyBlend = clamp01(1f - preDelayMs / 8f);

            reverbCore.configure(profile, size, decaySeconds, decayShape, immediateEarlyBlend, lowCpuMode);

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
                reverbCore.process(leftIn, rightIn, wetFrame);

                float wetLeft = softSaturate(wetFrame[0] * 1.06f);
                float wetRight = channelCount > 1
                        ? softSaturate(wetFrame[1] * 1.06f)
                        : softSaturate((wetFrame[0] + wetFrame[1]) * 0.59f);
                samples[frameOffset] = clampSample((leftDry * dryMix + wetLeft * wetGain) * blendGain);
                if (channelCount > 1) {
                    samples[frameOffset + 1] = clampSample((rightDry * dryMix + wetRight * wetGain) * blendGain);
                }
                for (int channel = 2; channel < channelCount; channel++) {
                    float dry = samples[frameOffset + channel];
                    float wet = softSaturate((wetLeft + wetRight) * 0.5f);
                    samples[frameOffset + channel] = clampSample((dry * dryMix + wet * wetGain) * blendGain);
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

    private static final class StereoReverbCore {
        private final ReverbTank leftTank;
        private final ReverbTank rightTank;
        private final InputDiffuser leftInput;
        private final InputDiffuser rightInput;
        private final WetHighPass leftLowCut;
        private final WetHighPass rightLowCut;
        private final WetLowPass leftHighCut;
        private final WetLowPass rightHighCut;
        private final MonoPeakFilter leftLowMidTrim;
        private final MonoPeakFilter rightLowMidTrim;
        private boolean lowCpuMode;
        private float stereoCrossfeed;
        private float directEarlyMix;
        private float earlyMix;
        private float lateMix;
        private float outputGain;

        StereoReverbCore(int sampleRate) {
            leftTank = new ReverbTank(sampleRate);
            rightTank = new ReverbTank(sampleRate);
            leftInput = new InputDiffuser(sampleRate);
            rightInput = new InputDiffuser(sampleRate);
            leftLowCut = new WetHighPass(sampleRate, 60f);
            rightLowCut = new WetHighPass(sampleRate, 60f);
            leftHighCut = new WetLowPass(sampleRate);
            rightHighCut = new WetLowPass(sampleRate);
            leftLowMidTrim = new MonoPeakFilter(sampleRate);
            rightLowMidTrim = new MonoPeakFilter(sampleRate);
        }

        void configure(ReverbProfile profile, float size, float decaySeconds, float decayShape, float immediateEarlyBlend, boolean lowCpuMode) {
            this.lowCpuMode = lowCpuMode;
            stereoCrossfeed = profile.crossfeed * (0.74f + size * 0.22f);
            directEarlyMix = Math.min(profile.earlyMix * 0.34f, 0.11f) * clamp01(immediateEarlyBlend);
            earlyMix = Math.max(0f, profile.earlyMix - directEarlyMix) * (0.94f + decayShape * 0.12f);
            lateMix = profile.lateMix * (0.92f + decayShape * 0.16f);
            outputGain = profile.outputGain * (0.95f + decayShape * 0.08f);
            leftInput.configure(profile.diffusionMs, profile.diffusionFeedback, size, 0f, lowCpuMode);
            rightInput.configure(profile.diffusionMs, profile.diffusionFeedback, size, 0.31f, lowCpuMode);
            leftTank.configure(profile, size, decaySeconds, decayShape, false, lowCpuMode);
            rightTank.configure(profile, size, decaySeconds, decayShape, true, lowCpuMode);
            leftLowCut.reset();
            rightLowCut.reset();
            float adaptiveHighCutHz = clamp(profile.highCutHz * (1f - decayShape * 0.08f), 4200f, 12000f);
            leftHighCut.configure(adaptiveHighCutHz);
            rightHighCut.configure(adaptiveHighCutHz);
            float adaptiveLowMidCutDb = profile.lowMidCutDb * (0.96f + size * 0.08f + decayShape * 0.14f);
            leftLowMidTrim.configure(480f, -adaptiveLowMidCutDb, 0.76f);
            rightLowMidTrim.configure(480f, -adaptiveLowMidCutDb, 0.76f);
        }

        void process(float leftIn, float rightIn, float[] wetFrame) {
            float directLeft = leftIn + rightIn * 0.12f;
            float directRight = rightIn + leftIn * 0.12f;
            float diffuseLeft = leftInput.process(leftIn + rightIn * 0.18f);
            float diffuseRight = rightInput.process(rightIn + leftIn * 0.18f);
            float lateLeft = leftTank.process(diffuseLeft, rightTank.previousOutput() * stereoCrossfeed);
            float lateRight = rightTank.process(diffuseRight, leftTank.previousOutput() * stereoCrossfeed);
            float wetLeft = (directLeft * directEarlyMix + leftInput.lastTap() * earlyMix + lateLeft * lateMix) * outputGain;
            float wetRight = (directRight * directEarlyMix + rightInput.lastTap() * earlyMix + lateRight * lateMix) * outputGain;
            wetFrame[0] = clampSample(leftHighCut.process(leftLowMidTrim.process(leftLowCut.process(wetLeft))));
            wetFrame[1] = clampSample(rightHighCut.process(rightLowMidTrim.process(rightLowCut.process(wetRight))));
        }
    }

    private static final class InputDiffuser {
        private final AllPassStage[] stages;
        private float tap;

        InputDiffuser(int sampleRate) {
            stages = new AllPassStage[] {
                    new AllPassStage(sampleRate, 8.0f),
                    new AllPassStage(sampleRate, 12.0f),
                    new AllPassStage(sampleRate, 16.0f)
            };
        }

        void configure(float[] diffusionMs, float feedback, float size, float spreadOffset, boolean lowCpuMode) {
            int activeStages = lowCpuMode ? stages.length - 1 : stages.length;
            for (int i = 0; i < stages.length; i++) {
                float scale = 0.82f + size * 0.45f + spreadOffset * (i == 1 ? 0.04f : 0f);
                stages[i].configure(diffusionMs[i] * scale, clamp(feedback - i * 0.05f, 0.35f, 0.75f), i < activeStages);
            }
            tap = 0f;
        }

        float process(float input) {
            float sample = input;
            for (AllPassStage stage : stages) {
                sample = stage.process(sample);
            }
            tap = sample;
            return sample;
        }

        float lastTap() {
            return tap;
        }
    }

    private static final class ReverbTank {
        private final DampedComb[] combs;
        private final AllPassStage[] allpasses;
        private final SimpleDelay earlyDelay;
        private final DcHighPass dcHighPass;
        private float previousOutput;
        private float widthMix;
        private float postDamping;
        private float inputGain;
        private float tankGain;

        ReverbTank(int sampleRate) {
            combs = new DampedComb[] {
                    new DampedComb(sampleRate, 40.0f),
                    new DampedComb(sampleRate, 48.0f),
                    new DampedComb(sampleRate, 56.0f),
                    new DampedComb(sampleRate, 68.0f)
            };
            allpasses = new AllPassStage[] {
                    new AllPassStage(sampleRate, 6.0f),
                    new AllPassStage(sampleRate, 9.0f)
            };
            earlyDelay = new SimpleDelay(sampleRate, 20f);
            dcHighPass = new DcHighPass();
        }

        void configure(ReverbProfile profile, float size, float decaySeconds, float decayShape, boolean rightChannel, boolean lowCpuMode) {
            float sizeScale = profile.minSizeScale + size * profile.sizeRange;
            float damping = clamp(profile.damping + 0.03f + decayShape * 0.09f + size * 0.02f, 0.12f, 0.68f);
            float inputDiff = clamp(profile.inputGain + size * 0.03f, 0.18f, 0.38f);
            float offset = rightChannel ? 1.013f : 0.987f;
            widthMix = profile.width * (0.94f + size * 0.05f);
            postDamping = clamp(profile.postDamping + 0.02f + decayShape * 0.06f, 0.1f, 0.32f);
            inputGain = inputDiff;
            tankGain = profile.tankGain * (0.96f + decayShape * 0.08f);
            previousOutput = 0f;
            int activeCombs = lowCpuMode ? Math.max(2, combs.length - 1) : combs.length;
            for (int i = 0; i < combs.length; i++) {
                float combDelayMs = profile.combDelayMs[i] * sizeScale * offset;
                float feedback = clamp(calculateRt60Feedback(combDelayMs, decaySeconds)
                        * (0.9f + profile.baseFeedback * 0.1f)
                        - i * profile.feedbackSpread,
                        0.32f,
                        profile.feedbackCeiling);
                float modulationDepthMs = profile.modDepthMs
                        * (1f + size * 0.12f + decayShape * 0.42f * profile.modulationScale);
                combs[i].configure(combDelayMs,
                        feedback,
                        damping + i * 0.015f,
                        modulationDepthMs,
                        profile.modRateHz + i * profile.modSpreadHz + (rightChannel ? 0.009f : 0f),
                        i < activeCombs,
                        lowCpuMode);
            }
            int activeAllpasses = lowCpuMode ? 1 : allpasses.length;
            for (int i = 0; i < allpasses.length; i++) {
                allpasses[i].configure(profile.allPassDelayMs[i] * (0.9f + size * 0.35f) * offset,
                        clamp(profile.allPassFeedback - i * 0.06f, 0.35f, 0.7f),
                        i < activeAllpasses);
            }
            earlyDelay.configure(profile.earlyDelayMs * (0.85f + size * 0.35f) * offset);
            dcHighPass.reset();
        }

        float process(float input, float crossfeed) {
            float seeded = earlyDelay.process(input) * inputGain + crossfeed;
            float tankSum = 0f;
            for (DampedComb comb : combs) {
                tankSum += comb.process(seeded);
            }
            float tankOut = tankSum / combs.length;
            for (AllPassStage allPass : allpasses) {
                tankOut = allPass.process(tankOut);
            }
            previousOutput += postDamping * (tankOut - previousOutput);
            previousOutput = dcHighPass.process(finiteOrZero(previousOutput * tankGain));
            previousOutput = clamp(previousOutput, -1.2f, 1.2f);
            return previousOutput * widthMix;
        }

        float previousOutput() {
            return previousOutput;
        }

        private static float calculateRt60Feedback(float delayMs, float decaySeconds) {
            float safeDelaySeconds = Math.max(0.001f, delayMs / 1000f);
            float safeRt60 = Math.max(0.35f, decaySeconds);
            return (float) Math.pow(0.001, safeDelaySeconds / safeRt60);
        }
    }

    private static final class WetHighPass {
        private final float alpha;
        private float previousInput;
        private float previousOutput;

        WetHighPass(int sampleRate, float cutoffHz) {
            float safeSampleRate = Math.max(8000f, sampleRate);
            float safeCutoff = Math.max(20f, cutoffHz);
            float rc = 1f / ((float) (2.0 * Math.PI) * safeCutoff);
            float dt = 1f / safeSampleRate;
            alpha = rc / (rc + dt);
        }

        float process(float input) {
            float output = alpha * (previousOutput + input - previousInput);
            previousInput = input;
            previousOutput = finiteOrZero(output);
            return previousOutput;
        }

        void reset() {
            previousInput = 0f;
            previousOutput = 0f;
        }
    }

    private static final class WetLowPass {
        private final int sampleRate;
        private float alpha = 1f;
        private float state;

        WetLowPass(int sampleRate) {
            this.sampleRate = Math.max(8000, sampleRate);
        }

        void configure(float cutoffHz) {
            float safeCutoff = clamp(cutoffHz, 1000f, sampleRate * 0.45f);
            float rc = 1f / ((float) (2.0 * Math.PI) * safeCutoff);
            float dt = 1f / sampleRate;
            alpha = dt / (rc + dt);
            state = 0f;
        }

        float process(float input) {
            state += alpha * (input - state);
            state = finiteOrZero(state);
            return state;
        }
    }

    private static final class MonoPeakFilter {
        private final int sampleRate;
        private float b0 = 1f;
        private float b1;
        private float b2;
        private float a1;
        private float a2;
        private float z1;
        private float z2;
        private boolean active;

        MonoPeakFilter(int sampleRate) {
            this.sampleRate = Math.max(8000, sampleRate);
        }

        void configure(float frequencyHz, float gainDb, float q) {
            if (Math.abs(gainDb) < 0.05f) {
                active = false;
                reset();
                return;
            }
            double frequency = clamp(frequencyHz, 20f, this.sampleRate * 0.45f);
            double omega = 2.0 * Math.PI * frequency / this.sampleRate;
            double sin = Math.sin(omega);
            double cos = Math.cos(omega);
            double safeQ = Math.max(0.25, q);
            double a = Math.pow(10.0, gainDb / 40.0);
            double alpha = sin / (2.0 * safeQ);

            double cb0 = 1.0 + alpha * a;
            double cb1 = -2.0 * cos;
            double cb2 = 1.0 - alpha * a;
            double ca0 = 1.0 + alpha / a;
            double ca1 = -2.0 * cos;
            double ca2 = 1.0 - alpha / a;

            b0 = (float) (cb0 / ca0);
            b1 = (float) (cb1 / ca0);
            b2 = (float) (cb2 / ca0);
            a1 = (float) (ca1 / ca0);
            a2 = (float) (ca2 / ca0);
            active = true;
            reset();
        }

        float process(float input) {
            if (!active) {
                return input;
            }
            float output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            return finiteOrZero(output);
        }

        void reset() {
            z1 = 0f;
            z2 = 0f;
        }
    }

    private static final class DampedComb {
        private final ModulatedDelay delay;
        private float feedback;
        private float damping;
        private float filterState;
        private boolean active = true;

        DampedComb(int sampleRate, float maxDelayMs) {
            delay = new ModulatedDelay(sampleRate, maxDelayMs);
        }

        void configure(float delayMs,
                       float feedback,
                       float damping,
                       float modDepthMs,
                       float modRateHz,
                       boolean active,
                       boolean lowCpuMode) {
            this.active = active;
            delay.configure(delayMs, lowCpuMode ? modDepthMs * 0.42f : modDepthMs, modRateHz, active);
            this.feedback = clamp(feedback, 0.2f, 0.88f);
            this.damping = clamp(damping, 0.05f, 0.6f);
            filterState = 0f;
        }

        float process(float input) {
            if (!active) {
                return 0f;
            }
            float delayed = delay.read();
            filterState += damping * (delayed - filterState);
            float filtered = filterState;
            float next = softSaturate(input + filtered * feedback);
            delay.write(next);
            return softSaturate(filtered * 0.84f + delayed * 0.16f);
        }
    }

    private static final class AllPassStage {
        private final float[] buffer;
        private final int sampleRate;
        private int delaySamples;
        private int index;
        private float feedback;
        private boolean active = true;

        AllPassStage(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            buffer = new float[Math.max(32, Math.round(maxDelayMs * sampleRate / 1000f) + 16)];
            delaySamples = 8;
        }

        void configure(float delayMs, float feedback, boolean active) {
            this.active = active;
            delaySamples = clampInt(Math.round(delayMs * sampleRate / 1000f), 4, buffer.length - 2);
            this.feedback = feedback;
            Arrays.fill(buffer, 0f);
            index = 0;
        }

        float process(float input) {
            if (!active) {
                return input;
            }
            int readIndex = index - delaySamples;
            if (readIndex < 0) {
                readIndex += buffer.length;
            }
            float delayed = buffer[readIndex];
            float output = delayed - input * feedback;
            buffer[index] = finiteOrZero(input + delayed * feedback);
            index++;
            if (index >= buffer.length) {
                index = 0;
            }
            return finiteOrZero(output);
        }
    }

    private static final class SimpleDelay {
        private final float[] buffer;
        private final int sampleRate;
        private int delaySamples;
        private int index;

        SimpleDelay(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            buffer = new float[Math.max(32, Math.round(maxDelayMs * sampleRate / 1000f) + 16)];
        }

        void configure(float delayMs) {
            delaySamples = clampInt(Math.round(delayMs * sampleRate / 1000f), 1, buffer.length - 2);
            Arrays.fill(buffer, 0f);
            index = 0;
        }

        float process(float input) {
            int readIndex = index - delaySamples;
            if (readIndex < 0) {
                readIndex += buffer.length;
            }
            float output = buffer[readIndex];
            buffer[index] = finiteOrZero(input);
            index++;
            if (index >= buffer.length) {
                index = 0;
            }
            return finiteOrZero(output);
        }
    }

    private static final class ModulatedDelay {
        private static final float TWO_PI = (float) (Math.PI * 2.0);
        private final float[] buffer;
        private final int sampleRate;
        private int writeIndex;
        private float delaySamples;
        private float modDepthSamples;
        private float phase;
        private float phaseIncrement;
        private boolean active = true;

        ModulatedDelay(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            buffer = new float[Math.max(64, Math.round(maxDelayMs * sampleRate / 1000f) + 32)];
        }

        void configure(float delayMs, float modDepthMs, float modRateHz, boolean active) {
            this.active = active;
            delaySamples = clamp(delayMs * sampleRate / 1000f, 4f, buffer.length - 4f);
            modDepthSamples = active
                    ? clamp(modDepthMs * sampleRate / 1000f, 0f, Math.min(5f, delaySamples * 0.12f))
                    : 0f;
            phase = 0f;
            phaseIncrement = active ? (float) (2.0 * Math.PI * modRateHz / sampleRate) : 0f;
            Arrays.fill(buffer, 0f);
            writeIndex = 0;
        }

        float read() {
            float modulation = active ? (float) Math.sin(phase) * modDepthSamples : 0f;
            float readPos = writeIndex - delaySamples - modulation;
            readPos = wrapReadPosition(readPos, buffer.length);
            int indexA = Math.min(buffer.length - 1, (int) readPos);
            int indexB = (indexA + 1) % buffer.length;
            float frac = readPos - indexA;
            return finiteOrZero(buffer[indexA] + (buffer[indexB] - buffer[indexA]) * frac);
        }

        void write(float value) {
            buffer[writeIndex] = clampSample(value);
            writeIndex++;
            if (writeIndex >= buffer.length) {
                writeIndex = 0;
            }
            if (active && modDepthSamples > 0f) {
                phase += phaseIncrement;
                if (phase >= TWO_PI) {
                    phase -= TWO_PI;
                }
            }
        }

        private static float wrapReadPosition(float readPos, int bufferLength) {
            if (!Float.isFinite(readPos) || bufferLength <= 0) {
                return 0f;
            }
            while (readPos < 0f) {
                readPos += bufferLength;
            }
            while (readPos >= bufferLength) {
                readPos -= bufferLength;
            }
            return readPos;
        }
    }

    private static final class DcHighPass {
        private float x1;
        private float y1;

        float process(float input) {
            float output = input - x1 + 0.995f * y1;
            x1 = input;
            y1 = output;
            return output;
        }

        void reset() {
            x1 = 0f;
            y1 = 0f;
        }
    }

    private static final class ReverbProfile {
        final float[] combDelayMs;
        final float[] allPassDelayMs;
        final float[] diffusionMs;
        final float minSizeScale;
        final float sizeRange;
        final float baseFeedback;
        final float decayRange;
        final float damping;
        final float inputGain;
        final float earlyMix;
        final float lateMix;
        final float outputGain;
        final float crossfeed;
        final float tankGain;
        final float width;
        final float postDamping;
        final float allPassFeedback;
        final float diffusionFeedback;
        final float feedbackSpread;
        final float modDepthMs;
        final float modRateHz;
        final float modSpreadHz;
        final float earlyDelayMs;
        final float feedbackCeiling;
        final float modulationScale;
        final float highCutHz;
        final float lowMidCutDb;

        ReverbProfile(float[] combDelayMs,
                      float[] allPassDelayMs,
                      float[] diffusionMs,
                      float minSizeScale,
                      float sizeRange,
                      float baseFeedback,
                      float decayRange,
                      float damping,
                      float inputGain,
                      float earlyMix,
                      float lateMix,
                      float outputGain,
                      float crossfeed,
                      float tankGain,
                      float width,
                      float postDamping,
                      float allPassFeedback,
                      float diffusionFeedback,
                      float feedbackSpread,
                      float modDepthMs,
                      float modRateHz,
                      float modSpreadHz,
                      float earlyDelayMs,
                      float feedbackCeiling,
                      float modulationScale,
                      float highCutHz,
                      float lowMidCutDb) {
            this.combDelayMs = combDelayMs;
            this.allPassDelayMs = allPassDelayMs;
            this.diffusionMs = diffusionMs;
            this.minSizeScale = minSizeScale;
            this.sizeRange = sizeRange;
            this.baseFeedback = baseFeedback;
            this.decayRange = decayRange;
            this.damping = damping;
            this.inputGain = inputGain;
            this.earlyMix = earlyMix;
            this.lateMix = lateMix;
            this.outputGain = outputGain;
            this.crossfeed = crossfeed;
            this.tankGain = tankGain;
            this.width = width;
            this.postDamping = postDamping;
            this.allPassFeedback = allPassFeedback;
            this.diffusionFeedback = diffusionFeedback;
            this.feedbackSpread = feedbackSpread;
            this.modDepthMs = modDepthMs;
            this.modRateHz = modRateHz;
            this.modSpreadHz = modSpreadHz;
            this.earlyDelayMs = earlyDelayMs;
            this.feedbackCeiling = feedbackCeiling;
            this.modulationScale = modulationScale;
            this.highCutHz = highCutHz;
            this.lowMidCutDb = lowMidCutDb;
        }

        static ReverbProfile forType(String type) {
            if ("Plate".equals(type)) {
                return new ReverbProfile(
                        new float[] {29.4f, 34.7f, 38.9f, 42.6f},
                        new float[] {3.7f, 5.9f},
                        new float[] {4.2f, 6.8f, 9.7f},
                        0.74f, 0.52f, 0.57f, 0.18f, 0.19f, 0.27f,
                        0.24f, 0.92f, 0.34f, 0.18f, 0.88f, 1.08f, 0.15f,
                        0.58f, 0.6f, 0.026f, 0.11f, 0.15f, 0.011f, 5.8f,
                        0.86f, 1.08f, 6500f, 1.7f);
            }
            if ("Hall".equals(type)) {
                return new ReverbProfile(
                        new float[] {36.8f, 44.5f, 52.1f, 61.3f},
                        new float[] {5.1f, 8.4f},
                        new float[] {5.4f, 8.1f, 12.4f},
                        0.88f, 0.78f, 0.61f, 0.2f, 0.24f, 0.25f,
                        0.18f, 0.96f, 0.31f, 0.22f, 0.9f, 1.12f, 0.18f,
                        0.6f, 0.54f, 0.022f, 0.18f, 0.115f, 0.009f, 9.4f,
                        0.85f, 1.18f, 6100f, 2.1f);
            }
            if ("Chamber".equals(type)) {
                return new ReverbProfile(
                        new float[] {24.6f, 29.8f, 35.4f, 41.1f},
                        new float[] {3.9f, 6.3f},
                        new float[] {4.0f, 6.0f, 8.8f},
                        0.78f, 0.58f, 0.55f, 0.17f, 0.2f, 0.26f,
                        0.22f, 0.93f, 0.33f, 0.17f, 0.87f, 1.03f, 0.15f,
                        0.56f, 0.58f, 0.024f, 0.13f, 0.14f, 0.01f, 6.6f,
                        0.86f, 1.08f, 6800f, 1.45f);
            }
            if ("Room".equals(type)) {
                return new ReverbProfile(
                        new float[] {15.8f, 19.7f, 23.4f, 27.1f},
                        new float[] {2.8f, 4.1f},
                        new float[] {2.9f, 4.3f, 6.2f},
                        0.66f, 0.36f, 0.47f, 0.12f, 0.27f, 0.22f,
                        0.3f, 0.88f, 0.38f, 0.12f, 0.84f, 0.98f, 0.13f,
                        0.48f, 0.5f, 0.018f, 0.075f, 0.095f, 0.008f, 3.7f,
                        0.84f, 0.94f, 5600f, 0.9f);
            }
            if ("Studio".equals(type)) {
                return new ReverbProfile(
                        new float[] {11.2f, 13.8f, 16.9f, 19.3f},
                        new float[] {2.1f, 3.3f},
                        new float[] {2.3f, 3.5f, 4.9f},
                        0.58f, 0.28f, 0.42f, 0.1f, 0.3f, 0.2f,
                        0.34f, 0.84f, 0.42f, 0.08f, 0.8f, 0.92f, 0.11f,
                        0.44f, 0.46f, 0.016f, 0.06f, 0.085f, 0.007f, 2.8f,
                        0.83f, 0.88f, 5000f, 0.8f);
            }
            return new ReverbProfile(
                    new float[] {20.1f, 24.2f, 28.7f, 33.1f},
                    new float[] {3.4f, 5.2f},
                    new float[] {3.5f, 5.3f, 7.6f},
                    0.72f, 0.48f, 0.52f, 0.16f, 0.22f, 0.24f,
                    0.24f, 0.9f, 0.35f, 0.15f, 0.86f, 1f, 0.14f,
                    0.52f, 0.54f, 0.022f, 0.1f, 0.125f, 0.009f, 5.1f,
                    0.85f, 1.04f, 6400f, 1.15f);
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

    private static float softSaturate(float value) {
        return finiteOrZero((float) Math.tanh(value * 0.92f));
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }
}
