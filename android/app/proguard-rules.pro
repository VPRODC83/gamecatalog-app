# ML Kit e OkHttp já trazem as próprias regras.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# MediaPipe (reconhecimento pela capa): o código nativo procura estas classes pelo nome.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**
# CameraX + ML Kit na câmera ao vivo
-keep class androidx.camera.mlkit.vision.** { *; }
