plugins {
    alias(libs.plugins.android.application)
}

// Cloud features need app/google-services.json; without it the app builds and runs with cloud disabled.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("google-services.json not found: building with Firebase disabled.")
}

android {
    namespace = "com.MrBRos.akhu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.MrBRos.akhu"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    // Bundled motion videos are played straight from the APK via AssetFileDescriptor; the NSFW
    // model is read straight from the APK too.
    androidResources {
        noCompress += listOf("mp4", "onnx")
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.glide)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.swiperefreshlayout)
    implementation(libs.exifinterface)
    implementation(libs.viewpager2)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.mlkit.subject.segmentation)
    implementation(libs.play.services.base)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.effect)
    implementation(libs.media3.transformer)
    implementation(libs.onnxruntime.android)
}
