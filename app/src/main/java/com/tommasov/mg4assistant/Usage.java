package com.tommasov.mg4assistant;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What this app has spent, counted here rather than asked of the provider.
 *
 * <p>Asking was tried first. OpenAI's newer usage endpoints refuse a project key outright —
 * {@code Missing scopes: api.usage.read} — and the legacy {@code /v1/usage} authorises the key
 * and then answers with empty arrays for days on which this app demonstrably made calls. An
 * admin key would work and is not a thing to carry around in a car.
 *
 * <p>Counting locally turns out to be better anyway, because every figure here is exact rather
 * than estimated. The chat response states its own token usage; the recorder knows to the
 * millisecond how long it listened; the synthesiser counts the characters it was given before
 * it sends them. Nothing is inferred.
 *
 * <p>Bytes are deliberately not among the figures. An earlier version counted them against a
 * monthly data allowance, which assumed every one of these cars has a SIM and a plan: many
 * have neither, and a meter measuring a limit that does not exist is worse than no meter.
 * What the app spends is measured in what the services charge for — time, tokens and
 * characters — and those are the same on every car.
 */
public final class Usage {

    private static final String PREFS = "mg4assistant_usage";
    private static final String KEY_SINCE = "since";
    private static final String KEY_EXCHANGES = "exchanges";
    private static final String KEY_LISTEN_MS = "listen_ms";
    private static final String KEY_TOKENS_IN = "tokens_in";
    private static final String KEY_TOKENS_OUT = "tokens_out";
    private static final String KEY_SPOKEN_CHARS = "spoken_chars";

    private final SharedPreferences prefs;

    public Usage(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_SINCE)) {
            prefs.edit().putLong(KEY_SINCE, System.currentTimeMillis()).apply();
        }
    }

    /** One question asked and answered, and how long it listened to hear it. */
    public void addExchange(long listenMillis) {
        prefs.edit()
                .putLong(KEY_EXCHANGES, exchanges() + 1)
                .putLong(KEY_LISTEN_MS, listenMillis() + listenMillis)
                .apply();
    }

    /** Tokens as the provider itself reported them, not as we guessed them. */
    public void addTokens(long in, long out) {
        prefs.edit()
                .putLong(KEY_TOKENS_IN, tokensIn() + in)
                .putLong(KEY_TOKENS_OUT, tokensOut() + out)
                .apply();
    }

    /** Characters handed to the synthesiser, which is what speech is charged by. */
    public void addSpoken(long characters) {
        prefs.edit().putLong(KEY_SPOKEN_CHARS, spokenChars() + characters).apply();
    }

    public long exchanges() {
        return prefs.getLong(KEY_EXCHANGES, 0);
    }

    public long listenMillis() {
        return prefs.getLong(KEY_LISTEN_MS, 0);
    }

    public long tokensIn() {
        return prefs.getLong(KEY_TOKENS_IN, 0);
    }

    public long tokensOut() {
        return prefs.getLong(KEY_TOKENS_OUT, 0);
    }

    public long spokenChars() {
        return prefs.getLong(KEY_SPOKEN_CHARS, 0);
    }

    public long since() {
        return prefs.getLong(KEY_SINCE, System.currentTimeMillis());
    }

    /** The date the current count began, for a line that says what the figures cover. */
    @NonNull
    public String sinceLabel() {
        return new SimpleDateFormat("d MMMM", Locale.getDefault()).format(new Date(since()));
    }

    /** Starts a new period. The only way the figures go down. */
    public void reset() {
        prefs.edit().clear().putLong(KEY_SINCE, System.currentTimeMillis()).apply();
    }
}
