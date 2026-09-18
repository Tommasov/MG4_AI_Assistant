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
 * <p>Recording starts when something asks it to and stops when the speaker does: a pause of
 * about a second after speech ends the take. That is what lets a whole exchange happen without
 * a hand on the screen, which is the point of the app. Two guards sit behind it — a take where
 * nobody ever spoke gives up after five seconds rather than uploading a lungful of nothing,
 * and thirty seconds is the hard ceiling whatever happens.
 *
 * <p>There is still no open microphone and no wake word. Continuous listening would drain the
 * month's data in four days.
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

    /*
     * End-of-sentence detection. The whole point of this app is not having to touch the
     * screen, and a recording that only stops when a finger says so costs two taps per
     * question. So the recorder listens for the speaker finishing instead.
     *
     * The thresholds are amplitudes on the 0..32767 scale, chosen from what the probe
     * actually measured in this car: speech peaked at 2777, 4797 and 4241 across the three
     * sources, and an idle cabin read close to nothing. They sit well below the speech
     * figures and well above silence, which leaves room for engine noise without swallowing
     * a quiet voice — but they are the one part of this that has never been tried against a
     * running engine, so the peak is reported back to the screen for retuning.
     */
    private static final int SPEECH_LEVEL = 1500;
    private static final int SILENCE_LEVEL = 800;
    /** A pause this long after speech ends the recording. Shorter clips people mid-thought. */
    private static final long END_SILENCE_MS = 1200;
    /** If nobody has said anything by now, stop rather than upload a lungful of nothing. */
    private static final long LEAD_IN_MS = 5000;

    /** Why a recording ended, which the screen says out loud in different words. */
    public enum Stop {
        /** The speaker finished and paused. The ordinary case, and the quiet one. */
        SILENCE,
        /** Somebody pressed the button again. */
        BY_HAND,
        /** Thirty seconds went by. */
        LIMIT,
        /** Nothing was ever said. */
        NOTHING_SAID
    }

    public interface Callback {
        void onStarted();

        /** 0 to 100, for something on screen that shows it is listening. */
        void onLevel(int percent);

        /** The recording ended: by hand, because speech stopped, or because of the cap. */
        void onStopped(@NonNull File audio, int millis, @NonNull Stop reason, int peak);

        void onFailed(@NonNull String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private MediaRecorder recorder;
    @Nullable private File target;
    @Nullable private Callback callback;
    @Nullable private Runnable levelPoll;
    private long startedAt;
    private boolean hitLimit;
    private boolean heardSpeech;
    private int peakSeen;
    private long quietSince;
    @NonNull private Stop stopReason = Stop.BY_HAND;

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
        this.heardSpeech = false;
        this.peakSeen = 0;
        this.quietSince = 0;
        this.stopReason = Stop.BY_HAND;

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
                    stopReason = Stop.LIMIT;
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
            fail("nothing was recorded");
            return;
        }
        Callback c = callback;
        if (c != null) {
            c.onStopped(file, millis, hitLimit ? Stop.LIMIT : stopReason, peakSeen);
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

                if (amplitude > peakSeen) {
                    peakSeen = amplitude;
                }
                long elapsed = System.currentTimeMillis() - startedAt;
                if (amplitude >= SPEECH_LEVEL) {
                    heardSpeech = true;
                    quietSince = 0;
                } else if (amplitude < SILENCE_LEVEL) {
                    if (quietSince == 0) {
                        quietSince = System.currentTimeMillis();
                    }
                    long quietFor = System.currentTimeMillis() - quietSince;
                    if (heardSpeech && quietFor >= END_SILENCE_MS) {
                        // The sentence is over. This is the path that makes the app usable
                        // without a screen.
                        stopReason = Stop.SILENCE;
                        stop();
                        return;
                    }
                    if (!heardSpeech && elapsed >= LEAD_IN_MS) {
                        stopReason = Stop.NOTHING_SAID;
                        stop();
                        return;
                    }
                } else {
                    // Between the two thresholds: neither clearly speech nor clearly silence,
                    // so the pause timer is left where it is rather than reset by a breath.
                    quietSince = quietSince == 0 ? 0 : quietSince;
                }
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
