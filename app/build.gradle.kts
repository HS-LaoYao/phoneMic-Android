plugins {
    id("com.android.application")
}

android {
    namespace = "com.phonemic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.phonemic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 打开压缩和资源裁剪，APK 能再小一圈
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 只是自用，lint 报错不要挡住构建
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 本项目不使用任何第三方库（连 AndroidX 都没有），所以没有 dependencies 块。
