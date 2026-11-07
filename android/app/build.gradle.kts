plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Versão: vem do workflow de publicação (tag vX.Y.Z); localmente usa 0.0.1
val appVersionName: String = (System.getenv("APP_VERSION") ?: "0.0.1").removePrefix("v")
val appVersionCode: Int = appVersionName.split(".").map { it.toIntOrNull() ?: 0 }.let { (it + listOf(0, 0, 0)).take(3) }.let { it[0] * 10000 + it[1] * 100 + it[2] }.coerceAtLeast(1)

android {
    namespace = "io.github.vprodc83.gamecatalog"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vprodc83.gamecatalog"
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // chave pública do TheGamesDB (segredo TGDB_API_KEY no GitHub); vazia = fonte desligada
        buildConfigField("String", "TGDB_KEY", "\"${(System.getenv("TGDB_API_KEY") ?: "").replace("\"", "")}\"")
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("ANDROID_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDROID_KEY_PASSWORD")
                keyAlias = "gamecatalog"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("ANDROID_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    androidResources { noCompress += "tflite" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // espelho opcional para o Robolectric baixar o Android de teste (quando o Maven Central limita downloads)
        unitTests.all { t -> System.getenv("ROBOLECTRIC_REPO")?.let { t.systemProperty("robolectric.dependency.repo.url", it) } }
    }
    lint { abortOnError = false; checkReleaseBuilds = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"; jniLibs.useLegacyPackaging = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    // câmera ao vivo (melhoria 14)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-mlkit-vision:1.4.2")
    // reconhecimento pela imagem da capa, no celular (melhoria 16)
    implementation("com.google.mediapipe:tasks-vision:0.10.26.1")
    // leitura do texto das etiquetas no próprio aparelho (modelo embutido, sem internet)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // capas e páginas de preço
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // login com a conta Google do aparelho e permissão para a planilha
    implementation("com.google.android.gms:play-services-auth:21.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
