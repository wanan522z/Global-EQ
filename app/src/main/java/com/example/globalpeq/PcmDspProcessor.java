package com.example.globalpeq;

import java.util.ArrayList;
import java.util.List;

final class PcmDspProcessor {
    private final List<Biquad> filters = new ArrayList<>();
    private int sampleRate = 48000;
    private int channelCount = 2;
    private float pregain = 1f;
    private VirtualBass virtualBass = new VirtualBass(48000, 2);

    void configure(Preset preset, int nextSampleRate, int nextChannelCount) {
        sampleRate = Math.max(8000, nextSampleRate);
        channelCount = Math.max(1, nextChannelCount);
        pregain = preset == null ? 1f : dbToLinear(preset.pregainMb / 100f);
        filters.clear();

        if (preset != null) {
            for (ParametricBand band : preset.bands) {
                if (band.enabled && band.gainMb != 0) {
                    filters.add(Biquad.fromBand(band, sampleRate, channelCount));
                }
            }
            virtualBass = new VirtualBass(sampleRate, channelCount);
            virtualBass.configure(preset.virtualBassCutoffHz,
                    preset.virtualBassEnabled ? preset.virtualBassAmountPercent : 0);
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
}
