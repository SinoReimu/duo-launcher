plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duo.launcher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.duo.launcher"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        // -PidSuffix=.dev builds a copy that installs next to the real launcher (other key, own data).
        (findProperty("idSuffix") as String?)?.let { applicationIdSuffix = it }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
