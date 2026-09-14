import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing inputs come from environment variables first, then Gradle
// properties (-P or gradle.properties). Passwords are never printed.
fun signingInput(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() }

val keystorePath = signingInput("KEYSTORE_FILE")
val keystoreFile = keystorePath?.let { rootProject.file(it) }
val hasReleaseKeystore = keystoreFile != null && keystoreFile.isFile

android {
    namespace = "com.djaramillo.minimalpairs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.djaramillo.minimalpairs"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = signingInput("KEYSTORE_PASSWORD")
                keyAlias = signingInput("KEY_ALIAS")
                keyPassword = signingInput("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                // Only the file name: the alias comes from a secret and must not reach the log.
                println("Signing release with: keystore ${keystoreFile!!.name}")
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No keystore configured (or the file is missing): sign with the
                // debug key so CI without secrets still produces an installable APK.
                println("Signing release with: debug key (KEYSTORE_FILE unset or not found)")
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Assets:
    //  - data/catalog holds the generated catalog.json + catalog-version.txt, so
    //    the app reads assets/catalog.json without copying files around.
    //  - The rendered clip pack lives in app/src/main/assets/clips/ (gitignored,
    //    installed by scripts/render-clips.py --install-assets or by CI). When it
    //    is absent we add data/placeholder-clips/ (layout: clips/index.json +
    //    clips/<voice>/<word>.webm, index.json says "complete": false) so the
    //    build never fails and the app can still run a tiny drill.
    sourceSets["main"].assets.srcDirs(rootProject.file("data/catalog"))
    if (!file("src/main/assets/clips/index.json").exists()) {
        sourceSets["main"].assets.srcDirs(rootProject.file("data/placeholder-clips"))
    }

    // Do not compress clips so AssetManager can memory-map them (openFd works).
    // The pack's sha256.txt manifest is only used to verify a *downloaded* pack
    // (ClipDownloader); the app never reads it from assets, so keep it out of
    // the APK (~80 KB deflated). The pattern is aapt's default plus that file.
    androidResources {
        noCompress += "webm"
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:<dir>_*:!CVS:!thumbs.db:!picasa.ini:!*~:!sha256.txt"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Name the APK minimal-pairs-<versionName>.apk for every variant.
    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                val suffix = if (variant.buildType.name == "release") "" else "-${variant.buildType.name}"
                output.outputFileName = "minimal-pairs-${variant.versionName}$suffix.apk"
            }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
