package com.tommasov.mg4assistant.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

/**
 * Records a spoken question, compressed, with a hard limit on how long it may run.
 *
 * <p>Both of those are data decisions before they are audio ones. The car has a 1 GB monthly
 * SIM and every second recorded is a second uploaded: raw 16 kHz PCM costs about 31 KB per
 * second, AAC at 24 kbps costs 3. Over a month of use that is the difference between the
 * assistant being a rounding error on the bill and being the bill.
 *
 * <p>The cap matters for the same reason. There is no open microphone here and no wake word:
 * recording starts when somebody presses the button and stops when they press it again, and
 * if nobody presses it again — a bag resting on the screen, a driver who forgot — it stops
 * itself. Continuous listening would drain the month's data in four days.
 *
 * <p>The file goes to the cache directory and is deleted once it has been sent. Nothing spoken
 * in this car is kept after the answer comes back.
 */
public final class VoiceRecorder {

    private static final String TAG = "VoiceRecorder";

    /** Speech recognition gains nothing above this, and every kHz is bytes. */
    private static final int SAMPLE_RATE = 16000;
    /** Mono AAC. Enough for speech, a tenth of the size of raw PCM. */
    private static final int BIT_RATE = 24000;
    /** The hard stop, in milliseconds. */
    public static final int MAX_DURATION_MS = 30000;
    private static final long LEVEL_INTERVAL_MS = 100;

    public interface Callback {
        void onStarted();

        /** 0 to 100, for something on screen that shows it is listening. */
        void onLevel(int percent);

        /** The recording ended: by hand, or because the cap was reached. */
        void onStopped(@NonNull File audio, int millis, boolean hitLimit);

        void onFailed(@NonNull String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private MediaRecorder recorder;
    @Nullable private File target;
    @Nullable private Callback callback;
    @Nullable private Runnable levelPoll;
    private long startedAt;
    private boolean hitLimit;

    public boolean isRecording() {
        return recorder != null;
    }

    /** Begins recording. RECORD_AUDIO must already be granted. */
    public void start(@NonNull Context context, @NonNull Callback callback) {
        if (recorder != null) {
            return;
        }
        this.callback = callback;
        this.hitLimit = false;

        File file = new File(context.getCacheDir(), "question.m4a");
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        target = file;

        MediaRecorder r = new MediaRecorder();
        try {
            // MIC, not VOICE_RECOGNITION: on this head unit all three sources were measured
            // working, and MIC is the one with no processing chain of somebody else's
            // choosing between the microphone and us.
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioChannels(1);
            r.setAudioSamplingRate(SAMPLE_RATE);
            r.setAudioEncodingBitRate(BIT_RATE);
            r.setMaxDuration(MAX_DURATION_MS);
            r.setOutputFile(file.getAbsolutePath());
            r.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    hitLimit = true;
                    main.post(this::stop);
                }
            });
            r.prepare();
            r.start();
        } catch (Exception e) {
            release(r);
            recorder = null;
            fail("could not start recording: " + describe(e));
            return;
        }

        recorder = r;
        startedAt = System.currentTimeMillis();
        callback.onStarted();
        startLevelPolling();
    }

    /** Ends the recording and hands the file over. Safe to call when not recording. */
    public void stop() {
        MediaRecorder r = recorder;
        if (r == null) {
            return;
        }
        recorder = null;
        stopLevelPolling();
        int millis = (int) (System.currentTimeMillis() - startedAt);
        boolean stopped;
        try {
            r.stop();
            stopped = true;
        } catch (RuntimeException e) {
            // stop() throws when almost nothing was captured — a tap instead of a press. The
            // file it leaves behind is unusable, so this is a failure and not a short answer.
            stopped = false;
        } finally {
            release(r);
        }

        File file = target;
        if (!stopped || file == null || !file.exists() || file.length() == 0) {
            fail("nothing was recorded — hold the button a moment longer");
            return;
        }
        Callback c = callback;
        if (c != null) {
            c.onStopped(file, millis, hitLimit);
        }
    }

    /** Ends the recording and throws the audio away. */
    public void cancel() {
        MediaRecorder r = recorder;
        recorder = null;
        stopLevelPolling();
        if (r != null) {
            try {
                r.stop();
            } catch (RuntimeException ignored) {
                // Nothing captured; there was nothing to salvage anyway.
            }
            release(r);
        }
        deleteRecording();
    }

    /** Removes the cached audio. Call once it has been sent, or when giving up on it. */
    public void deleteRecording() {
        File file = target;
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        target = null;
    }

    private void startLevelPolling() {
        levelPoll = new Runnable() {
            @Override
            public void run() {
                MediaRecorder r = recorder;
                Callback c = callback;
                if (r == null || c == null) {
                    return;
                }
                int amplitude = 0;
                try {
                    amplitude = r.getMaxAmplitude();
                } catch (RuntimeException ignored) {
                    // Some devices refuse this while starting up; a missed frame is harmless.
                }
                // 32767 is the ceiling; speech sits far below it, so the scale is compressed
                // to keep the indicator moving instead of sitting flat near zero.
                int percent = (int) Math.min(100, Math.round(Math.sqrt(amplitude / 32767.0) * 130));
                c.onLevel(percent);
                main.postDelayed(this, LEVEL_INTERVAL_MS);
            }
        };
        main.postDelayed(levelPoll, LEVEL_INTERVAL_MS);
    }

    private void stopLevelPolling() {
        if (levelPoll != null) {
            main.removeCallbacks(levelPoll);
            levelPoll = null;
        }
    }

    private void fail(@NonNull String reason) {
        Log.w(TAG, reason);
        deleteRecording();
        Callback c = callback;
        if (c != null) {
            c.onFailed(reason);
        }
    }

    private static void release(@NonNull MediaRecorder r) {
        try {
            r.reset();
        } catch (RuntimeException ignored) {
            // Already in a bad state; release is what matters.
        }
        r.release();
    }

    @NonNull
    private static String describe(@NonNull Exception e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
