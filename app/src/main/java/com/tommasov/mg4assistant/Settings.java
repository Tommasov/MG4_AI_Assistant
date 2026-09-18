package com.tommasov.mg4assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.tommasov.mg4assistant.probe.ChatApi;
import com.tommasov.mg4assistant.voice.RemoteVoice;

/**
 * What the driver has chosen: which service answers, which model, which voice, and the keys
 * to reach them with.
 *
 * <p>The keys live here rather than in {@code BuildConfig} now. That was always the intention
 * and it was deferred for one reason — there is no copy-paste on this head unit, so a key
 * typed by hand is barely possible — but leaving them compiled in meant the APK could never
 * be published: constants come out of a dex with grep, and this key has credit attached. A
 * value compiled in is still honoured as a starting point, so an existing private build keeps
 * working until its owner types a key of their own.
 *
 * <p>The two services are not interchangeable. xAI answers questions and nothing else: it has
 * no transcription and no synthesis, so hearing and speaking always go to OpenAI whatever is
 * chosen for the conversation. That is stated plainly on the settings screen rather than
 * discovered as a failure.
 */
public final class Settings {

    public static final String PROVIDER_OPENAI = "openai";
    public static final String PROVIDER_XAI = "xai";

    private static final String PREFS = "mg4assistant";
    private static final String KEY_OPENAI = "openai_key";
    private static final String KEY_XAI = "xai_key";
    private static final String KEY_PROVIDER = "chat_provider";
    private static final String KEY_MODEL = "chat_model";
    private static final String KEY_VOICE = "remote_voice";
    private static final String KEY_CAR_VOICE = "use_car_voice";
    private static final String KEY_WHEEL_START = "wheel_starts_app";
    private static final String KEY_LISTEN_OPEN = "listen_on_open";
    private static final String KEY_OPENAI_OK = "openai_key_verified";
    private static final String KEY_XAI_OK = "xai_key_verified";

    private final SharedPreferences prefs;

    public Settings(@NonNull Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- keys

    @NonNull
    public String openAiKey() {
        String stored = prefs.getString(KEY_OPENAI, "");
        if (!stored.isEmpty()) {
            return stored;
        }
        // A key compiled into a private build still counts, but only as a default.
        String builtIn = ChatApi.builtInKey();
        return builtIn.startsWith("sk-") ? builtIn : "";
    }

    @NonNull
    public String xaiKey() {
        String stored = prefs.getString(KEY_XAI, "");
        if (!stored.isEmpty()) {
            return stored;
        }
        String builtIn = ChatApi.builtInKey();
        return builtIn.startsWith("xai-") ? builtIn : "";
    }

    public void setOpenAiKey(@NonNull String key) {
        // The verdict belongs to the key that earned it. Storing a new key clears it, so the
        // badge can never claim that a key the service has never seen is known to work.
        prefs.edit().putString(KEY_OPENAI, key.trim()).remove(KEY_OPENAI_OK).apply();
    }

    public void setXaiKey(@NonNull String key) {
        prefs.edit().putString(KEY_XAI, key.trim()).remove(KEY_XAI_OK).apply();
    }

    /**
     * Whether the service accepted this key the last time it was asked.
     *
     * <p>Remembered rather than re-checked on every visit to the screen: a check is a network
     * round trip, and a settings screen that reaches for the aerial each time it is opened is
     * a settings screen that is slow in a tunnel. It is a record of the last answer, not a
     * live state, which is why a key that stops working shows green until it is checked again
     * — the tap on the row is there for exactly that.
     */
    public boolean keyVerified(@NonNull String provider) {
        return prefs.getBoolean(PROVIDER_XAI.equals(provider) ? KEY_XAI_OK : KEY_OPENAI_OK,
                false);
    }

    public void setKeyVerified(@NonNull String provider, boolean verified) {
        prefs.edit()
                .putBoolean(PROVIDER_XAI.equals(provider) ? KEY_XAI_OK : KEY_OPENAI_OK, verified)
                .apply();
    }

    /**
     * True when nothing was entered here and the build brought a key of its own.
     *
     * <p>Only ever true of a debug build: a release carries an empty constant by construction,
     * because a key in a dex comes out with grep. Worth saying on screen rather than leaving
     * someone to wonder why an app with no key entered is answering questions.
     */
    /**
     * What was actually entered on this car, with no fallback to the build.
     *
     * <p>The screen has to show this rather than {@link #openAiKey()}: a debug build with a
     * key of its own would otherwise display a key beside a button offering to remove it, and
     * pressing that button would appear to do nothing at all.
     */
    @NonNull
    public String enteredOpenAiKey() {
        return prefs.getString(KEY_OPENAI, "");
    }

    @NonNull
    public String enteredXaiKey() {
        return prefs.getString(KEY_XAI, "");
    }

    /**
     * Whether the app starts recording the moment it appears.
     *
     * <p>On by default, because it is what this app is for. Every way of opening it — the
     * wheel, a gesture, the icon — lands on a screen whose only control is "tap to speak",
     * and an assistant that has to be touched after being opened is an assistant that has
     * been opened twice.
     *
     * <p>The guard against surprise is not this flag but the two conditions in
     * AssistantActivity: no key entered, or microphone not yet granted, and it stays quiet.
     * So a car that has just been set up never starts listening at somebody unprepared.
     */
    public boolean listenOnOpen() {
        return prefs.getBoolean(KEY_LISTEN_OPEN, true);
    }

    public void setListenOnOpen(boolean listen) {
        prefs.edit().putBoolean(KEY_LISTEN_OPEN, listen).apply();
    }

    public boolean usingBuiltInKey() {
        return prefs.getString(KEY_OPENAI, "").isEmpty()
                && prefs.getString(KEY_XAI, "").isEmpty()
                && !ChatApi.builtInKey().isEmpty();
    }

    // ---------------------------------------------------------------- provider

    /** Which service answers questions. Falls back to whichever key exists. */
    @NonNull
    public String chatProvider() {
        String chosen = prefs.getString(KEY_PROVIDER, "");
        if (PROVIDER_XAI.equals(chosen) && !xaiKey().isEmpty()) {
            return PROVIDER_XAI;
        }
        if (PROVIDER_OPENAI.equals(chosen) && !openAiKey().isEmpty()) {
            return PROVIDER_OPENAI;
        }
        return openAiKey().isEmpty() && !xaiKey().isEmpty() ? PROVIDER_XAI : PROVIDER_OPENAI;
    }

    public void setChatProvider(@NonNull String provider) {
        // The model belongs to the provider that was chosen with it; keeping it across a
        // switch would send an xAI model id to OpenAI and produce a 404 that reads like a
        // broken connection.
        prefs.edit().putString(KEY_PROVIDER, provider).remove(KEY_MODEL).apply();
    }

    /** The key for whatever answers questions. May be empty. */
    @NonNull
    public String chatKey() {
        return PROVIDER_XAI.equals(chatProvider()) ? xaiKey() : openAiKey();
    }

    /**
     * The key for hearing and speaking, which is always OpenAI's: xAI publishes no
     * transcription or speech endpoint.
     */
    @NonNull
    public String speechKey() {
        return openAiKey();
    }

    public boolean canAnswer() {
        return !TextUtils.isEmpty(chatKey());
    }

    public boolean canHear() {
        return !TextUtils.isEmpty(speechKey());
    }

    // ---------------------------------------------------------------- model and voice

    /** Empty means "work it out from the account on first use". */
    @NonNull
    public String chatModel() {
        return prefs.getString(KEY_MODEL, "");
    }

    public void setChatModel(@NonNull String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    @NonNull
    public String voice() {
        return prefs.getString(KEY_VOICE, RemoteVoice.VOICES[0]);
    }

    public void setVoice(@NonNull String voice) {
        prefs.edit().putString(KEY_VOICE, voice).apply();
    }

    /**
     * Whether a long press on the wheel opens the assistant.
     *
     * <p>Off by default and deliberately so: the wheel's transport keys belong to whatever is
     * playing, and an app that opened itself on a track skip would be worse than no shortcut
     * at all. Which key this car's voice button actually reports is not yet known, so this
     * stays off until the diagnostics screen says what arrives.
     */
    public boolean wheelStartsApp() {
        return prefs.getBoolean(KEY_WHEEL_START, false);
    }

    public void setWheelStartsApp(boolean value) {
        prefs.edit().putBoolean(KEY_WHEEL_START, value).apply();
    }

    public boolean useCarVoice() {
        return prefs.getBoolean(KEY_CAR_VOICE, false);
    }

    public void setUseCarVoice(boolean value) {
        prefs.edit().putBoolean(KEY_CAR_VOICE, value).apply();
    }
}
