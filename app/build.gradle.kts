plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.forai.codepad"
    compileSdk {
        version = release(37)
    }

    // CI sets GITHUB_RUN_NUMBER so every build has a higher versionCode and updates install over older ones.
    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
    // Set codepad.minify=false in gradle.properties to build an unshrunk release.
    val minify = (findProperty("codepad.minify") as String?) != "false"

    defaultConfig {
        applicationId = "com.forai.codepad"
        minSdk = 28
        targetSdk = 37
        versionCode = runNumber
        versionName = "2.1." + runNumber

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Persistent signing key. It lives in the repo (app/codepad.keystore) so every build - debug or release,
    // on any machine - is signed with the same key and new APKs install over old ones.
    signingConfigs {
        create("codepad") {
            storeFile = file("codepad.keystore")
            storePassword = "qvCZrX8B2Pq3ZV4yDLoI"
            keyAlias = "codepad"
            keyPassword = "qvCZrX8B2Pq3ZV4yDLoI"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("codepad")
        }
        release {
            signingConfig = signingConfigs.getByName("codepad")
            optimization {
                enable = minify
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}