plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lsh.crmsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lsh.crmsync"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 업데이트 설치가 막히지 않도록 항상 같은 서명 키 사용 (개인용 앱)
    signingConfigs {
        create("fixed") {
            storeFile = file("crmsync.jks")
            storePassword = "crmsync123"
            keyAlias = "crmsync"
            keyPassword = "crmsync123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
