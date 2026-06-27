package com.example.globalpeq;

public final class ReverbHarness {
    public static void main(String[] args) {
        Preset preset = Preset.flat(true)
                .withReverbType("Hall")
                .withReverbSettings(0, 600, 20, 70, 100);
        PcmDspProcessor dsp = new PcmDspProcessor();
        dsp.configure(preset, 48000, 2, true, AdvancedModeConfig.DEFAULT);
        float[] samples = new float[480];
        for (int i = 0; i < samples.length / 2; i++) {
            float s = (float) Math.sin(2.0 * Math.PI * 440.0 * i / 48000.0) * 0.1f;
            samples[i * 2] = s;
            samples[i * 2 + 1] = s;
        }
        dsp.processInterleaved(samples, samples.length / 2);
        float peak = 0f;
        float sum = 0f;
        for (float v : samples) {
            peak = Math.max(peak, Math.abs(v));
            sum += Math.abs(v);
        }
        System.out.println("peak=" + peak);
        System.out.println("avgabs=" + (sum / samples.length));
        for (int i = 0; i < 12; i++) {
            System.out.println(i + ": " + samples[i]);
        }
    }
}
