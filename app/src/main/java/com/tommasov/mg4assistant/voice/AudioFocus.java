package com.tommasov.mg4assistant.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Holds the car's audio for the length of one exchange.
 *
 * <p>Taken when the button is pressed, not when the answer is ready. Two reasons, and the
 * first is the one that matters: the microphone is in the same cabin as the speakers, so
 * music playing while somebody asks a question ends up in the recording and then in the
 * transcription. Quietening the radio is part of hearing the question, not a courtesy.
 *
 * <p>The second is that it stays held through thinking and answering. Releasing it between
 * the question and the reply would let the radio swell back for the two seconds in between
 * and then be cut off again, which is worse than either.
 *
 * <p>{@code GAIN_TRANSIENT} rather than {@code MAY_DUCK}: ducked music is still music under a
 * spoken answer in a moving car. The radio pauses and resumes on its own afterwards.
 */
public final class AudioFocus {

    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    @Nullable private AudioFocusRequest request;
    @Nullable private AudioManager manager;

    @NonNull
    public AudioAttributes attributes() {
        return attributes;
    }

    /** Takes the audio, if it is not held already. */
    public void acquire(@NonNull Context context) {
        if (request != null) {
            return;
        }
        AudioManager audio =
                (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        manager = audio;
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .build();
        audio.requestAudioFocus(request);
    }

    /**
     * Gives it back. Safe to call when nothing is held, which matters: every path out of an
     * exchange calls this, including the ones that failed, and a focus never released is a
     * radio that never comes back.
     */
    public void release() {
        AudioFocusRequest held = request;
        AudioManager audio = manager;
        request = null;
        manager = null;
        if (held != null && audio != null) {
            audio.abandonAudioFocusRequest(held);
        }
    }
}
