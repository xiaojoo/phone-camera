import java.util.Properties

plugins {
    id("com.android.application")
}

// 发布签名读 android/keystore.properties；没有就退回 debug 签名，
// 这样仓库里不带密钥也能构建出可安装的 release 包（只是不能覆盖升级）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.camera"

    compileSdk = 37

    signingConfigs {
        create("release") {
            storeFile = keystoreProps["storeFile"]
                ?.let { file(it as String) }
            storePassword = keystoreProps["storePassword"] as String?
            keyAlias = keystoreProps["keyAlias"] as String?
            keyPassword = keystoreProps["keyPassword"] as String?
        }
    }

    defaultConfig {
        applicationId = "com.camera"

        minSdk = 24
        targetSdk = 37

        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            signingConfig = if (keystoreProps.isEmpty())
                signingConfigs.getByName("debug")
            else signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {

    implementation(
        "androidx.core:core-ktx:1.17.0"
    )

    implementation(
        "androidx.appcompat:appcompat:1.8.0"
    )

    implementation(
        "androidx.camera:camera-core:1.6.2"
    )

    implementation(
        "androidx.camera:camera-camera2:1.6.2"
    )

    implementation(
        "androidx.camera:camera-lifecycle:1.6.2"
    )

    implementation(
        "androidx.camera:camera-view:1.6.2"
    )
}