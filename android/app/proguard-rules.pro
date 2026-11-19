# ML Kit e OkHttp já trazem as próprias regras.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# CameraX + ML Kit na câmera ao vivo
-keep class androidx.camera.mlkit.vision.** { *; }
