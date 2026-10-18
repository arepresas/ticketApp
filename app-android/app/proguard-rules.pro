# Keep OkHttp's optional platform integrations (Conscrypt/BouncyCastle).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization keeps its generated serializers via companion
# objects; R8 needs them reachable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# @JavascriptInterface methods are called reflectively from JS.
-keepclassmembers class com.ticketapp.android.web.SessionBridge {
    @android.webkit.JavascriptInterface <methods>;
}