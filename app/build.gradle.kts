import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// リリース署名の設定。keystore.properties が無い環境（CI や他の開発者）でも
// ビルド自体は通るようにし、その場合は未署名 APK になる。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.ayati.volumetap"
    compileSdk = 36

    // 対応言語の一覧（locale_config）を values-* から作り、Android 13 以降の
    // アプリごとの言語設定に出す（design-i18n.md §4.1）。values/ の言語は res/resources.properties。
    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "com.ayati.volumetap"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.17.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")

                // minSdk 26 なので v1（JAR 署名）は不要。
                // v3 は既定で無効だが、将来の鍵ローテーションに必要なので明示的に有効化する。
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            // 自前のネイティブコードは無いが、AndroidX の推移的依存で .so が同梱される
            // （libandroidx.graphics.path.so / libdatastore_shared_counter.so）。
            // なお現時点でこれらは配布元で既にシンボル削除済みのため抽出結果は空になり、
            // Play の「デバッグシンボルがありません」警告は解消できない。
            // 将来シンボル付きで配布されたときに自動で効くよう設定だけ残す。
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
