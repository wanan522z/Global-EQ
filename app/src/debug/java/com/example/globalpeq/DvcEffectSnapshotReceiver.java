package com.example.globalpeq;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.audiofx.DynamicsProcessing;

import java.util.Locale;

/** Read-only debug probe for comparing a session-0 DynamicsProcessing client we do not control. */
public final class DvcEffectSnapshotReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        DynamicsProcessing effect = null;
        StringBuilder out = new StringBuilder();
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    0, 2, false, 0, false, 0, true, 1, true).build();
            effect = new DynamicsProcessing(-1000, 0, config);
            out.append("control=").append(effect.hasControl())
                    .append(" enabled=").append(effect.getEnabled());
            for (int channel = 0; channel < 2; channel++) {
                out.append("\nch").append(channel)
                        .append(" input=").append(f(effect.getInputGainByChannelIndex(channel)));
                appendEq(out, " pre", effect.getPreEqByChannelIndex(channel));
                appendEq(out, " post", effect.getPostEqByChannelIndex(channel));
                DynamicsProcessing.Limiter limiter = effect.getLimiterByChannelIndex(channel);
                out.append("\n limiter enabled=").append(limiter.isEnabled())
                        .append(" inUse=").append(limiter.isInUse())
                        .append(" attack=").append(f(limiter.getAttackTime()))
                        .append(" release=").append(f(limiter.getReleaseTime()))
                        .append(" ratio=").append(f(limiter.getRatio()))
                        .append(" threshold=").append(f(limiter.getThreshold()))
                        .append(" postGain=").append(f(limiter.getPostGain()));
            }
        } catch (Throwable error) {
            out.append("\nerror=").append(error.getClass().getSimpleName())
                    .append(':').append(error.getMessage());
        } finally {
            if (effect != null) {
                effect.release();
            }
        }
        setResultCode(0);
        setResultData(out.toString());
    }

    private static void appendEq(StringBuilder out, String name, DynamicsProcessing.Eq eq) {
        out.append("\n").append(name)
                .append(" enabled=").append(eq.isEnabled())
                .append(" inUse=").append(eq.isInUse())
                .append(" bands=").append(eq.getBandCount());
        for (int band = 0; band < eq.getBandCount(); band++) {
            DynamicsProcessing.EqBand value = eq.getBand(band);
            out.append(' ').append(band).append('@')
                    .append(f(value.getCutoffFrequency())).append('=')
                    .append(f(value.getGain()));
        }
    }

    private static String f(float value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
