package com.example.globalpeq;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FrequencyCurve {
    static final FrequencyCurve DEFAULT = new FrequencyCurve("Default", Collections.emptyList());
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
    private static final int MIN_HZ = 20;
    private static final int MAX_HZ = 20000;
    private static final int SMOOTH_POINTS_PER_OCTAVE = 96;
    private static final double LOG2_MIN_HZ = log2(MIN_HZ);
    private static final double LOG2_MAX_HZ = log2(MAX_HZ);

    final String name;
    final List<Point> points;
    private final double[] pointLogHz;
    private final float[] pointGainsDb;
    private final double pointLogStart;
    private final double pointLogSpan;

    FrequencyCurve(String name, List<Point> points) {
        this.name = name == null || name.trim().isEmpty() ? "Imported" : name.trim();
        List<Point> sorted = new ArrayList<>();
        if (points != null) {
            for (Point point : points) {
                if (point != null && point.frequencyHz >= MIN_HZ && point.frequencyHz <= MAX_HZ) {
                    sorted.add(point);
                }
            }
        }
        sorted.sort((left, right) -> Integer.compare(left.frequencyHz, right.frequencyHz));
        this.points = Collections.unmodifiableList(sorted);
        int count = sorted.size();
        pointLogHz = new double[count];
        pointGainsDb = new float[count];
        for (int i = 0; i < count; i++) {
            Point point = sorted.get(i);
            pointLogHz[i] = log2(point.frequencyHz);
            pointGainsDb[i] = point.gainDb;
        }
        pointLogStart = count == 0 ? LOG2_MIN_HZ : pointLogHz[0];
        pointLogSpan = count <= 1 ? 0d : pointLogHz[count - 1] - pointLogStart;
    }

    boolean isDefault() {
        return points.isEmpty();
    }

    float gainAtHz(int frequencyHz) {
        return gainAtFrequency(frequencyHz);
    }

    float gainAtFrequency(double frequencyHz) {
        int count = pointGainsDb.length;
        if (count == 0 || frequencyHz <= 0) {
            return 0f;
        }
        if (frequencyHz <= points.get(0).frequencyHz) {
            return pointGainsDb[0];
        }
        int last = count - 1;
        if (frequencyHz >= points.get(last).frequencyHz) {
            return pointGainsDb[last];
        }

        double logHz = log2(frequencyHz);
        if (pointLogSpan <= 0d) {
            return pointGainsDb[0];
        }
        int index = (int) ((logHz - pointLogStart) * (last / pointLogSpan));
        if (index < 0) {
            index = 0;
        } else if (index >= last) {
            index = last - 1;
        }

        while (index > 0 && logHz < pointLogHz[index]) {
            index--;
        }
        while (index < last - 1 && logHz > pointLogHz[index + 1]) {
            index++;
        }

        double leftLog = pointLogHz[index];
        double rightLog = pointLogHz[index + 1];
        if (rightLog <= leftLog) {
            return pointGainsDb[index];
        }
        double t = (logHz - leftLog) / (rightLog - leftLog);
        return (float) (pointGainsDb[index] + (pointGainsDb[index + 1] - pointGainsDb[index]) * t);
    }

    FrequencyCurve normalizedAtHz(int frequencyHz) {
        if (isDefault()) {
            return this;
        }
        float offsetDb = -gainAtHz(frequencyHz);
        return withGainOffset(offsetDb);
    }

    FrequencyCurve withGainOffset(float offsetDb) {
        if (isDefault() || Math.abs(offsetDb) < 0.0001f) {
            return this;
        }
        List<Point> shifted = new ArrayList<>();
        for (Point point : points) {
            shifted.add(new Point(point.frequencyHz, point.gainDb + offsetDb));
        }
        return new FrequencyCurve(name, shifted);
    }

    FrequencyCurve withName(String nextName) {
        return new FrequencyCurve(nextName, points);
    }

    String toJson() {
        JSONObject object = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            object.put("name", name);
            for (Point point : points) {
                JSONObject pointObject = new JSONObject();
                pointObject.put("frequencyHz", point.frequencyHz);
                pointObject.put("gainDb", point.gainDb);
                array.put(pointObject);
            }
            object.put("points", array);
        } catch (JSONException ignored) {
            return "{}";
        }
        return object.toString();
    }

    static FrequencyCurve fromJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return DEFAULT;
        }
        try {
            JSONObject object = new JSONObject(json);
            JSONArray array = object.optJSONArray("points");
            List<Point> points = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject point = array.optJSONObject(i);
                    if (point != null) {
                        points.add(new Point(point.optInt("frequencyHz", 0), (float) point.optDouble("gainDb", 0)));
                    }
                }
            }
            return new FrequencyCurve(object.optString("name", "Imported"), resamplePoints(points));
        } catch (JSONException ex) {
            return DEFAULT;
        }
    }

    static FrequencyCurve fromText(String name, String text) {
        List<Point> points = new ArrayList<>();
        if (text == null) {
            return new FrequencyCurve(name, points);
        }
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String cleaned = line.replace('\t', ' ').trim();
            int comment = cleaned.indexOf('#');
            if (comment >= 0) {
                cleaned = cleaned.substring(0, comment).trim();
            }
            if (cleaned.startsWith("*") || cleaned.startsWith("//")) {
                continue;
            }
            if (cleaned.isEmpty()) {
                continue;
            }
            List<Float> values = numericValues(cleaned);
            for (int i = 0; i < values.size() - 1; i++) {
                float frequency = values.get(i);
                float gain = values.get(i + 1);
                if (frequency >= MIN_HZ && frequency <= MAX_HZ && gain >= -300 && gain <= 300) {
                    points.add(new Point(Math.round(frequency), gain));
                    break;
                }
            }
        }
        return new FrequencyCurve(name, resamplePoints(points)).normalizedAtHz(1000);
    }

    static FrequencyCurve builtInWoodenEars() {
        return fromPairs("WoodenEarsTarget", new float[][]{
                {20.786f, 3.1047f},
                {29.2033f, 2.7754f},
                {36.5305f, 2.9724f},
                {45.8318f, 2.7475f},
                {60.0945f, 2.3726f},
                {80.2991f, 1.6229f},
                {98.8584f, 1.5479f},
                {129.6225f, 0.7982f},
                {161.6054f, 0.5733f},
                {210.5652f, -0.4013f},
                {243.4023f, -0.5513f},
                {292.2009f, -0.1014f},
                {353.0001f, 0.0485f},
                {413.2244f, -0.3263f},
                {595.5245f, 0.4234f},
                {705.9657f, 0.4234f},
                {880.1549f, 1.0231f},
                {1125.3313f, 1.248f},
                {1276.466f, 1.6979f},
                {1611.6014f, 3.797f},
                {2034.7264f, 6.1211f},
                {2279.0928f, 7.7705f},
                {2701.755f, 8.8201f},
                {3202.8008f, 8.97f},
                {4199.4941f, 7.0208f},
                {6568.7756f, 2.6725f},
                {7593.1611f, 1.0981f},
                {7935.5624f, 0.7232f},
                {8558.8424f, 0.9482f},
                {9231.0764f, 1.6229f},
                {9831.4319f, 1.5479f},
                {10274.7645f, 0.6483f},
                {11436.4545f, -2.5005f},
                {12334.7037f, -4.7496f},
                {13881.5202f, -5.5555f},
                {15499.7531f, -6.6801f},
                {16638.3312f, -8.7418f},
                {18724.838f, -15.3017f}
        });
    }

    private static FrequencyCurve fromPairs(String name, float[][] pairs) {
        List<Point> points = new ArrayList<>();
        for (float[] pair : pairs) {
            points.add(new Point(Math.round(pair[0]), pair[1]));
        }
        return new FrequencyCurve(name, resamplePoints(points));
    }

    private static List<Float> numericValues(String text) {
        List<Float> values = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text.replace('\uFEFF', ' '));
        while (matcher.find()) {
            try {
                values.add(Float.parseFloat(matcher.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    FrequencyCurve withFractionalOctaveSmoothing(double windowOctaves) {
        if (isDefault() || windowOctaves <= 0) {
            return this;
        }
        return new FrequencyCurve(name, smoothPointPass(resamplePoints(points), windowOctaves));
    }

    private static List<Point> resamplePoints(List<Point> points) {
        if (points == null || points.isEmpty()) {
            return points;
        }
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort((left, right) -> Integer.compare(left.frequencyHz, right.frequencyHz));
        return resampleLogSpaced(sorted);
    }

    private static List<Point> resampleLogSpaced(List<Point> points) {
        List<Point> resampled = new ArrayList<>();
        int minHz = MIN_HZ;
        int maxHz = MAX_HZ;
        double start = LOG2_MIN_HZ;
        double end = LOG2_MAX_HZ;
        int count = Math.max(points.size(), (int) Math.ceil((end - start) * SMOOTH_POINTS_PER_OCTAVE) + 1);
        int lastHz = -1;
        for (int i = 0; i < count; i++) {
            double t = count <= 1 ? 0 : i / (double) (count - 1);
            int hz = (int) Math.round(Math.pow(2.0, start + (end - start) * t));
            hz = Math.max(minHz, Math.min(maxHz, hz));
            if (hz == lastHz) {
                continue;
            }
            resampled.add(new Point(hz, interpolatedGainAtHz(points, hz)));
            lastHz = hz;
        }
        return resampled;
    }

    private static List<Point> smoothPointPass(List<Point> points, double windowOctaves) {
        List<Point> smoothed = new ArrayList<>();
        for (Point point : points) {
            double center = log2(point.frequencyHz);
            double weighted = 0;
            double weightSum = 0;
            for (Point candidate : points) {
                double distance = Math.abs(log2(candidate.frequencyHz) - center);
                if (distance > windowOctaves * 2.4) {
                    continue;
                }
                double weight = Math.exp(-0.5 * Math.pow(distance / windowOctaves, 2.0));
                weighted += candidate.gainDb * weight;
                weightSum += weight;
            }
            float gainDb = weightSum <= 0.0001 ? point.gainDb : (float) (weighted / weightSum);
            smoothed.add(new Point(point.frequencyHz, gainDb));
        }
        return smoothed;
    }

    private static float interpolatedGainAtHz(List<Point> points, int frequencyHz) {
        if (frequencyHz <= points.get(0).frequencyHz) {
            return points.get(0).gainDb;
        }
        int last = points.size() - 1;
        if (frequencyHz >= points.get(last).frequencyHz) {
            return points.get(last).gainDb;
        }
        double logHz = log2(frequencyHz);
        for (int i = 0; i < last; i++) {
            Point left = points.get(i);
            Point right = points.get(i + 1);
            if (frequencyHz >= left.frequencyHz && frequencyHz <= right.frequencyHz) {
                double leftLog = log2(left.frequencyHz);
                double rightLog = log2(right.frequencyHz);
                if (rightLog <= leftLog) {
                    return left.gainDb;
                }
                double t = (logHz - leftLog) / (rightLog - leftLog);
                return (float) (left.gainDb + (right.gainDb - left.gainDb) * t);
            }
        }
        return 0f;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    static final class Point {
        final int frequencyHz;
        final float gainDb;

        Point(int frequencyHz, float gainDb) {
            this.frequencyHz = frequencyHz;
            this.gainDb = gainDb;
        }
    }
}
