import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// Release signing comes from a properties file outside the repository (storeFile, storePassword, keyAlias,
// keyPassword). Default: ~/.android/burrow-release.properties; override with -Pburrow.signing=/path/to/file.
// Without the file, release builds are produced unsigned.
val releaseSigning: Properties? =
    file((findProperty("burrow.signing") as String?) ?: "${System.getProperty("user.home")}/.android/burrow-release.properties")
        .takeIf { it.isFile }
        ?.let { source -> Properties().apply { source.inputStream().use { load(it) } } }

android {
    namespace = "app.burrow"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.burrow"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        releaseSigning?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No R8: libkiwix is called through JNI and the APK size is dominated by its native libraries.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // libkiwix ships ~50 MB of native code per ABI, so build one APK per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jsoup)
    implementation(libs.libkiwix)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kxml2)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
