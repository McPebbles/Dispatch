# Tombot R8 rules.
#
# The app has no reflection-driven serialization and no JavaScript bridge, so
# the default AndroidX/Kotlin consumer rules cover almost everything. The rules
# below are defensive.

# If a @JavascriptInterface is ever added, keep its methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the WebView client callbacks reachable from the framework.
-keep class * extends android.webkit.WebViewClient { *; }
-keep class * extends android.webkit.WebChromeClient { *; }

# Strip all logging from release builds. Nothing about the user's session,
# cart or scan codes should ever reach logcat in a release APK.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
