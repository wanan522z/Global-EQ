package com.example.globalpeq;

final class PeqMath {
    static final int HEADROOM_LIMIT_MB = 1800;

    private PeqMath() {
    }

    static int gainAtHzMb(int frequencyHz, Preset preset) {
        return gainAtFrequencyMb(frequencyHz, preset);
    }

    static int gainAtFrequencyMb(double frequencyHz, Preset preset) {
        if (frequencyHz <= 0 || preset == null) {
            return 0;
        }

        return preset.pregainMb + rawEqGainAtHzMb(frequencyHz, preset);
    }

    static int visualGainAtHzMb(int frequencyHz, Preset preset) {
        return visualGainAtFrequencyMb(frequencyHz, preset);
    }

    static int visualGainAtFrequencyMb(double frequencyHz, Preset preset) {
        if (frequencyHz <= 0 || preset == null) {
            return 0;
        }

        return preset.pregainMb + rawEqGainAtHzMb(frequencyHz, preset);
    }

    static int geqBandGainMb(int bandIndex, Preset preset) {
        if (preset == null || bandIndex < 0 || bandIndex >= preset.geqGainsMb.length) {
            return 0;
        }
        return preset.geqGainsMb[bandIndex];
    }

    private static int rawEqGainAtHzMb(double frequencyHz, Preset preset) {
        return preset.mode == EqMode.GEQ
                ? rawGeqGainAtFrequencyMb(frequencyHz, preset)
                : rawPeqGainAtHzMb(frequencyHz, preset);
    }

    private static int rawPeqGainAtHzMb(double frequencyHz, Preset preset) {
        double sum = 0;
        for (ParametricBand band : preset.bands) {
            if (!band.enabled || band.gainMb == 0) {
                continue;
            }
            sum += bandGainAtHzMb(frequencyHz, band);
        }
        return (int) Math.round(sum);
    }

    static boolean bandMayClip(Preset preset, int bandIndex, int maxLevelMb) {
        if (preset == null || bandIndex < 0) {
            return false;
        }
        if (preset.mode == EqMode.GEQ) {
            if (bandIndex >= preset.geqGainsMb.length || preset.geqGainsMb[bandIndex] <= 0) {
                return false;
            }
            return preset.pregainMb + preset.geqGainsMb[bandIndex] > maxLevelMb;
        }
        if (bandIndex >= preset.bands.length) {
            return false;
        }
        ParametricBand target = preset.bands[bandIndex];
        if (!target.enabled || target.gainMb <= 0) {
            return false;
        }
        return preset.pregainMb + target.gainMb > maxLevelMb;
    }

    static boolean presetMayClip(Preset preset, int maxLevelMb) {
        if (preset == null) {
            return false;
        }
        if (preset.mode == EqMode.GEQ) {
            for (int gainMb : preset.geqGainsMb) {
                if (gainMb > 0 && preset.pregainMb + gainMb > maxLevelMb) {
                    return true;
                }
            }
            return false;
        }
        for (ParametricBand band : preset.bands) {
            if (band.enabled && band.gainMb > 0 && preset.pregainMb + band.gainMb > maxLevelMb) {
                return true;
            }
        }
        return false;
    }

    static int bandGainAtHzMb(int frequencyHz, ParametricBand band) {
        return bandGainAtHzMb((double) frequencyHz, band);
    }

    static int bandGainAtHzMb(double frequencyHz, ParametricBand band) {
        if (frequencyHz <= 0 || band == null || !band.enabled || band.gainMb == 0) {
            return 0;
        }
        if (band.type == FilterType.LOW_PASS || band.type == FilterType.HIGH_PASS) {
            return (int) Math.round(Math.abs(band.gainMb) * responseWeight(frequencyHz, band));
        }
        return (int) Math.round(band.gainMb * responseWeight(frequencyHz, band));
    }

    private static double responseWeight(double frequencyHz, ParametricBand band) {
        double octaves = Math.log(frequencyHz / (double) band.frequencyHz) / Math.log(2.0);
        double q = band.qHundred / 100.0;
        double width = Math.max(0.18, 1.0 / q);

        switch (band.type) {
            case LOW_SHELF:
                return 1.0 / (1.0 + Math.exp(octaves * 6.0 * q));
            case HIGH_SHELF:
                return 1.0 / (1.0 + Math.exp(-octaves * 6.0 * q));
            case LOW_PASS:
                return frequencyHz > band.frequencyHz ? -0.8 * Math.min(1.0, Math.abs(octaves) / width) : 0;
            case HIGH_PASS:
                return frequencyHz < band.frequencyHz ? -0.8 * Math.min(1.0, Math.abs(octaves) / width) : 0;
            case PEAK:
            default:
                return Math.exp(-0.5 * Math.pow(octaves / width, 2.0));
        }
    }

    private static int rawGeqGainAtHzMb(int frequencyHz, Preset preset) {
        return rawGeqGainAtFrequencyMb(frequencyHz, preset);
    }

    private static int rawGeqGainAtFrequencyMb(double frequencyHz, Preset preset) {
        if (preset == null || preset.geqGainsMb.length == 0) {
            return 0;
        }
        int[] freqs = Preset.GEQ_FREQUENCIES;
        int[] gains = preset.geqGainsMb;
        int count = Math.min(freqs.length, gains.length);
        double logHz = log2(frequencyHz);
        double weighted = 0;
        double weightSum = 0;

        for (int i = 0; i < count; i++) {
            double distanceOct = logHz - log2(freqs[i]);
            double weight = Math.exp(-0.5 * Math.pow(distanceOct / 0.72, 2.0));
            weighted += gains[i] * weight;
            weightSum += weight;
        }

        if (weightSum <= 0.0001) {
            return 0;
        }
        return (int) Math.round(weighted / weightSum);
    }

    static int geqCurvePointGainMb(int frequencyHz, Preset preset) {
        return rawGeqGainAtHzMb(frequencyHz, preset);
    }

    static int nearestGeqBandIndex(int frequencyHz) {
        int[] freqs = Preset.GEQ_FREQUENCIES;
        int nearest = 0;
        double best = Double.MAX_VALUE;
        double logHz = log2(Math.max(1, frequencyHz));
        for (int i = 0; i < freqs.length; i++) {
            double distance = Math.abs(logHz - log2(freqs[i]));
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

}
