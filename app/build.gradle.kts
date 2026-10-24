plugins {
    id("com.android.application")
}

// 签名密钥由 CI 注入（路径走环境变量，密钥本体存在仓库 Secret 里，不进代码库）。
// 必须固定：否则每次 CI 都在全新的 runner 上重新生成 debug.keystore，
// 签名每版都不一样，手机上就只能先卸载旧的才能装。
val ksPath = System.getenv("PHONEMIC_KEYSTORE") ?: ""
val signed = ksPath.isNotBlank() && file(ksPath).exists()

android {
    namespace = "com.phonemic"
    compileSdk = 34

    signingConfigs {
        if (signed) {
            create("ci") {
                storeFile = file(ksPath)
                storePassword = System.getenv("PHONEMIC_KS_PASS")
                keyAlias = System.getenv("PHONEMIC_KEY_ALIAS")
                keyPassword = System.getenv("PHONEMIC_KEY_PASS")
            }
        }
    }

    defaultConfig {
        applicationId = "com.phonemic"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            if (signed) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            // 打开压缩和资源裁剪，APK 能再小一圈
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (signed) signingConfig = signingConfigs.getByName("ci")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 8 起 BuildConfig 默认不生成，而「关于」和「检查更新」要用它读版本号
    buildFeatures {
        buildConfig = true
    }

    // 只是自用，lint 报错不要挡住构建
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// 本项目不使用任何第三方库（连 AndroidX 都没有），所以没有 dependencies 块。
