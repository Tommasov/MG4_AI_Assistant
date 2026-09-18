package com.tommasov.mg4assistant;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.tommasov.mg4assistant.keys.KeyEntry;
import com.tommasov.mg4assistant.keys.KeyFinder;
import com.tommasov.mg4assistant.probe.ChatApi;
import com.tommasov.mg4assistant.voice.CarVoice;
import com.tommasov.mg4assistant.voice.RemoteVoice;

import java.util.List;

/**
 * Where the driver says which service answers, with which model and in which voice.
 *
 * <p>The one screen in this app meant to be touched. Everything else is built so that a whole
 * exchange happens without a hand leaving the wheel; this is done once, parked, and then not
 * again — so it can afford text fields and lists.
 *
 * <p>Keys typed here replace the one compiled into a private build. That matters beyond
 * convenience: a key in {@code BuildConfig} comes out of the APK with grep, which is what has
 * kept this app out of the download catalogue.
 */
public class SettingsActivity extends AppCompatActivity {

    private Settings settings;
    private Usage usage;
    private final RemoteVoice sampleVoice = new RemoteVoice();
    private final Handler main = new Handler(Looper.getMainLooper());

    private KeyEntry keyEntry;
    private TextView openAiState;
    private TextView xaiState;
    private Button providerButton;
    private Button modelButton;
    private Button voiceButton;
    private Button wheelButton;
    private Button listenOpenButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settings = new Settings(this);
        usage = new Usage(this);

        keyEntry = new KeyEntry(this, settings, this::show);
        openAiState = findViewById(R.id.key_openai_state);
        xaiState = findViewById(R.id.key_xai_state);
        providerButton = findViewById(R.id.button_provider);
        modelButton = findViewById(R.id.button_model);
        voiceButton = findViewById(R.id.button_voice);
        wheelButton = findViewById(R.id.button_wheel);
        listenOpenButton = findViewById(R.id.button_listen_open);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_usage_reset).setOnClickListener(v -> Dialogs.builder(this)
                .setTitle(R.string.settings_usage_reset)
                .setMessage(R.string.settings_usage_reset_explain)
                .setPositiveButton(R.string.settings_usage_reset, (d, w) -> {
                    usage.reset();
                    show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        findViewById(R.id.button_diagnostics).setOnClickListener(
                v -> startActivity(new Intent(this, ProbeActivity.class)));

        findViewById(R.id.key_from_file).setOnClickListener(v -> keyEntry.fromFile());
        findViewById(R.id.key_paste).setOnClickListener(v -> keyEntry.paste());
        findViewById(R.id.key_type).setOnClickListener(v -> keyEntry.type());
        // The key itself is the control for removing it. Nothing else on this screen is worth
        // a delete button, and a fourth button in that row would be the one pressed by mistake.
        findViewById(R.id.key_openai_row).setOnClickListener(v -> keyEntry.openKey(true));
        findViewById(R.id.key_xai_row).setOnClickListener(v -> keyEntry.openKey(false));

        providerButton.setOnClickListener(v -> toggleProvider());
        modelButton.setOnClickListener(v -> chooseModel());
        voiceButton.setOnClickListener(v -> chooseVoice());
        wheelButton.setOnClickListener(v -> {
            settings.setWheelStartsApp(!settings.wheelStartsApp());
            show();
        });
        listenOpenButton.setOnClickListener(v -> {
            settings.setListenOnOpen(!settings.listenOnOpen());
            show();
        });

        show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        keyEntry.onPermissionResult(requestCode, grantResults);
    }

    /**
     * The keys as they should be seen: masked, and labelled with what each one is for.
     *
     * <p>Which service needs which is not obvious and getting it wrong is expensive in
     * confusion: xAI answers questions and has neither transcription nor speech, so an OpenAI
     * key is required for the app to hear or speak at all, whatever answers.
     */
    private void showKeys() {
        openAiState.setText(getString(R.string.key_state_openai, describe(settings.enteredOpenAiKey())));
        xaiState.setText(getString(R.string.key_state_xai, describe(settings.enteredXaiKey())));
        paintBadge(R.id.key_openai_badge, settings.openAiKey(),
                settings.keyVerified(Settings.PROVIDER_OPENAI));
        paintBadge(R.id.key_xai_badge, settings.xaiKey(),
                settings.keyVerified(Settings.PROVIDER_XAI));
        TextView notice = findViewById(R.id.keys_notice);
        notice.setText(settings.usingBuiltInKey()
                ? getString(R.string.settings_keys_builtin)
                : getString(R.string.settings_keys_notice));
    }

    @NonNull
    private String describe(@NonNull String key) {
        return key.isEmpty() ? getString(R.string.key_state_none) : KeyFinder.mask(key);
    }

    /**
     * Green, amber or grey, on the effective key rather than the entered one.
     *
     * <p>The distinction matters in a debug build: a key compiled in is a service that is
     * genuinely hooked up, and a grey dot beside a service answering questions would be the
     * screen contradicting the app. It shows amber, which is the truth — it is there and
     * nobody has checked it.
     */
    private void paintBadge(int id, @NonNull String key, boolean verified) {
        int colour = key.isEmpty()
                ? R.color.badge_off
                : (verified ? R.color.badge_ok : R.color.badge_unchecked);
        findViewById(id).setBackgroundTintList(
                ColorStateList.valueOf(ContextCompat.getColor(this, colour)));
    }

    private void show() {
        String provider = settings.chatProvider();
        providerButton.setText(getString(R.string.settings_provider,
                Settings.PROVIDER_XAI.equals(provider) ? "xAI" : "OpenAI"));

        String model = settings.chatModel();
        modelButton.setText(getString(R.string.settings_model,
                model.isEmpty() ? getString(R.string.settings_model_auto) : model));

        voiceButton.setText(settings.useCarVoice()
                ? getString(R.string.voice_car)
                : getString(R.string.voice_remote_named, settings.voice()));

        wheelButton.setText(getString(R.string.settings_wheel,
                getString(settings.wheelStartsApp() ? R.string.on : R.string.off)));

        listenOpenButton.setText(getString(R.string.settings_listen_open,
                getString(settings.listenOnOpen() ? R.string.on : R.string.off)));

        showUsage();
        showKeys();

        TextView notice = findViewById(R.id.speech_notice);
        notice.setText(settings.canHear()
                ? getString(R.string.settings_speech_notice)
                : getString(R.string.settings_speech_missing));
    }

    /**
     * What has been spent since the counter was last started.
     *
     * <p>Quantities, not money. Every figure here is exact — the provider states its own token
     * counts, the recorder knows how long it listened, the synthesiser counts what it was
     * given — whereas a price is a number that goes stale without telling anyone.
     *
     * <p>Megabytes are not among them. They were, measured against a monthly allowance, until
     * it was pointed out that plenty of these cars have no SIM at all: a meter for a limit
     * that may not exist is worse than none.
     */
    private void showUsage() {
        TextView summary = findViewById(R.id.usage_summary);
        summary.setText(getString(R.string.settings_usage_summary,
                usage.sinceLabel(),
                usage.exchanges(),
                usage.listenMillis() / 1000f / 60f,
                (usage.tokensIn() + usage.tokensOut()) / 1000f,
                usage.spokenChars() / 1000f));

    }

    /** Switches which service answers. Only offers one it has a key for. */
    private void toggleProvider() {
        boolean toXai = !Settings.PROVIDER_XAI.equals(settings.chatProvider());
        if (toXai && settings.xaiKey().isEmpty()) {
            Dialogs.toast(this, R.string.settings_no_xai_key);
            return;
        }
        if (!toXai && settings.openAiKey().isEmpty()) {
            Dialogs.toast(this, R.string.settings_no_openai_key);
            return;
        }
        settings.setChatProvider(toXai ? Settings.PROVIDER_XAI : Settings.PROVIDER_OPENAI);
        show();
    }

    /**
     * Asks the account which models it has and lets one be picked.
     *
     * <p>Not a hard-coded list. Which models an account can reach changes without warning and
     * differs between the two services; a name typed from memory produces a 404 that reads,
     * from the driver's seat, exactly like a broken connection.
     */
    private void chooseModel() {
        String key = settings.chatKey();
        if (TextUtils.isEmpty(key)) {
            Dialogs.toast(this, R.string.settings_no_key_at_all);
            return;
        }
        modelButton.setEnabled(false);
        modelButton.setText(R.string.working);
        new Thread(() -> {
            ChatApi.Result result = ChatApi.listModels(key);
            List<String> all = ChatApi.modelsFrom(result);
            main.post(() -> {
                modelButton.setEnabled(true);
                if (!result.ok || all.isEmpty()) {
                    show();
                    Dialogs.builder(this)
                            .setTitle(R.string.settings_model)
                            .setMessage(result.summary + "\n\n"
                                    + ChatApi.forDisplay(result.detail))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                // Only the ones that take messages: an account's catalogue is mostly
                // embeddings, speech and images, and those answer a chat request with a 404.
                List<String> chat = ChatApi.chatModelsFrom(all);
                final String[] names = chat.toArray(new String[0]);
                Dialogs.builder(this)
                        .setTitle(R.string.settings_model_pick)
                        .setItems(names, (d, which) -> {
                            settings.setChatModel(names[which]);
                            show();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            });
        }).start();
    }

    /**
     * Picks the voice, and says something in it straight away.
     *
     * <p>Chosen by ear because it cannot be chosen any other way: OpenAI's voices are all
     * English-trained and all carry some of it into Italian, differently from one another.
     */
    private void chooseVoice() {
        final List<String> options = new java.util.ArrayList<>();
        final List<String> labels = new java.util.ArrayList<>();
        if (CarVoice.isAvailable(this)) {
            options.add("");
            labels.add(getString(R.string.voice_car));
        }
        for (String voice : RemoteVoice.VOICES) {
            options.add(voice);
            labels.add(voice);
        }
        Dialogs.builder(this)
                .setTitle(R.string.settings_voice_pick)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String chosen = options.get(which);
                    settings.setUseCarVoice(chosen.isEmpty());
                    if (!chosen.isEmpty()) {
                        settings.setVoice(chosen);
                        sampleVoice.setVoice(chosen);
                        sampleVoice.speak(this, settings.speechKey(),
                                getString(R.string.voice_sample), new RemoteVoice.Callback() {
                                    @Override
                                    public void onSpeaking(long bytes, long elapsed,
                                                           int characters) {
                                    }

                                    @Override
                                    public void onFinished() {
                                    }

                                    @Override
                                    public void onFailed(@NonNull String reason) {
                                        Dialogs.toast(SettingsActivity.this, reason);
                                    }
                                });
                    }
                    show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onStop() {
        sampleVoice.stop();
        super.onStop();
    }
}
