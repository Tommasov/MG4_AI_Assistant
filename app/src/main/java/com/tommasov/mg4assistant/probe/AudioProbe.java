package com.tommasov.mg4assistant.probe;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Finds out whether this vehicle actually lets an ordinary app read its microphone.
 *
 * <p>Asking the {@code PackageManager} whether a microphone exists is not the same question.
 * On Android Automotive the hardware can be present, the permission granted, the
 * {@link AudioRecord} open — and every sample still come back as zero, because the input is
 * held by the factory voice assistant or muted at a layer below us. That failure is silent,
 * and it is the one that would waste the most time if it were discovered later, so the probe
 * measures the samples rather than trusting the handles.
 *
 * <p>Nothing is kept. The audio is reduced to a peak amplitude as it is read and the buffer
 * is dropped; there is no file and no upload anywhere in this class.
 */
public final class AudioProbe {

    /** 16 kHz mono is what speech-to-text services want, so it is what gets tested. */
    private static final int SAMPLE_RATE = 16000;

    /** The sources worth trying, in the order a voice assistant would reach for them. */
    public static final int[] SOURCES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    };

    public static final class Result {
        public final int source;
        @NonNull public final String sourceName;
        public final boolean opened;
        public final boolean recording;
        public final long samplesRead;
        /** 0 to 32767. Zero across the whole window means the stream exists but is silent. */
        public final int peak;
        @Nullable public final String error;

        Result(int source, @NonNull String sourceName, boolean opened, boolean recording,
               long samplesRead, int peak, @Nullable String error) {
            this.source = source;
            this.sourceName = sourceName;
            this.opened = opened;
            this.recording = recording;
            this.samplesRead = samplesRead;
            this.peak = peak;
            this.error = error;
        }

        /** What the numbers mean, in the words the finding will be reported in. */
        @NonNull
        public String verdict() {
            if (error != null) {
                return "failed: " + error;
            }
            if (!opened) {
                return "could not be opened";
            }
            if (!recording) {
                return "opened but never started recording";
            }
            if (samplesRead == 0) {
                return "started but returned no samples";
            }
            if (peak == 0) {
                return "returns digital silence — the stream is open but carries nothing";
            }
            if (peak < 200) {
                // Roughly -44 dBFS. Could be a quiet cabin; could be a dead input.
                return "very quiet (peak " + peak + ") — retry while speaking to be sure";
            }
            return "working (peak " + peak + ")";
        }
    }

    private AudioProbe() {
    }

    @NonNull
    public static String nameOf(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            default:
                return "source " + source;
        }
    }

    /**
     * Records from {@code source} for {@code millis} and reports what came back. Blocking:
     * call it off the main thread. Requires RECORD_AUDIO to have been granted already.
     */
    @NonNull
    public static Result record(int source, int millis) {
        String name = nameOf(source);
        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            return new Result(source, name, false, false, 0, 0,
                    "this device reports no usable buffer size at 16 kHz mono");
        }

        AudioRecord recorder = null;
        try {
            // Four times the minimum: on a head unit the reading thread can be starved for a
            // while, and a buffer at exactly the minimum overruns and drops samples, which
            // would read as a quieter microphone than there really is.
            recorder = new AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer * 4);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                return new Result(source, name, false, false, 0, 0, null);
            }
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                return new Result(source, name, true, false, 0, 0, null);
            }

            short[] buffer = new short[minBuffer / 2];
            long samples = 0;
            int peak = 0;
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read < 0) {
                    return new Result(source, name, true, true, samples, peak,
                            "read returned " + read);
                }
                samples += read;
                for (int i = 0; i < read; i++) {
                    int magnitude = Math.abs(buffer[i]);
                    if (magnitude > peak) {
                        peak = magnitude;
                    }
                }
            }
            return new Result(source, name, true, true, samples, peak, null);
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            return new Result(source, name, false, false, 0, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop();
                    }
                } catch (IllegalStateException ignored) {
                    // Already stopped; nothing to salvage and nothing to report.
                }
                recorder.release();
            }
        }
    }
}
