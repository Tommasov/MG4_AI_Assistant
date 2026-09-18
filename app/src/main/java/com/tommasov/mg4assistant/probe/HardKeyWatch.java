package com.tommasov.mg4assistant.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Listens for the vehicle's hardware key broadcast, to find out whether an ordinary app can
 * hear the steering wheel button at all.
 *
 * <p>Read out of the firmware, not guessed: {@code VrSpeechService$VoiceKeyEventReceiver}
 * registers for {@code com.saic.keyevent.hardkey.report} and reads three extras from it —
 * the key code, whether the key is down, and whether the press was long. The same string
 * turns up inside the system server, inside Android Auto and CarPlay, and inside half the
 * factory apps, so it is how this car distributes hardware keys generally.
 *
 * <p>The one thing the firmware cannot say is whether the sender restricts who may receive
 * it. If the system broadcasts it with a receiver permission, an ordinary app hears nothing
 * and there is no error to tell it so — the events simply never arrive. That is precisely
 * why this is a probe and not an assumption: press the button in the car, and either lines
 * appear here or they do not.
 */
public final class HardKeyWatch {

    /** The broadcast the factory voice service listens to. */
    public static final String ACTION = "com.saic.keyevent.hardkey.report";

    public static final String EXTRA_KEYCODE = "android.intent.extra.hardkey.keycode";
    public static final String EXTRA_DOWN = "android.intent.extra.hardkey.down";
    public static final String EXTRA_LONGPRESS = "android.intent.extra.hardkey.longpress";

    /**
     * The other two keys this wheel reports, named by the owner who pressed them in front of
     * the log on 18 September 2026: the hollow star, which he has set to cycle regeneration,
     * and the filled star, which he has set to open the camera.
     *
     * <p>Both are assignable from the car's own settings, and that briefly looked like the way
     * in: the voice key's short press belongs to the factory assistant and cannot be taken from
     * it, while these two are free. It was ruled out on 19 September 2026, and the reason is
     * worth keeping. People already use them. Whoever fits this app to a car has, by then,
     * spent months with one star on the camera and the other on regeneration, and an assistant
     * that asks for one of those back is asking them to give up a habit for a newcomer.
     *
     * <p>So these constants exist to name a keycode in a log, and nothing here listens for
     * them. Note also that the broadcast arrives whatever a key is assigned to — it reports
     * the press, it does not replace the action — so hearing one would not have stopped the
     * camera opening either. The obstacle was never technical.
     */
    public static final int KEYCODE_STAR_HOLLOW = 286;
    public static final int KEYCODE_STAR_FILLED = 17;

    /**
     * The code the voice service compares against. Outside the range Android 9 defines — the
     * platform stops at 285 — so it is SAIC's own numbering for the wheel button.
     */
    public static final int KEYCODE_VOICE_WHEEL = 287;

    /** Enough to see a pattern; a longer list would push the rest of the report off screen. */
    private static final int MAX_EVENTS = 12;

    public interface Listener {
        /** The event as a line, so whoever is listening can file it somewhere lasting. */
        void onEvent(@NonNull String detail);
    }

    /** One press, for a caller that wants to act on it rather than print it. */
    public static final class Event {
        public final int keycode;
        public final boolean down;
        public final boolean longPress;

        Event(int keycode, boolean down, boolean longPress) {
            this.keycode = keycode;
            this.down = down;
            this.longPress = longPress;
        }
    }

    @Nullable private Event last;

    /** The most recent press, or null if none has arrived. */
    @Nullable
    public Event last() {
        return last;
    }

    private final List<String> events = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Nullable private BroadcastReceiver receiver;
    @Nullable private Context registeredOn;

    /** Starts listening. Safe to call twice. */
    public void start(@NonNull Context context, @NonNull Listener listener) {
        if (receiver != null) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (intent == null || !ACTION.equals(intent.getAction())) {
                    return;
                }
                int keycode = intent.getIntExtra(EXTRA_KEYCODE, -1);
                boolean down = intent.getBooleanExtra(EXTRA_DOWN, false);
                boolean longPress = intent.getBooleanExtra(EXTRA_LONGPRESS, false);
                last = new Event(keycode, down, longPress);
                String detail = "keycode " + keycode
                        + (keycode == KEYCODE_VOICE_WHEEL ? " (wheel voice)" : "")
                        + ", down " + down + ", long " + longPress;
                add(clock.format(new Date()) + "  " + detail);
                listener.onEvent(detail);
            }
        };
        // Exported: the sender is the system, not this app.
        ContextCompat.registerReceiver(appContext, receiver, new IntentFilter(ACTION),
                ContextCompat.RECEIVER_EXPORTED);
        registeredOn = appContext;
    }

    /** Stops listening. Safe to call when not started. */
    public void stop() {
        BroadcastReceiver r = receiver;
        Context context = registeredOn;
        receiver = null;
        registeredOn = null;
        if (r != null && context != null) {
            try {
                context.unregisterReceiver(r);
            } catch (IllegalArgumentException ignored) {
                // Never registered, or already gone.
            }
        }
    }

    private void add(@NonNull String line) {
        events.add(line);
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /** What has been heard so far, as report lines. */
    @NonNull
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("  listening for ").append(ACTION).append('\n');
        if (events.isEmpty()) {
            sb.append("  nothing yet — press the voice button on the steering wheel.\n");
            sb.append("  If pressing it does nothing here, the system is sending this "
                    + "broadcast with a receiver permission and an ordinary app cannot "
                    + "hear the wheel this way.\n");
            return sb.toString();
        }
        for (String event : events) {
            sb.append("  ").append(event).append('\n');
        }
        return sb.toString();
    }
}
