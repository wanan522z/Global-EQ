package com.example.globalpeq;

final class PeqMath {
    static final int HEADROOM_LIMIT_MB = 1800;
    private static final int VISUAL_RESPONSE_SAMPLE_RATE = 48000;
    private static final double MIN_RESPONSE_MAGNITUDE = 1.0e-9;

    // 保留常量名，方便后续维护。现在 pass cut 不再所有级固定 Q=0.71。
    private static final int PASS_CUT_Q_HUNDRED = 71;

    // q 接近最大时，用更低 Q 做更圆滑的 12dB/oct 曲线。
    private static final int PASS_SMOOTH_Q_HUNDRED = 50;

    // Butterworth 高阶最后几级 Q 可能很高，限制一下更适合安卓实时 DSP 和 UI 频响显示。
    private static final double PASS_BUTTERWORTH_MIN_Q = 0.35;
    private static final double PASS_BUTTERWORTH_MAX_Q = 6.0;

    private static final int PASS_SHELF_Q_HUNDRED = -1;
    private static final double PASS_SHELF_SLOPE = 0.8;
    private static final double PASS_SHELF_OFFSET_RATIO = 1.25;
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

        int order = passFilterOrderFromQ(band.qHundred);
        int biquadStageCount = Math.max(1, order / 2);
        int extraGainStages = band.gainMb == 0 ? 0 : 1;
        ParametricBand[] expanded = new ParametricBand[biquadStageCount + extraGainStages];

        int index = 0;
        for (int i = 0; i < biquadStageCount; i++) {
            int qHundred = passStageQHundred(order, i, band.qHundred);

            expanded[index++] = new ParametricBand(
                    band.type,
                    true,
                    band.frequencyHz,
                    0,
                    qHundred);
        }

        if (band.gainMb != 0) {
            expanded[index] = new ParametricBand(
                    passGainOverlayType(band.type),
                    true,
                    passShelfFrequencyHz(band),
                    band.gainMb,
                    PASS_SHELF_Q_HUNDRED);
        }

        return expanded;
    }

    private static boolean isGainDrivenPassType(FilterType type) {
        return type == FilterType.LOW_PASS || type == FilterType.HIGH_PASS;
    }

    /*
     * 按你的当前设计保留：
     *
     * HIGH_PASS + LOW_SHELF
     * LOW_PASS  + HIGH_SHELF
     *
     * 这不是传统“通过区增益”，而是你要的切除区/边缘形状增益。
     */
    private static FilterType passGainOverlayType(FilterType type) {
        return type == FilterType.HIGH_PASS ? FilterType.LOW_SHELF : FilterType.HIGH_SHELF;
    }

    private static int passShelfFrequencyHz(ParametricBand band) {
        double shifted = band.type == FilterType.HIGH_PASS
                ? band.frequencyHz * PASS_SHELF_OFFSET_RATIO
                : band.frequencyHz / PASS_SHELF_OFFSET_RATIO;
        return (int) Math.round(shifted);
    }

    /*
     * qHundred 语义：
     *
     * q = 0    -> 最陡，接近 wall
     * q = 1800 -> 最平滑
     *
     * 这里返回滤波器总阶数。
     * 2阶 = 12dB/oct
     * 4阶 = 24dB/oct
     * 8阶 = 48dB/oct
     * 16阶 = 96dB/oct
     * 32阶 = 192dB/oct
     */
    private static int passFilterOrderFromQ(int qHundred) {
        double normalized = Math.max(0.0, Math.min(1.0,
                qHundred / (double) ParametricBand.MAX_Q_HUNDRED));

        double steep = 1.0 - normalized;

        if (steep > 0.90) {
            return 32;
        }
        if (steep > 0.72) {
            return 16;
        }
        if (steep > 0.52) {
            return 8;
        }
        if (steep > 0.32) {
            return 4;
        }
        return 2;
    }

    private static int passStageQHundred(int order, int stageIndex, int originalQHundred) {
        double normalized = Math.max(0.0, Math.min(1.0,
                originalQHundred / (double) ParametricBand.MAX_Q_HUNDRED));

        /*
         * q 接近 18 的时候，你反馈“不够顺滑”。
         * 所以最低阶时不用标准 Q=0.707，而用 Q=0.50，让曲线更圆、更松。
         */
        if (order <= 2 && normalized > 0.88) {
            return PASS_SMOOTH_Q_HUNDRED;
        }

        return butterworthStageQHundred(order, stageIndex);
    }

    /*
     * 偶数阶 Butterworth 的二阶分段 Q。
     *
     * 重要：
     * 旧写法是 N 个相同 Q=0.707 的 biquad 叠加。
     * 那会导致 cutoff 点重复衰减，q=0 时看起来切掉太多额外频率。
     *
     * 现在按 Butterworth 分配每一级 Q：
     * 4阶大概是 Q=0.54, 1.31
     * 8阶大概是 Q=0.51, 0.60, 0.90, 2.56
     */
    private static int butterworthStageQHundred(int order, int stageIndex) {
        int safeOrder = Math.max(2, order);
        int stageCount = Math.max(1, safeOrder / 2);
        int safeStageIndex = Math.max(0, Math.min(stageCount - 1, stageIndex));

        double angle = (2.0 * safeStageIndex + 1.0) * Math.PI / (2.0 * safeOrder);
        double q = 1.0 / (2.0 * Math.cos(angle));

        q = Math.max(PASS_BUTTERWORTH_MIN_Q, Math.min(PASS_BUTTERWORTH_MAX_Q, q));

        return (int) Math.round(q * 100.0);
    }

    private static double[] normalizedPassShelfCoefficients(boolean highShelf,
                                                            double frequency,
                                                            double safeSampleRate,
                                                            int gainMb) {
        double[] identity = {1.0, 0.0, 0.0, 0.0, 0.0};
        double a = Math.pow(10.0, gainMb / 4000.0);
        double omega = 2.0 * Math.PI * frequency / safeSampleRate;
        double sin = Math.sin(omega);
        double cos = Math.cos(omega);
        double alphaBase = (a + 1.0 / a) * (1.0 / PASS_SHELF_SLOPE - 1.0) + 2.0;
        double alpha = sin * 0.5 * Math.sqrt(Math.max(0.0, alphaBase));
        double beta = 2.0 * Math.sqrt(a) * alpha;
        double b0;
        double b1;
        double b2;
        double a0;
        double a1;
        double a2;

        if (highShelf) {
            b0 = a * ((a + 1.0) + (a - 1.0) * cos + beta);
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos);
            b2 = a * ((a + 1.0) + (a - 1.0) * cos - beta);
            a0 = (a + 1.0) - (a - 1.0) * cos + beta;
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cos);
            a2 = (a + 1.0) - (a - 1.0) * cos - beta;
        } else {
            b0 = a * ((a + 1.0) - (a - 1.0) * cos + beta);
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos);
            b2 = a * ((a + 1.0) - (a - 1.0) * cos - beta);
            a0 = (a + 1.0) + (a - 1.0) * cos + beta;
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos);
            a2 = (a + 1.0) + (a - 1.0) * cos - beta;
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

    static double[] normalizedBiquadCoefficients(ParametricBand band, int sampleRate) {
        double[] identity = {1.0, 0.0, 0.0, 0.0, 0.0};
        if (band == null) {
            return identity;
        }
        double safeSampleRate = Math.max(8000.0, sampleRate);
        double frequency = Math.max(20.0, Math.min(safeSampleRate * 0.45, band.frequencyHz));
        double omega = 2.0 * Math.PI * frequency / safeSampleRate;
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
                if (band.qHundred < 0) {
                    return normalizedPassShelfCoefficients(false, frequency, safeSampleRate, band.gainMb);
                }
                b0 = a * ((a + 1.0) - (a - 1.0) * cos + beta * sin);
                b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cos);
                b2 = a * ((a + 1.0) - (a - 1.0) * cos - beta * sin);
                a0 = (a + 1.0) + (a - 1.0) * cos + beta * sin;
                a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cos);
                a2 = (a + 1.0) + (a - 1.0) * cos - beta * sin;
                break;
            case HIGH_SHELF:
                if (band.qHundred < 0) {
                    return normalizedPassShelfCoefficients(true, frequency, safeSampleRate, band.gainMb);
                }
                b0 = a * ((a + 1.0) + (a - 1.0) * cos + beta * sin);
                b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cos);
                b2 = a * ((a + 1.0) + (a - 1.0) * cos - beta * sin);
                a0 = (a + 1.0) - (a - 1.0) * cos + beta * sin;
                a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cos);
                a2 = (a + 1.0) - (a - 1.0) * cos - beta * sin;
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