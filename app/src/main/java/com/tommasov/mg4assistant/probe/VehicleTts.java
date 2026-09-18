package com.tommasov.mg4assistant.probe;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks the car's own speech synthesis to say something, to find out whether an outside app may
 * use it — and in which language.
 *
 * <p>Everything here was read out of the firmware (SaicVoiceTTS_overseas_eh32.apk) and none of
 * it is verified on the vehicle, which is the point of this class. The service is declared
 * {@code exported="true"} with no {@code android:permission}, so binding should be allowed;
 * whether it then answers an app that is not SAIC's own is a different question, and only the
 * car can settle it.
 *
 * <p>There is no AIDL to compile against, so the calls are made by hand onto a {@link Parcel}.
 * The transaction numbers below were read from the {@code TRANSACTION_*} constants inside
 * {@code ITtsService$Stub} and never counted off the method list — in this very interface the
 * two disagree for four methods out of five, which is exactly the trap the head-unit notes
 * warn about.
 *
 * <p>The one thing this cannot report is whether a sound actually came out. {@code transact}
 * returning true means the call reached the service, nothing more. Only the person in the car
 * can say whether they heard anything, which is why the report ends by asking them.
 */
public final class VehicleTts {

    public static final String PACKAGE = "com.saicmotor.voicetts";
    private static final String SERVICE = "com.saicmotor.voicetts.TtsService";
    private static final String DESCRIPTOR = "com.saicmotor.voicetts.ITtsService";

    /** promptCommonWords(String words, boolean, String sourceId) */
    private static final int TX_PROMPT = 1;
    /** promptCommonWordsByLang(String words, int lang, boolean, String sourceId) */
    private static final int TX_PROMPT_BY_LANG = 4;

    /** From Constant in the TTS package. The full list runs to 14; these are the two to try. */
    private static final int LANG_ENG_GBR = 1;
    private static final int LANG_ITA_ITA = 9;

    /**
     * The trailing String is a source id. Left empty because that is what the service's own
     * constants hold: TTS_GREETING_SOURCE_ID and TTS_SECURITY_SOURCE_ID are both "".
     */
    private static final String SOURCE_ID = "";

    /**
     * The boolean parameter, whose meaning is not known. Nothing in the dump names it, and
     * guessing once would mean reporting a failure that might only be a wrong flag — so both
     * values get a turn, and the report says which one was in use.
     */
    private static final boolean[] FLAGS = {true, false};

    /** Long enough for a short sentence to finish before the next attempt starts. */
    private static final long GAP_MS = 4000;
    private static final long BIND_TIMEOUT_MS = 8000;

    public interface Callback {
        /** Always called on the main thread, exactly once. */
        void onResult(@NonNull String report);
    }

    private VehicleTts() {
    }

    /** Whether the TTS package is installed at all. False on the emulator. */
    public static boolean isPresent(@NonNull Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void probe(@NonNull Context context, @NonNull Callback callback) {
        final Context appContext = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        final StringBuilder report = new StringBuilder();

        if (!isPresent(appContext)) {
            callback.onResult("  " + PACKAGE + ": not installed — nothing to try\n");
            return;
        }

        final boolean[] answered = {false};
        final ServiceConnection[] connection = new ServiceConnection[1];

        final Runnable onTimeout = () -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            unbind(appContext, connection[0]);
            callback.onResult("  bind: no answer within " + (BIND_TIMEOUT_MS / 1000)
                    + "s — the service did not connect\n");
        };
        main.postDelayed(onTimeout, BIND_TIMEOUT_MS);

        connection[0] = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                main.removeCallbacks(onTimeout);
                report.append("  bind: connected\n");
                speakInTurn(appContext, main, binder, report, connection[0], callback);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Nothing to add: by the time this fires the attempts have been reported.
            }
        };

        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);
        boolean requested;
        try {
            requested = appContext.bindService(intent, connection[0], Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            requested = false;
            report.append("  bind: refused — ").append(e.getMessage()).append('\n');
        }
        if (!requested) {
            answered[0] = true;
            main.removeCallbacks(onTimeout);
            if (report.length() == 0) {
                report.append("  bind: bindService returned false — service not bindable\n");
            }
            callback.onResult(report.toString());
        }
    }

    /**
     * Runs the attempts one after another with a pause between them, so that if the car does
     * speak, the sentences do not talk over each other and the listener can tell which
     * language came out.
     */
    private static void speakInTurn(@NonNull Context appContext, @NonNull Handler main,
                                    @NonNull IBinder binder, @NonNull StringBuilder report,
                                    @Nullable ServiceConnection connection,
                                    @NonNull Callback callback) {
        final List<Attempt> attempts = new ArrayList<>();
        for (boolean flag : FLAGS) {
            attempts.add(new Attempt("English, byLang", TX_PROMPT_BY_LANG,
                    "Speech test, one two three", LANG_ENG_GBR, flag));
            attempts.add(new Attempt("Italian, byLang", TX_PROMPT_BY_LANG,
                    "Prova di sintesi vocale, uno due tre", LANG_ITA_ITA, flag));
            attempts.add(new Attempt("default language", TX_PROMPT,
                    "Speech test without language", -1, flag));
        }

        final int[] index = {0};
        final Runnable[] step = new Runnable[1];
        step[0] = () -> {
            if (index[0] >= attempts.size()) {
                unbind(appContext, connection);
                report.append("\n  → listen: which of these, if any, was spoken aloud? "
                        + "A call that returns without error still proves only that the "
                        + "service accepted it.\n");
                callback.onResult(report.toString());
                return;
            }
            Attempt a = attempts.get(index[0]++);
            report.append("  ").append(a.label).append(", flag ").append(a.flag)
                    .append(": ").append(call(binder, a)).append('\n');
            main.postDelayed(step[0], GAP_MS);
        };
        main.post(step[0]);
    }

    @NonNull
    private static String call(@NonNull IBinder binder, @NonNull Attempt a) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(a.text);
            if (a.transaction == TX_PROMPT_BY_LANG) {
                data.writeInt(a.lang);
            }
            data.writeInt(a.flag ? 1 : 0);
            data.writeString(SOURCE_ID);
            boolean accepted = binder.transact(a.transaction, data, reply, 0);
            // A void AIDL method still writes an exception header; this rethrows whatever the
            // service threw, which is far more informative than the boolean.
            reply.readException();
            return accepted ? "accepted" : "transact returned false";
        } catch (Exception e) {
            String message = e.getMessage();
            return "refused — " + e.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void unbind(@NonNull Context appContext,
                               @Nullable ServiceConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            appContext.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Never bound, or already unbound. Nothing to salvage either way.
        }
    }

    private static final class Attempt {
        final String label;
        final int transaction;
        final String text;
        final int lang;
        final boolean flag;

        Attempt(String label, int transaction, String text, int lang, boolean flag) {
            this.label = label;
            this.transaction = transaction;
            this.text = text;
            this.lang = lang;
            this.flag = flag;
        }
    }
}
