# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Upstream JNI resolves these package/class/method names directly. Only core/imgproc are built.
-keep class org.opencv.** { *; }

# Existing Gson backups/DTOs use unannotated property names as their file format.
# Room/Retrofit supply their own consumer rules; retain these reflection-only fields too.
-keepclassmembers,allowoptimization class com.n8rtz.winetracker.data.local.** { <fields>; }
-keepclassmembers,allowoptimization class com.n8rtz.winetracker.data.importer.** { <fields>; }
-keepclassmembers,allowoptimization class com.n8rtz.winetracker.network.** { <fields>; }

# Production ONNX Runtime uses JNI/reflection; preserve the genuine runtime entry points.
-keep class ai.onnxruntime.** { *; }
