# Mantener las entradas de Xposed (LSPatch las descubre por reflexión)
-keep class com.virtualcam.app.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
