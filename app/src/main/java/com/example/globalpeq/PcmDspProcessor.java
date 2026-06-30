package com.example.globalpeq;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PcmDspProcessor {
    private static final String TAG = "PcmDspProcessor";
    private static final int EXTRA_BASS_MAX_GAIN_MB = 1500;

    private final List<Biquad> filters = new ArrayList<>();
    private int sampleRate = 48000;
    private int channelCount = 2;
    private float pregain = 1f;
    private float effectHeadroom = 1f;
    private PsychoacousticBassProcessor psychoacousticBass = new PsychoacousticBassProcessor(48000, 2);
    private AlgorithmicReverb reverb = new AlgorithmicReverb(48000, 2);
    private LookaheadLimiter limiter = new LookaheadLimiter(48000, 2);
    private boolean processingActive;

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
        AdvancedModeConfig safeConfig = config == null ? AdvancedModeConfig.DEFAULT : config;
        pregain = preset == null ? 1f : dbToLinear(preset.pregainMb / 100f);
        effectHeadroom = 1f;
        filters.clear();

        if (preset != null) {
            if (preset.extraBassEnabled && preset.extraBassAmountPercent > 0) {
                int extraBassGainMb = Math.round(preset.extraBassAmountPercent / 100f * EXTRA_BASS_MAX_GAIN_MB);
                filters.add(Biquad.fromBand(
                        new ParametricBand(
                                FilterType.LOW_SHELF,
                                true,
                                preset.extraBassCutoffHz,
                                extraBassGainMb,
                                70),
                        sampleRate,
                        channelCount));
            }
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
                    if (!band.enabled) {
                        continue;
                    }
                    for (ParametricBand effectiveBand : PeqMath.effectiveResponseBands(band)) {
                        filters.add(Biquad.fromBand(effectiveBand, sampleRate, channelCount));
                    }
                }
            }
            if (psychoacousticBass.sampleRate != sampleRate || psychoacousticBass.channelCount != channelCount) {
                psychoacousticBass = new PsychoacousticBassProcessor(sampleRate, channelCount);
            }
            psychoacousticBass.configure(
                    preset.systemVirtualBassCutoffHz,
                    0,
                    preset.dspVirtualBassCutoffHz,
                    enableDspBass ? preset.dspVirtualBassAmountPercent : 0,
                    false);
            if (reverb.sampleRate != sampleRate || reverb.channelCount != channelCount) {
                reverb = new AlgorithmicReverb(sampleRate, channelCount);
            }
            reverb.configure(
                    preset.reverbType,
                    preset.reverbDecayPercent,
                    preset.reverbPredelayMs,
                    preset.reverbSizePercent,
                    preset.reverbMixPercent,
                    preset.reverbMainMb,
                    false);
        } else {
            psychoacousticBass.configure(95, 0, 95, 0, false);
            reverb.configure("Default", 0, 0, 0, 0, 0, false);
        }

        if (limiter.sampleRate != sampleRate || limiter.channelCount != channelCount) {
            limiter = new LookaheadLimiter(sampleRate, channelCount);
        }
        limiter.configure(
                safeConfig.lookaheadMs,
                safeConfig.latencyMs,
                safeConfig.limiterCeilingPermille / 1000f,
                safeConfig.limiterReleaseMs);

        processingActive = Math.abs(pregain * effectHeadroom - 1f) > 0.0001f
                || !filters.isEmpty()
                || psychoacousticBass.isActive()
                || reverb.isActive()
                || limiter.isActive();
    }

    void processInterleaved(float[] samples, int frameCount) {
        if (samples == null || frameCount <= 0 || !processingActive) {
            return;
        }

        int sampleCount = Math.min(samples.length, frameCount * channelCount);
        for (int i = 0; i < sampleCount; i++) {
            float input = finiteOrZero(samples[i]);
            // Keep full internal headroom here so boosted peaks reach the limiter intact
            // instead of being hard-clipped before the DSP chain can control them.
            samples[i] = finiteOrZero(input * pregain * effectHeadroom);
        }
        for (Biquad filter : filters) {
            filter.process(samples, sampleCount, channelCount);
        }
        psychoacousticBass.process(samples, sampleCount, channelCount);
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
            double[] coefficients = PeqMath.normalizedBiquadCoefficients(band, sampleRate);
            biquad.b0 = (float) coefficients[0];
            biquad.b1 = (float) coefficients[1];
            biquad.b2 = (float) coefficients[2];
            biquad.a1 = (float) coefficients[3];
            biquad.a2 = (float) coefficients[4];
            if (!Float.isFinite(biquad.b0)
                    || !Float.isFinite(biquad.b1)
                    || !Float.isFinite(biquad.b2)
                    || !Float.isFinite(biquad.a1)
                    || !Float.isFinite(biquad.a2)) {
                biquad.b0 = 1f;
                biquad.b1 = 0f;
                biquad.b2 = 0f;
                biquad.a1 = 0f;
                biquad.a2 = 0f;
            }
            return biquad;
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (channelCount == 2) {
                for (int i = 0; i + 1 < sampleCount; i += 2) {
                    float inputLeft = samples[i];
                    float outputLeft = finiteOrZero(b0 * inputLeft + z1[0]);
                    float nextZ1Left = finiteOrZero(b1 * inputLeft - a1 * outputLeft + z2[0]);
                    float nextZ2Left = finiteOrZero(b2 * inputLeft - a2 * outputLeft);
                    z1[0] = nextZ1Left;
                    z2[0] = nextZ2Left;
                    samples[i] = outputLeft;

                    float inputRight = samples[i + 1];
                    float outputRight = finiteOrZero(b0 * inputRight + z1[1]);
                    float nextZ1Right = finiteOrZero(b1 * inputRight - a1 * outputRight + z2[1]);
                    float nextZ2Right = finiteOrZero(b2 * inputRight - a2 * outputRight);
                    z1[1] = nextZ1Right;
                    z2[1] = nextZ2Right;
                    samples[i + 1] = outputRight;
                }
                return;
            }
            for (int i = 0; i < sampleCount; i++) {
                int channel = i % channelCount;
                float input = samples[i];
                float output = finiteOrZero(b0 * input + z1[channel]);
                float nextZ1 = finiteOrZero(b1 * input - a1 * output + z2[channel]);
                float nextZ2 = finiteOrZero(b2 * input - a2 * output);
                z1[channel] = nextZ1;
                z2[channel] = nextZ2;
                samples[i] = output;
            }
        }
    }

    private static final class PsychoacousticBassProcessor {
        private static final int MIN_CUTOFF_HZ = 20;
        private static final int MAX_CUTOFF_HZ = 220;
        private final int sampleRate;
        private final int channelCount;

        private MonoBiquad sourceHighPass;
        private MonoBiquad sourceLowPass;
        private MonoBiquad harmonicHighPass;
        private MonoBiquad harmonicLowPass;
        private MonoBiquad[] dryBassLowStageA;
        private MonoBiquad[] dryBassLowStageB;

        private float detectorState;
        private float cubicState;

        private float wetMix;
        private float secondHarmonicGain;
        private float thirdHarmonicGain;
        private float harmonicOutputGain;
        private float bassCompressorEnvelope;
        private float bassCompressorAttackCoeff;
        private float bassCompressorReleaseCoeff;
        private float bassCompressorThreshold = 0.24f;
        private float bassCompressorRatio = 1.24f;
        private float bassCompressorMakeup = 1.04f;
        private float bassCompressorKnee = 0.08f;
        private float transientFastEnvelope;
        private float transientSlowEnvelope;
        private float transientFastCoeff;
        private float transientSlowCoeff;
        private float transientDuckThreshold = 0.010f;
        private float transientDuckRange = 0.060f;
        private float transientDuckDepth = 0.32f;
        private float dryBassDuckEnvelope;
        private float dryBassDuckAttackCoeff;
        private float dryBassDuckReleaseCoeff;
        private float dryBassDuckThreshold = 0.08f;
        private float dryBassDuckRange = 0.20f;
        private float dryBassDuckDepth = 0.18f;
        private boolean active;

        PsychoacousticBassProcessor(int sampleRate, int channelCount) {
            this.sampleRate = Math.max(8000, sampleRate);
            this.channelCount = Math.max(1, channelCount);
            rebuildFilters(95, 0f);
        }

        void configure(int virtualCutoffHz,
                       int virtualAmountPercent,
                       int dspCutoffHz,
                       int dspAmountPercent,
                       boolean lowCpuMode) {
            int amountPercent = Math.max(
                    clampInt(virtualAmountPercent, 0, 100),
                    clampInt(dspAmountPercent, 0, 100)
            );
            if (amountPercent <= 0) {
                active = false;
                wetMix = 0f;
                resetRuntime();
                return;
            }

            int requestedCutoff = dspAmountPercent > 0 ? dspCutoffHz : virtualCutoffHz;
            if (requestedCutoff <= 0) {
                requestedCutoff = Math.max(virtualCutoffHz, dspCutoffHz);
            }
            int targetCutoffHz = clampInt(requestedCutoff, MIN_CUTOFF_HZ, MAX_CUTOFF_HZ);
            float amount = amountPercent / 100f;
            float cutoffProgress = clamp((targetCutoffHz - MIN_CUTOFF_HZ) / (float) (MAX_CUTOFF_HZ - MIN_CUTOFF_HZ), 0f, 1f);
            float cutoffCompensation = 1.90f - 1.35f * (float) Math.sqrt(cutoffProgress);
            float outputTrim = 1.08f - 0.42f * cutoffProgress;

            rebuildFilters(targetCutoffHz, amount);

            wetMix = (0.72f + (float) Math.pow(amount, 0.98f) * 8.80f) * cutoffCompensation;

            secondHarmonicGain = 1.20f + amount * 1.80f;
            thirdHarmonicGain = 0.04f + amount * 0.10f;
            harmonicOutputGain = (0.72f + amount * 0.88f) * outputTrim;
            bassCompressorThreshold = 0.30f - amount * 0.02f;
            bassCompressorRatio = 1.10f + amount * 0.10f;
            bassCompressorMakeup = 1.00f + amount * 0.03f;
            bassCompressorKnee = 0.07f + amount * 0.02f;
            bassCompressorAttackCoeff = envelopeCoeff(0.0055f);
            bassCompressorReleaseCoeff = envelopeCoeff(0.020f);
            transientFastCoeff = envelopeCoeff(0.0055f);
            transientSlowCoeff = envelopeCoeff(0.030f);
            transientDuckThreshold = 0.010f + amount * 0.004f;
            transientDuckRange = 0.040f + amount * 0.015f;
            transientDuckDepth = 0.34f + amount * 0.16f;
            dryBassDuckAttackCoeff = envelopeCoeff(0.0045f);
            dryBassDuckReleaseCoeff = envelopeCoeff(0.045f);
            dryBassDuckThreshold = 0.075f + amount * 0.020f;
            dryBassDuckRange = 0.18f + amount * 0.06f;
            dryBassDuckDepth = 0.12f + amount * 0.10f;

            if (lowCpuMode) {
                wetMix *= 0.96f;
                harmonicOutputGain *= 0.95f;
                bassCompressorMakeup *= 0.99f;
            }

            active = true;
            detectorState = 0f;
            cubicState = 0f;
            bassCompressorEnvelope = 0f;
            transientFastEnvelope = 0f;
            transientSlowEnvelope = 0f;
            dryBassDuckEnvelope = 0f;
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (!active || samples == null || samples.length == 0) {
                return;
            }

            int safeChannelCount = Math.max(1, Math.min(this.channelCount, channelCount > 0 ? channelCount : this.channelCount));
            int safeSampleCount = Math.min(sampleCount, samples.length);
            int frameCount = safeSampleCount / safeChannelCount;
            if (frameCount <= 0) {
                return;
            }

            for (int frame = 0; frame < frameCount; frame++) {
                int frameOffset = frame * safeChannelCount;
                float mono = 0f;
                for (int ch = 0; ch < safeChannelCount; ch++) {
                    mono += finiteOrZero(samples[frameOffset + ch]);
                }
                mono /= safeChannelCount;

                float lowBand = sourceLowPass.process(sourceHighPass.process(mono));
                float harmonic = shapeHarmonics(lowBand);
                float wet = applyTransientWetDuck(lowBand, harmonic * wetMix);
                float dryBassGain = applyDryBassSidechain(wet);

                for (int ch = 0; ch < safeChannelCount; ch++) {
                    float input = finiteOrZero(samples[frameOffset + ch]);
                    float dryBass = extractDryBass(input, ch);
                    float dryRest = input - dryBass;
                    samples[frameOffset + ch] = clampSample(dryRest + dryBass * dryBassGain + wet);
                }
            }
        }

        boolean isActive() {
            return active;
        }

        private float shapeHarmonics(float lowBand) {
            float normalized = finiteOrZero(lowBand);

            float squared = normalized * normalized;
            detectorState += (squared - detectorState) * 0.010f;
            float second = (squared - detectorState) * secondHarmonicGain;
            float cubic = normalized * normalized * normalized;
            cubicState += (cubic - cubicState) * 0.010f;
            float third = (cubic - cubicState) * thirdHarmonicGain;
            float source = second + third;

            float bandLimited = harmonicHighPass.process(source);
            bandLimited = harmonicLowPass.process(bandLimited);
            return applyBassCompressor(finiteOrZero(bandLimited * harmonicOutputGain));
        }

        private void rebuildFilters(int targetCutoffHz, float amount) {
            float safeTargetCutoff = clamp(targetCutoffHz, MIN_CUTOFF_HZ, Math.min(MAX_CUTOFF_HZ, sampleRate * 0.20f));
            float cutoffProgress = clamp((safeTargetCutoff - MIN_CUTOFF_HZ) / (float) (MAX_CUTOFF_HZ - MIN_CUTOFF_HZ), 0f, 1f);
            float sourceHpRatio = 0.54f + cutoffProgress * 0.28f;
            float sourceLpRatio = (1.38f + amount * 0.05f) - cutoffProgress * 0.26f;
            float dryBassLpHz = clamp(
                    safeTargetCutoff * (1.08f - cutoffProgress * 0.06f),
                    32f,
                    Math.min(MAX_CUTOFF_HZ, sampleRate * 0.16f));

            float sourceHpHz = clamp(
                    safeTargetCutoff * sourceHpRatio,
                    20f,
                    Math.min(MAX_CUTOFF_HZ, sampleRate * 0.12f));
            float sourceLpHz = clamp(
                    safeTargetCutoff * sourceLpRatio,
                    sourceHpHz + (18f - cutoffProgress * 10f),
                    Math.min(MAX_CUTOFF_HZ, sampleRate * 0.18f));
            sourceHighPass = createMonoFilter(FilterType.HIGH_PASS, sourceHpHz, 85);
            sourceLowPass = createMonoFilter(FilterType.LOW_PASS, sourceLpHz, 85);

            float harmonicHpHz = clamp(
                    safeTargetCutoff * 1.74f,
                    Math.max(38f, safeTargetCutoff * 1.58f),
                    safeTargetCutoff * 1.92f);
            float harmonicLpHz = clamp(
                    safeTargetCutoff * (2.30f + amount * 0.08f),
                    harmonicHpHz + 20f,
                    Math.min(560f, sampleRate * 0.14f));

            harmonicHighPass = createMonoFilter(FilterType.HIGH_PASS, harmonicHpHz, 70);
            harmonicLowPass = createMonoFilter(FilterType.LOW_PASS, harmonicLpHz, 70);
            dryBassLowStageA = new MonoBiquad[channelCount];
            dryBassLowStageB = new MonoBiquad[channelCount];
            for (int ch = 0; ch < channelCount; ch++) {
                dryBassLowStageA[ch] = createMonoFilter(FilterType.LOW_PASS, dryBassLpHz, 80);
                dryBassLowStageB[ch] = createMonoFilter(FilterType.LOW_PASS, dryBassLpHz, 80);
            }
        }

        private void resetRuntime() {
            detectorState = 0f;
            cubicState = 0f;
            bassCompressorEnvelope = 0f;
            transientFastEnvelope = 0f;
            transientSlowEnvelope = 0f;
            dryBassDuckEnvelope = 0f;
            if (sourceHighPass != null) {
                sourceHighPass.reset();
            }
            if (sourceLowPass != null) {
                sourceLowPass.reset();
            }
            if (harmonicHighPass != null) {
                harmonicHighPass.reset();
            }
            if (harmonicLowPass != null) {
                harmonicLowPass.reset();
            }
            if (dryBassLowStageA != null) {
                for (MonoBiquad filter : dryBassLowStageA) {
                    if (filter != null) {
                        filter.reset();
                    }
                }
            }
            if (dryBassLowStageB != null) {
                for (MonoBiquad filter : dryBassLowStageB) {
                    if (filter != null) {
                        filter.reset();
                    }
                }
            }
        }

        private MonoBiquad createMonoFilter(FilterType type, float frequencyHz, int qHundred) {
            return MonoBiquad.fromBand(new ParametricBand(type, true, Math.round(frequencyHz), 0, qHundred), sampleRate);
        }

        private float applyBassCompressor(float sample) {
            float level = Math.abs(sample);
            float coeff = level > bassCompressorEnvelope ? bassCompressorAttackCoeff : bassCompressorReleaseCoeff;
            bassCompressorEnvelope = level + coeff * (bassCompressorEnvelope - level);
            float halfKnee = bassCompressorKnee * 0.5f;
            float compressedLevel;
            if (bassCompressorEnvelope <= bassCompressorThreshold - halfKnee) {
                compressedLevel = bassCompressorEnvelope;
            } else if (bassCompressorEnvelope >= bassCompressorThreshold + halfKnee) {
                compressedLevel = bassCompressorThreshold
                        + (bassCompressorEnvelope - bassCompressorThreshold) / Math.max(1.0f, bassCompressorRatio);
            } else {
                float dryLevel = bassCompressorEnvelope;
                float wetLevel = bassCompressorThreshold
                        + (bassCompressorEnvelope - bassCompressorThreshold) / Math.max(1.0f, bassCompressorRatio);
                float t = (bassCompressorEnvelope - (bassCompressorThreshold - halfKnee)) / Math.max(0.0001f, bassCompressorKnee);
                t = t * t * (3f - 2f * t);
                compressedLevel = dryLevel + (wetLevel - dryLevel) * t;
            }
            float gain = bassCompressorEnvelope > 0.0001f ? compressedLevel / bassCompressorEnvelope : 1f;
            return finiteOrZero(sample * gain * bassCompressorMakeup);
        }

        private float applyTransientWetDuck(float lowBand, float wet) {
            float level = Math.abs(lowBand);
            transientFastEnvelope = level + transientFastCoeff * (transientFastEnvelope - level);
            transientSlowEnvelope = level + transientSlowCoeff * (transientSlowEnvelope - level);
            float transientAmount = Math.max(0f, transientFastEnvelope - transientSlowEnvelope - transientDuckThreshold);
            float duck = clamp(transientAmount / Math.max(0.0001f, transientDuckRange), 0f, transientDuckDepth);
            return finiteOrZero(wet * (1f - duck));
        }

        private float applyDryBassSidechain(float wet) {
            float level = Math.abs(wet);
            float coeff = level > dryBassDuckEnvelope ? dryBassDuckAttackCoeff : dryBassDuckReleaseCoeff;
            dryBassDuckEnvelope = level + coeff * (dryBassDuckEnvelope - level);
            float duckAmount = Math.max(0f, dryBassDuckEnvelope - dryBassDuckThreshold);
            float duck = clamp(duckAmount / Math.max(0.0001f, dryBassDuckRange), 0f, dryBassDuckDepth);
            return 1f - duck;
        }

        private float extractDryBass(float input, int channel) {
            if (dryBassLowStageA == null || dryBassLowStageB == null || channel < 0 || channel >= dryBassLowStageA.length) {
                return 0f;
            }
            float stageA = dryBassLowStageA[channel].process(input);
            return dryBassLowStageB[channel].process(stageA);
        }

        private float envelopeCoeff(float seconds) {
            float safeSeconds = Math.max(0.001f, seconds);
            return (float) Math.exp(-1.0 / (sampleRate * safeSeconds));
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static int clampInt(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }


    private static final class MonoBiquad {
        private float z1;
        private float z2;
        private float b0;
        private float b1;
        private float b2;
        private float a1;
        private float a2;

        static MonoBiquad fromBand(ParametricBand band, int sampleRate) {
            Biquad stereo = Biquad.fromBand(band, sampleRate, 1);
            MonoBiquad mono = new MonoBiquad();
            mono.b0 = stereo.b0;
            mono.b1 = stereo.b1;
            mono.b2 = stereo.b2;
            mono.a1 = stereo.a1;
            mono.a2 = stereo.a2;
            return mono;
        }

        void process(float[] samples, int sampleCount) {
            for (int i = 0; i < sampleCount; i++) {
                float input = samples[i];
                float output = finiteOrZero(b0 * input + z1);
                float nextZ1 = finiteOrZero(b1 * input - a1 * output + z2);
                float nextZ2 = finiteOrZero(b2 * input - a2 * output);
                z1 = nextZ1;
                z2 = nextZ2;
                samples[i] = output;
            }
        }

        float process(float input) {
            float output = finiteOrZero(b0 * input + z1);
            z1 = finiteOrZero(b1 * input - a1 * output + z2);
            z2 = finiteOrZero(b2 * input - a2 * output);
            return output;
        }

        void reset() {
            z1 = 0f;
            z2 = 0f;
        }
    }

    private static final class AlgorithmicReverb {
        private static final int NEGATIVE_INFINITY_MB = -12000;

        /*
         * 湿声整体标定。
         * 如果你装进去还是觉得小，把 2.15f 提到 2.45f。
         * 如果觉得太冲，把 2.15f 降到 1.85f。
         */
        private static final float WET_RETURN_TRIM = 2.15f;

        /*
         * wet return 软限制阈值。
         * 不是总线 limiter，只是防止混响本身突然爆。
         */
        private static final float WET_RETURN_CEILING = 0.96f;

        private final int sampleRate;
        private final int channelCount;

        private final float[][] preDelayBuffers;
        private final int[] preDelayIndices;

        /*
         * 使用外部 StereoReverbCore。
         */
        private final StereoReverbCore reverbCore;

        /*
         * 固定 wet frame，process 内不 new。
         */
        private final float[] wetFrame = new float[2];

        private boolean activeWet;

        private float dryGain = 1f;
        private float wetGain = 0f;

        private float currentDryGain = 1f;
        private float currentWetGain = 0f;

        private int preDelayLength;
        private float tailLevel;

        private float returnLimiterEnvelope;
        private float returnLimiterGain = 1f;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = Math.max(8000, sampleRate);
            this.channelCount = Math.max(1, channelCount);

            this.preDelayBuffers = new float[this.channelCount][Math.max(1, this.sampleRate / 2)];
            this.preDelayIndices = new int[this.channelCount];

            this.reverbCore = new StereoReverbCore(this.sampleRate);

            configure("Default", 0, 0, 0, 0, 0, false);
        }

        void configure(String type,
                       int decayPercent,
                       int preDelayMs,
                       int sizePercent,
                       int mixPercent,
                       int mainMb,
                       boolean lowCpuMode) {
            ReverbProfile profile = ReverbProfile.forType(type);

            float size = clamp01(sizePercent / 100f);
            float mix = clamp01(mixPercent / 100f);

            /*
             * 沿用你项目原来的 decay 映射，避免 UI 参数含义变掉。
             */
            float decaySeconds = clamp(decayPercent / 100f, 0f, 12f);
            float decayShape = clamp01((Math.max(0.25f, decaySeconds) - 0.25f) / 11.75f);

            activeWet = !"Default".equals(type) && mix > 0.0001f;

            dryGain = mainMb <= NEGATIVE_INFINITY_MB ? 0f : dbToLinear(mainMb / 100f);

            /*
             * 重新标定 Mix 曲线：
             * 之前 wet 太小，尤其是用户把推子拉满时也不像“全湿声”。
             *
             * 新曲线：
             * - 低 mix：仍然有可听见的空间感
             * - 中 mix：明显
             * - 满 mix：湿声接近原声响度
             */
            float wetMix = activeWet ? (float) Math.pow(mix, 0.58f) : 0f;

            /*
             * 比旧版大幅提高 return 电平，但不是单纯暴力乘法。
             * mix 越大，wet return 才真正打开。
             */
            float sendDrive = activeWet
                    ? (0.62f + 3.15f * wetMix + 2.10f * mix * mix)
                    : 0f;

            /*
             * wetGain 最终标定。
             * profile.wetBoost 仍然保留 type 差异。
             * WET_RETURN_TRIM 用来把整体电平拉到“推子满时接近原声”。
             */
            wetGain = activeWet
                    ? profile.wetBoost
                      * wetMix
                      * sendDrive
                      * WET_RETURN_TRIM
                      * (0.96f + size * 0.28f + decayShape * 0.16f)
                    : 0f;

            preDelayLength = Math.max(
                    0,
                    Math.min(preDelayBuffers[0].length - 1, preDelayMs * sampleRate / 1000)
            );

            float profileDecaySeconds = clamp(
                    decaySeconds * (0.78f + profile.decayScale + size * 0.20f),
                    0.22f,
                    14f
            );

            reverbCore.configure(profile, size, profileDecaySeconds, decayShape, lowCpuMode);

            currentDryGain = dryGain;
            currentWetGain = wetGain;
            tailLevel = 0f;
            returnLimiterEnvelope = 0f;
            returnLimiterGain = 1f;

            /*
             * 不 new，只清空已有 predelay buffer。
             */
            for (int channel = 0; channel < preDelayIndices.length; channel++) {
                preDelayIndices[channel] = 0;

                float[] buffer = preDelayBuffers[channel];
                for (int i = 0; i < buffer.length; i++) {
                    buffer[i] = 0f;
                }
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (samples == null || samples.length == 0) {
                return;
            }

            int safeChannelCount = Math.max(1, channelCount);
            int safeSampleCount = Math.min(sampleCount, samples.length);
            int frames = safeSampleCount / safeChannelCount;

            if (frames <= 0) {
                return;
            }

            if (!activeWet && Math.abs(dryGain - 1f) < 0.0001f) {
                return;
            }

            float inputPeak = 0f;
            for (int i = 0; i < safeSampleCount; i++) {
                float abs = Math.abs(samples[i]);
                if (abs > inputPeak) {
                    inputPeak = abs;
                }
            }

            /*
             * 无输入且尾巴已经很低时，不继续跑 reverbCore。
             */
            if (!activeWet || (inputPeak < 0.00012f && tailLevel < 0.00016f)) {
                applyDryGainOnly(samples, safeSampleCount);
                tailLevel *= 0.76f;
                currentWetGain += (0f - currentWetGain) * 0.02f;
                currentDryGain += (dryGain - currentDryGain) * 0.02f;
                returnLimiterEnvelope *= 0.92f;
                returnLimiterGain += (1f - returnLimiterGain) * 0.012f;
                return;
            }

            float wetPeak = 0f;

            /*
             * 参数平滑。
             */
            final float gainSmooth = 0.0025f;

            for (int frame = 0; frame < frames; frame++) {
                currentDryGain += (dryGain - currentDryGain) * gainSmooth;
                currentWetGain += (wetGain - currentWetGain) * gainSmooth;

                int frameOffset = frame * safeChannelCount;

                float leftDry = samples[frameOffset];
                float rightDry = safeChannelCount > 1 ? samples[frameOffset + 1] : leftDry;

                float leftIn = preDelayProcess(leftDry, 0);
                float rightIn = preDelayProcess(rightDry, Math.min(1, preDelayIndices.length - 1));

                reverbCore.process(leftIn, rightIn, wetFrame);

                float wetLeft = wetFrame[0];
                float wetRight = safeChannelCount > 1
                        ? wetFrame[1]
                        : (wetFrame[0] + wetFrame[1]) * 0.5f;

                /*
                 * 先应用 wetGain，再做 return limiter。
                 * 这样 Mix 拉满时响度足够，但不会突然爆。
                 */
                wetLeft *= currentWetGain;
                wetRight *= currentWetGain;

                float wetAbs = Math.max(Math.abs(wetLeft), Math.abs(wetRight));

                returnLimiterEnvelope += (wetAbs - returnLimiterEnvelope)
                        * (wetAbs > returnLimiterEnvelope ? 0.045f : 0.0028f);

                float targetLimiterGain = returnLimiterEnvelope > WET_RETURN_CEILING
                        ? WET_RETURN_CEILING / Math.max(returnLimiterEnvelope, WET_RETURN_CEILING)
                        : 1f;

                returnLimiterGain += (targetLimiterGain - returnLimiterGain)
                        * (targetLimiterGain < returnLimiterGain ? 0.10f : 0.004f);

                wetLeft = softSaturate(wetLeft * returnLimiterGain);
                wetRight = softSaturate(wetRight * returnLimiterGain);

                float absWet = Math.max(Math.abs(wetLeft), Math.abs(wetRight));
                if (absWet > wetPeak) {
                    wetPeak = absWet;
                }

                samples[frameOffset] = finiteOrZero(leftDry * currentDryGain + wetLeft);

                if (safeChannelCount > 1) {
                    samples[frameOffset + 1] = finiteOrZero(rightDry * currentDryGain + wetRight);
                }

                if (safeChannelCount > 2) {
                    float wetMono = (wetLeft + wetRight) * 0.5f;

                    for (int channel = 2; channel < safeChannelCount; channel++) {
                        float dry = samples[frameOffset + channel];
                        samples[frameOffset + channel] = finiteOrZero(dry * currentDryGain + wetMono);
                    }
                }
            }

            tailLevel = Math.max(wetPeak, tailLevel * 0.935f);
        }

        boolean isActive() {
            return activeWet || Math.abs(dryGain - 1f) >= 0.0001f;
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

            writeIndex++;
            if (writeIndex >= buffer.length) {
                writeIndex = 0;
            }

            preDelayIndices[channel] = writeIndex;
            return output;
        }

        private void applyDryGainOnly(float[] samples, int sampleCount) {
            if (Math.abs(dryGain - 1f) < 0.0001f) {
                return;
            }

            final float gainSmooth = 0.004f;

            for (int i = 0; i < sampleCount; i++) {
                currentDryGain += (dryGain - currentDryGain) * gainSmooth;
                samples[i] = finiteOrZero(samples[i] * currentDryGain);
            }
        }

        private static float softSaturate(float value) {
            if (value > 3f) {
                return 1f;
            }

            if (value < -3f) {
                return -1f;
            }

            float x2 = value * value;
            return value * (27f + x2) / (27f + 9f * x2);
        }

        private static float dbToLinear(float db) {
            return (float) Math.pow(10.0, db / 20.0);
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        private static float clamp01(float value) {
            return clamp(value, 0f, 1f);
        }

        private static float finiteOrZero(float value) {
            return value == value
                    && value != Float.POSITIVE_INFINITY
                    && value != Float.NEGATIVE_INFINITY
                    ? value
                    : 0f;
        }
    }

    private static final class LookaheadLimiter {
        private final int sampleRate;
        private final int channelCount;
        private float[] delayBuffer = new float[0];
        private int writeFrame;
        private int delayFrames;
        private int primedFrames;
        private float envelope;
        private float gain = 1f;
        private float ceiling = 0.985f;
        private float attackCoeff = 0.05f;
        private float releaseCoeff = 0.998f;

        LookaheadLimiter(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }

        void configure(int lookaheadMs, int latencyMs, float ceiling, int releaseMs) {
            this.ceiling = clamp(ceiling, 0.93f, 0.999f);
            delayFrames = Math.max(0, Math.min(sampleRate / 8, lookaheadMs * sampleRate / 1000));
            int latencyFrames = Math.max(delayFrames + 1, Math.min(sampleRate / 2, latencyMs * sampleRate / 1000));
            int bufferFrames = Math.max(delayFrames + 2, latencyFrames + 8);
            int bufferSamples = Math.max(channelCount, bufferFrames * channelCount);
            if (delayBuffer.length != bufferSamples) {
                delayBuffer = new float[bufferSamples];
            } else {
                Arrays.fill(delayBuffer, 0f);
            }
            writeFrame = 0;
            primedFrames = 0;
            envelope = 0f;
            gain = 1f;
            float attackSeconds = Math.max(0.0008f, Math.max(lookaheadMs, 1) / 1000f * 0.45f);
            float releaseSeconds = Math.max(0.025f, releaseMs / 1000f);
            attackCoeff = (float) Math.exp(-1.0 / (sampleRate * attackSeconds));
            releaseCoeff = (float) Math.exp(-1.0 / (sampleRate * releaseSeconds));
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (samples == null || sampleCount <= 0) {
                return;
            }

            int frames = sampleCount / channelCount;
            int bufferFrames = Math.max(1, delayBuffer.length / this.channelCount);
            for (int frame = 0; frame < frames; frame++) {
                int frameOffset = frame * channelCount;
                float peak = 0f;
                for (int channel = 0; channel < channelCount; channel++) {
                    peak = Math.max(peak, Math.abs(samples[frameOffset + channel]));
                }

                if (peak > envelope) {
                    envelope = peak + attackCoeff * (envelope - peak);
                } else {
                    envelope = peak + releaseCoeff * (envelope - peak);
                }
                float targetGain = envelope > ceiling ? ceiling / Math.max(envelope, ceiling) : 1f;
                float smoothing = targetGain < gain ? 0.55f : 0.08f;
                gain += (targetGain - gain) * smoothing;

                int readFrame = writeFrame - delayFrames;
                if (readFrame < 0) {
                    readFrame += bufferFrames;
                }
                for (int channel = 0; channel < channelCount; channel++) {
                    int writeIndex = writeFrame * this.channelCount + channel;
                    float input = samples[frameOffset + channel];
                    float delayed = primedFrames >= delayFrames
                            ? delayBuffer[readFrame * this.channelCount + channel]
                            : input;
                    delayBuffer[writeIndex] = input;
                    samples[frameOffset + channel] = finiteOrZero(softLimit(delayed * gain, ceiling));
                }

                writeFrame++;
                primedFrames++;
                if (writeFrame >= bufferFrames) {
                    writeFrame = 0;
                }
            }
        }

        boolean isActive() {
            return true;
        }

        private static float softLimit(float value, float ceiling) {
            float safe = Math.max(0.5f, ceiling);
            float normalized = value / safe;
            if (Math.abs(normalized) < 0.9f) {
                return value;
            }
            return fastTanh(normalized) * safe;
        }
    }

    private static final class StereoReverbCore {
        private final InputDiffuser leftInput;
        private final InputDiffuser rightInput;
        private final EarlyReflectionCluster earlyReflections;
        private final FdnReverbTank fdnTank;
        private final WetHighPass leftInputLowCut;
        private final WetHighPass rightInputLowCut;
        private final WetHighPass leftLowCut;
        private final WetHighPass rightLowCut;
        private final WetLowPass leftHighCut;
        private final WetLowPass rightHighCut;
        private final MonoPeakFilter leftBoxCut;
        private final MonoPeakFilter rightBoxCut;
        private float earlyMix;
        private float lateMix;
        private float outputGain;

        StereoReverbCore(int sampleRate) {
            leftInput = new InputDiffuser(sampleRate);
            rightInput = new InputDiffuser(sampleRate);
            earlyReflections = new EarlyReflectionCluster(sampleRate);
            fdnTank = new FdnReverbTank(sampleRate);
            leftInputLowCut = new WetHighPass(sampleRate, 120f);
            rightInputLowCut = new WetHighPass(sampleRate, 120f);
            leftLowCut = new WetHighPass(sampleRate, 120f);
            rightLowCut = new WetHighPass(sampleRate, 120f);
            leftHighCut = new WetLowPass(sampleRate);
            rightHighCut = new WetLowPass(sampleRate);
            leftBoxCut = new MonoPeakFilter(sampleRate);
            rightBoxCut = new MonoPeakFilter(sampleRate);
        }

        void configure(ReverbProfile profile, float size, float decaySeconds, float decayShape, boolean lowCpuMode) {
            leftInput.configure(profile.diffusionMs, profile.diffusionFeedback, size, 0.0f, lowCpuMode);
            rightInput.configure(profile.diffusionMs, profile.diffusionFeedback, size, 0.29f, lowCpuMode);
            earlyReflections.configure(profile, size, lowCpuMode);
            fdnTank.configure(profile, size, decaySeconds, decayShape, lowCpuMode);
            leftInputLowCut.reset();
            rightInputLowCut.reset();
            leftLowCut.reset();
            rightLowCut.reset();
            leftHighCut.configure(profile.highCutHz * (0.95f - decayShape * 0.12f));
            rightHighCut.configure(profile.highCutHz * (0.95f - decayShape * 0.12f));
            float boxCutDb = -(2.1f + size * 0.7f + decayShape * 0.9f);
            leftBoxCut.configure(520f, boxCutDb, 0.95f);
            rightBoxCut.configure(520f, boxCutDb, 0.95f);
            earlyMix = profile.earlyMix * (0.88f + size * 0.05f);
            lateMix = profile.lateMix * (0.98f + decayShape * 0.16f + size * 0.1f);
            outputGain = profile.outputGain * (0.86f + size * 0.08f);
        }

        void process(float leftIn, float rightIn, float[] wetFrame) {
            leftIn = leftInputLowCut.process(leftIn);
            rightIn = rightInputLowCut.process(rightIn);
            float diffuseLeft = leftInput.process(leftIn + rightIn * 0.07f);
            float diffuseRight = rightInput.process(rightIn + leftIn * 0.07f);
            float[] early = earlyReflections.process(diffuseLeft, diffuseRight);
            float[] late = fdnTank.process(diffuseLeft, diffuseRight);
            float wetLeft = (early[0] * earlyMix + late[0] * lateMix) * outputGain;
            float wetRight = (early[1] * earlyMix + late[1] * lateMix) * outputGain;
            wetFrame[0] = clampSample(leftHighCut.process(leftBoxCut.process(leftLowCut.process(wetLeft))));
            wetFrame[1] = clampSample(rightHighCut.process(rightBoxCut.process(rightLowCut.process(wetRight))));
        }
    }

    private static final class InputDiffuser {
        private final AllPassStage[] stages;

        InputDiffuser(int sampleRate) {
            stages = new AllPassStage[] {
                    new AllPassStage(sampleRate, 6.0f),
                    new AllPassStage(sampleRate, 10.0f),
                    new AllPassStage(sampleRate, 15.0f)
            };
        }

        void configure(float[] diffusionMs, float feedback, float size, float spreadOffset, boolean lowCpuMode) {
            int activeStages = lowCpuMode ? 2 : stages.length;
            for (int i = 0; i < stages.length; i++) {
                float scale = 0.98f + size * 0.34f + spreadOffset * 0.06f;
                stages[i].configure(diffusionMs[i] * scale, clamp(feedback - i * 0.018f, 0.5f, 0.81f), i < activeStages);
            }
        }

        float process(float input) {
            float sample = input;
            for (AllPassStage stage : stages) {
                sample = stage.process(sample);
            }
            return sample;
        }
    }

    private static final class EarlyReflectionCluster {
        private final StereoTapDelay[] taps;
        private final float[] output = new float[2];

        EarlyReflectionCluster(int sampleRate) {
            taps = new StereoTapDelay[] {
                    new StereoTapDelay(sampleRate, 18f),
                    new StereoTapDelay(sampleRate, 24f),
                    new StereoTapDelay(sampleRate, 31f),
                    new StereoTapDelay(sampleRate, 39f),
                    new StereoTapDelay(sampleRate, 47f),
                    new StereoTapDelay(sampleRate, 55f)
            };
        }

        void configure(ReverbProfile profile, float size, boolean lowCpuMode) {
            int activeTaps = lowCpuMode ? 4 : taps.length;
            for (int i = 0; i < taps.length; i++) {
                float leftMs = profile.earlyLeftMs[i] * (0.94f + size * 0.3f);
                float rightMs = profile.earlyRightMs[i] * (0.94f + size * 0.3f);
                taps[i].configure(leftMs, rightMs, profile.earlyGain[i] * 0.9f, i < activeTaps);
            }
        }

        float[] process(float left, float right) {
            float wetLeft = 0f;
            float wetRight = 0f;
            float mono = (left + right) * 0.5f;
            for (StereoTapDelay tap : taps) {
                float[] tapOut = tap.process(left, right, mono);
                wetLeft += tapOut[0];
                wetRight += tapOut[1];
            }
            output[0] = wetLeft;
            output[1] = wetRight;
            return output;
        }
    }

    private static final class StereoTapDelay {
        private final SimpleDelay leftDelay;
        private final SimpleDelay rightDelay;
        private final float[] output = new float[2];
        private float gain;
        private boolean active;

        StereoTapDelay(int sampleRate, float maxDelayMs) {
            leftDelay = new SimpleDelay(sampleRate, maxDelayMs);
            rightDelay = new SimpleDelay(sampleRate, maxDelayMs);
        }

        void configure(float leftMs, float rightMs, float gain, boolean active) {
            this.gain = gain;
            this.active = active;
            leftDelay.configure(Math.max(1f, leftMs));
            rightDelay.configure(Math.max(1f, rightMs));
        }

        float[] process(float left, float right, float mono) {
            if (!active) {
                output[0] = 0f;
                output[1] = 0f;
                return output;
            }
            output[0] = leftDelay.process(left + mono * 0.24f) * gain;
            output[1] = rightDelay.process(right + mono * 0.24f) * gain;
            return output;
        }
    }

    private static final class FdnReverbTank {
        private static final int MATRIX_ORDER = 8;

        private final FdnLine[] lines;
        private final float[] delayed = new float[MATRIX_ORDER];
        private final float[] mixed = new float[MATRIX_ORDER];
        private final float[] output = new float[2];

        private int activeLines = MATRIX_ORDER;
        private float stereoWidth;
        private float inputGain;

        FdnReverbTank(int sampleRate) {
            /*
             * Delay 基准改得更不规则一点，减少固定音高/金属振铃。
             * 原来 40/47/55/63/72/81/91/103 太接近规律递增。
             */
            lines = new FdnLine[] {
                    new FdnLine(sampleRate, 37.9f),
                    new FdnLine(sampleRate, 43.7f),
                    new FdnLine(sampleRate, 52.3f),
                    new FdnLine(sampleRate, 61.1f),
                    new FdnLine(sampleRate, 71.9f),
                    new FdnLine(sampleRate, 83.3f),
                    new FdnLine(sampleRate, 96.7f),
                    new FdnLine(sampleRate, 111.5f)
            };
        }

        void configure(ReverbProfile profile,
                       float size,
                       float decaySeconds,
                       float decayShape,
                       boolean lowCpuMode) {
            activeLines = lowCpuMode ? 4 : MATRIX_ORDER;

            stereoWidth = profile.width * (0.88f + size * 0.12f);
            inputGain = profile.inputGain * (0.92f + size * 0.08f);

            for (int i = 0; i < lines.length; i++) {
                float delayMs = profile.fdnDelayMs[i] * (profile.minSizeScale + size * profile.sizeScaleRange);

                /*
                 * 关键改动：
                 * feedback 上限降低，且高序号 line 稍微多衰减。
                 * 这样可以明显减少 “叮——”“嗡——” 的固定频率尾巴。
                 */
                float feedback = clamp(
                        calculateRt60Feedback(delayMs, decaySeconds)
                                * profile.feedbackScale
                                - i * 0.011f
                                - decayShape * 0.010f,
                        0.18f,
                        0.875f
                );

                /*
                 * damping 不再随着 decayShape 过度变暗。
                 * 这里略提高基础 damping，让尾巴保持清楚，但不尖锐。
                 */
                float damping = clamp(
                        profile.damping
                                + decayShape * 0.08f
                                + i * 0.008f,
                        0.14f,
                        0.68f
                );

                /*
                 * 调制略微增强，用来打散固定 delay 共振。
                 * 不要太大，否则尾巴会晃、跑调。
                 */
                float modDepthMs = profile.modDepthMs * (0.82f + size * 0.35f);
                float modRateHz = profile.modRateHz + i * profile.modSpreadHz;

                lines[i].configure(
                        delayMs,
                        feedback,
                        damping,
                        modDepthMs,
                        modRateHz,
                        i * 0.61f,
                        i < activeLines
                );
            }
        }

        float[] process(float left, float right) {
            float mono = (left + right) * 0.5f * inputGain;

            for (int i = 0; i < lines.length; i++) {
                delayed[i] = i < activeLines ? lines[i].read() : 0f;
            }

            hadamard8(delayed, mixed);

            for (int i = 0; i < lines.length; i++) {
                if (i >= activeLines) {
                    continue;
                }

                /*
                 * 轻微降低 excitation，减少 FDN 被输入持续顶响。
                 */
                float excitation = ((i & 1) == 0 ? left : right) * 0.28f
                        + ((i & 2) == 0 ? mono : -mono) * 0.21f;

                lines[i].write(excitation + mixed[i] * 0.35355338f);
            }

            /*
             * 输出组合稍微换一下，减少左右固定相关振铃。
             */
            float leftOut = (delayed[0] + delayed[2] + delayed[5] - delayed[7]) * 0.25f;
            float rightOut = (delayed[1] + delayed[3] + delayed[4] - delayed[6]) * 0.25f;

            float mid = (leftOut + rightOut) * 0.5f;

            output[0] = mid + (leftOut - mid) * stereoWidth;
            output[1] = mid + (rightOut - mid) * stereoWidth;

            return output;
        }

        private static float calculateRt60Feedback(float delayMs, float decaySeconds) {
            float safeDelaySeconds = Math.max(0.001f, delayMs / 1000f);
            float safeRt60 = Math.max(0.35f, decaySeconds);
            return (float) Math.pow(0.001, safeDelaySeconds / safeRt60);
        }

        private static void hadamard8(float[] in, float[] out) {
            float a0 = in[0] + in[1];
            float a1 = in[0] - in[1];
            float a2 = in[2] + in[3];
            float a3 = in[2] - in[3];
            float a4 = in[4] + in[5];
            float a5 = in[4] - in[5];
            float a6 = in[6] + in[7];
            float a7 = in[6] - in[7];

            float b0 = a0 + a2;
            float b1 = a1 + a3;
            float b2 = a0 - a2;
            float b3 = a1 - a3;
            float b4 = a4 + a6;
            float b5 = a5 + a7;
            float b6 = a4 - a6;
            float b7 = a5 - a7;

            out[0] = b0 + b4;
            out[1] = b1 + b5;
            out[2] = b2 + b6;
            out[3] = b3 + b7;
            out[4] = b0 - b4;
            out[5] = b1 - b5;
            out[6] = b2 - b6;
            out[7] = b3 - b7;
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private static final class FdnLine {
        private final ModulatedDelay delay;
        private float feedback;
        private float damping;
        private float filterState;
        private boolean active;

        FdnLine(int sampleRate, float maxDelayMs) {
            delay = new ModulatedDelay(sampleRate, maxDelayMs);
        }

        void configure(float delayMs,
                       float feedback,
                       float damping,
                       float modDepthMs,
                       float modRateHz,
                       float phaseOffset,
                       boolean active) {
            this.active = active;
            this.feedback = feedback;
            this.damping = damping;
            this.filterState = 0f;
            delay.configure(delayMs, modDepthMs, modRateHz, phaseOffset, active);
        }

        float read() {
            if (!active) {
                return 0f;
            }
            float delayed = delay.read();
            filterState += damping * (delayed - filterState);
            return finiteOrZero(filterState * feedback);
        }

        void write(float value) {
            if (active) {
                delay.write(softSaturate(value));
            }
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
                z1 = 0f;
                z2 = 0f;
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
            z1 = 0f;
            z2 = 0f;
            active = true;
        }

        float process(float input) {
            if (!active) {
                return input;
            }
            float output = finiteOrZero(b0 * input + z1);
            z1 = finiteOrZero(b1 * input - a1 * output + z2);
            z2 = finiteOrZero(b2 * input - a2 * output);
            return output;
        }

        void reset() {
            z1 = 0f;
            z2 = 0f;
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
        private float oscSin;
        private float oscCos;
        private float sinStep = 1f;
        private float cosStep = 1f;
        private boolean active = true;

        ModulatedDelay(int sampleRate, float maxDelayMs) {
            this.sampleRate = sampleRate;
            buffer = new float[Math.max(64, Math.round(maxDelayMs * sampleRate / 1000f) + 32)];
        }

        void configure(float delayMs, float modDepthMs, float modRateHz, float phaseOffset, boolean active) {
            this.active = active;
            delaySamples = clamp(delayMs * sampleRate / 1000f, 4f, buffer.length - 4f);
            modDepthSamples = active
                    ? clamp(modDepthMs * sampleRate / 1000f, 0f, Math.min(2.8f, delaySamples * 0.08f))
                    : 0f;
            if (active && modDepthSamples > 0f) {
                float phaseIncrement = (float) (2.0 * Math.PI * modRateHz / sampleRate);
                oscSin = (float) Math.sin(phaseOffset);
                oscCos = (float) Math.cos(phaseOffset);
                sinStep = (float) Math.sin(phaseIncrement);
                cosStep = (float) Math.cos(phaseIncrement);
            } else {
                oscSin = 0f;
                oscCos = 1f;
                sinStep = 0f;
                cosStep = 1f;
            }
            Arrays.fill(buffer, 0f);
            writeIndex = 0;
        }

        float read() {
            float modulation = (active && modDepthSamples > 0f) ? oscSin * modDepthSamples : 0f;
            float readPos = writeIndex - delaySamples - modulation;
            readPos = wrapReadPosition(readPos, buffer.length);
            int indexA = Math.min(buffer.length - 1, (int) readPos);
            int indexM1 = indexA <= 0 ? buffer.length - 1 : indexA - 1;
            int indexB = (indexA + 1) % buffer.length;
            int indexC = (indexA + 2) % buffer.length;
            float frac = readPos - indexA;
            float y0 = buffer[indexM1];
            float y1 = buffer[indexA];
            float y2 = buffer[indexB];
            float y3 = buffer[indexC];
            float a = (-0.5f * y0) + (1.5f * y1) - (1.5f * y2) + (0.5f * y3);
            float b = y0 - (2.5f * y1) + (2f * y2) - (0.5f * y3);
            float c = (-0.5f * y0) + (0.5f * y2);
            float d = y1;
            return finiteOrZero(((a * frac + b) * frac + c) * frac + d);
        }

        void write(float value) {
            buffer[writeIndex] = clampSample(value);
            writeIndex++;
            if (writeIndex >= buffer.length) {
                writeIndex = 0;
            }
            if (active && modDepthSamples > 0f) {
                float nextSin = oscSin * cosStep + oscCos * sinStep;
                float nextCos = oscCos * cosStep - oscSin * sinStep;
                oscSin = nextSin;
                oscCos = nextCos;
            }
        }

        private static float wrapReadPosition(float readPos, int bufferLength) {
            if (!Float.isFinite(readPos) || bufferLength <= 0) {
                return 0f;
            }
            if (readPos < 0f) {
                readPos += bufferLength;
            }
            if (readPos >= bufferLength) {
                readPos -= bufferLength;
            }
            return readPos;
        }
    }

    private static final class ReverbProfile {
        final float[] fdnDelayMs;
        final float[] diffusionMs;
        final float[] earlyLeftMs;
        final float[] earlyRightMs;
        final float[] earlyGain;

        final float minSizeScale;
        final float sizeScaleRange;
        final float feedbackScale;
        final float decayScale;
        final float damping;
        final float diffusionFeedback;
        final float earlyMix;
        final float lateMix;
        final float outputGain;
        final float wetBoost;
        final float width;
        final float inputGain;
        final float modDepthMs;
        final float modRateHz;
        final float modSpreadHz;
        final float highCutHz;

        /*
         * 静态缓存，避免每次 configure 时 new ReverbProfile / new float[]。
         */

        private static final ReverbProfile HALL = new ReverbProfile(
                /*
                 * Delay 更不规则，减少金属音。
                 */
                new float[] {29.1f, 34.9f, 40.6f, 46.3f, 53.8f, 62.7f, 74.1f, 88.9f},
                new float[] {3.5f, 5.8f, 8.9f},
                new float[] {7.1f, 11.3f, 16.8f, 22.5f, 31.8f, 42.2f},
                new float[] {9.2f, 14.7f, 19.4f, 27.6f, 36.1f, 48.7f},
                new float[] {0.38f, 0.29f, 0.24f, 0.20f, 0.16f, 0.13f},

                0.86f,
                0.82f,

                /*
                 * feedbackScale 降一点，避免长尾固定共振。
                 */
                0.94f,

                0.28f,

                /*
                 * damping 稍高，尾巴更清楚，但 diffusion 降低，减少 allpass 金属感。
                 */
                0.36f,
                0.60f,

                0.24f,
                0.90f,
                0.30f,
                1.00f,
                1.05f,
                0.28f,

                /*
                 * 调制略加强，打散 ringing。
                 */
                0.070f,
                0.12f,
                0.007f,

                /*
                 * 不回到 5k 那种糊声，只稍微收一点。
                 */
                10800f
        );

        private static final ReverbProfile PLATE = new ReverbProfile(
                new float[] {22.7f, 27.1f, 32.8f, 38.6f, 45.9f, 54.2f, 64.7f, 76.9f},
                new float[] {2.7f, 4.6f, 6.8f},
                new float[] {5.4f, 8.1f, 12.0f, 16.7f, 23.6f, 31.9f},
                new float[] {6.2f, 9.8f, 13.7f, 18.8f, 25.4f, 34.1f},
                new float[] {0.42f, 0.33f, 0.27f, 0.21f, 0.17f, 0.14f},

                0.74f,
                0.58f,
                0.90f,
                0.20f,
                0.40f,
                0.58f,

                /*
                 * Plate early 少一点，late 稍强，保留板式质感但减少尖锐振铃。
                 */
                0.17f,
                0.94f,
                0.28f,
                0.96f,
                0.98f,
                0.24f,

                0.055f,
                0.18f,
                0.010f,

                12800f
        );

        private static final ReverbProfile CHAMBER = new ReverbProfile(
                new float[] {20.8f, 25.4f, 30.7f, 36.8f, 43.3f, 51.6f, 61.2f, 72.8f},
                new float[] {2.9f, 4.6f, 7.1f},
                new float[] {5.8f, 9.1f, 13.9f, 19.7f, 28.1f, 37.3f},
                new float[] {7.0f, 10.8f, 15.4f, 21.3f, 30.2f, 39.8f},
                new float[] {0.39f, 0.31f, 0.25f, 0.20f, 0.16f, 0.13f},

                0.76f,
                0.62f,
                0.92f,
                0.22f,
                0.37f,
                0.59f,

                0.22f,
                0.91f,
                0.29f,
                0.96f,
                1.00f,
                0.25f,

                0.060f,
                0.14f,
                0.009f,

                11200f
        );

        private static final ReverbProfile ROOM = new ReverbProfile(
                new float[] {13.7f, 16.9f, 20.7f, 25.1f, 30.4f, 36.2f, 43.8f, 52.6f},
                new float[] {2.1f, 3.4f, 5.0f},
                new float[] {3.9f, 6.2f, 9.7f, 13.8f, 18.5f, 24.2f},
                new float[] {4.8f, 7.4f, 10.9f, 15.1f, 20.4f, 26.7f},
                new float[] {0.44f, 0.35f, 0.28f, 0.22f, 0.17f, 0.13f},

                0.64f,
                0.40f,
                0.86f,
                0.16f,
                0.41f,
                0.55f,

                /*
                 * Room 早反射多一点，late 少一点，减少“空桶金属尾巴”。
                 */
                0.30f,
                0.80f,
                0.32f,
                0.88f,
                0.92f,
                0.21f,

                0.040f,
                0.12f,
                0.008f,

                9600f
        );

        private static final ReverbProfile STUDIO = new ReverbProfile(
                new float[] {11.5f, 14.2f, 17.6f, 21.4f, 25.9f, 31.1f, 37.2f, 44.6f},
                new float[] {1.7f, 2.8f, 4.1f},
                new float[] {3.2f, 5.0f, 7.7f, 10.9f, 14.8f, 19.3f},
                new float[] {3.8f, 5.9f, 8.5f, 11.9f, 15.7f, 20.9f},
                new float[] {0.41f, 0.32f, 0.26f, 0.21f, 0.16f, 0.12f},

                0.56f,
                0.30f,
                0.84f,
                0.14f,
                0.43f,
                0.52f,

                /*
                 * Studio 更短、更干净，减少尾巴参与感。
                 */
                0.34f,
                0.72f,
                0.34f,
                0.82f,
                0.90f,
                0.18f,

                0.034f,
                0.11f,
                0.007f,

                11500f
        );

        private static final ReverbProfile DEFAULT = new ReverbProfile(
                new float[] {18.4f, 21.9f, 25.5f, 29.4f, 33.7f, 38.9f, 44.8f, 51.7f},
                new float[] {2.9f, 4.7f, 6.9f},
                new float[] {4.9f, 7.7f, 11.8f, 16.4f, 22.9f, 31.2f},
                new float[] {6.0f, 9.4f, 13.2f, 18.5f, 25.8f, 34.7f},
                new float[] {0.40f, 0.31f, 0.25f, 0.20f, 0.16f, 0.13f},

                0.70f,
                0.50f,
                0.88f,
                0.20f,
                0.38f,
                0.56f,

                0.24f,
                0.84f,
                0.30f,
                0.90f,
                0.96f,
                0.23f,

                0.042f,
                0.13f,
                0.008f,

                10800f
        );

        ReverbProfile(float[] fdnDelayMs,
                      float[] diffusionMs,
                      float[] earlyLeftMs,
                      float[] earlyRightMs,
                      float[] earlyGain,
                      float minSizeScale,
                      float sizeScaleRange,
                      float feedbackScale,
                      float decayScale,
                      float damping,
                      float diffusionFeedback,
                      float earlyMix,
                      float lateMix,
                      float outputGain,
                      float wetBoost,
                      float width,
                      float inputGain,
                      float modDepthMs,
                      float modRateHz,
                      float modSpreadHz,
                      float highCutHz) {
            this.fdnDelayMs = fdnDelayMs;
            this.diffusionMs = diffusionMs;
            this.earlyLeftMs = earlyLeftMs;
            this.earlyRightMs = earlyRightMs;
            this.earlyGain = earlyGain;
            this.minSizeScale = minSizeScale;
            this.sizeScaleRange = sizeScaleRange;
            this.feedbackScale = feedbackScale;
            this.decayScale = decayScale;
            this.damping = damping;
            this.diffusionFeedback = diffusionFeedback;
            this.earlyMix = earlyMix;
            this.lateMix = lateMix;
            this.outputGain = outputGain;
            this.wetBoost = wetBoost;
            this.width = width;
            this.inputGain = inputGain;
            this.modDepthMs = modDepthMs;
            this.modRateHz = modRateHz;
            this.modSpreadHz = modSpreadHz;
            this.highCutHz = highCutHz;
        }

        static ReverbProfile forType(String type) {
            if ("Hall".equals(type)) {
                return HALL;
            }

            if ("Plate".equals(type)) {
                return PLATE;
            }

            if ("Chamber".equals(type)) {
                return CHAMBER;
            }

            if ("Room".equals(type)) {
                return ROOM;
            }

            if ("Studio".equals(type)) {
                return STUDIO;
            }

            return DEFAULT;
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
        float abs = Math.abs(value);
        if (abs < 0.88f) {
            return value;
        }
        return finiteOrZero(fastTanh(value * 0.92f));
    }

    private static float fastTanh(float value) {
        float x = clamp(value, -3f, 3f);
        float x2 = x * x;
        return x * (27f + x2) / (27f + 9f * x2);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }
}
