// Montador dos pacotes de índice (roda no GitHub Actions, não vai para o celular).
plugins { id("org.jetbrains.kotlin.jvm"); application }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
application { mainClass.set("io.github.vprodc83.montador.MainKt"); applicationDefaultJvmArgs = listOf("-Xmx6g") }
dependencies { implementation(project(":visao")); implementation("org.json:json:20240303") }
