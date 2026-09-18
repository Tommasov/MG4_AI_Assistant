package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A short log that survives the app being closed, killed or reinstalled over.
 *
 * <p>Every measurement this app takes in the car has until now been shown once and then lost:
 * a line of status text read at a traffic light, or not read at all. That is fine for telling
 * the driver what is happening and useless for finding anything out, because the moments worth
 * measuring are exactly the moments when nobody can look at the screen — the instant the
 * steering wheel key is pressed and the factory assistant covers everything, or the third of a
 * second in which the recorder decides the speaker has stopped.
 *
 * <p>So findings are written here as they happen, one line each, and read back afterwards in
 * the quiet of a parked car. Preferences rather than a file: an append of one short line, tens
 * of times, with no stream to keep open and nothing to flush at a moment when the process may
 * be about to be killed.
 */
public final class Journal {

    private static final String PREFS = "mg4assistant_journal";
    private static final String SEPARATOR = "\n";

    private final SharedPreferences prefs;
    private final String key;
    private final int maxLines;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public Journal(@NonNull Context context, @NonNull String name, int maxLines) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.key = name;
        this.maxLines = maxLines;
    }

    /** Records one line, stamped with the time it happened. */
    public void add(@NonNull String line) {
        List<String> all = lines();
        all.add(clock.format(new Date()) + "  " + line);
        while (all.size() > maxLines) {
            all.remove(0);
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            if (i > 0) {
                joined.append(SEPARATOR);
            }
            joined.append(all.get(i));
        }
        // Committed rather than applied. The press being recorded is often the one that hands
        // the screen to another app, and an asynchronous write is a write that may not have
        // happened yet when this process is put to sleep.
        prefs.edit().putString(key, joined.toString()).commit();
    }

    @NonNull
    public List<String> lines() {
        String stored = prefs.getString(key, "");
        List<String> all = new ArrayList<>();
        if (stored.isEmpty()) {
            return all;
        }
        for (String line : stored.split(SEPARATOR)) {
            if (!line.isEmpty()) {
                all.add(line);
            }
        }
        return all;
    }

    public boolean isEmpty() {
        return lines().isEmpty();
    }

    public void clear() {
        prefs.edit().remove(key).commit();
    }

    /** The lines indented as report findings, or nothing at all when there are none. */
    @NonNull
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (String line : lines()) {
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }
}
