plugins {
    id("com.android.application")
    kotlin("android")
}

val releaseStoreFile = providers.gradleProperty("DOCSCAN_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("DOCSCAN_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("DOCSCAN_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("DOCSCAN_RELEASE_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val releaseRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
if (releaseRequested && releaseSigningValues.any { it.isNullOrBlank() }) {
    throw GradleException("Release signing is not configured. Provide DOCSCAN_RELEASE_* values in user-level Gradle properties.")
}

android {
    namespace = "app.docscan.android"
    compileSdk = 35

    sourceSets.getByName("androidTest").assets.srcDir("../core/src/main/sqldelight/databases")

    defaultConfig {
        applicationId = "app.docscan.scanner"
        minSdk = 29
        targetSdk = 35
        versionCode = 359
        versionName = "0.4.240"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("productRelease") {
            if (releaseStoreFile != null) storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("productRelease")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)?.outputFileName =
                "DocScan-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("app.cash.sqldelight:android-driver:2.3.2")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.opencv:opencv:4.14.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
