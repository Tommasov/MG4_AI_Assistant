import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The API key may live in apikeys.properties (git-ignored), same arrangement as the launcher's
// Open Charge Map key. A missing key is not a build error: the probe reports that it has no
// key and skips the network checks, so a fresh clone still builds and still tells you what
// the head unit can do locally.
val apiKeysFile = rootProject.file("apikeys.properties")
val apiKeys = Properties().apply {
    if (apiKeysFile.exists()) {
        load(FileInputStream(apiKeysFile))
    }
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(FileInputStream(keystorePropertiesFile))
    }
}

android {
    namespace = "com.tommasov.mg4assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tommasov.mg4assistant"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-probe"

        // The author's report endpoint. Write-only by design: it accepts a report and can do
        // nothing else — no reading back, no listing, no deleting, with any key. That is what
        // makes it safe to ship the write key inside an APK, which this one does.
        buildConfigField(
            "String",
            "PROBE_URL",
            "\"https://ws2.tommasovietina.it/mg4/probe.php\""
        )

        // Not a secret, and not treated as one: it travels in every installed APK. Absent
        // from a fresh clone, and then the send button is not offered rather than offered
        // and broken.
        buildConfigField("String", "PROBE_KEY", "\"${apiKeys.getProperty("probe.key", "")}\"")

        // The folder reports land in on the server. Stable on purpose: it is where the
        // author will go looking.
        buildConfigField("String", "PROBE_APP", "\"assistant\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        // The chat API key is a build-type decision, not a default, and this is the whole of
        // the reason.
        //
        // It used to sit in defaultConfig, which meant every build carried whatever was in
        // apikeys.properties and the rule "this APK must not go into apps.json" was enforced
        // by memory alone. BuildConfig constants come straight out of a dex with grep and
        // this key has credit attached, so memory was not good enough. Now a release cannot
        // carry one: the constant is empty whatever the properties file says, and the key
        // arrives on the car as a file instead — see KeyFinder.
        debug {
            buildConfigField(
                "String",
                "API_KEY",
                "\"${apiKeys.getProperty("api.key", "")}\""
            )
        }

        release {
            buildConfigField("String", "API_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(libs.appcompat)

    // Not dependencies, only a version floor — see the same block in MG4 Browser. AppCompat
    // 1.7 brings kotlin-stdlib 1.8.22, which absorbed what used to live in the -jdk7 and
    // -jdk8 artifacts; something further down still asks for those at 1.6.21, so the same
    // classes arrive twice and checkDuplicateClasses stops the build.
    constraints {
        implementation(libs.kotlin.stdlib.jdk7)
        implementation(libs.kotlin.stdlib.jdk8)
    }
}
