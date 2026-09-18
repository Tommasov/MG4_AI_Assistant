package com.tommasov.mg4assistant.voice;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLHandshakeException;

/**
 * Turns the recorded question into text, using OpenAI's transcription endpoint.
 *
 * <p>There is no local alternative on this car and that is a measured fact, not an
 * assumption: the head unit has no {@code RecognitionService}, and the factory assistant's
 * own recogniser only ever hands out actions to perform — its interface has no method that
 * returns what was said. So the audio has to leave the vehicle.
 *
 * <p>The request is multipart/form-data, assembled by hand. An HTTP client library would do
 * it in three lines and weigh more than everything else in this app put together; the awkward
 * part is only getting the boundaries right, and that is written once.
 *
 * <p>The content length is computed rather than streamed in chunks. Chunked uploads are the
 * easier code and the worse behaviour here — the car is often on a phone hotspot, and a
 * proxy that mishandles chunked encoding fails in a way that looks like a broken key.
 */
public final class Transcriber {

    private static final String ENDPOINT = "https://api.openai.com/v1/audio/transcriptions";
    private static final String MODEL = "whisper-1";
    private static final String BOUNDARY = "----mg4assistant" + Long.toHexString(System.nanoTime());
    private static final int CONNECT_TIMEOUT_MS = 15000;
    /** Generous: the upload is small but the hotspot may not be. */
    private static final int READ_TIMEOUT_MS = 60000;

    public interface Callback {
        /** Always on the main thread. The text may legitimately be empty — silence. */
        void onText(@NonNull String text, long elapsedMs, long bytesSent);

        void onFailed(@NonNull String reason);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Transcriber() {
    }

    /**
     * @param language an ISO-639-1 code such as "it". Telling the model the language up front
     *                 is not a formality: left to guess, it will now and then decide a short
     *                 Italian phrase was Spanish and transcribe it as such.
     */
    public static void transcribe(@NonNull String key, @NonNull File audio,
                                  @NonNull String language, @NonNull Callback callback) {
        WORKER.execute(() -> {
            long started = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                byte[] head = (field("model", MODEL)
                        + field("language", language)
                        + field("response_format", "json")
                        + "--" + BOUNDARY + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + audio.getName() + "\"\r\n"
                        + "Content-Type: audio/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
                long total = head.length + audio.length() + tail.length;

                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setRequestProperty("Content-Type",
                        "multipart/form-data; boundary=" + BOUNDARY);
                conn.setFixedLengthStreamingMode(total);

                try (OutputStream out = conn.getOutputStream();
                     InputStream in = new FileInputStream(audio)) {
                    out.write(head);
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.write(tail);
                }

                int code = conn.getResponseCode();
                boolean ok = code >= 200 && code < 300;
                String body = read(ok ? conn.getInputStream() : conn.getErrorStream());
                long elapsed = System.currentTimeMillis() - started;

                if (!ok) {
                    fail(callback, describeStatus(code) + " — " + shorten(body));
                    return;
                }
                String text = new JSONObject(body).optString("text", "").trim();
                MAIN.post(() -> callback.onText(text, elapsed, total));
            } catch (SSLHandshakeException e) {
                fail(callback, "TLS handshake refused — the vehicle's trust store is part of "
                        + "the firmware and is never updated");
            } catch (UnknownHostException e) {
                fail(callback, "host not resolved — the car is probably offline");
            } catch (SocketTimeoutException e) {
                fail(callback, "timed out while uploading");
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

    @NonNull
    private static String field(@NonNull String name, @NonNull String value) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
    }

    private static void fail(@NonNull Callback callback, @NonNull String reason) {
        MAIN.post(() -> callback.onFailed(reason));
    }

    @NonNull
    private static String describeStatus(int code) {
        switch (code) {
            case 401:
                return "HTTP 401, the key was rejected";
            case 413:
                return "HTTP 413, the recording was too large";
            case 429:
                return "HTTP 429, rate limited or out of credit";
            default:
                return "HTTP " + code;
        }
    }

    @NonNull
    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    @NonNull
    private static String shorten(@NonNull String text) {
        String trimmed = text.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }
}
