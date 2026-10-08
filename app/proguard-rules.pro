# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable

# Conscrypt (TLS 1.3 keying-material export for Wireless Debugging) ships
# adapters for pre-L Android's bundled Conscrypt whose platform classes
# (com.android.org.conscrypt / org.apache.harmony.xnet.provider.jsse) never
# exist on API 21+; the adapter paths are dead code at runtime.
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**
