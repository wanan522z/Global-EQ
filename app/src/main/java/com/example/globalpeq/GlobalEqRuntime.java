package com.example.globalpeq;

final class GlobalEqRuntime {
    private static final GlobalEqualizerEngine ENGINE = new GlobalEqualizerEngine();

    private GlobalEqRuntime() {
    }

    static GlobalEqualizerEngine engine() {
        return ENGINE;
    }
}
