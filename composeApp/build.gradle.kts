import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    // Desktop JVM target: used to render README screenshots headlessly and run fast unit tests.
    jvm("desktop")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.multiplatform.settings.noarg)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.multiplatform.settings.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
        }
    }
}

android {
    namespace = "com.xrmx.tides"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xrmx.tides"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number and tag so every published APK can be installed over the last one.
        versionCode = providers.environmentVariable("TIDES_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("TIDES_VERSION_NAME").orNull ?: "1.0.0"
    }
    signingConfigs {
        // A stable release key from CI secrets. Without it, release builds fall back to the debug key (local builds only).
        val keystore = providers.environmentVariable("TIDES_KEYSTORE_FILE").orNull
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("TIDES_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TIDES_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("TIDES_KEY_PASSWORD").get()
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// `./gradlew :composeApp:run` renders the README screenshots into docs/screenshots.
compose.desktop {
    application {
        mainClass = "com.xrmx.tides.screenshots.RenderScreenshotsKt"
        args += listOf(rootProject.file("docs/screenshots").absolutePath)
        jvmArgs += listOf("-Djava.awt.headless=true")
    }
}
