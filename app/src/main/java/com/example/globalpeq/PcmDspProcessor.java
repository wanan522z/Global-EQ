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
            samples[i] = clampSample(input * pregain * effectHeadroom);
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
        private static final float PI = (float) Math.PI;
        private static final float TWO_PI = (float) (Math.PI * 2.0);

        private final int sampleRate;
        private final int channelCount;

        private MonoBiquad sourceHighPass;
        private MonoBiquad sourceLowPass;
        private MonoBiquad sourceLowPass2;

        private MonoBiquad harmonicHighPass;
        private MonoBiquad harmonicLowPass;
        private MonoBiquad harmonicLowPass2;

        private float[] monoSource = new float[0];
        private float[] monoLow = new float[0];
        private float[] harmonicBand = new float[0];

        private float sourceEnvelope;
        private float envelopeAttackCoeff;
        private float envelopeReleaseCoeff;

        private float previousLow;
        private int samplesSinceCrossing;
        private int minPeriodSamples;
        private int maxPeriodSamples;

        private float minTrackHz;
        private float maxTrackHz;
        private float targetHz;
        private float trackedHz;
        private float periodSmooth;
        private float trackingReliability;
        private float frequencySmoothCoeff;

        private float phase;

        private float harmonicMix;
        private float sourceScale;
        private float wetCeiling;

        private float h2Mix;
        private float h3Mix;
        private float h4Mix;

        private float wetLimiterEnvelope;
        private float wetLimiterGain = 1f;

        PsychoacousticBassProcessor(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            configure(95, 0, 95, 0, false);
        }

        void configure(int virtualCutoffHz,
                       int virtualAmountPercent,
                       int dspCutoffHz,
                       int dspAmountPercent,
                       boolean lowCpuMode) {
            float virtualAmount = clamp01(virtualAmountPercent / 100f);
            float dspAmount = clamp01(dspAmountPercent / 100f);
            float amount = Math.max(virtualAmount, dspAmount);

            int requestedCutoff = dspAmount > 0f ? dspCutoffHz : virtualCutoffHz;
            if (requestedCutoff <= 0) {
                requestedCutoff = Math.max(virtualCutoffHz, dspCutoffHz);
            }

            int cutoff = clampInt(requestedCutoff, 30, 220);

            float lowCutoffBlend = 1f - clamp01((cutoff - 30f) / 70f);
            float midCutoffBlend = clamp01((cutoff - 70f) / 75f);

            float fartRise = clamp01((cutoff - 45f) / 22f);
            float fartFall = 1f - clamp01((cutoff - 90f) / 50f);
            float fartZoneBlend = fartRise * fartFall;

            sourceHighPass = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.HIGH_PASS,
                            true,
                            Math.max(18, Math.round(cutoff * 0.30f)),
                            0,
                            70),
                    sampleRate);

            sourceLowPass = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.LOW_PASS,
                            true,
                            cutoff,
                            0,
                            62),
                    sampleRate);

            sourceLowPass2 = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.LOW_PASS,
                            true,
                            cutoff,
                            0,
                            62),
                    sampleRate);

            int harmonicHpHz = clampInt(
                    Math.round(cutoff * (1.18f + midCutoffBlend * 0.24f)),
                    48,
                    280);

            int harmonicLpHz = clampInt(
                    Math.round(cutoff * (5.25f + lowCutoffBlend * 1.05f + midCutoffBlend * 0.45f)),
                    harmonicHpHz + 150,
                    900);

            harmonicHighPass = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.HIGH_PASS,
                            true,
                            harmonicHpHz,
                            0,
                            70),
                    sampleRate);

            harmonicLowPass = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.LOW_PASS,
                            true,
                            harmonicLpHz,
                            0,
                            68),
                    sampleRate);

            harmonicLowPass2 = MonoBiquad.fromBand(
                    new ParametricBand(
                            FilterType.LOW_PASS,
                            true,
                            harmonicLpHz,
                            0,
                            68),
                    sampleRate);

            minTrackHz = clamp(cutoff * 0.42f, 22f, 160f);
            maxTrackHz = clamp(cutoff * 1.15f, minTrackHz + 8f, 260f);

            minPeriodSamples = Math.max(8, Math.round(sampleRate / maxTrackHz));
            maxPeriodSamples = Math.max(minPeriodSamples + 4, Math.round(sampleRate / minTrackHz));

            targetHz = clamp(cutoff * 0.82f, minTrackHz, maxTrackHz);
            trackedHz = targetHz;
            periodSmooth = 0f;
            trackingReliability = 0f;
            samplesSinceCrossing = maxPeriodSamples;
            previousLow = 0f;
            phase = 0f;

            envelopeAttackCoeff = onePoleCoeff(8.0f + fartZoneBlend * 4.0f, sampleRate);
            envelopeReleaseCoeff = onePoleCoeff(90.0f + lowCutoffBlend * 30.0f + fartZoneBlend * 25.0f, sampleRate);
            frequencySmoothCoeff = onePoleCoeff(42.0f + fartZoneBlend * 25.0f, sampleRate);

            h2Mix = 0.96f + lowCutoffBlend * 0.22f - fartZoneBlend * 0.10f;
            h3Mix = 0.72f + fartZoneBlend * 0.10f + lowCutoffBlend * 0.06f;
            h4Mix = 0.28f + midCutoffBlend * 0.08f + fartZoneBlend * 0.04f;

            sourceScale = 0.88f + amount * (0.42f + lowCutoffBlend * 0.12f);
            harmonicMix = amount * (3.10f + lowCutoffBlend * 0.85f - fartZoneBlend * 0.06f);
            wetCeiling = 0.46f + amount * 0.26f;

            sourceEnvelope = 0f;
            wetLimiterEnvelope = 0f;
            wetLimiterGain = 1f;
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (!isActive()) {
                return;
            }

            int frameCount = sampleCount / channelCount;
            ensureCapacity(frameCount);

            for (int frame = 0; frame < frameCount; frame++) {
                int frameOffset = frame * channelCount;
                float mono = 0f;
                for (int channel = 0; channel < channelCount; channel++) {
                    mono += samples[frameOffset + channel];
                }
                monoSource[frame] = mono / channelCount;
            }

            System.arraycopy(monoSource, 0, monoLow, 0, frameCount);
            sourceHighPass.process(monoLow, frameCount);
            sourceLowPass.process(monoLow, frameCount);
            sourceLowPass2.process(monoLow, frameCount);

            for (int frame = 0; frame < frameCount; frame++) {
                float low = monoLow[frame];
                float absLow = Math.abs(low);

                sourceEnvelope += (absLow - sourceEnvelope)
                        * (absLow > sourceEnvelope ? envelopeAttackCoeff : envelopeReleaseCoeff);

                updateFrequencyTracker(low, absLow);

                trackedHz += (targetHz - trackedHz) * frequencySmoothCoeff;
                trackedHz = clamp(trackedHz, minTrackHz, maxTrackHz);

                float step = TWO_PI * trackedHz / sampleRate;
                phase += step;
                if (phase >= TWO_PI) {
                    phase -= TWO_PI;
                }

                float levelGate = clamp01((sourceEnvelope - 0.0028f) / 0.020f);
                float reliable = clamp01(trackingReliability) * levelGate;

                float amp = sourceEnvelope * sourceScale * reliable;

                float h2 = fastSin(phase * 2f);
                float h3 = fastSin(phase * 3f);
                float h4 = fastSin(phase * 4f);

                float generated = amp * (h2 * h2Mix + h3 * h3Mix + h4 * h4Mix);

                harmonicBand[frame] = finiteOrZero(generated);
            }

            harmonicHighPass.process(harmonicBand, frameCount);
            harmonicLowPass.process(harmonicBand, frameCount);
            harmonicLowPass2.process(harmonicBand, frameCount);

            for (int frame = 0; frame < frameCount; frame++) {
                float wet = harmonicBand[frame] * harmonicMix;

                float wetAbs = Math.abs(wet);
                wetLimiterEnvelope += (wetAbs - wetLimiterEnvelope)
                        * (wetAbs > wetLimiterEnvelope ? 0.075f : 0.0042f);

                float targetGain = wetLimiterEnvelope > wetCeiling
                        ? wetCeiling / Math.max(wetLimiterEnvelope, wetCeiling)
                        : 1f;

                float limiterCoeff = targetGain < wetLimiterGain ? 0.12f : 0.0065f;
                wetLimiterGain += (targetGain - wetLimiterGain) * limiterCoeff;

                float generated = softLimitWet(wet * wetLimiterGain, wetCeiling * 1.50f);

                int frameOffset = frame * channelCount;
                for (int channel = 0; channel < channelCount; channel++) {
                    float original = samples[frameOffset + channel];
                    samples[frameOffset + channel] = finiteOrZero(original + generated);
                }
            }
        }

        private void updateFrequencyTracker(float low, float absLow) {
            samplesSinceCrossing++;

            float threshold = Math.max(0.0008f, sourceEnvelope * 0.10f);
            boolean risingCrossing = previousLow < 0f && low >= 0f && absLow > threshold;

            if (risingCrossing) {
                int period = samplesSinceCrossing;

                if (period >= minPeriodSamples && period <= maxPeriodSamples) {
                    if (periodSmooth <= 0f) {
                        periodSmooth = period;
                    } else {
                        periodSmooth += (period - periodSmooth) * 0.20f;
                    }

                    float measuredHz = sampleRate / Math.max(1f, periodSmooth);
                    targetHz = clamp(measuredHz, minTrackHz, maxTrackHz);

                    trackingReliability += (1f - trackingReliability) * 0.22f;
                    samplesSinceCrossing = 0;
                } else if (period > maxPeriodSamples * 2) {
                    trackingReliability *= 0.992f;
                    samplesSinceCrossing = 0;
                }
            }

            if (samplesSinceCrossing > maxPeriodSamples * 3) {
                trackingReliability *= 0.9985f;
                samplesSinceCrossing = maxPeriodSamples * 3;
            }

            previousLow = low;
        }

        boolean isActive() {
            return harmonicMix > 0.0001f;
        }

        private void ensureCapacity(int frameCount) {
            if (monoSource.length < frameCount) {
                monoSource = new float[frameCount];
                monoLow = new float[frameCount];
                harmonicBand = new float[frameCount];
            }
        }

        private static float onePoleCoeff(float timeMs, int sampleRate) {
            float safeMs = Math.max(0.1f, timeMs);
            float safeSampleRate = Math.max(8000f, sampleRate);
            return 1f - (float) Math.exp(-1.0 / (safeSampleRate * safeMs / 1000f));
        }

        private static float softLimitWet(float value, float ceiling) {
            float safe = Math.max(0.05f, ceiling);
            float normalized = value / safe;
            if (Math.abs(normalized) < 1.0f) {
                return value;
            }
            return fastTanh(normalized) * safe;
        }

        private static float fastSin(float value) {
            float x = value;
            while (x > PI) {
                x -= TWO_PI;
            }
            while (x < -PI) {
                x += TWO_PI;
            }

            float y = 1.27323954f * x - 0.405284735f * x * Math.abs(x);
            return 0.225f * (y * Math.abs(y) - y) + y;
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
    }

    private static final class AlgorithmicReverb {
        private static final int NEGATIVE_INFINITY_MB = -12000;

        private final int sampleRate;
        private final int channelCount;
        private final float[][] preDelayBuffers;
        private final int[] preDelayIndices;
        private final StereoReverbCore reverbCore;
        private final float[] wetFrame = new float[2];
        private boolean activeWet;
        private float dryGain;
        private float wetGain;
        private int preDelayLength;
        private float tailLevel;

        AlgorithmicReverb(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.preDelayBuffers = new float[Math.max(1, channelCount)][Math.max(1, sampleRate / 2)];
            this.preDelayIndices = new int[Math.max(1, channelCount)];
            this.reverbCore = new StereoReverbCore(sampleRate);
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
            float decaySeconds = clamp(decayPercent / 100f, 0f, 12f);
            float decayShape = clamp01((Math.max(0.25f, decaySeconds) - 0.25f) / 11.75f);
            activeWet = !"Default".equals(type) && mix > 0f;
            dryGain = mainMb <= NEGATIVE_INFINITY_MB ? 0f : dbToLinear(mainMb / 100f);
            float wetMix = activeWet ? (float) Math.pow(mix, 0.72f) : 0f;
            float sendDrive = activeWet ? (0.22f + 3.6f * wetMix + 2.9f * mix * mix) : 0f;
            wetGain = activeWet
                    ? profile.wetBoost * wetMix * sendDrive * (0.96f + size * 0.28f + decayShape * 0.22f)
                    : 0f;
            preDelayLength = Math.max(0, Math.min(preDelayBuffers[0].length - 1, preDelayMs * sampleRate / 1000));
            float profileDecaySeconds = clamp(
                    decaySeconds * (0.78f + profile.decayScale + size * 0.2f),
                    0.28f,
                    14f);
            reverbCore.configure(profile, size, profileDecaySeconds, decayShape, lowCpuMode);
            tailLevel = 0f;
            for (int channel = 0; channel < preDelayIndices.length; channel++) {
                preDelayIndices[channel] = 0;
                Arrays.fill(preDelayBuffers[channel], 0f);
            }
        }

        void process(float[] samples, int sampleCount, int channelCount) {
            if (!activeWet && Math.abs(dryGain - 1f) < 0.0001f) {
                return;
            }

            int frames = sampleCount / channelCount;
            float inputPeak = 0f;
            for (int i = 0; i < sampleCount; i++) {
                inputPeak = Math.max(inputPeak, Math.abs(samples[i]));
            }
            if (!activeWet || (inputPeak < 0.00012f && tailLevel < 0.00018f)) {
                applyDryGainOnly(samples, sampleCount);
                tailLevel *= 0.8f;
                return;
            }

            float wetPeak = 0f;
            for (int frame = 0; frame < frames; frame++) {
                int frameOffset = frame * channelCount;
                float leftDry = samples[frameOffset];
                float rightDry = channelCount > 1 ? samples[frameOffset + 1] : leftDry;
                float leftIn = preDelayProcess(leftDry, 0);
                float rightIn = preDelayProcess(rightDry, Math.min(1, preDelayIndices.length - 1));
                reverbCore.process(leftIn, rightIn, wetFrame);

                float wetLeft = softSaturate(wetFrame[0]);
                float wetRight = channelCount > 1
                        ? softSaturate(wetFrame[1])
                        : softSaturate((wetFrame[0] + wetFrame[1]) * 0.5f);
                wetPeak = Math.max(wetPeak, Math.max(Math.abs(wetLeft), Math.abs(wetRight)));
                samples[frameOffset] = finiteOrZero(leftDry * dryGain + wetLeft * wetGain);
                if (channelCount > 1) {
                    samples[frameOffset + 1] = finiteOrZero(rightDry * dryGain + wetRight * wetGain);
                }
                for (int channel = 2; channel < channelCount; channel++) {
                    float dry = samples[frameOffset + channel];
                    samples[frameOffset + channel] = finiteOrZero(dry * dryGain + (wetLeft + wetRight) * 0.5f * wetGain);
                }
            }
            tailLevel = Math.max(wetPeak, tailLevel * 0.94f);
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
            preDelayIndices[channel] = (writeIndex + 1) % buffer.length;
            return output;
        }

        private void applyDryGainOnly(float[] samples, int sampleCount) {
            if (Math.abs(dryGain - 1f) < 0.0001f) {
                return;
            }
            for (int i = 0; i < sampleCount; i++) {
                samples[i] = finiteOrZero(samples[i] * dryGain);
            }
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
            lines = new FdnLine[] {
                    new FdnLine(sampleRate, 40f),
                    new FdnLine(sampleRate, 47f),
                    new FdnLine(sampleRate, 55f),
                    new FdnLine(sampleRate, 63f),
                    new FdnLine(sampleRate, 72f),
                    new FdnLine(sampleRate, 81f),
                    new FdnLine(sampleRate, 91f),
                    new FdnLine(sampleRate, 103f)
            };
        }

        void configure(ReverbProfile profile, float size, float decaySeconds, float decayShape, boolean lowCpuMode) {
            activeLines = lowCpuMode ? 4 : MATRIX_ORDER;
            stereoWidth = profile.width * (0.88f + size * 0.12f);
            inputGain = profile.inputGain * (0.92f + size * 0.08f);
            for (int i = 0; i < lines.length; i++) {
                float delayMs = profile.fdnDelayMs[i] * (profile.minSizeScale + size * profile.sizeScaleRange);
                float feedback = clamp(calculateRt60Feedback(delayMs, decaySeconds) * profile.feedbackScale - i * 0.008f, 0.2f, 0.91f);
                float damping = clamp(profile.damping + decayShape * 0.14f + i * 0.012f, 0.08f, 0.72f);
                float modDepthMs = profile.modDepthMs * (0.6f + size * 0.32f);
                float modRateHz = profile.modRateHz + i * profile.modSpreadHz;
                lines[i].configure(delayMs, feedback, damping, modDepthMs, modRateHz, i * 0.61f, i < activeLines);
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
                float excitation = ((i & 1) == 0 ? left : right) * 0.32f
                        + ((i & 2) == 0 ? mono : -mono) * 0.24f;
                lines[i].write(excitation + mixed[i] * 0.35355338f);
            }
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
                return new ReverbProfile(
                        new float[] {29.8f, 34.7f, 39.2f, 43.6f, 49.1f, 56.4f, 63.7f, 72.3f},
                        new float[] {3.7f, 6.1f, 9.6f},
                        new float[] {7.1f, 11.3f, 16.8f, 22.5f, 31.8f, 42.2f},
                        new float[] {9.2f, 14.7f, 19.4f, 27.6f, 36.1f, 48.7f},
                        new float[] {0.38f, 0.29f, 0.24f, 0.2f, 0.16f, 0.13f},
                        0.86f, 0.82f, 0.98f, 0.28f, 0.24f, 0.7f, 0.24f, 0.92f, 0.3f, 1.02f, 1.05f, 0.28f, 0.045f, 0.12f, 0.007f, 5200f);
            }
            if ("Plate".equals(type)) {
                return new ReverbProfile(
                        new float[] {23.9f, 27.8f, 31.6f, 36.2f, 40.9f, 46.4f, 52.7f, 59.8f},
                        new float[] {2.8f, 4.9f, 7.1f},
                        new float[] {5.4f, 8.1f, 12.0f, 16.7f, 23.6f, 31.9f},
                        new float[] {6.2f, 9.8f, 13.7f, 18.8f, 25.4f, 34.1f},
                        new float[] {0.42f, 0.33f, 0.27f, 0.21f, 0.17f, 0.14f},
                        0.74f, 0.58f, 0.95f, 0.2f, 0.2f, 0.72f, 0.18f, 0.98f, 0.28f, 1.0f, 0.98f, 0.24f, 0.034f, 0.18f, 0.01f, 6100f);
            }
            if ("Chamber".equals(type)) {
                return new ReverbProfile(
                        new float[] {21.3f, 24.7f, 28.6f, 33.1f, 37.4f, 42.9f, 48.5f, 55.9f},
                        new float[] {3.0f, 4.8f, 7.4f},
                        new float[] {5.8f, 9.1f, 13.9f, 19.7f, 28.1f, 37.3f},
                        new float[] {7.0f, 10.8f, 15.4f, 21.3f, 30.2f, 39.8f},
                        new float[] {0.39f, 0.31f, 0.25f, 0.2f, 0.16f, 0.13f},
                        0.76f, 0.62f, 0.96f, 0.22f, 0.22f, 0.69f, 0.22f, 0.95f, 0.29f, 0.98f, 1.0f, 0.25f, 0.036f, 0.14f, 0.009f, 5600f);
            }
            if ("Room".equals(type)) {
                return new ReverbProfile(
                        new float[] {14.2f, 17.1f, 19.8f, 22.9f, 26.3f, 29.8f, 34.2f, 38.7f},
                        new float[] {2.2f, 3.6f, 5.3f},
                        new float[] {3.9f, 6.2f, 9.7f, 13.8f, 18.5f, 24.2f},
                        new float[] {4.8f, 7.4f, 10.9f, 15.1f, 20.4f, 26.7f},
                        new float[] {0.44f, 0.35f, 0.28f, 0.22f, 0.17f, 0.13f},
                        0.64f, 0.4f, 0.9f, 0.16f, 0.28f, 0.66f, 0.28f, 0.88f, 0.32f, 0.9f, 0.92f, 0.21f, 0.028f, 0.12f, 0.008f, 4700f);
            }
            if ("Studio".equals(type)) {
                return new ReverbProfile(
                        new float[] {11.8f, 13.6f, 15.8f, 18.3f, 21.1f, 24.4f, 27.8f, 31.5f},
                        new float[] {1.8f, 2.9f, 4.4f},
                        new float[] {3.2f, 5.0f, 7.7f, 10.9f, 14.8f, 19.3f},
                        new float[] {3.8f, 5.9f, 8.5f, 11.9f, 15.7f, 20.9f},
                        new float[] {0.41f, 0.32f, 0.26f, 0.21f, 0.16f, 0.12f},
                        0.56f, 0.3f, 0.88f, 0.14f, 0.31f, 0.64f, 0.32f, 0.84f, 0.34f, 0.84f, 0.9f, 0.18f, 0.022f, 0.11f, 0.007f, 4200f);
            }
            return new ReverbProfile(
                    new float[] {18.4f, 21.9f, 25.5f, 29.4f, 33.7f, 38.9f, 44.8f, 51.7f},
                    new float[] {2.9f, 4.7f, 6.9f},
                    new float[] {4.9f, 7.7f, 11.8f, 16.4f, 22.9f, 31.2f},
                    new float[] {6.0f, 9.4f, 13.2f, 18.5f, 25.8f, 34.7f},
                    new float[] {0.4f, 0.31f, 0.25f, 0.2f, 0.16f, 0.13f},
                    0.7f, 0.5f, 0.93f, 0.2f, 0.24f, 0.68f, 0.24f, 0.92f, 0.3f, 0.94f, 0.96f, 0.23f, 0.032f, 0.13f, 0.008f, 5000f);
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
