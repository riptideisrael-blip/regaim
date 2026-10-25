# The protected build (release): the code is shrunk and its names scrambled (R8), so the app can't simply be read
# back from the APK. Kept as they are: what is looked up by name while the app runs.

# Enum names are saved and read back as text (scene keys like "green", stored tiers, styles): never renamed.
-keepclassmembers enum com.regaim.app.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# The on-phone AI models (laughter, faces, labels) load their Java parts from native code.
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Optional pieces the libraries mention but don't need.
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn kotlinx.coroutines.debug.**

# Crash reports still say which line (the file names themselves are hidden).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
