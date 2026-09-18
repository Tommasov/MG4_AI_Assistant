package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether anything on this head unit can turn speech into text.
 *
 * <p>This is the finding the whole project hangs on. Android's {@link SpeechRecognizer} is an
 * interface, not an implementation: on ordinary phones the implementation arrives with the
 * Google app, and this car has no Play Services at all. If nothing here answers, then getting
 * from a spoken question to text means recording audio ourselves and sending it to a
 * transcription service — a second provider, a second key and a per-minute cost that the
 * conversation API alone would not have.
 *
 * <p>Three separate questions, because they can disagree: whether the framework says a
 * recogniser is available, which services are actually installed, and whether the
 * "speak now" activity intent resolves to anything. A car could have the last without the
 * first — a factory assistant that answers the intent but registers no service.
 */
public final class SpeechProbe {

    public static final class Result {
        public final boolean frameworkSaysAvailable;
        @NonNull public final List<String> recognitionServices;
        @NonNull public final List<String> recognizeIntentHandlers;

        Result(boolean frameworkSaysAvailable, @NonNull List<String> recognitionServices,
               @NonNull List<String> recognizeIntentHandlers) {
            this.frameworkSaysAvailable = frameworkSaysAvailable;
            this.recognitionServices = recognitionServices;
            this.recognizeIntentHandlers = recognizeIntentHandlers;
        }

        public boolean anythingFound() {
            return frameworkSaysAvailable
                    || !recognitionServices.isEmpty()
                    || !recognizeIntentHandlers.isEmpty();
        }
    }

    private SpeechProbe() {
    }

    @NonNull
    public static Result probe(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        return new Result(
                SpeechRecognizer.isRecognitionAvailable(context),
                packagesFor(pm.queryIntentServices(
                        new Intent(RecognitionService.SERVICE_INTERFACE), 0)),
                packagesFor(pm.queryIntentActivities(
                        new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0)));
    }

    @NonNull
    private static List<String> packagesFor(List<ResolveInfo> resolved) {
        List<String> out = new ArrayList<>();
        if (resolved == null) {
            return out;
        }
        for (ResolveInfo info : resolved) {
            if (info.serviceInfo != null) {
                out.add(info.serviceInfo.packageName);
            } else if (info.activityInfo != null) {
                out.add(info.activityInfo.packageName);
            }
        }
        return out;
    }
}
