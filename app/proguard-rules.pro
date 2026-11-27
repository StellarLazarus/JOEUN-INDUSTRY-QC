-optimizationpasses 5
-dontusemixedcaseclassnames
-repackageclasses 'o'
-allowaccessmodification
-keep class androidx.webkit.** { *; }
-keep class com.android.billingclient.** { *; }
-keep class com.google.android.gms.internal.play_billing.** { *; }
-keep public class com.google.android.gms.ads.** { public *; }
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