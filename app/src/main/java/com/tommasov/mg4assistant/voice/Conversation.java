package com.tommasov.mg4assistant.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What has been said so far, kept deliberately short.
 *
 * <p>A chat API has no memory: the entire conversation is uploaded again on every turn, and
 * charged for again with it. An hour of back and forth with an unbounded history sends — and
 * pays for — the early exchanges dozens of times over. So the history is capped, and the cap
 * is small.
 *
 * <p>The system prompt shapes the answers for listening rather than reading. It asks for no
 * markdown and no preamble, and for the length the question deserves — an earlier version
 * capped everything at two sentences and turned explanations into telegrams.
 */
public final class Conversation {

    /** Exchanges kept. Six is enough to follow a thread, short enough to stay cheap. */
    private static final int MAX_TURNS = 6;

    /**
     * Brevity was overdone in the first version — "one or two short sentences" turned every
     * answer into a telegram, including the ones that needed explaining. What follows asks
     * for the length the question deserves and lets a real explanation run, while still
     * refusing the things that make an answer unlistenable: preambles, bullet points and
     * markdown nobody can hear.
     */
    private static final String SYSTEM_PROMPT =
            "Sei l'assistente di bordo di una MG4 e ti ascoltano, non ti leggono. Rispondi "
            + "sempre in italiano corrente, senza parole inglesi quando esiste l'equivalente "
            + "italiano. Dai alla risposta la lunghezza che merita: una frase se la domanda "
            + "è semplice, anche cinque o sei se c'è qualcosa da spiegare davvero. Metti la "
            + "risposta per prima e la spiegazione dopo. Mai elenchi puntati, mai markdown, "
            + "mai premesse come 'certo' o 'ottima domanda'. Se non sai una cosa, dillo "
            + "subito invece di girarci intorno. Ricorda quello che ci siamo detti prima in "
            + "questa conversazione e fai riferimento ad esso quando serve.";

    /**
     * Minutes of silence after which a new question starts a new conversation. The history
     * outlives the screen on purpose — see {@link #shared()} — and without this a question
     * asked tomorrow would arrive on top of yesterday's thread.
     */
    private static final long IDLE_RESET_MS = 10 * 60 * 1000L;

    @Nullable private static Conversation shared;

    /**
     * The conversation the assistant screen uses.
     *
     * <p>Held for the life of the process rather than the activity. The screen is destroyed
     * and rebuilt more often than it looks: a day-to-night switch does it, and so does coming
     * back from the factory assistant that the steering wheel button is wired to. Keeping the
     * history in the activity meant it was quietly lost every time, which is exactly what it
     * looked like from the driver's seat — an assistant with no memory.
     */
    @NonNull
    public static synchronized Conversation shared() {
        long now = System.currentTimeMillis();
        if (shared == null || now - shared.lastUsedAt > IDLE_RESET_MS) {
            shared = new Conversation();
        }
        shared.lastUsedAt = now;
        return shared;
    }

    private final Deque<JSONObject> turns = new ArrayDeque<>();
    private long lastUsedAt = System.currentTimeMillis();

    public void addUser(@NonNull String text) {
        add("user", text);
    }

    public void addAssistant(@NonNull String text) {
        add("assistant", text);
    }

    private void add(@NonNull String role, @NonNull String text) {
        try {
            JSONObject message = new JSONObject();
            message.put("role", role);
            message.put("content", text);
            turns.addLast(message);
            lastUsedAt = System.currentTimeMillis();
        } catch (Exception e) {
            // A message that will not serialise is one we simply do not remember; the next
            // question still works, with slightly less context.
            return;
        }
        while (turns.size() > MAX_TURNS * 2) {
            turns.removeFirst();
        }
    }

    public void clear() {
        turns.clear();
    }

    /** The array to send, system prompt first. */
    @NonNull
    public JSONArray toMessages() {
        JSONArray array = new JSONArray();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", SYSTEM_PROMPT);
            array.put(system);
        } catch (Exception ignored) {
            // Without the system prompt the answers get longer and chattier, but still work.
        }
        for (JSONObject message : turns) {
            array.put(message);
        }
        return array;
    }
}
