package com.tommasov.mg4assistant;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;

/**
 * Toasts at a size that can be read from the driver's seat.
 *
 * <p>The same mechanism as MG4 Simple Launcher and MG4 Browser, and it belongs here for the
 * same reason: a toast built with the activity keeps the system's own text size, which on
 * this screen is a line of grey nobody can finish reading before it fades. A message that
 * cannot be read in the seconds it lasts might as well not be shown — and this app's toasts
 * are the ones that explain why it is asking for the microphone.
 *
 * <p>The activity stays underneath the wrapper. A context made with
 * {@code createConfigurationContext} has no window token and throws {@code BadTokenException}
 * the moment anything is shown with it; overriding the configuration on a
 * {@link ContextThemeWrapper} keeps the activity and changes only the font scale.
 *
 * <p>Multiplies whatever the system is set to rather than replacing it: a driver who has
 * already enlarged the head unit's font is asking for larger text, not for ours.
 */
public final class Dialogs {

    /** One number, in one place, because that is how it will be retuned. */
    private static final float FONT_SCALE = 1.4f;

    private Dialogs() {
    }

    public static void toast(@NonNull Context context, @StringRes int messageRes) {
        Toast.makeText(scaled(context), messageRes, Toast.LENGTH_LONG).show();
    }

    /** As above, for a message assembled at runtime rather than read from resources. */
    public static void toast(@NonNull Context context, @NonNull CharSequence message) {
        Toast.makeText(scaled(context), message, Toast.LENGTH_LONG).show();
    }

    /** An {@link AlertDialog.Builder} whose text is sized for the car. */
    @NonNull
    public static AlertDialog.Builder builder(@NonNull Context context) {
        return new AlertDialog.Builder(scaled(context));
    }

    @NonNull
    public static Context scaled(@NonNull Context context) {
        Configuration override = new Configuration();
        override.fontScale = context.getResources().getConfiguration().fontScale * FONT_SCALE;
        ContextThemeWrapper wrapper = new ContextThemeWrapper(context, R.style.Theme_MG4Assistant);
        // Must happen before anything reads resources from the wrapper.
        wrapper.applyOverrideConfiguration(override);
        return wrapper;
    }
}
