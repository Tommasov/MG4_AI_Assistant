package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Watches the steering wheel for a few minutes, including while this app is out of sight.
 *
 * <p>It exists because of a circularity that made the earlier test unanswerable. The question
 * is whether the wheel's voice key can reach an ordinary app; the diagnostics screen listened
 * for it, and stopped listening in {@code onStop}. But pressing that key is precisely what
 * brings the factory assistant to the front, which puts the diagnostics screen into
 * {@code onStop}. The press being measured switched off the instrument measuring it, and the
 * empty screen that came back afterwards could equally have meant "the wheel does not reach
 * apps" or "we stopped listening a moment too soon" — opposite conclusions, identical evidence.
 *
 * <p>So the watch is a window: opened deliberately, it survives the screen going away, writes
 * every event to a {@link Journal} the instant it arrives, and closes itself after three
 * minutes. Bounded rather than permanent for one specific reason — the media-button half of
 * it holds an active {@code MediaSession} declaring itself to be playing, which is how it gets
 * offered the buttons at all, and a session like that left running in the background is a
 * session competing with the car's own music player for play and pause.
 *
 * <p>One instance per process. Two diagnostics screens would otherwise register two receivers
 * and record every press twice, which on a test whose whole purpose is counting presses would
 * be a quietly misleading result.
 */
public final class WheelWatch {

    /** Long enough to try short, long and double presses without rushing; short enough to
     *  keep the media session out of the music player's way. */
    public static final long WINDOW_MS = 3 * 60 * 1000L;

    private static final String PREFS = "mg4assistant_wheel";
    private static final String KEY_UNTIL = "open_until";
    private static final int MAX_LINES = 60;

    @Nullable private static WheelWatch instance;

    public interface Listener {
        void onEvent();
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Journal journal;
    private final HardKeyWatch hardKeys = new HardKeyWatch();
    private final MediaKeyWatch mediaKeys = new MediaKeyWatch();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable expire = this::close;

    /** Set while a screen is showing the findings, null the rest of the time. */
    @Nullable private Listener listener;
    private boolean watching;

    @NonNull
    public static synchronized WheelWatch shared(@NonNull Context context) {
        if (instance == null) {
            instance = new WheelWatch(context.getApplicationContext());
        }
        return instance;
    }

    private WheelWatch(@NonNull Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.journal = new Journal(app, "wheel", MAX_LINES);
    }

    // ---------------------------------------------------------------- the window

    /** Opens a window and starts listening. */
    public void open() {
        prefs.edit().putLong(KEY_UNTIL, System.currentTimeMillis() + WINDOW_MS).commit();
        journal.add("watch opened");
        startWatching();
    }

    /**
     * Picks a window back up after the screen, or the whole process, has been away.
     *
     * <p>Worth doing rather than making the person start again: a press that killed this app
     * is the most interesting kind of press there is, and whoever comes back to the screen to
     * see what happened should find the watch still running.
     */
    public void resumeIfOpen() {
        if (isOpen()) {
            startWatching();
        } else {
            stopWatching();
        }
    }

    public void close() {
        if (isOpen()) {
            journal.add("watch closed");
        }
        prefs.edit().remove(KEY_UNTIL).commit();
        stopWatching();
        Listener l = listener;
        if (l != null) {
            l.onEvent();
        }
    }

    public boolean isOpen() {
        return remainingMs() > 0;
    }

    public long remainingMs() {
        return Math.max(0, prefs.getLong(KEY_UNTIL, 0) - System.currentTimeMillis());
    }

    /** Attached while a screen is visible; detached so nothing holds on to a dead activity. */
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void clearLog() {
        journal.clear();
        Listener l = listener;
        if (l != null) {
            l.onEvent();
        }
    }

    // ---------------------------------------------------------------- listening

    private void startWatching() {
        if (watching) {
            return;
        }
        watching = true;
        hardKeys.start(app, detail -> record("broadcast", detail));
        mediaKeys.start(app, detail -> record("media button", detail));
        main.removeCallbacks(expire);
        main.postDelayed(expire, remainingMs());
    }

    private void stopWatching() {
        main.removeCallbacks(expire);
        if (!watching) {
            return;
        }
        watching = false;
        hardKeys.stop();
        mediaKeys.stop();
    }

    /**
     * Files an event that arrived somewhere other than this object's own receivers.
     *
     * <p>For the manifest receiver, which may be running in a process this class knows nothing
     * about. Kept to the open window so that ordinary driving, where the wheel is pressed all
     * the time for its real purposes, does not quietly fill the log.
     */
    public void recordExternal(@NonNull String source, @NonNull String detail) {
        if (isOpen()) {
            record(source, detail);
        }
    }

    private void record(@NonNull String source, @NonNull String detail) {
        journal.add(source + "  " + detail);
        Listener l = listener;
        if (l != null) {
            l.onEvent();
        }
    }

    // ---------------------------------------------------------------- the report

    /** The whole finding: what is being listened for, whether it is listening, and what came. */
    @NonNull
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(HardKeyWatch.ACTION).append(", and media buttons via an active "
                + "MediaSession\n");
        if (isOpen()) {
            sb.append("  watching — ").append(Math.round(remainingMs() / 1000f))
                    .append(" s left. Press the wheel now: short, long, and twice quickly.\n");
        } else {
            sb.append("  not watching. Press Watch the wheel, then go and press it.\n");
        }
        if (journal.isEmpty()) {
            sb.append("  nothing recorded yet.\n");
            return sb.toString();
        }
        sb.append(journal.describe());
        if (hasManifestEvent()) {
            sb.append("  → a line tagged \"manifest\" means the car's broadcast reaches a "
                    + "receiver declared in the manifest, so the wheel can wake this app when "
                    + "it is not running.\n");
        }
        if (!hasKeyEvent()) {
            // Worth saying outright, because an empty-looking log after a real test is the
            // finding, not the absence of one.
            sb.append("  → the watch ran and no key arrived. If that holds with the engine on "
                    + "and Hello MG off, the wheel does not reach an ordinary app by either "
                    + "route, and the settings switch should stay off.\n");
        }
        return sb.toString();
    }

    /** True when the manifest receiver has been heard from: the awkward half of the question. */
    private boolean hasManifestEvent() {
        for (String line : journal.lines()) {
            if (line.contains("manifest")) {
                return true;
            }
        }
        return false;
    }

    /** True once something other than the watch's own bookkeeping has been recorded. */
    private boolean hasKeyEvent() {
        for (String line : journal.lines()) {
            if (line.contains("broadcast") || line.contains("media button")) {
                return true;
            }
        }
        return false;
    }
}
