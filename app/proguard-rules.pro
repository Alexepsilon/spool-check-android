# Standard Android optimizations are inherited from
# proguard-android-optimize.txt. Add app-specific rules here if needed.

# Keep ML Kit text recognition's reflection-loaded classes.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }

# ML Kit ships with optional script libs we don't depend on.
-dontwarn com.google.mlkit.vision.text.chinese.**
-dontwarn com.google.mlkit.vision.text.devanagari.**
-dontwarn com.google.mlkit.vision.text.japanese.**
-dontwarn com.google.mlkit.vision.text.korean.**
