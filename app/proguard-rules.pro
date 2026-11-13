# NexVPN proguard
-keep class com.nex.vpn.** { *; }
-keepclassmembers class * extends android.app.Service { *; }
-dontwarn okhttp3.**
