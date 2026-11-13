# NexVPN proguard
-keep class com.nex.vpn.** { *; }
-keepclassmembers class * extends android.app.Service { *; }
-dontwarn okhttp3.**

# gomobile/xray AAR — JNI ищет классы и методы по исходным именам
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
