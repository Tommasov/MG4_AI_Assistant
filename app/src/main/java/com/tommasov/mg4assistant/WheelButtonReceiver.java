package com.tommasov.mg4assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Starts the assistant from the steering wheel, when the app is not already running.
 *
 * <p>This is declared in the manifest rather than registered in code, and it has to be: a
 * receiver created by a running activity exists only while that activity does, so it can
 * never be the thing that opens the app in the first place.
 *
 * <p>It listens for a media button and not for the car's own key broadcast, which would be
 * the obvious choice and does not work. {@code com.saic.keyevent.hardkey.report} is an
 * implicit broadcast, and since Android 8 manifest-declared receivers are not delivered those
 * at all — so the car's hardware key can reach this app while it is open and never wake it.
 * {@code MEDIA_BUTTON} is one of the few that still start an app, which is how a music player
 * resumes from a headset button hours after it was last used.
 *
 * <p><b>Off unless asked for.</b> The wheel's transport keys belong to whatever is playing
 * music, and an assistant that opened itself every time somebody skipped a track would be
 * indefensible. So this does nothing until the setting is switched on, and even then it acts
 * on a long press alone and never consumes the event.
 */
public class WheelButtonReceiver extends BroadcastReceiver {

    private static final String TAG = "WheelButton";

    @Override
    public void onReceive(Context context, @Nullable Intent intent) {
        if (intent == null || !Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            return;
        }
        if (!new Settings(context).wheelStartsApp()) {
            return;
        }
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        if (!isWheelVoice(event)) {
            return;
        }
        Log.i(TAG, "opening the assistant from " + KeyEvent.keyCodeToString(event.getKeyCode()));
        Intent open = new Intent(context, AssistantActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(open);
    }

    /**
     * Whether this press is the one meant to open the assistant.
     *
     * <p>A long press only. A short press of play or pause is somebody wanting their music to
     * stop, and taking it would make the car worse in a way nobody asked for. Which key the
     * wheel's voice button actually reports is not yet known on this vehicle, so both the
     * dedicated voice codes and a held transport key count — the diagnostics screen is what
     * will narrow it down.
     */
    private static boolean isWheelVoice(@NonNull KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return event.isLongPress() || event.getRepeatCount() > 0;
            default:
                return false;
        }
    }
}
