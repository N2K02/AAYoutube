# Keep line numbers so crash stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Methods the YouTube page calls through the WebView script bridge must keep their names.
-keep class com.aayoutube.app.WebBridge { *; }
-keepclassmembers class com.aayoutube.app.WebBridge {
    @android.webkit.JavascriptInterface <methods>;
}
