package com.tommasov.mg4assistant.probe;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the car itself already has in the way of voice.
 *
 * <p>The SAIC adapter exposes a {@code VoiceVuiService} — there is a factory voice assistant
 * on this head unit. That matters twice over. It is the most likely reason an ordinary app
 * would find the microphone open but silent, because on Automotive the assistant commonly
 * holds the input; and if it turns out to expose anything usable, part of the plumbing this
 * project would otherwise have to buy from a transcription service may already be on board.
 *
 * <p>Nothing here calls a transaction. The adapter's transaction numbers have to be read from
 * the {@code TRANSACTION_*} constants in the right {@code $Stub} class, never counted off a
 * method list, and this build has no business guessing them — it only reports what exists,
 * which is exactly the kind of finding the emulator cannot produce.
 */
public final class VehicleProbe {

    /** The adapter the launcher already talks to, per the head-unit reference. */
    private static final String ADAPTER_PACKAGE = "com.saicmotor.adapterservice";

    private static final String[] ADAPTER_SERVICES = {
            "com.saicmotor.adapterservice.services.VoiceVuiService",
            "com.saicmotor.adapterservice.services.GeneralService",
            "com.saicmotor.adapterservice.services.MapService",
    };

    /** Substrings worth a look when hunting for the factory assistant and its engines. */
    private static final String[] INTERESTING = {
            "saic", "voice", "vui", "speech", "asr", "tts", "iflytek", "xunfei", "nuance",
    };

    public static final class Result {
        public final boolean automotive;
        public final boolean adapterInstalled;
        @Nullable public final String adapterVersion;
        /** Adapter services that resolve, by class name. */
        @NonNull public final List<String> adapterServices;
        /** Other packages on board whose name hints at voice, with their exported services. */
        @NonNull public final List<String> voicePackages;

        Result(boolean automotive, boolean adapterInstalled, @Nullable String adapterVersion,
               @NonNull List<String> adapterServices, @NonNull List<String> voicePackages) {
            this.automotive = automotive;
            this.adapterInstalled = adapterInstalled;
            this.adapterVersion = adapterVersion;
            this.adapterServices = adapterServices;
            this.voicePackages = voicePackages;
        }
    }

    private VehicleProbe() {
    }

    @NonNull
    public static Result probe(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();

        boolean automotive = pm.hasSystemFeature("android.hardware.type.automotive");

        String adapterVersion = null;
        boolean adapterInstalled = false;
        try {
            PackageInfo info = pm.getPackageInfo(ADAPTER_PACKAGE, 0);
            adapterInstalled = true;
            adapterVersion = info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // Expected on the emulator, which carries nothing from SAIC.
            adapterInstalled = false;
        }

        List<String> services = new ArrayList<>();
        for (String className : ADAPTER_SERVICES) {
            Intent intent = new Intent();
            intent.setClassName(ADAPTER_PACKAGE, className);
            List<ResolveInfo> resolved = pm.queryIntentServices(intent, 0);
            boolean found = resolved != null && !resolved.isEmpty();
            services.add(shortName(className) + ": " + (found ? "present" : "not found"));
        }

        return new Result(automotive, adapterInstalled, adapterVersion, services,
                voicePackages(pm));
    }

    /**
     * Installed packages whose name suggests speech, each with any services it exports.
     *
     * <p>Deliberately a wide net read narrowly: the point is to hand back a list to look at,
     * not to decide anything. The factory assistant on these units is not always under a SAIC
     * package name — the speech engines are often licensed from someone else.
     */
    @NonNull
    private static List<String> voicePackages(@NonNull PackageManager pm) {
        List<String> out = new ArrayList<>();
        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(0);
        } catch (Exception e) {
            out.add("could not list packages: " + e.getClass().getSimpleName());
            return out;
        }
        for (ApplicationInfo app : installed) {
            String name = app.packageName.toLowerCase(Locale.US);
            if (!matchesAny(name)) {
                continue;
            }
            StringBuilder line = new StringBuilder(app.packageName);
            try {
                PackageInfo info = pm.getPackageInfo(app.packageName,
                        PackageManager.GET_SERVICES);
                ServiceInfo[] serviceInfos = info.services;
                if (serviceInfos != null) {
                    int exported = 0;
                    for (ServiceInfo service : serviceInfos) {
                        if (service.exported) {
                            exported++;
                        }
                    }
                    line.append(" — ").append(serviceInfos.length).append(" services, ")
                            .append(exported).append(" exported");
                }
            } catch (Exception e) {
                line.append(" — services unreadable");
            }
            out.add(line.toString());
        }
        return out;
    }

    private static boolean matchesAny(@NonNull String packageName) {
        for (String needle : INTERESTING) {
            if (packageName.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static String shortName(@NonNull String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }
}
