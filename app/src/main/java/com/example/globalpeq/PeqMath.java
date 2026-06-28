package com.example.globalpeq;

final class PeqMath {
    static final int HEADROOM_LIMIT_MB = 1800;
    private static final int VISUAL_RESPONSE_SAMPLE_RATE = 48000;
    private static final double MIN_RESPONSE_MAGNITUDE = 1.0e-9;
    private static final int PASS_SECOND_ORDER_Q_HUNDRED = 71;
    private static final int PASS_GAIN_Q_HUNDRED = 100;
    private static final int PASS_MAX_SLOPE_STEP = 16;
    private static final ParametricBand[] EMPTY_BANDS = new ParametricBand[0];

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
            if (!band.enabled) {
                continue;
            }
            if (band.gainMb == 0
                    && band.type != FilterType.LOW_PASS
                    && band.type != FilterType.HIGH_PASS) {
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
        if (frequencyHz <= 0 || band == null || !band.enabled) {
            return 0;
        }
        double gainDb = 0.0;
        for (ParametricBand effectiveBand : effectiveResponseBands(band)) {
            double[] coefficients = normalizedBiquadCoefficients(effectiveBand, VISUAL_RESPONSE_SAMPLE_RATE);
            double magnitude = biquadMagnitudeAtFrequency(coefficients, frequencyHz, VISUAL_RESPONSE_SAMPLE_RATE);
            gainDb += 20.0 * Math.log10(Math.max(MIN_RESPONSE_MAGNITUDE, magnitude));
        }
        return (int) Math.round(gainDb * 100.0);
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

    static ParametricBand[] effectiveResponseBands(ParametricBand band) {
        if (band == null || !band.enabled) {
            return EMPTY_BANDS;
        }
        if (!isGainDrivenPassType(band.type)) {
            if (band.gainMb == 0) {
                return EMPTY_BANDS;
            }
            return new ParametricBand[] {band};
        }

        int slopeStepCount = passSlopeStepCountFromQ(band.qHundred);
        int biquadStageCount = slopeStepCount / 2;
        boolean includeFirstOrderStage = (slopeStepCount % 2) != 0;
        int extraGainStages = band.gainMb == 0 ? 0 : 1;
        ParametricBand[] expanded = new ParametricBand[(includeFirstOrderStage ? 1 : 0) + biquadStageCount + extraGainStages];
        int index = 0;
        if (includeFirstOrderStage) {
            expanded[index++] = new ParametricBand(band.type, true, band.frequencyHz, 0, 0);
        }
        for (int i = 0; i < biquadStageCount; i++) {
            expanded[index++] = new ParametricBand(
                    band.type,
                    true,
                    band.frequencyHz,
                    0,
                    PASS_SECOND_ORDER_Q_HUNDRED);
        }
        if (band.gainMb != 0) {
            expanded[index] = new ParametricBand(
                    passGainOverlayType(band.type),
                    true,
                    band.frequencyHz,
                    band.gainMb,
                    PASS_GAIN_Q_HUNDRED);
        }
        return expanded;
    }

    private static boolean isGainDrivenPassType(FilterType type) {
        return type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS;
    }

    private static FilterType passGainOverlayType(FilterType type) {
        return type == FilterType.HIGH_PASS ? FilterType.HIGH_SHELF : FilterType.LOW_SHELF;
    }

    private static int passSlopeStepCountFromQ(int qHundred) {
        double normalized = Math.max(0.0, Math.min(1.0, qHundred / 1000.0));
        return 1 + (int) Math.round(normalized * (PASS_MAX_SLOPE_STEP - 1));
    }

    static double[] normalizedBiquadCoefficients(ParametricBand band, int sampleRate) {
        double[] identity = {1.0, 0.0, 0.0, 0.0, 0.0};
        if (band == null) {
            return identity;
        }
        double safeSampleRate = Math.max(8000.0, sampleRate);
        double frequency = Math.max(20.0, Math.min(safeSampleRate * 0.45, band.frequencyHz));
        double omega = 2.0 * Math.PI * frequency / safeSampleRate;
        double k = Math.tan(Math.PI * frequency / safeSampleRate);
        double sin = Math.sin(omega);
        double cos = Math.cos(omega);
        double q = Math.max(0.2, band.qHundred / 100.0);
        double a = Math.pow(10.0, band.gainMb / 4000.0);
        double alpha = sin / (2.0 * q);
        double beta = Math.sqrt(a) / q;
        double b0;
        double b1;
        double b2;
        double a0;
        double a1;
        double a2;

        switch (band.type) {
            case LOW_SHELF:
                b0 = a * ((a + 1.0) - (a - 1.0) * cos + beta * sin);
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos);
                b2 = a * ((a + 1.0) - (a - 1.0) * cos - beta * sin);
                a0 = (a + 1.0) + (a - 1.0) * cos + beta * sin;
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos);
                a2 = (a + 1.0) + (a - 1.0) * cos - beta * sin;
                break;
            case HIGH_SHELF:
                b0 = a * ((a + 1.0) + (a - 1.0) * cos + beta * sin);
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos);
                b2 = a * ((a + 1.0) + (a - 1.0) * cos - beta * sin);
                a0 = (a + 1.0) - (a - 1.0) * cos + beta * sin;
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cos);
                a2 = (a + 1.0) - (a - 1.0) * cos - beta * sin;
                break;
            case LOW_PASS:
                if (band.qHundred <= 0) {
                    double norm = 1.0 / (1.0 + k);
                    return new double[] {
                            k * norm,
                            k * norm,
                            0.0,
                            (k - 1.0) * norm,
                            0.0
                    };
                }
                b0 = (1.0 - cos) * 0.5;
                b1 = 1.0 - cos;
                b2 = (1.0 - cos) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cos;
                a2 = 1.0 - alpha;
                break;
            case HIGH_PASS:
                if (band.qHundred <= 0) {
                    double norm = 1.0 / (1.0 + k);
                    return new double[] {
                            1.0 * norm,
                            -1.0 * norm,
                            0.0,
                            (k - 1.0) * norm,
                            0.0
                    };
                }
                b0 = (1.0 + cos) * 0.5;
                b1 = -(1.0 + cos);
                b2 = (1.0 + cos) * 0.5;
                a0 = 1.0 + alpha;
                a1 = -2.0 * cos;
                a2 = 1.0 - alpha;
                break;
            case PEAK:
            default:
                b0 = 1.0 + alpha * a;
                b1 = -2.0 * cos;
                b2 = 1.0 - alpha * a;
                a0 = 1.0 + alpha / a;
                a1 = -2.0 * cos;
                a2 = 1.0 - alpha / a;
                break;
        }

        if (!Double.isFinite(a0) || Math.abs(a0) < 1.0e-12) {
            return identity;
        }
        double nb0 = b0 / a0;
        double nb1 = b1 / a0;
        double nb2 = b2 / a0;
        double na1 = a1 / a0;
        double na2 = a2 / a0;
        if (!Double.isFinite(nb0)
                || !Double.isFinite(nb1)
                || !Double.isFinite(nb2)
                || !Double.isFinite(na1)
                || !Double.isFinite(na2)) {
            return identity;
        }
        return new double[] {nb0, nb1, nb2, na1, na2};
    }

    static double biquadMagnitudeAtFrequency(double[] coefficients, double frequencyHz, int sampleRate) {
        if (coefficients == null || coefficients.length < 5 || frequencyHz <= 0 || sampleRate <= 0) {
            return 1.0;
        }
        double omega = 2.0 * Math.PI * Math.max(0.0, frequencyHz) / sampleRate;
        double cos = Math.cos(omega);
        double sin = Math.sin(omega);
        double cos2 = Math.cos(2.0 * omega);
        double sin2 = Math.sin(2.0 * omega);

        double numeratorReal = coefficients[0] + coefficients[1] * cos + coefficients[2] * cos2;
        double numeratorImag = -coefficients[1] * sin - coefficients[2] * sin2;
        double denominatorReal = 1.0 + coefficients[3] * cos + coefficients[4] * cos2;
        double denominatorImag = -coefficients[3] * sin - coefficients[4] * sin2;

        double numerator = Math.hypot(numeratorReal, numeratorImag);
        double denominator = Math.hypot(denominatorReal, denominatorImag);
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator) || denominator < MIN_RESPONSE_MAGNITUDE) {
            return 1.0;
        }
        return numerator / denominator;
    }

}
