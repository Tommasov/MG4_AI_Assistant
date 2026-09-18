package com.tommasov.mg4assistant.voice;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Speaks through the vehicle's own synthesiser: free, instant, and working with no connection.
 *
 * <p>Verified on the car: an ordinary app binds to {@code com.saicmotor.voicetts} and the
 * sentences come out of the speakers. The catch is the one the firmware predicted — the ten
 * voices shipped on this unit do not include Italian, and Italian text handed to it is read
 * aloud by the English voice, mangled. So this is the right voice for an assistant that
 * answers in English, and the wrong one for the Italian it answers in today.
 *
 * <p>Kept anyway, and offered as a switch, because the difference it makes is not small:
 * no download, no wait, and about 26 KB per exchange instead of 65. Whether that trade is
 * worth an English accent is a judgement to make in the driver's seat, not on paper.
 *
 * <p>The transaction numbers were read from the {@code TRANSACTION_*} constants inside
 * {@code ITtsService$Stub} and never counted off the method list — in this interface the two
 * disagree for four methods out of five. See voice-reference.md.
 */
public final class CarVoice {

    private static final String TAG = "CarVoice";

    public static final String PACKAGE = "com.saicmotor.voicetts";
    public static final String SERVICE = "com.saicmotor.voicetts.TtsService";
    public static final String DESCRIPTOR = "com.saicmotor.voicetts.ITtsService";

    /** promptCommonWordsByLang(String words, int lang, boolean, String sourceId) */
    public static final int TX_PROMPT_BY_LANG = 4;
    /** promptCommonWords(String words, boolean, String sourceId) */
    public static final int TX_PROMPT = 1;
    /** stopPrompt() */
    public static final int TX_STOP = 3;

    public static final int LANG_ENG_GBR = 1;
    public static final int LANG_ITA_ITA = 9;

    /** The service's own constants for this field are empty strings. */
    private static final String SOURCE_ID = "";
    /** Meaning unknown; both values were accepted and spoken on the car. */
    private static final boolean FLAG = true;

    private static final long BIND_TIMEOUT_MS = 8000;

    public interface Callback {
        /** The service accepted the text. It cannot tell us a sound actually came out. */
        void onAccepted();

        void onFailed(@NonNull String reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());

    @Nullable private IBinder binder;
    @Nullable private ServiceConnection connection;
    @Nullable private Context boundContext;

    /** Whether the vehicle's speech package is installed. False everywhere but the car. */
    public static boolean isAvailable(@NonNull Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Says {@code text} in {@code language}, binding on first use and staying bound.
     *
     * <p>The connection is kept rather than made per sentence: binding costs a round trip
     * through the system, and an assistant that pauses before every answer to shake hands
     * would give away the one advantage this voice has over the remote one.
     */
    public void speak(@NonNull Context context, @NonNull String text, int language,
                      @NonNull Callback callback) {
        final Context appContext = context.getApplicationContext();
        IBinder existing = binder;
        if (existing != null && existing.isBinderAlive()) {
            deliver(existing, text, language, callback);
            return;
        }
        if (!isAvailable(appContext)) {
            callback.onFailed(PACKAGE + " is not installed");
            return;
        }

        final boolean[] answered = {false};
        final Runnable onTimeout = () -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            callback.onFailed("the speech service did not connect");
        };
        main.postDelayed(onTimeout, BIND_TIMEOUT_MS);

        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                binder = service;
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                main.removeCallbacks(onTimeout);
                deliver(service, text, language, callback);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                binder = null;
            }
        };
        connection = conn;
        boundContext = appContext;

        Intent intent = new Intent();
        intent.setClassName(PACKAGE, SERVICE);
        boolean requested;
        try {
            requested = appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            requested = false;
        }
        if (!requested) {
            answered[0] = true;
            main.removeCallbacks(onTimeout);
            connection = null;
            callback.onFailed("the car refused the connection to its speech service");
        }
    }

    /** Cuts off whatever is being said. */
    public void stop() {
        IBinder b = binder;
        if (b == null || !b.isBinderAlive()) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel replyParcel = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            b.transact(TX_STOP, data, replyParcel, 0);
            replyParcel.readException();
        } catch (Exception e) {
            Log.w(TAG, "stopPrompt refused", e);
        } finally {
            replyParcel.recycle();
            data.recycle();
        }
    }

    /** Lets the service go. Call when the screen does. */
    public void release() {
        ServiceConnection conn = connection;
        Context context = boundContext;
        connection = null;
        boundContext = null;
        binder = null;
        if (conn != null && context != null) {
            try {
                context.unbindService(conn);
            } catch (IllegalArgumentException ignored) {
                // Never bound, or already unbound.
            }
        }
    }

    private void deliver(@NonNull IBinder target, @NonNull String text, int language,
                         @NonNull Callback callback) {
        Parcel data = Parcel.obtain();
        Parcel replyParcel = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(text);
            data.writeInt(language);
            data.writeInt(FLAG ? 1 : 0);
            data.writeString(SOURCE_ID);
            target.transact(TX_PROMPT_BY_LANG, data, replyParcel, 0);
            // A void AIDL method still writes an exception header; this rethrows whatever the
            // service threw, which says far more than the boolean transact returns.
            replyParcel.readException();
            callback.onAccepted();
        } catch (Exception e) {
            String message = e.getMessage();
            callback.onFailed("the car's voice refused it — " + e.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message));
        } finally {
            replyParcel.recycle();
            data.recycle();
        }
    }
}
