package com.tommasov.mg4assistant.keys;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tommasov.mg4assistant.Dialogs;
import com.tommasov.mg4assistant.R;
import com.tommasov.mg4assistant.Settings;
import com.tommasov.mg4assistant.probe.ChatApi;

import java.util.List;

/**
 * The three ways a key gets into this app, and the check that follows each of them.
 *
 * <p>Separated from the settings screen because none of it is about settings. Finding a file,
 * asking for storage, reading the clipboard and calling the service to see whether the key is
 * any good are four different concerns that happen to share a button each, and folding them
 * into the screen would have made the screen unreadable.
 *
 * <p>There is one set of buttons rather than one per service, because a key says which service
 * it belongs to: OpenAI's begin {@code sk-}, xAI's begin {@code xai-}. Asking the person to
 * first state which service they are about to paste a key for would be asking them for
 * something the key already knows.
 *
 * <p>Every key is checked against the service the moment it arrives. This is the difference
 * between a wrong character being a five-second message on a parked car and being a mysterious
 * silence three days later on a motorway.
 */
public final class KeyEntry {

    /** Told when a key was stored or removed, so the screen can redraw. */
    public interface Listener {
        void onKeysChanged();
    }

    public static final int REQUEST_STORAGE = 7;

    private final AppCompatActivity activity;
    private final Settings settings;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    public KeyEntry(@NonNull AppCompatActivity activity, @NonNull Settings settings,
                    @NonNull Listener listener) {
        this.activity = activity;
        this.settings = settings;
        this.listener = listener;
    }

    // ---------------------------------------------------------------- the three ways in

    /** Whatever was last copied, if it looks like a key. Instant when it works. */
    public void paste() {
        String key = KeyFinder.fromClipboard(activity);
        if (key.isEmpty()) {
            Dialogs.toast(activity, R.string.key_clipboard_empty);
            return;
        }
        accept(key, activity.getString(R.string.key_origin_clipboard));
    }

    /**
     * Goes looking for a file with a key in it.
     *
     * <p>The permission is asked for here rather than at startup, and only the first time this
     * button is pressed: an assistant that demands access to storage before it has explained
     * why is an assistant people refuse.
     */
    public void fromFile() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Dialogs.builder(activity)
                    .setTitle(R.string.key_from_file)
                    .setMessage(R.string.key_storage_why)
                    .setPositiveButton(android.R.string.ok, (d, w) -> ActivityCompat
                            .requestPermissions(activity,
                                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                                    REQUEST_STORAGE))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        search();
    }

    /** Forwarded from the activity: the search waits for the answer rather than the button. */
    public void onPermissionResult(int requestCode, @NonNull int[] grantResults) {
        if (requestCode != REQUEST_STORAGE) {
            return;
        }
        // Refused is not an error to complain about. The app's own folder needs no permission,
        // so the search still runs and may still find something an adb push left there.
        search();
    }

    private void search() {
        Dialogs.toast(activity, R.string.key_searching);
        new Thread(() -> {
            final List<KeyFinder.Found> found = KeyFinder.search(activity);
            main.post(() -> {
                if (found.isEmpty()) {
                    Dialogs.builder(activity)
                            .setTitle(R.string.key_none_found_title)
                            .setMessage(R.string.key_none_found)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                if (found.size() == 1) {
                    // Confirmed rather than taken. The scan reads files nobody pointed it at,
                    // so the one thing it owes the person is to say which file it read and
                    // which key came out of it before that key starts spending money.
                    final KeyFinder.Found only = found.get(0);
                    Dialogs.builder(activity)
                            .setTitle(R.string.key_found_title)
                            .setMessage(activity.getString(R.string.key_found_confirm,
                                    KeyFinder.mask(only.key), only.where))
                            .setPositiveButton(R.string.key_use,
                                    (d, w) -> accept(only.key, only.where))
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                    return;
                }
                final KeyFinder.Found[] all = found.toArray(new KeyFinder.Found[0]);
                String[] labels = new String[all.length];
                for (int i = 0; i < all.length; i++) {
                    labels[i] = all[i].label();
                }
                Dialogs.builder(activity)
                        .setTitle(R.string.key_pick)
                        .setItems(labels, (d, which) -> accept(all[which].key, all[which].where))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    /**
     * The manual path, kept for completeness rather than for comfort.
     *
     * <p>A dialog rather than a field on the screen: a 164-character key laid out across the
     * width of this dashboard was both unreadable and, with a passenger, indiscreet. Whatever
     * is typed is run through the same extraction as a file, so a key pasted with a stray
     * quote, a newline or the words "API key:" in front of it still arrives clean.
     */
    public void type() {
        EditText field = new EditText(Dialogs.scaled(activity));
        field.setHint(R.string.settings_openai_hint);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        int padding = activity.getResources().getDimensionPixelSize(R.dimen.row_gap);
        field.setPadding(padding, padding, padding, padding);

        Dialogs.builder(activity)
                .setTitle(R.string.key_type_title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String key = KeyFinder.fromText(field.getText().toString());
                    if (key.isEmpty()) {
                        Dialogs.toast(activity, R.string.key_not_a_key);
                        return;
                    }
                    accept(key, activity.getString(R.string.key_origin_typed));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * What a tap on a service's row offers: check it again, or forget it.
     *
     * <p>Checking is the reason the row is worth touching. The badge records the last answer
     * rather than a live one, so a service that has stopped working — a revoked key, an
     * account out of credit — goes on showing green until somebody asks again, and this is
     * where they ask.
     */
    public void openKey(boolean openAi) {
        String entered = openAi ? settings.enteredOpenAiKey() : settings.enteredXaiKey();
        String effective = openAi ? settings.openAiKey() : settings.xaiKey();
        if (effective.isEmpty()) {
            return;
        }
        androidx.appcompat.app.AlertDialog.Builder builder = Dialogs.builder(activity)
                .setTitle(R.string.key_row_title)
                .setMessage(activity.getString(R.string.key_row_explain,
                        KeyFinder.mask(effective)))
                .setPositiveButton(R.string.key_check, (d, w) -> verify(effective))
                .setNegativeButton(android.R.string.cancel, null);
        // Nothing to remove when the key came from the build rather than from this car.
        if (!entered.isEmpty()) {
            builder.setNeutralButton(R.string.key_remove, (d, w) -> {
                if (openAi) {
                    settings.setOpenAiKey("");
                } else {
                    settings.setXaiKey("");
                }
                listener.onKeysChanged();
            });
        }
        builder.show();
    }

    // ---------------------------------------------------------------- storing and checking

    /** Files it under the right service, says where it came from, then checks it. */
    private void accept(@NonNull String key, @NonNull String origin) {
        if (KeyFinder.isOpenAi(key)) {
            settings.setOpenAiKey(key);
        } else if (KeyFinder.isXai(key)) {
            settings.setXaiKey(key);
        } else {
            Dialogs.toast(activity, R.string.key_not_a_key);
            return;
        }
        listener.onKeysChanged();
        Dialogs.toast(activity, activity.getString(R.string.key_saved, origin));
        verify(key);
    }

    /**
     * Asks the service whether the key is real.
     *
     * <p>A listing request rather than a question put to a model: it costs nothing, it needs
     * no model name to be chosen first, and it fails in exactly the same way a wrong key would
     * fail later. A key that is refused is still kept — the refusal may be a flat network on a
     * car in a garage — but it is reported at once and in the service's own words.
     */
    private void verify(@NonNull String key) {
        Dialogs.toast(activity, R.string.key_checking);
        new Thread(() -> {
            final ChatApi.Result result = ChatApi.listModels(key);
            main.post(() -> {
                settings.setKeyVerified(ChatApi.providerFor(key) == ChatApi.Provider.XAI
                        ? Settings.PROVIDER_XAI : Settings.PROVIDER_OPENAI, result.ok);
                if (activity.isFinishing()) {
                    listener.onKeysChanged();
                    return;
                }
                Dialogs.builder(activity)
                        .setTitle(result.ok ? R.string.key_works_title : R.string.key_refused_title)
                        .setMessage(result.ok
                                ? activity.getString(R.string.key_works,
                                ChatApi.chatModelsFrom(ChatApi.modelsFrom(result)).size())
                                : activity.getString(R.string.key_refused, result.summary))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                listener.onKeysChanged();
            });
        }).start();
    }
}
