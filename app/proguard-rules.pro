# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.cybersensei.tvplayer.data.model.** { *; }
-keep class com.cybersensei.tvplayer.data.api.** { *; }

# Gson
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
