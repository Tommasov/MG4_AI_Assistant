package com.tommasov.mg4assistant.probe;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tommasov.mg4assistant.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;

/**
 * The smallest possible conversation with a chat API, for finding out whether this car can
 * hold one at all.
 *
 * <p>Speaks to xAI or to OpenAI without being told which: both serve the same
 * {@code /v1/chat/completions} shape, and the key says where it belongs — {@code xai-…} or
 * {@code sk-…}. That is not a feature for its own sake. Which provider this project ends up
 * using is still open, and a probe that can only reach the one without credit on it measures
 * nothing.
 *
 * <p>Written against {@link HttpURLConnection} rather than an HTTP client library. One request
 * does not justify a dependency, and this has to run on a head unit whose system pieces are
 * all from 2018.
 *
 * <p>The failure this is really watching for is TLS. The vehicle's trust store is part of a
 * frozen firmware, and a certificate authority that post-dates it will not verify no matter
 * how correct everything else is — a failure that has nothing to do with the key, the model
 * or the network, and looks like all three if it is not named. So it is named.
 */
public final class ChatApi {

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** Enough of a response body to diagnose one, short enough to read on the car's screen. */
    private static final int MAX_BODY_CHARS = 900;
    /** OpenAI lists a hundred-odd models; the screen is not the place for all of them. */
    private static final int MAX_MODELS_SHOWN = 8;

    /** Where a key belongs, worked out from its own prefix. */
    public enum Provider {
        OPENAI("OpenAI", "https://api.openai.com/v1/"),
        XAI("xAI", "https://api.x.ai/v1/"),
        UNKNOWN("unrecognised", "");

        @NonNull public final String label;
        @NonNull public final String baseUrl;

        Provider(@NonNull String label, @NonNull String baseUrl) {
            this.label = label;
            this.baseUrl = baseUrl;
        }
    }

    public static final class Result {
        public final boolean ok;
        /** HTTP status, or -1 when the request never got an answer. */
        public final int httpCode;
        @NonNull public final String summary;
        /**
         * The response body, whole. It is kept intact because it has to be parsed, and it is
         * shortened only on its way to the screen — cutting it here once cost a model list
         * that arrived complete, was trimmed to 900 characters, and then would not parse,
         * which the report announced as an account with no models on it.
         */
        @NonNull public final String detail;
        public final long elapsedMs;

        Result(boolean ok, int httpCode, @NonNull String summary, @NonNull String detail,
               long elapsedMs) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.summary = summary;
            this.detail = detail;
            this.elapsedMs = elapsedMs;
        }
    }

    private ChatApi() {
    }

    /** The key compiled in from apikeys.properties, if any. Normally empty — see the probe. */
    @NonNull
    public static String builtInKey() {
        return BuildConfig.API_KEY == null ? "" : BuildConfig.API_KEY;
    }

    /** Which service a key belongs to. Guessing a base URL wrongly wastes a whole round trip. */
    @NonNull
    public static Provider providerFor(@NonNull String key) {
        if (key.startsWith("xai-")) {
            return Provider.XAI;
        }
        if (key.startsWith("sk-")) {
            return Provider.OPENAI;
        }
        return Provider.UNKNOWN;
    }

    /**
     * Lists the models the key can reach.
     *
     * <p>The cheapest useful request there is: it proves DNS, TLS, the key and the account in
     * one go, and costs no tokens. It also settles which model name to use — worth more than
     * it sounds, since hard-coding a guess at a model id is the kind of mistake that reads as
     * a broken connection.
     */
    @NonNull
    public static Result listModels(@NonNull String key) {
        return request(key, "GET", "models", null);
    }

    /** Model ids parsed out of a successful {@link #listModels} body. */
    @NonNull
    public static List<String> modelsFrom(@NonNull Result result) {
        List<String> ids = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(result.detail).optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    String id = data.getJSONObject(i).optString("id", "");
                    if (!id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            // The caller still has the raw body to look at, which is the useful part.
        }
        return ids;
    }

    /**
     * A model from the list that will actually answer a chat request.
     *
     * <p>Not simply the first one. OpenAI's catalogue is mostly not chat — it lists embedding,
     * speech, transcription and image models in no helpful order, and sending a chat request
     * to one of those returns a 404 that reads exactly like a wrong endpoint. So the pick is
     * made from what the account really has, filtered to the families that take messages.
     */
    @NonNull
    public static List<String> chatModelsFrom(@NonNull List<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (isChatModel(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static boolean isChatModel(@NonNull String id) {
        String lower = id.toLowerCase(Locale.US);
        boolean family = lower.startsWith("gpt-") || lower.startsWith("grok")
                || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4");
        if (!family) {
            return false;
        }
        return !(lower.contains("audio") || lower.contains("realtime")
                || lower.contains("image") || lower.contains("transcribe")
                || lower.contains("tts") || lower.contains("search")
                || lower.contains("embedding") || lower.contains("moderation"));
    }

    @Nullable
    public static String chatModelFrom(@NonNull List<String> ids) {
        for (String id : ids) {
            if (isChatModel(id)) {
                return id;
            }
        }
        return null;
    }

    /** Shortens a long catalogue for a screen that has to be read from a driver's seat. */
    @NonNull
    public static String summarise(@NonNull List<String> ids) {
        if (ids.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(ids.size(), MAX_MODELS_SHOWN);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
        }
        if (ids.size() > shown) {
            sb.append(" … and ").append(ids.size() - shown).append(" more");
        }
        return sb.toString();
    }

    /** One question, one answer. Nothing optional is sent: fewer parameters, fewer excuses. */
    @NonNull
    public static Result chat(@NonNull String key, @NonNull String model,
                              @NonNull String prompt) {
        try {
            JSONObject message = new JSONObject();
            message.put("role", "user");
            message.put("content", prompt);
            return chat(key, model, new JSONArray().put(message));
        } catch (Exception e) {
            return new Result(false, -1, "could not build the request",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    /**
     * A conversation rather than a single question.
     *
     * <p>Worth remembering what this costs: the whole array is re-sent on every turn, so an
     * unbounded history grows both the bill and the request without bound. Whoever builds the
     * array is responsible for keeping it short — see Conversation.
     */
    @NonNull
    public static Result chat(@NonNull String key, @NonNull String model,
                              @NonNull JSONArray messages) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            return request(key, "POST", "chat/completions", body.toString());
        } catch (Exception e) {
            return new Result(false, -1, "could not build the request",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), 0);
        }
    }

    /**
     * Tokens in and out, as the provider itself reported them in the response body.
     *
     * <p>Taken from the answer rather than counted here: token counting depends on the
     * tokeniser of the model that happened to answer, and an estimate that drifts is worse
     * than no figure at all when the point is to know what was spent.
     *
     * @return {in, out}, or {0, 0} if the body carried no usage block.
     */
    @NonNull
    public static long[] usageFrom(@NonNull Result result) {
        try {
            JSONObject usage = new JSONObject(result.detail).optJSONObject("usage");
            if (usage != null) {
                return new long[]{usage.optLong("prompt_tokens", 0),
                        usage.optLong("completion_tokens", 0)};
            }
        } catch (Exception e) {
            // No usage block is not an error: the figures simply do not move.
        }
        return new long[]{0, 0};
    }

    /** The assistant's reply, pulled out of a successful {@link #chat} body. */
    @Nullable
    public static String replyFrom(@NonNull Result result) {
        try {
            JSONArray choices = new JSONObject(result.detail).optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            return message == null ? null : message.optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    @NonNull
    private static Result request(@NonNull String key, @NonNull String method,
                                  @NonNull String path, @Nullable String body) {
        if (TextUtils.isEmpty(key)) {
            return new Result(false, -1, "no key",
                    "Paste one into the field above.", 0);
        }
        Provider provider = providerFor(key);
        if (provider == Provider.UNKNOWN) {
            return new Result(false, -1, "unrecognised key",
                    "A key should start with sk- (OpenAI) or xai- (xAI). Without knowing "
                            + "which, there is no host to send it to.", 0);
        }

        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(provider.baseUrl + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }

            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            String text = read(ok ? connection.getInputStream() : connection.getErrorStream());
            long elapsed = System.currentTimeMillis() - started;
            return new Result(ok, code, ok ? "HTTP " + code : describeStatus(code),
                    text.trim(), elapsed);
        } catch (SSLHandshakeException e) {
            // The one worth calling out by name: nothing about the key or the model is wrong.
            return new Result(false, -1, "TLS handshake refused",
                    "The certificate could not be verified. The vehicle's trust store ships "
                            + "with the firmware and is not updated, so a certificate "
                            + "authority newer than it will fail here and nowhere else. ("
                            + e.getMessage() + ")",
                    System.currentTimeMillis() - started);
        } catch (SSLException e) {
            return new Result(false, -1, "TLS failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - started);
        } catch (UnknownHostException e) {
            return new Result(false, -1, "host not resolved",
                    "DNS could not resolve the API host — most likely the car is not online. ("
                            + e.getMessage() + ")",
                    System.currentTimeMillis() - started);
        } catch (SocketTimeoutException e) {
            return new Result(false, -1, "timed out",
                    "No answer within the timeout. (" + e.getMessage() + ")",
                    System.currentTimeMillis() - started);
        } catch (IOException | RuntimeException e) {
            return new Result(false, -1, "request failed",
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - started);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @NonNull
    private static String describeStatus(int code) {
        switch (code) {
            case 401:
                return "HTTP 401 — the key was rejected";
            case 403:
                return "HTTP 403 — the key is not allowed to do this";
            case 404:
                return "HTTP 404 — no such endpoint or model";
            case 429:
                return "HTTP 429 — rate limited or out of credit";
            default:
                return "HTTP " + code;
        }
    }

    @NonNull
    private static String read(@Nullable InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return "could not read the response: " + e.getMessage();
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing left to salvage.
            }
        }
    }

    /** A response body cut down to what fits on the car's screen. Display only. */
    @NonNull
    public static String forDisplay(@NonNull String text) {
        String trimmed = text.trim();
        return trimmed.length() <= MAX_BODY_CHARS
                ? trimmed
                : trimmed.substring(0, MAX_BODY_CHARS) + "… (truncated)";
    }
}
