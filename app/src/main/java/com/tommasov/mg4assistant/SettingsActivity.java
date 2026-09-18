package com.tommasov.mg4assistant;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

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
    private final RemoteVoice sampleVoice = new RemoteVoice();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText openAiField;
    private EditText xaiField;
    private Button providerButton;
    private Button modelButton;
    private Button voiceButton;
    private Button wheelButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        settings = new Settings(this);

        openAiField = findViewById(R.id.key_openai);
        xaiField = findViewById(R.id.key_xai);
        providerButton = findViewById(R.id.button_provider);
        modelButton = findViewById(R.id.button_model);
        voiceButton = findViewById(R.id.button_voice);
        wheelButton = findViewById(R.id.button_wheel);

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_diagnostics).setOnClickListener(
                v -> startActivity(new Intent(this, ProbeActivity.class)));

        openAiField.setText(settings.openAiKey());
        xaiField.setText(settings.xaiKey());
        // Saved as they are typed rather than behind a Save button: there is no draft state
        // worth protecting here, and a key entered and then lost to a back press would be a
        // small disaster on a screen with no keyboard worth the name.
        openAiField.addTextChangedListener(saveTo(true));
        xaiField.addTextChangedListener(saveTo(false));

        providerButton.setOnClickListener(v -> toggleProvider());
        modelButton.setOnClickListener(v -> chooseModel());
        voiceButton.setOnClickListener(v -> chooseVoice());
        wheelButton.setOnClickListener(v -> {
            settings.setWheelStartsApp(!settings.wheelStartsApp());
            show();
        });

        show();
    }

    @NonNull
    private TextWatcher saveTo(boolean openAi) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (openAi) {
                    settings.setOpenAiKey(e.toString());
                } else {
                    settings.setXaiKey(e.toString());
                }
                show();
            }
        };
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

        TextView notice = findViewById(R.id.speech_notice);
        notice.setText(settings.canHear()
                ? getString(R.string.settings_speech_notice)
                : getString(R.string.settings_speech_missing));
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
                                    public void onSpeaking(long bytes, long elapsed) {
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
