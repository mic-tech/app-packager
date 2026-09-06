import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. The password is read from a file outside the project at build
// time, so neither it nor the keystore ever lands in version control. Override
// with -Ppackager.keystore=... / -Ppackager.keystorePasswordFile=... / -Ppackager.keyAlias=...
val home: String = System.getProperty("user.home")
val keystoreFile = file(
    providers.gradleProperty("packager.keystore")
        .getOrElse("$home/.keystores/mic-tech-upload.keystore"),
)
val keystorePasswordFile = file(
    providers.gradleProperty("packager.keystorePasswordFile")
        .getOrElse("$home/.keystores/mic-tech-upload-pwd.txt"),
)
val releaseSigningAvailable = keystoreFile.isFile && keystorePasswordFile.isFile

android {
    namespace = "com.mictech.apppackager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mictech.apppackager"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                val secret = keystorePasswordFile.readText().trim()
                storeFile = keystoreFile
                storePassword = secret
                keyAlias = providers.gradleProperty("packager.keyAlias")
                    .getOrElse("mic-tech-upload")
                keyPassword = secret
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Release signing keystore not found at $keystoreFile — " +
                        "assembleRelease will produce an unsigned APK.",
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.documentfile)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    // Real org.json, so the manifest the writer emits can be parsed back in a
    // JVM test instead of hitting android.jar's stubbed-out implementation.
    testImplementation(libs.json)
}
