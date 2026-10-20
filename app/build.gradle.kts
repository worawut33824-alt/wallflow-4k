import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---- Release signing -------------------------------------------------------
// Looks for keystore.properties in the project root first (local builds),
// then falls back to environment variables (CI / GitHub Actions).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        // Read as text and drop any BOM. Loading the stream directly would fold
        // a UTF-8 BOM into the first key name and silently produce an unsigned
        // build, which is a very easy failure to miss.
        load(keystorePropsFile.readText(Charsets.UTF_8).removePrefix("๏ปฟ").reader())
    }
}

fun prop(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val storeFilePath = prop("storeFile", "WALLFLOW_STORE_FILE")
val resolvedStoreFile = storeFilePath?.let { rootProject.file(it) }
val hasSigning = resolvedStoreFile != null && resolvedStoreFile.exists()

// Say so out loud. An unsigned release APK cannot be installed, and finding
// that out on the phone instead of here wastes a whole round trip.
gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("Release") }) {
        if (hasSigning) {
            logger.lifecycle("WallFlow: release signing with ${resolvedStoreFile?.name}")
        } else {
            logger.warn("WallFlow: NO release signing key found - the release APK will be unsigned. Run tools/create-keystore.ps1.")
        }
    }
}

android {
    namespace = "com.admrichest.wallflow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.admrichest.wallflow"
        minSdk = 29
        targetSdk = 36
        versionCode = 15
        versionName = "1.3.1"
    }

    androidResources {
        // Ship only the two languages the app actually has.
        localeFilters += listOf("en", "th")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = resolvedStoreFile
                storePassword = prop("storePassword", "WALLFLOW_STORE_PASSWORD")
                keyAlias = prop("keyAlias", "WALLFLOW_KEY_ALIAS")
                keyPassword = prop("keyPassword", "WALLFLOW_KEY_PASSWORD")
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
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }

    // Keep the download small: no per-language splits, we only ship en + th.
    bundle {
        language { enableSplit = false }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
}
