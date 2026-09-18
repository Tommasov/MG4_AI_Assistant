package com.tommasov.mg4assistant.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Speaks the answer with OpenAI's synthesiser, because the car's own has no Italian voice.
 *
 * <p>That is measured, not assumed: the vehicle's {@code ITtsService} accepts language code 9
 * and then reads Italian with an English voice, mispronouncing it completely. The ten voices
 * actually shipped in the firmware do not include Italian. So an assistant that answers in
 * Italian has to bring its own voice.
 *
 * <p>This is the most expensive part of the whole chain in bytes: an answer of a few words
 * measured 56 KB, against 15 KB for the question going up and a couple for the text. mp3 has
 * a fixed bitrate, so a short reply does not cost proportionally less — which is why the
 * system prompt still discourages padding even though it no longer caps the length.
 *
 * <p>Audio focus is not taken here. It belongs to the whole exchange and is held by the
 * caller from the moment the button is pressed, so the radio is out of the way while the
 * question is being recorded and not only while the answer is read back.
 */
public final class RemoteVoice {

    private static final String TAG = "RemoteVoice";
    private static final String ENDPOINT = "https://api.openai.com/v1/audio/speech";
    private static final String MODEL = "tts-1";

    /**
     * The voices OpenAI offers, in the order the switch cycles them.
     *
     * <p>All of them are trained on English and all of them carry some of that into Italian —
     * the first build used "alloy" and it came out with an accent that sounded mixed rather
     * than foreign. That is a limit of the service, not a setting that is wrong, so the answer
     * is to let the ear choose: they differ enough from one another that one of them will sit
     * better than the rest in this particular car.
     */
    public static final String[] VOICES = {"alloy", "nova", "shimmer", "echo", "fable", "onyx"};

    /**
     * mp3 rather than opus. Opus would be roughly a third smaller, which matters on a car
     * paying for its own mobile data — but this head unit is a 2018 build and mp3 is the
     * format nothing refuses. Worth revisiting once there is a car to test opus playback on.
     */
    private static final String FORMAT = "mp3";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    public interface Callback {
        void onSpeaking(long bytesReceived, long elapsedMs, int characters);

        void onFinished();

        void onFailed(@NonNull String reason);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable private MediaPlayer player;
    @NonNull private String voice = VOICES[0];

    /** Which voice speaks. Anything not in {@link #VOICES} is ignored. */
    public void setVoice(@NonNull String name) {
        for (String candidate : VOICES) {
            if (candidate.equals(name)) {
                voice = name;
                return;
            }
        }
    }

    @NonNull
    public String voice() {
        return voice;
    }

    /** Fetches the audio and plays it. Any speech already playing is cut off first. */
    public void speak(@NonNull Context context, @NonNull String key, @NonNull String text,
                      @NonNull Callback callback) {
        stop();
        final Context appContext = context.getApplicationContext();
        WORKER.execute(() -> {
            long started = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", MODEL);
                body.put("voice", voice);
                body.put("input", text);
                body.put("response_format", FORMAT);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(payload);
                }

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String error = readText(conn.getErrorStream());
                    fail(callback, "HTTP " + code + (error.isEmpty() ? "" : " — " + error));
                    return;
                }

                // Written to a file rather than streamed into the player: MediaPlayer wants a
                // seekable source, and a reply is small enough that the wait is the network's
                // and not the disk's.
                File file = new File(appContext.getCacheDir(), "reply." + FORMAT);
                long bytes = 0;
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        bytes += read;
                    }
                }
                final long received = bytes;
                final long elapsed = System.currentTimeMillis() - started;
                final int characters = text.length();
                MAIN.post(() -> play(appContext, file, received, elapsed, characters,
                        callback));
            } catch (Exception e) {
                String message = e.getMessage();
                fail(callback, e.getClass().getSimpleName()
                        + (message == null ? "" : ": " + message));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void play(@NonNull Context appContext, @NonNull File file, long bytes,
                      long elapsed, int characters, @NonNull Callback callback) {
        // The attributes still matter — they are what routes this to the assistant channel
        // rather than the media one — but the focus itself belongs to the caller now: it is
        // taken when the button is pressed and held until the answer ends, so that the radio
        // is out of the way while the question is being recorded too.
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();

        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setAudioAttributes(attributes);
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnCompletionListener(p -> {
                releasePlayer(appContext);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                callback.onFinished();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                releasePlayer(appContext);
                callback.onFailed("playback failed (" + what + "/" + extra + ")");
                return true;
            });
            mp.prepare();
            mp.start();
            callback.onSpeaking(bytes, elapsed, characters);
        } catch (Exception e) {
            releasePlayer(appContext);
            callback.onFailed("could not play the reply: " + e.getClass().getSimpleName());
        }
    }

    /** Stops anything currently being spoken. */
    public void stop() {
        MediaPlayer mp = player;
        if (mp == null) {
            return;
        }
        try {
            if (mp.isPlaying()) {
                mp.stop();
            }
        } catch (IllegalStateException ignored) {
            // Already finished; release is all that is left to do.
        }
        mp.release();
        player = null;
    }


    private void releasePlayer(@NonNull Context appContext) {
        MediaPlayer mp = player;
        if (mp != null) {
            try {
                mp.release();
            } catch (Exception e) {
                Log.w(TAG, "could not release the player", e);
            }
            player = null;
        }
    }

    private static void fail(@NonNull Callback callback, @NonNull String reason) {
        MAIN.post(() -> callback.onFailed(reason));
    }

    @NonNull
    private static String readText(@Nullable InputStream in) {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            String text = out.toString(StandardCharsets.UTF_8.name()).trim();
            return text.length() <= 300 ? text : text.substring(0, 300) + "…";
        } catch (Exception e) {
            return "";
        }
    }
}
