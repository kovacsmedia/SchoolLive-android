# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembernames interface * { @retrofit2.http.* <methods>; }

# Gson
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# App models
-keep class hu.schoollive.player.api.models.** { *; }

# BCrypt
-keep class org.mindrot.jbcrypt.** { *; }
