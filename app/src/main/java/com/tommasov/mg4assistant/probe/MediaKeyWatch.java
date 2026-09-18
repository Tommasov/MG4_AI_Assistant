package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The other way a steering wheel can reach an ordinary app: as a media button.
 *
 * <p>Worth trying because the first way may be closed. The car broadcasts its hardware keys
 * on {@code com.saic.keyevent.hardkey.report}, but if the system sends that with a receiver
 * permission then nothing reaches an app like this one and nothing says so either — and the
 * two apps that are known to receive it, Android Auto and CarPlay, are system apps holding
 * {@code CAR_PROJECTION}, so they prove nothing about what is possible without it.
 *
 * <p>Media buttons need no permission at all. An app with an active {@link MediaSession} is
 * offered the wheel's transport keys, and on many head units the voice key arrives among them
 * as {@code KEYCODE_MEDIA_PLAY_PAUSE} held down, or as {@code KEYCODE_VOICE_ASSIST}. Whether
 * this car does that is exactly the sort of thing that cannot be read out of a firmware dump.
 *
 * <p>The session claims to be playing, and has to: a session that reports itself stopped is
 * not a candidate for the buttons, so a probe that did not lie about this would measure
 * nothing. It plays no audio and is released the moment the screen goes away.
 */
public final class MediaKeyWatch {

    private static final int MAX_EVENTS = 12;

    public interface Listener {
        /** The event as a line, so whoever is listening can file it somewhere lasting. */
        void onEvent(@NonNull String detail);
    }

    private final List<String> events = new ArrayList<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Nullable private MediaSession session;

    /** Starts claiming media buttons. Safe to call twice. */
    public void start(@NonNull Context context, @NonNull Listener listener) {
        if (session != null) {
            return;
        }
        try {
            MediaSession created = new MediaSession(context.getApplicationContext(),
                    "mg4assistant-probe");
            created.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            created.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(@NonNull Intent intent) {
                    KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event != null) {
                        String detail = KeyEvent.keyCodeToString(event.getKeyCode())
                                + " (" + event.getKeyCode() + ")"
                                + ", action " + (event.getAction() == KeyEvent.ACTION_DOWN
                                        ? "down" : "up")
                                + ", long " + event.isLongPress()
                                + ", repeat " + event.getRepeatCount();
                        add(clock.format(new Date()) + "  " + detail);
                        listener.onEvent(detail);
                    }
                    // Not consumed: whatever else answers these keys should keep answering
                    // them. This is a probe, not a claim on the wheel.
                    return false;
                }
            });
            created.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY
                            | PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build());
            created.setActive(true);
            session = created;
        } catch (Exception e) {
            String detail = "could not claim media buttons: " + e.getClass().getSimpleName();
            add(clock.format(new Date()) + "  " + detail);
            listener.onEvent(detail);
        }
    }

    /** Gives the buttons back. Safe to call when not started. */
    public void stop() {
        MediaSession s = session;
        session = null;
        if (s != null) {
            try {
                s.setActive(false);
                s.release();
            } catch (Exception ignored) {
                // Already gone; nothing to release.
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
        sb.append("  claiming media buttons with an active MediaSession\n");
        if (events.isEmpty()) {
            sb.append("  nothing yet — press the wheel keys, voice and transport both.\n");
            sb.append("  If the transport keys appear here and the voice key does not, the "
                    + "wheel's voice button is not routed as a media key on this car.\n");
            return sb.toString();
        }
        for (String event : events) {
            sb.append("  ").append(event).append('\n');
        }
        return sb.toString();
    }
}
