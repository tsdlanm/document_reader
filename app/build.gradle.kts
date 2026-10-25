import java.util.Properties

plugins {
    id("com.android.application")
}

// Optional release key for publishing. Create keystore.properties in the
// project root (it is git-ignored) with storeFile, storePassword, keyAlias
// and keyPassword. Without it, release builds are signed with the shared
// debug key so they can still be installed for testing.
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tsdlanm.documentreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tsdlanm.documentreader"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Keep only the languages the app is translated to.
        resourceConfigurations += listOf("en", "in")
    }

    signingConfigs {
        getByName("debug") {
            // Shared debug key (not secret) so APKs built on any machine or on
            // CI can update each other without uninstalling first.
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
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
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Do not embed the dependency metadata block; it only adds size.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Test-only: the app itself has zero dependencies.
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
