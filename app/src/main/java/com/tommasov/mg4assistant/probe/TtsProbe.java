package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Whether this head unit can speak, and in which languages.
 *
 * <p>The counterpart to {@link SpeechProbe}, and subject to the same doubt: the usual engine
 * on a phone is Google's, which ships with Play Services. Some AOSP builds carry PicoTTS
 * instead, which exists but has no Italian — a distinction that matters here and is the
 * reason this reports languages rather than a yes or no.
 *
 * <p>Asynchronous because {@link TextToSpeech} is: it binds to a service and answers through
 * a callback. It is also given a deadline, because on a device with no engine at all that
 * callback has been known never to arrive, and a probe that hangs reports nothing.
 */
public final class TtsProbe {

    private static final long TIMEOUT_MS = 6000;

    public interface Callback {
        /** Always called on the main thread, exactly once. */
        void onResult(@NonNull Result result);
    }

    public static final class Result {
        /** True if the engine reported {@link TextToSpeech#SUCCESS} on init. */
        public final boolean initialised;
        public final boolean timedOut;
        @Nullable public final String defaultEngine;
        @NonNull public final List<String> engines;
        /** Languages checked against the engine, with their availability constant. */
        @NonNull public final List<String> languages;

        Result(boolean initialised, boolean timedOut, @Nullable String defaultEngine,
               @NonNull List<String> engines, @NonNull List<String> languages) {
            this.initialised = initialised;
            this.timedOut = timedOut;
            this.defaultEngine = defaultEngine;
            this.engines = engines;
            this.languages = languages;
        }
    }

    private TtsProbe() {
    }

    public static void probe(@NonNull Context context, @NonNull final Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler handler = new Handler(Looper.getMainLooper());
        // One-element holders so the init callback and the timeout can both reach them.
        final TextToSpeech[] engineHolder = new TextToSpeech[1];
        final boolean[] answered = new boolean[1];

        final Runnable onTimeout = new Runnable() {
            @Override
            public void run() {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                shutdown(engineHolder[0]);
                callback.onResult(new Result(false, true, null,
                        new ArrayList<String>(), new ArrayList<String>()));
            }
        };
        handler.postDelayed(onTimeout, TIMEOUT_MS);

        engineHolder[0] = new TextToSpeech(appContext, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(final int status) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (answered[0]) {
                            return;
                        }
                        answered[0] = true;
                        handler.removeCallbacks(onTimeout);

                        TextToSpeech tts = engineHolder[0];
                        List<String> engines = new ArrayList<>();
                        List<String> languages = new ArrayList<>();
                        String defaultEngine = null;
                        if (tts != null) {
                            try {
                                for (TextToSpeech.EngineInfo info : tts.getEngines()) {
                                    engines.add(info.label + " (" + info.name + ")");
                                }
                                defaultEngine = tts.getDefaultEngine();
                                if (status == TextToSpeech.SUCCESS) {
                                    addLanguage(tts, languages, Locale.ITALIAN);
                                    addLanguage(tts, languages, Locale.ENGLISH);
                                }
                            } catch (Exception e) {
                                // An engine that throws while being questioned is a finding
                                // in itself; report what was gathered before it did.
                                engines.add("query failed: " + e.getClass().getSimpleName());
                            }
                        }
                        shutdown(tts);
                        callback.onResult(new Result(status == TextToSpeech.SUCCESS, false,
                                defaultEngine, engines, languages));
                    }
                });
            }
        });
    }

    private static void addLanguage(@NonNull TextToSpeech tts, @NonNull List<String> into,
                                    @NonNull Locale locale) {
        into.add(locale.getLanguage() + ": " + describe(tts.isLanguageAvailable(locale)));
    }

    @NonNull
    private static String describe(int availability) {
        switch (availability) {
            case TextToSpeech.LANG_AVAILABLE:
                return "available";
            case TextToSpeech.LANG_COUNTRY_AVAILABLE:
                return "available with country";
            case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
                return "available with country and variant";
            case TextToSpeech.LANG_MISSING_DATA:
                return "missing voice data";
            case TextToSpeech.LANG_NOT_SUPPORTED:
                return "not supported";
            default:
                return "unknown (" + availability + ")";
        }
    }

    private static void shutdown(@Nullable TextToSpeech tts) {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (Exception ignored) {
                // Nothing useful to do; the probe is finished with it either way.
            }
        }
    }
}
