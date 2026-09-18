package com.tommasov.mg4assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tommasov.mg4assistant.probe.ChatApi;
import com.tommasov.mg4assistant.probe.HardKeyWatch;
import com.tommasov.mg4assistant.ui.AmbientGlowView;
import com.tommasov.mg4assistant.voice.AudioFocus;
import com.tommasov.mg4assistant.voice.CarVoice;
import com.tommasov.mg4assistant.voice.Conversation;
import com.tommasov.mg4assistant.voice.RemoteVoice;
import com.tommasov.mg4assistant.voice.Transcriber;
import com.tommasov.mg4assistant.voice.VoiceRecorder;

import java.io.File;
import java.util.List;

/**
 * The assistant: press, speak, get an answer.
 *
 * <p>The shape of this screen is dictated by what the car turned out to be able to do, which
 * was established by the probe rather than assumed. The microphone is readable, so the audio
 * is captured here. Nothing on board can turn speech into text — the factory recogniser only
 * hands out actions to perform — so the audio goes to a transcription service. The answer is
 * spoken as well as shown, by one of two voices: the car's own, which is free and instant but
 * has no Italian and reads it with an English accent, or a remote one that costs bytes and a
 * second of waiting and pronounces it properly. Which is better is not a thing to settle on
 * paper, so it is a switch.
 *
 * <p>Push to talk, never an open microphone. That is a data decision as much as a privacy
 * one: continuous listening would spend the car's monthly gigabyte in four days.
 */
public class AssistantActivity extends AppCompatActivity {

    private static final int REQUEST_RECORD_AUDIO = 1;
    private static final String PREFS = "mg4assistant";
    private static final String KEY_MODEL = "chat_model";
    private static final String KEY_CAR_VOICE = "use_car_voice";
    private static final String KEY_VOICE_NAME = "remote_voice";
    /** Transcription is told the language rather than left to guess it. */
    private static final String LANGUAGE = "it";
    /** Long enough for the speakers to fall quiet before the microphone opens again. */
    private static final long FOLLOW_UP_DELAY_MS = 600;

    private Button talkButton;
    private Button voiceButton;
    private ProgressBar level;
    private TextView status;
    private TextView heard;
    private TextView reply;
    private AmbientGlowView glow;

    private final VoiceRecorder recorder = new VoiceRecorder();
    private final AudioFocus audioFocus = new AudioFocus();
    private Settings settings;
    /**
     * The steering wheel, if this car lets an ordinary app hear it. Whether the broadcast
     * arrives at all is the open question; until it is answered this listens for nothing and
     * costs nothing.
     */
    private final HardKeyWatch wheel = new HardKeyWatch();
    private final RemoteVoice remoteVoice = new RemoteVoice();
    private final CarVoice carVoice = new CarVoice();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean busy;
    private boolean useCarVoice;
    /**
     * How the last take ended. A take the speaker finished by pausing is a conversation; one
     * stopped by a finger or by the ceiling is not, and should not reopen the microphone by
     * itself.
     */
    @NonNull private VoiceRecorder.Stop lastStop = VoiceRecorder.Stop.BY_HAND;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_assistant);
        settings = new Settings(this);

        talkButton = findViewById(R.id.button_talk);
        level = findViewById(R.id.level);
        status = findViewById(R.id.status);
        heard = findViewById(R.id.heard);
        reply = findViewById(R.id.reply);
        glow = findViewById(R.id.glow);

        talkButton.setOnClickListener(v -> onTalkPressed());

        // The car's voice only exists on the car; anywhere else the switch would offer a
        // choice of one.
        voiceButton = findViewById(R.id.button_voice);
        if (CarVoice.isAvailable(this)) {
            useCarVoice = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_CAR_VOICE, false);
            voiceButton.setOnClickListener(v -> toggleVoice());
            voiceButton.setOnLongClickListener(v -> cycleRemoteVoice());
            showVoice();
        } else {
            useCarVoice = false;
            // Even without the car's voice there is a choice worth having: which of the
            // remote voices speaks. Long press cycles them.
            voiceButton.setOnLongClickListener(v -> cycleRemoteVoice());
            showVoice();
        }
        remoteVoice.setVoice(getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_VOICE_NAME, RemoteVoice.VOICES[0]));
        findViewById(R.id.button_settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        if (apiKey().isEmpty()) {
            talkButton.setEnabled(false);
            status.setText(R.string.error_no_key);
        } else {
            status.setText(R.string.status_ready);
        }
    }

    // ---------------------------------------------------------------- recording

    private void onTalkPressed() {
        if (busy) {
            return;
        }
        if (recorder.isRecording()) {
            recorder.stop();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        // Taken now, not when the answer is ready: music in the cabin ends up in the
        // recording and from there in the transcription.
        audioFocus.acquire(this);
        heard.setText("");
        reply.setText("");
        recorder.start(this, new VoiceRecorder.Callback() {
            @Override
            public void onStarted() {
                // Listening: present, but quieter than the answer. The glow is a state
                // indicator before it is decoration, and the loudest state should be the
                // one where the car is talking back.
                glow.setIntensity(0.4f);
                talkButton.setText(R.string.talk_recording);
                level.setProgress(0);
                level.setVisibility(View.VISIBLE);
                status.setText("");
            }

            @Override
            public void onLevel(int percent) {
                level.setProgress(percent);
            }

            @Override
            public void onFloor(int noiseFloor, int speechLevel) {
                // Shown while listening because it is the number that explains a recording
                // that will not stop, or one that never hears a word: the cabin was louder
                // or quieter than the thresholds assumed.
                status.setText(getString(R.string.status_floor, noiseFloor, speechLevel));
            }

            @Override
            public void onStopped(@NonNull File audio, int millis,
                                  @NonNull VoiceRecorder.Stop reason, int peak) {
                level.setVisibility(View.INVISIBLE);
                lastStop = reason;
                if (reason == VoiceRecorder.Stop.NOTHING_SAID) {
                    // Nothing was ever said into it, so there is nothing worth a round trip.
                    // Common when the follow-up microphone opens and the conversation is over.
                    recorder.deleteRecording();
                    glow.setIntensity(0f);
                    audioFocus.release();
                    setBusy(false);
                    status.setText(getString(R.string.status_nothing_heard_peak, peak));
                    return;
                }
                if (reason == VoiceRecorder.Stop.LIMIT) {
                    status.setText(R.string.status_limit);
                }
                transcribe(audio, millis);
            }

            @Override
            public void onFailed(@NonNull String reason) {
                level.setVisibility(View.INVISIBLE);
                glow.setIntensity(0f);
                audioFocus.release();
                setBusy(false);
                status.setText(reason);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            status.setText(R.string.error_mic_denied);
        }
    }

    // ---------------------------------------------------------------- the chain

    private void transcribe(@NonNull File audio, int millis) {
        glow.setIntensity(0.6f);
        setBusy(true);
        status.setText(R.string.status_transcribing);
        Transcriber.transcribe(apiKey(), audio, LANGUAGE, new Transcriber.Callback() {
            @Override
            public void onText(@NonNull String text, long elapsedMs, long bytesSent) {
                // The recording has served its purpose; nothing spoken in this car is kept.
                recorder.deleteRecording();
                status.setText(getString(R.string.status_cost,
                        Math.round(millis / 1000f), Math.round(bytesSent / 1024f)));
                if (text.isEmpty()) {
                    glow.setIntensity(0f);
                    audioFocus.release();
                    setBusy(false);
                    status.setText(R.string.status_nothing_heard);
                    return;
                }
                heard.setText(getString(R.string.status_heard, text));
                answer(text);
            }

            @Override
            public void onFailed(@NonNull String reason) {
                recorder.deleteRecording();
                glow.setIntensity(0f);
                audioFocus.release();
                setBusy(false);
                status.setText(reason);
            }
        });
    }

    private void answer(@NonNull String question) {
        status.setText(R.string.status_thinking);
        Conversation.shared().addUser(question);
        // The conversation may run on a different service from the voice: xAI can answer
        // while OpenAI listens and speaks.
        final String key = settings.chatKey();
        new Thread(() -> {
            String model = chatModel(key);
            if (model == null) {
                main.post(() -> {
                    glow.setIntensity(0f);
                    audioFocus.release();
                    setBusy(false);
                    status.setText(getString(R.string.error_no_model, "models request failed"));
                });
                return;
            }
            ChatApi.Result result = ChatApi.chat(key, model, Conversation.shared().toMessages());
            String text = ChatApi.replyFrom(result);
            main.post(() -> {
                setBusy(false);
                if (!result.ok || text == null) {
                    glow.setIntensity(0f);
                    audioFocus.release();
                    status.setText(result.summary);
                    reply.setText(ChatApi.forDisplay(result.detail));
                    return;
                }
                String trimmed = text.trim();
                Conversation.shared().addAssistant(trimmed);
                reply.setText(trimmed);
                speak(trimmed);
            });
        }).start();
    }

    /**
     * Which model to talk to, decided once and remembered.
     *
     * <p>Asked of the account rather than hard-coded: a guessed model id fails with a 404 that
     * reads exactly like a broken connection, and the catalogue is mostly not chat — sending
     * messages to an embedding model would do the same. Looked up on first use only, because
     * the answer does not change between one question and the next.
     */
    @Nullable
    private String chatModel(@NonNull String key) {
        String chosen = settings.chatModel();
        if (!chosen.isEmpty()) {
            return chosen;
        }
        ChatApi.Result models = ChatApi.listModels(key);
        if (!models.ok) {
            return null;
        }
        List<String> ids = ChatApi.modelsFrom(models);
        String picked = ChatApi.chatModelFrom(ids);
        if (picked != null) {
            settings.setChatModel(picked);
        }
        return picked;
    }

    // ---------------------------------------------------------------- speaking

    /**
     * Says the answer out loud, with whichever voice is selected.
     *
     * <p>A failure here is reported and then let go: the answer is already on screen, and an
     * assistant that throws the reply away because the speaker would not cooperate is worse
     * than one that simply goes quiet.
     */
    private void speak(@NonNull String text) {
        if (useCarVoice) {
            // Language 1 is English. Asking it for Italian is possible and produces the
            // English voice reading Italian words, which is worse than not speaking.
            carVoice.speak(this, text, CarVoice.LANG_ENG_GBR, new CarVoice.Callback() {
                @Override
                public void onAccepted() {
                    status.setText(R.string.status_spoken_by_car);
                    glow.setIntensity(1f);
                    // The car's interface has no "finished speaking" callback on the call we
                    // make, so the glow is timed from the length of the sentence rather than
                    // from the speaker. promptCommonWordsByLangWithStatus (tx 5) would report
                    // it properly, at the price of implementing IPromptCallBack as a Binder.
                    main.postDelayed(() -> {
                        glow.setIntensity(0f);
                        audioFocus.release();
                        listenAgain();
                    }, estimateSpeechMillis(text) + FOLLOW_UP_DELAY_MS);
                }

                @Override
                public void onFailed(@NonNull String reason) {
                    glow.setIntensity(0f);
                    audioFocus.release();
                    status.setText(reason);
                }
            });
            return;
        }
        remoteVoice.speak(this, apiKey(), text, new RemoteVoice.Callback() {
            @Override
            public void onSpeaking(long bytesReceived, long elapsedMs) {
                glow.setIntensity(1f);
                status.setText(getString(R.string.status_spoken_remote,
                        Math.round(bytesReceived / 1024f), elapsedMs));
            }

            @Override
            public void onFinished() {
                glow.setIntensity(0f);
                status.setText(R.string.status_ready);
                // A breath before listening again, or the tail of the assistant's own voice
                // lands in the next recording.
                main.postDelayed(() -> {
                    audioFocus.release();
                    listenAgain();
                }, FOLLOW_UP_DELAY_MS);
            }

            @Override
            public void onFailed(@NonNull String reason) {
                glow.setIntensity(0f);
                audioFocus.release();
                status.setText(reason);
            }
        });
    }

    /**
     * Reopens the microphone after an answer, so a conversation costs one press and not one
     * per question.
     *
     * <p>Only after a take the speaker ended themselves. If the last one was stopped by hand
     * or ran into the thirty second ceiling, the exchange was not a conversation and listening
     * again would be the app deciding on its own to record.
     *
     * <p>Nothing said within the lead-in closes it quietly, so the loop ends by falling silent
     * rather than by being told to.
     */
    private void listenAgain() {
        if (isFinishing() || busy || recorder.isRecording()) {
            return;
        }
        if (lastStop != VoiceRecorder.Stop.SILENCE) {
            return;
        }
        startRecording();
    }

    /**
     * Roughly how long a sentence takes to say, for the states where nothing tells us.
     * Speech runs around twelve characters a second; the floor keeps very short answers from
     * making the glow blink.
     */
    private static long estimateSpeechMillis(@NonNull String text) {
        return Math.max(1500L, Math.min(20000L, text.length() * 1000L / 12L));
    }

    private void toggleVoice() {
        useCarVoice = !useCarVoice;
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_CAR_VOICE, useCarVoice).apply();
        remoteVoice.stop();
        carVoice.stop();
        showVoice();
        // Said plainly at the moment of choosing, because the drawback is not obvious until
        // you have heard it: this voice has no Italian and will not admit it, it will simply
        // read Italian as though it were English.
        status.setText(useCarVoice ? getString(R.string.voice_car_warning)
                : getString(R.string.status_ready));
    }

    private void showVoice() {
        voiceButton.setText(useCarVoice
                ? getString(R.string.voice_car)
                : getString(R.string.voice_remote_named, remoteVoice.voice()));
    }

    /**
     * Steps to the next remote voice and speaks a sample in it, so the choice is made by ear.
     *
     * <p>All of OpenAI's voices are English-trained and none of them speaks Italian like a
     * native. They are wrong in different ways, though, and which wrongness is bearable is
     * not something anyone can decide from a list of names.
     */
    private boolean cycleRemoteVoice() {
        if (useCarVoice) {
            return false;
        }
        String[] all = RemoteVoice.VOICES;
        int next = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i].equals(remoteVoice.voice())) {
                next = (i + 1) % all.length;
                break;
            }
        }
        remoteVoice.setVoice(all[next]);
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_VOICE_NAME, all[next]).apply();
        showVoice();
        audioFocus.acquire(this);
        speak(getString(R.string.voice_sample));
        return true;
    }

    // ---------------------------------------------------------------- plumbing

    private void setBusy(boolean value) {
        busy = value;
        talkButton.setEnabled(!value);
        talkButton.setText(value ? R.string.talk_busy : R.string.talk_idle);
    }

    /** The key for hearing and speaking. Always OpenAI's; xAI has neither. */
    @NonNull
    private String apiKey() {
        return settings.speechKey();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // A long press on the wheel closes the assistant: the way out that does not need the
        // screen, on a screen the whole app exists to avoid.
        wheel.start(this, () -> {
            HardKeyWatch.Event last = wheel.last();
            if (last != null && last.keycode == HardKeyWatch.KEYCODE_VOICE_WHEEL
                    && last.longPress && last.down) {
                finish();
            }
        });
    }

    @Override
    protected void onStop() {
        wheel.stop();
        // Leaving the screen with the microphone live would be the one way this app listens
        // when nobody asked it to.
        recorder.cancel();
        glow.setIntensity(0f);
        audioFocus.release();
        remoteVoice.stop();
        carVoice.stop();
        carVoice.release();
        super.onStop();
    }
}
