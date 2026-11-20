# ============================================================
# 混淆 / 裁剪配置
#
# 模块被下载站搬运、二次打包后，第一件事通常是翻 dex 找 hook 点。
# 原则：**只保留运行时必须按名字找到的东西**，其余全部改名并压平包结构。
# ============================================================

-dontwarn io.github.libxposed.annotation.**

# ---- Xposed 入口 ----
# 入口类名允许混淆：META-INF/xposed/java_init.list 里的类名由这条规则同步改写
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault

# ---- 类名 / 包名混淆加强 ----
# 未被 keep 的类全部压到根包：dex 里不再出现 com.worldmargin.autospark.* 的目录结构，
# 也就看不出 host/ app/ ui/ 的分层与职责；allowaccessmodification 让 R8 能跨类合并、内联。
-repackageclasses ''
-allowaccessmodification

# ---- JNI 桥：类名和方法名必须保留，RegisterNatives 按名字查找 ----
# so 侧不导出 Java_xxx 符号（只有 JNI_OnLoad），但注册映射用的是这两个名字，
# 因此 R8 不能改。类名保持明文在 DEX 里，是动态注册方案的已知折中。
-keep class com.worldmargin.autospark.core.NativeFileReader {
    native <methods>;
}

# ---- Rhino 脚本引擎：release 必须整包保留 ----
# Rhino 大量使用反射与按类名查找，被 R8 重命名 / 裁剪后脚本执行会直接失败
# （症状：模板预览报「预览失败：<混淆后的类名>」）。
-keep class org.mozilla.javascript.** { *; }
-keepclassmembers class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

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
