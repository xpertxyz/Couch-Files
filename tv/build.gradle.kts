plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xpertxyz.sharetotv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xpertxyz.sharetotv"
        minSdk = 28
        targetSdk = 37
        versionCode = 7
        versionName = "1.0.6b"

    }

    signingConfigs {
        create("release") {
            storeFile = file("/Users/pkStudio/Library/Mobile Documents/com~apple~CloudDocs/playStore/Couch Files/secret.jks")
            storePassword = "Bpk@1108"
            keyPassword = "Bpk@1108"
            keyAlias = "key0"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)
    implementation(libs.compose.icons.extended)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}