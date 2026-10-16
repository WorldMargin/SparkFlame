-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.modelcontextprotocol.**$$serializer { *; }
-keepclassmembers class io.modelcontextprotocol.** { *** Companion; }
-keepclasseswithmembers class io.modelcontextprotocol.** { kotlinx.serialization.KSerializer serializer(...); }
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ---- Rhino 脚本引擎：release 必须整包保留 ----
# Rhino 大量使用反射与按类名查找，被 R8 重命名 / 裁剪后脚本执行会直接失败
# （症状：模板预览报「预览失败：<混淆后的类名>」）。
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# 脚本上下文 ctx.* 与引擎自身的类会被 JS 按名字访问
-keep class com.worldmargin.autospark.features.autospark.script.** { *; }
-keepclassmembers class com.worldmargin.autospark.features.autospark.script.** { *; }

# Rhino 内部有一条 JSON 转换路径引用 java.beans.*（Android 上没有这些类），
# R8 的 missing class 检查会因此中断构建；那条路径在 Android 上不会执行。
-dontwarn java.beans.**

# ---- WebView 的 JS 桥（Monaco 编辑器）----
# @JavascriptInterface 的方法由网页按名字调用；R8 改名 / 删除后 JS 回调进不来，
# 症状：编辑页能打开、行号也有，但脚本内容是空的（onReady 没到，待写入的文本一直没送出去）。
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
