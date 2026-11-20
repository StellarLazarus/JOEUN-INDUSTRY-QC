-optimizationpasses 5
-dontusemixedcaseclassnames
-repackageclasses 'o'
-allowaccessmodification
-keep class androidx.webkit.** { *; }
-keepattributes JavascriptInterface
-keep class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}