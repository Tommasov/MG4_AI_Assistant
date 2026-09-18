package com.tommasov.mg4assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.tommasov.mg4assistant.probe.HardKeyWatch;
import com.tommasov.mg4assistant.probe.WheelWatch;

/**
 * Declared in the manifest, to answer the one question a running app cannot answer about
 * itself: whether the steering wheel can reach this app when this app is not running.
 *
 * <p>The car was measured on 18 September 2026 and settled the first half of it. The wheel's
 * voice key does arrive, as {@code com.saic.keyevent.hardkey.report} with keycode 287, and a
 * long press arrives distinguishably — but it arrived at a receiver registered by a live
 * activity, which proves only that a living app can hear the wheel. The same trip disproved
 * the route this app had been built on: a MediaSession sat active and claiming media buttons
 * throughout, and not one wheel press reached it. That route is gone.
 *
 * <p>Which leaves a deduction standing where a measurement belongs. Android 8 stopped
 * delivering implicit broadcasts to manifest receivers, so the honest expectation is that this
 * receiver never fires — but the rule has exceptions, the sender is a system app that may well
 * target its broadcast, and the cost of finding out is this class. It was the same style of
 * reasoning, confidently applied and never checked, that put the feature on media buttons for
 * a month.
 *
 * <p>So: it records into the wheel watch, but only while a watch window is open, which keeps
 * ordinary driving out of the log. And it opens the assistant on a long press when the setting
 * asks it to — the feature and its own proof in one place.
 */
public class WheelBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || !HardKeyWatch.ACTION.equals(intent.getAction())) {
            return;
        }
        int keycode = intent.getIntExtra(HardKeyWatch.EXTRA_KEYCODE, -1);
        boolean down = intent.getBooleanExtra(HardKeyWatch.EXTRA_DOWN, false);
        boolean longPress = intent.getBooleanExtra(HardKeyWatch.EXTRA_LONGPRESS, false);

        // Tagged as "manifest" so the report shows at a glance which of the two routes heard
        // it. A line tagged this way with the app closed is the finding this exists for.
        WheelWatch.shared(context).recordExternal("manifest",
                "keycode " + keycode + describeKey(keycode)
                        + ", down " + down + ", long " + longPress);

        if (!down || !longPress || keycode != HardKeyWatch.KEYCODE_VOICE_WHEEL) {
            return;
        }
        if (!new Settings(context).wheelStartsApp()) {
            return;
        }
        Intent open = new Intent(context, AssistantActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(open);
        } catch (RuntimeException e) {
            // Starting an activity from the background is itself restricted from Android 10
            // on. This head unit is Android 9, where it is allowed — but a refusal here is a
            // finding rather than a crash, so it is written down and not thrown.
            WheelWatch.shared(context).recordExternal("manifest",
                    "could not open the app: " + e.getClass().getSimpleName());
        }
    }

    /** The three wheel keys this car is known to report, named by the owner who pressed them. */
    @NonNull
    private static String describeKey(int keycode) {
        switch (keycode) {
            case HardKeyWatch.KEYCODE_VOICE_WHEEL:
                return " (wheel voice)";
            case HardKeyWatch.KEYCODE_STAR_HOLLOW:
                return " (hollow star — regen by default)";
            case HardKeyWatch.KEYCODE_STAR_FILLED:
                return " (filled star — camera by default)";
            default:
                return "";
        }
    }
}
