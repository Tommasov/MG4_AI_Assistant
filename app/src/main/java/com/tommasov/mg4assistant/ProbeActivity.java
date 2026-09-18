package com.tommasov.mg4assistant;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tommasov.mg4assistant.probe.AudioProbe;
import com.tommasov.mg4assistant.probe.ChatApi;
import com.tommasov.mg4assistant.probe.HardKeyWatch;
import com.tommasov.mg4assistant.probe.ProbeReport;
import com.tommasov.mg4assistant.probe.SpeechProbe;
import com.tommasov.mg4assistant.probe.TtsProbe;
import com.tommasov.mg4assistant.probe.VehicleTts;
import com.tommasov.mg4assistant.probe.VehicleProbe;

import java.util.List;
import java.util.Locale;

/**
 * Establishes what this head unit can actually do, before anything is built on top of it.
 *
 * <p>An assistant needs three things from the car — a microphone it may read, a way from
 * speech to text, and a way back from text to speech — and not one of them can be taken for
 * granted here. There are no Play Services, which is where a phone gets the last two; the
 * factory assistant may be holding the first; and the emulator carries nothing of SAIC, so it
 * cannot answer any of it. The only way to know is to run this in the car.
 *
 * <p>Which is also why the report is written to be read off the screen. The vehicle has no
 * adb: whatever this finds has to survive being photographed at a standstill, so the verdicts
 * come first and the detail after.
 */
public class ProbeActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 1;
    /** Long enough to catch a word, short enough that nobody minds waiting for it. */
    private static final int MIC_TEST_MS = 2000;

    private static final String PREFS = "mg4assistant";
    private static final String KEY_API = "api_key";

    private TextView report;
    private EditText apiKeyField;
    private Button micButton;
    private Button apiButton;
    private Button carVoiceButton;
    private Button sendButton;

    private final HardKeyWatch hardKeys = new HardKeyWatch();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // One string per section, so re-running one test leaves the others' findings alone.
    private String deviceSection = "";
    private String vehicleSection = "";
    private String sttSection = "";
    private String ttsSection = "";
    private String micSection = "";
    private String carTtsSection = "";
    private String apiSection = "";
    private String hardKeySection = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_probe);

        report = findViewById(R.id.report);
        apiKeyField = findViewById(R.id.api_key);
        micButton = findViewById(R.id.button_mic);
        apiButton = findViewById(R.id.button_api);
        apiKeyField.setText(rememberedKey());

        findViewById(R.id.button_back).setOnClickListener(v -> finish());
        findViewById(R.id.button_rerun).setOnClickListener(v -> runLocalProbes());
        micButton.setOnClickListener(v -> startMicTest());
        apiButton.setOnClickListener(v -> startApiTest());
        // Only the vehicle has the SAIC speech package; anywhere else the button would
        // have nothing to talk to.
        carVoiceButton = findViewById(R.id.button_car_voice);
        carVoiceButton.setVisibility(VehicleTts.isPresent(this) ? View.VISIBLE : View.GONE);
        carVoiceButton.setOnClickListener(v -> startCarVoiceTest());

        // No write key in this build means nowhere to send: offer nothing rather than
        // something that fails.
        sendButton = findViewById(R.id.button_send);
        sendButton.setVisibility(ProbeReport.isConfigured() ? View.VISIBLE : View.GONE);
        sendButton.setOnClickListener(v -> askThenSend());

        micSection = note(getString(R.string.not_run_yet));
        carTtsSection = note(getString(R.string.not_run_yet));
        apiSection = note(getString(R.string.not_run_yet));
        runLocalProbes();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Only while the screen is up: a receiver left registered would have this
        // app woken by every button on the wheel, for nothing.
        hardKeys.start(this, () -> {
            hardKeySection = hardKeys.describe();
            render();
        });
        hardKeySection = hardKeys.describe();
        render();
    }

    @Override
    protected void onStop() {
        hardKeys.stop();
        super.onStop();
    }

    // ---------------------------------------------------------------- local probes

    /**
     * Everything that needs neither a permission nor the network — off the main thread.
     *
     * <p>It used to run inline and it cost nine seconds on a loaded emulator, long enough that
     * Android put up an "app isn't responding" dialog over the top of it. The expensive part
     * is the vehicle sweep: listing every installed package and then asking each promising one
     * for its services. That is not work to do on the thread that answers taps, and on the car
     * it would be slower still.
     */
    private void runLocalProbes() {
        deviceSection = note(getString(R.string.working));
        vehicleSection = note(getString(R.string.working));
        sttSection = note(getString(R.string.working));
        ttsSection = note(getString(R.string.working));
        render();

        new Thread(() -> {
            final String device = describeDevice();
            final String vehicle = describeVehicle();
            final String stt = describeSpeechRecognition();
            mainHandler.post(() -> {
                deviceSection = device;
                vehicleSection = vehicle;
                sttSection = stt;
                render();
            });
        }).start();

        TtsProbe.probe(this, result -> {
            ttsSection = describeTts(result);
            render();
        });
    }

    @NonNull
    private String describeDevice() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        PackageManager pm = getPackageManager();
        StringBuilder sb = new StringBuilder();
        line(sb, "model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")");
        line(sb, "android", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        line(sb, "screen", metrics.widthPixels + "×" + metrics.heightPixels
                + " at " + metrics.densityDpi + " dpi");
        line(sb, "microphone hardware",
                yesNo(pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)));
        line(sb, "audio output",
                yesNo(pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)));
        // Stated plainly because it explains most of what follows: without Play Services the
        // usual speech and synthesis engines are simply not on the device.
        line(sb, "play services", yesNo(isInstalled("com.google.android.gms")));
        return sb.toString();
    }

    @NonNull
    private String describeVehicle() {
        VehicleProbe.Result result = VehicleProbe.probe(this);
        StringBuilder sb = new StringBuilder();
        line(sb, "automotive build", yesNo(result.automotive));
        line(sb, "SAIC adapter", result.adapterInstalled
                ? "installed" + (result.adapterVersion == null
                        ? "" : " (" + result.adapterVersion + ")")
                : "not installed — expected on the emulator");
        for (String service : result.adapterServices) {
            line(sb, "  " + service, "");
        }
        if (result.voicePackages.isEmpty()) {
            line(sb, "voice-related packages", "none found");
        } else {
            line(sb, "voice-related packages", String.valueOf(result.voicePackages.size()));
            for (String entry : result.voicePackages) {
                line(sb, "  " + entry, "");
            }
        }
        return sb.toString();
    }

    @NonNull
    private String describeSpeechRecognition() {
        SpeechProbe.Result result = SpeechProbe.probe(this);
        StringBuilder sb = new StringBuilder();
        line(sb, "framework reports available", yesNo(result.frameworkSaysAvailable));
        line(sb, "recognition services", list(result.recognitionServices));
        line(sb, "handlers for \"speak now\"", list(result.recognizeIntentHandlers));
        if (!result.anythingFound()) {
            sb.append("\n  → nothing on board can turn speech into text. Voice input would "
                    + "mean recording audio here and sending it to a transcription service: "
                    + "a second provider, a second key, a per-minute cost.\n");
        }
        return sb.toString();
    }

    @NonNull
    private String describeTts(@NonNull TtsProbe.Result result) {
        StringBuilder sb = new StringBuilder();
        if (result.timedOut) {
            line(sb, "engine", "never answered within the timeout — treat as absent");
            return sb.toString();
        }
        line(sb, "initialised", yesNo(result.initialised));
        line(sb, "default engine",
                result.defaultEngine == null ? "none" : result.defaultEngine);
        line(sb, "engines installed", list(result.engines));
        line(sb, "languages", list(result.languages));
        if (!result.initialised) {
            sb.append("\n  → the car cannot speak. A reply would have to be shown as text, "
                    + "or synthesised remotely and played back.\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- microphone

    private void startMicTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Dialogs.toast(this, R.string.mic_permission_rationale);
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        micButton.setEnabled(false);
        micSection = note(getString(R.string.working));
        render();

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            // Every source in turn: a unit that refuses MIC may still hand over
            // VOICE_RECOGNITION, and knowing which one works is the whole answer.
            for (int source : AudioProbe.SOURCES) {
                AudioProbe.Result result = AudioProbe.record(source, MIC_TEST_MS);
                line(sb, result.sourceName, result.verdict());
            }
            final String finished = sb.toString();
            mainHandler.post(() -> {
                micSection = finished;
                micButton.setEnabled(true);
                render();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicTest();
        } else {
            micSection = note(getString(R.string.mic_permission_denied));
            render();
        }
    }

    // ---------------------------------------------------------------- car speech

    /**
     * Asks the vehicle's own synthesiser to speak, in English and then in Italian.
     *
     * <p>Worth knowing because a local voice costs nothing, arrives instantly and works with
     * no connection. Worth doubting because the firmware ships ten languages and Italian is
     * not among them: the number for it exists in the engine's own catalogue, the voice data
     * may well not be on this car. The attempt settles in a minute what the dump cannot.
     */
    private void startCarVoiceTest() {
        carVoiceButton.setEnabled(false);
        carTtsSection = note(getString(R.string.working));
        render();
        VehicleTts.probe(this, result -> {
            carTtsSection = result;
            carVoiceButton.setEnabled(true);
            render();
        });
    }

    // ---------------------------------------------------------------- chat API

    private void startApiTest() {
        // Taken from the field rather than compiled in, so one build serves every key and no
        // secret rides inside an APK that ends up in a catalogue. Typing a key on the car's
        // keyboard would be miserable; pasting one is not, which is what the browser and the
        // paste box on the server are for.
        final String key = apiKeyField.getText().toString().trim();
        if (key.isEmpty()) {
            apiSection = note(getString(R.string.api_no_key));
            render();
            return;
        }
        rememberKey(key);
        apiButton.setEnabled(false);
        apiSection = note(getString(R.string.working));
        render();

        new Thread(() -> {
            StringBuilder sb = new StringBuilder();

            // Which service this is comes from the key's own prefix, so there is nothing to
            // choose and nothing to get wrong.
            line(sb, "provider", ChatApi.providerFor(key).label);

            ChatApi.Result models = ChatApi.listModels(key);
            line(sb, "models request", models.summary + " in " + models.elapsedMs + " ms");
            List<String> ids = ChatApi.modelsFrom(models);
            if (!models.ok) {
                line(sb, "response", ChatApi.forDisplay(models.detail));
            } else {
                line(sb, "models available", ChatApi.summarise(ids));
            }

            // Only worth a round trip once the listing has proved the connection, and only
            // against a model the account actually has that takes messages — OpenAI's
            // catalogue is mostly embeddings, speech and images, and one of those would
            // answer a chat request with a 404 that reads like a wrong endpoint.
            String model = models.ok ? ChatApi.chatModelFrom(ids) : null;
            if (models.ok && model == null) {
                line(sb, "chat", "no chat-capable model in this account's list");
            } else if (model != null) {
                ChatApi.Result chat = ChatApi.chat(key, model, "Reply with exactly: ok");
                line(sb, "chat with " + model,
                        chat.summary + " in " + chat.elapsedMs + " ms");
                String reply = ChatApi.replyFrom(chat);
                line(sb, "reply", reply == null ? ChatApi.forDisplay(chat.detail) : reply.trim());
            }

            final String finished = sb.toString();
            mainHandler.post(() -> {
                apiSection = finished;
                apiButton.setEnabled(true);
                render();
            });
        }).start();
    }

    // ---------------------------------------------------------------- report

    private void render() {
        StringBuilder sb = new StringBuilder();
        section(sb, getString(R.string.section_device), deviceSection);
        section(sb, "Vehicle", vehicleSection);
        section(sb, getString(R.string.section_stt), sttSection);
        section(sb, getString(R.string.section_tts), ttsSection);
        section(sb, getString(R.string.section_mic), micSection);
        section(sb, getString(R.string.section_car_tts), carTtsSection);
        section(sb, getString(R.string.section_hardkey), hardKeySection);
        section(sb, getString(R.string.section_api), apiSection);
        report.setText(sb.toString());
    }

    private static void section(@NonNull StringBuilder sb, @NonNull String heading,
                                @NonNull String body) {
        sb.append(heading.toUpperCase(Locale.getDefault())).append('\n');
        String text = body.isEmpty() ? "  —" : body;
        sb.append(text);
        // A section whose body is a plain sentence rather than a list of findings arrives
        // without its closing newline, which would run the next heading onto it.
        if (!text.endsWith("\n")) {
            sb.append('\n');
        }
        sb.append('\n');
    }

    /** A one-line section body, indented to sit under its heading like the findings do. */
    @NonNull
    private static String note(@NonNull String text) {
        return "  " + text + "\n";
    }

    private static void line(@NonNull StringBuilder sb, @NonNull String label,
                             @NonNull String value) {
        sb.append("  ").append(label);
        if (!value.isEmpty()) {
            sb.append(": ").append(value);
        }
        sb.append('\n');
    }

    @NonNull
    private String yesNo(boolean value) {
        return getString(value ? R.string.yes : R.string.no);
    }

    @NonNull
    private static String list(@NonNull List<String> values) {
        if (values.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    private boolean isInstalled(@NonNull String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * The key last used, or the one from apikeys.properties if a developer build carried one.
     *
     * <p>Kept in ordinary preferences, which is app-private storage and no worse than the
     * compiled-in alternative it replaces — but this is a key, so it should be one made for
     * this purpose and revoked when the probing is over, not a production key.
     */
    @NonNull
    private String rememberedKey() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, ChatApi.builtInKey());
    }

    private void rememberKey(@NonNull String key) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, key).apply();
    }

    /**
     * Asks before sending, and asks for one sentence while it is at it.
     *
     * <p>Two things happen in this dialogue and both are needed. The report leaves the car for
     * somebody else's server and it names every voice-related package on the unit, so it is
     * not something to send on a stray tap without saying so. And findings arriving on their
     * own are half a report: whether the engine was running, whether anyone spoke into the
     * microphone, is the half that makes the numbers mean anything — and nobody writes that
     * down afterwards.
     */
    private void askThenSend() {
        Context scaled = Dialogs.scaled(this);

        TextView explanation = new TextView(scaled);
        explanation.setText(R.string.send_explain);
        explanation.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        explanation.setTextSize(getResources().getDimension(R.dimen.report_text_size)
                / getResources().getDisplayMetrics().scaledDensity);

        EditText note = new EditText(scaled);
        note.setHint(R.string.send_note_hint);
        note.setSingleLine(true);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        int pad = getResources().getDimensionPixelSize(R.dimen.report_gap);
        LinearLayout body = new LinearLayout(scaled);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, 0);
        body.addView(explanation);
        body.addView(note);

        Dialogs.builder(this)
                .setTitle(R.string.send_title)
                .setView(body)
                .setPositiveButton(R.string.action_send,
                        (d, which) -> send(note.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void send(@NonNull String note) {
        String text = report.getText().toString();
        if (text.trim().isEmpty()) {
            Dialogs.toast(this, R.string.nothing_to_send);
            return;
        }
        sendButton.setEnabled(false);
        sendButton.setText(R.string.sending);
        ProbeReport.send(text, note, new ProbeReport.Callback() {
            @Override
            public void onSent(@NonNull String reportName) {
                restoreSendButton();
                Dialogs.toast(ProbeActivity.this, getString(R.string.sent, reportName));
            }

            @Override
            public void onFailed(@NonNull String reason) {
                restoreSendButton();
                // A dialog rather than a toast: the reason is the only thing that says
                // whether to try again or to fix something.
                Dialogs.builder(ProbeActivity.this)
                        .setTitle(R.string.send_failed)
                        .setMessage(reason)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        });
    }

    private void restoreSendButton() {
        sendButton.setEnabled(true);
        sendButton.setText(R.string.action_send);
    }
}
