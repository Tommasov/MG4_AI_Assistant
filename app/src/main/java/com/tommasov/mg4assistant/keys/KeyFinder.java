package com.tommasov.mg4assistant.keys;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Environment;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds an API key that is already somewhere on the car, so that nobody has to type one.
 *
 * <p>This exists because of a hard limit of the hardware. An OpenAI project key is around 164
 * characters of mixed case and punctuation, and the head unit's keyboard is a touchscreen at a
 * standstill with no autocorrect worth the name and no obvious place to paste from. Typing one
 * correctly is a ten-minute job that fails silently on the first wrong character — the app
 * would simply behave as though the network were broken. That single problem is what kept the
 * key compiled into the APK, and kept the app unpublishable with it.
 *
 * <p>So the key arrives as a file instead. Put it in a text file, get the file onto the car by
 * whatever means the car already has — a USB stick mounts under {@code /storage}, the Download
 * folder is where anything else on this head unit puts a file, adb push works for the author —
 * and this class goes and reads it. Nothing is typed and nothing can be mistyped.
 *
 * <p>Deliberately conservative about what it opens. Only small files with a text-like
 * extension are read at all, no more than a couple of hundred of them, and no deeper than two
 * levels below each starting point: a scan that wandered into a folder of downloaded APKs
 * would be slow, pointless and alarming in equal measure.
 */
public final class KeyFinder {

    /**
     * What a key looks like on both services. Anchored on the prefix rather than the length
     * because OpenAI has changed the length twice — {@code sk-}, then {@code sk-proj-}, now
     * longer again — and a rule that encodes today's length rejects tomorrow's key.
     */
    private static final Pattern KEY = Pattern.compile("(?:sk|xai)-[A-Za-z0-9_\\-]{20,}");

    /** A key file is a few hundred bytes. Anything larger is something else. */
    private static final int MAX_BYTES = 64 * 1024;

    private static final int MAX_FILES = 400;
    private static final int MAX_DEPTH = 2;

    /**
     * Where MG4 Browser puts what it downloads. Its own constant, mirrored here.
     *
     * <p>Searched, but never to be recommended as somewhere to keep a key. That browser
     * deletes anything in this folder older than an hour, and does it at the start of every
     * new download — deliberately, because it is a service browser that hands an APK to the
     * installer and has no business hoarding files. So it is a way of carrying a key onto the
     * car, good for the minutes between downloading and importing, and not a place the file
     * will still be next week. The text on screen says so.
     */
    private static final String BROWSER_DOWNLOADS =
            "Android/data/com.tommasov.mg4browser/files/downloads";

    /**
     * Extensions worth opening. A whitelist rather than a blacklist: the point is not to guess
     * which files are binary, it is to never read one by accident.
     */
    private static final Set<String> TEXT_LIKE = new HashSet<>();

    static {
        TEXT_LIKE.add("txt");
        TEXT_LIKE.add("key");
        TEXT_LIKE.add("json");
        TEXT_LIKE.add("env");
        TEXT_LIKE.add("cfg");
        TEXT_LIKE.add("conf");
        TEXT_LIKE.add("ini");
        TEXT_LIKE.add("properties");
        TEXT_LIKE.add("md");
        TEXT_LIKE.add("csv");
        TEXT_LIKE.add("log");
        TEXT_LIKE.add("");
    }

    /** One key, and where it was read from, so the person can tell two of them apart. */
    public static final class Found {
        public final String key;
        public final String where;

        Found(@NonNull String key, @NonNull String where) {
            this.key = key;
            this.where = where;
        }

        /** The file it came from and the tail of the key: enough to choose between two. */
        @NonNull
        public String label() {
            return where + "  —  " + mask(key);
        }
    }

    private KeyFinder() {
    }

    /**
     * Every key found on the car, newest place first.
     *
     * <p>Runs file IO and belongs on a worker thread. Returns an empty list rather than
     * throwing when storage is unreadable, which on this head unit is a normal state of
     * affairs rather than an error: the permission may not be granted, and the app's own
     * folder is searched regardless because it never needs one.
     */
    @NonNull
    public static List<Found> search(@NonNull Context context) {
        List<Found> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int[] budget = {MAX_FILES};

        // The app's own external folder first. It needs no permission at all, which makes it
        // the one place that works even when storage access has been refused.
        scan(context.getExternalFilesDir(null), MAX_DEPTH, found, seen, budget);

        scan(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                MAX_DEPTH, found, seen, budget);

        File external = Environment.getExternalStorageDirectory();
        scan(new File(external, BROWSER_DOWNLOADS), MAX_DEPTH, found, seen, budget);
        // The root itself, one level only: a file dropped straight onto the card is a normal
        // thing to do, but recursing from here would walk the whole of internal storage.
        scan(external, 0, found, seen, budget);

        for (File volume : usbVolumes()) {
            scan(volume, MAX_DEPTH, found, seen, budget);
        }
        return found;
    }

    /**
     * Removable volumes, which on this head unit is how most people will carry a file to the
     * car. Listed from {@code /storage} rather than asked of StorageManager because the
     * public API for this only arrived at API 30 and the useful half of it is still hidden.
     */
    @NonNull
    private static List<File> usbVolumes() {
        List<File> volumes = new ArrayList<>();
        File[] mounted = new File("/storage").listFiles();
        if (mounted == null) {
            return volumes;
        }
        for (File volume : mounted) {
            String name = volume.getName();
            // "emulated" is internal storage under another name and has already been scanned;
            // "self" is a symlink back into it.
            if ("emulated".equals(name) || "self".equals(name)) {
                continue;
            }
            if (volume.isDirectory() && volume.canRead()) {
                volumes.add(volume);
            }
        }
        return volumes;
    }

    private static void scan(File dir, int depth, @NonNull List<Found> found,
                             @NonNull Set<String> seen, @NonNull int[] budget) {
        if (dir == null || budget[0] <= 0 || !dir.isDirectory() || !dir.canRead()) {
            return;
        }
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (budget[0] <= 0) {
                return;
            }
            if (entry.isDirectory()) {
                if (depth > 0) {
                    scan(entry, depth - 1, found, seen, budget);
                }
                continue;
            }
            if (!isWorthOpening(entry)) {
                continue;
            }
            budget[0]--;
            String key = fromText(read(entry));
            if (key.isEmpty() || !seen.add(key)) {
                continue;
            }
            found.add(new Found(key, describe(entry)));
        }
    }

    private static boolean isWorthOpening(@NonNull File file) {
        if (!file.isFile() || !file.canRead() || file.length() == 0
                || file.length() > MAX_BYTES) {
            return false;
        }
        return TEXT_LIKE.contains(extensionOf(file.getName()));
    }

    @NonNull
    private static String extensionOf(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.US);
    }

    @NonNull
    private static String read(@NonNull File file) {
        byte[] buffer = new byte[(int) Math.min(file.length(), MAX_BYTES)];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < buffer.length) {
                int n = in.read(buffer, read, buffer.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            // ISO-8859-1 never throws on a stray byte. A key is ASCII, so nothing that
            // matters can be mangled, and a file that turns out to be binary simply fails to
            // match instead of failing to decode.
            return new String(buffer, 0, read, "ISO-8859-1");
        } catch (IOException | OutOfMemoryError e) {
            return "";
        }
    }

    /** A path worth showing on screen: short, and recognisable as somewhere the person put it. */
    @NonNull
    private static String describe(@NonNull File file) {
        String path = file.getAbsolutePath();
        String external = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (path.startsWith(external + "/")) {
            return path.substring(external.length() + 1);
        }
        if (path.startsWith("/storage/")) {
            return path.substring("/storage/".length());
        }
        return path;
    }

    /** The first key-shaped thing in a piece of text, or empty. */
    @NonNull
    public static String fromText(@NonNull String text) {
        Matcher matcher = KEY.matcher(text);
        return matcher.find() ? matcher.group() : "";
    }

    /**
     * A key sitting in the clipboard, if one is.
     *
     * <p>Kept because it costs nothing and is instant when it works: anything selected and
     * copied in the browser lands here, and the system clipboard crosses app boundaries even
     * on a head unit with no visible paste anywhere.
     */
    @NonNull
    public static String fromClipboard(@NonNull Context context) {
        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return "";
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null) {
            return "";
        }
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence text = clip.getItemAt(i).coerceToText(context);
            if (text != null) {
                String key = fromText(text.toString());
                if (!key.isEmpty()) {
                    return key;
                }
            }
        }
        return "";
    }

    public static boolean isOpenAi(@NonNull String key) {
        return key.startsWith("sk-");
    }

    public static boolean isXai(@NonNull String key) {
        return key.startsWith("xai-");
    }

    /**
     * A key as it should appear on screen: enough to recognise, not enough to copy.
     *
     * <p>The old screen printed the whole thing across a metre of dashboard. A key is a
     * password with a bill attached and the passenger can read it from there.
     */
    @NonNull
    public static String mask(@NonNull String key) {
        if (key.isEmpty()) {
            return "";
        }
        if (key.length() <= 14) {
            return key.substring(0, Math.min(3, key.length())) + "…";
        }
        return key.substring(0, 8) + "…" + key.substring(key.length() - 4);
    }
}
