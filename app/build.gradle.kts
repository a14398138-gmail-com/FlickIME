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
        versionCode = 33
        versionName = "0.33"
    }
    androidResources { noCompress += listOf("bin") }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> { testLogging { showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL } }
