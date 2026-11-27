plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.flickime"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.flickime"
        minSdk = 26
        targetSdk = 35
        versionCode = 78
        versionName = "0.78"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16") } }
    }
    androidResources {
        noCompress += listOf("bin", "gguf")
        // with the medium zenz model present (not in git: tools/zenz_medium_gguf.py), leave the small one out
        if (file("src/main/assets/zenz-medium.gguf").exists())
            ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:.*:!CVS:!thumbs.db:!picasa.ini:!*~:!zenz.gguf"
    }
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    // the prediction benchmark also runs standalone (tools/predeval/run.sh)
    sourceSets["test"].java.srcDir("../tools/predeval")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
