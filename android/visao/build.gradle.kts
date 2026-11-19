// Visão (melhoria 24): reconhecimento de capas e mídias por pontos característicos, em Kotlin puro.
// O mesmo código roda no celular (app) e no montador dos pacotes de índice (GitHub Actions).
plugins { id("org.jetbrains.kotlin.jvm") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies { testImplementation("junit:junit:4.13.2") }
