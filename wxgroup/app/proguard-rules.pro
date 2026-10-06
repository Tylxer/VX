# Xposed 模块不需要混淆，且混淆会破坏 xposed_init 里声明的入口类名
-keep class com.v.wxgroup.HookEntry { *; }
-keep class com.v.wxgroup.core.** { *; }
-keep class com.v.wxgroup.data.** { *; }
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }
