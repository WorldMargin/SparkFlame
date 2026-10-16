# 星火燎原（抖音自动续火花）

一个 LSPosed 模块：注入抖音进程，**自动给「今天还没互动」的会话续火花**（发一条你自己写的文案）。
文案是一段 JavaScript，可以用模板、宏和自定义函数组合出来 —— 目标就是「每天自动续，但话说得像真人说的」。

## 署名与来源

本模块 fork 自开源项目 **[yfishyon/doumcp](https://github.com/yfishyon/doumcp)**（Apache-2.0 许可），
只保留「续火花」这一条链路；**界面、脚本引擎、模板 / 宏 / 函数体系、Monaco 编辑器接入都是重写的**。

思路上的致谢：

- **yfishyon/doumcp** —— 抖音逆向与注入能力的基础（会话、IM 发送、火花字段等）
- **WorldMargin** —— 模板 / 宏 / 函数 这套组合思路
- **DeepSeek 油猴脚本** —— 本项目由该思路迁移至安卓

## 功能

- **续火花**：抖音判定「今天还没互动」的会话自动发一条私信；每天每个会话只发一次；没选模板的会话一律不发
- **模板**：内置模板（空白 / 按时问候 / 火花天数打卡 / 简短提醒）+ 自定义模板；
  一级模板可折叠展开看**派生模板**（继承它的会话）；支持「继承（跟随父模板）」与「克隆（内容固定）」
- **宏**：一段独立小脚本，可挂到多个模板或具体会话，在模板之后执行；用完即删，不影响模板内容
- **自定义函数**：全局定义，执行前注入成 `ctx.functions.<名字>`，供模板 / 宏调用
- **脚本上下文**：`ctx.date / time / name / days / state …` + `ctx.pick / countdown / nextNewYear …`
- **编辑器**：Monaco（VSCode 同款，离线内置）—— `ctx.*` 与 JS 内置成员的补全、类型检查、
  悬浮文档、格式化、双指缩放字号；可在设置里切回原生编辑器
- **预览**：用真实会话快照渲染一遍，与实际发送走同一套组装
- **手动发送**：随时给「已选目标」或勾选的会话立刻发一次
- **使用文档**：App 内可看 / 可导出；文档里带**可直接贴给 AI 的提示词**，让 AI 照着 API 写脚本
- **配置导出 / 导入**：换机、备份

## 构建

需要 JDK 21 + Android SDK（`local.properties` 指向 SDK）。

```powershell
# 仓库根目录
.\gradlew.bat assembleDebug --console=plain
# 产物：app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

> ⚠️ 不要把 gradle 的输出接进管道（`... | Select-String …`、`> out.txt`）：
> Gradle 守护进程会继承 stdout 管道并一直活着，管道等不到 EOF，看起来像命令卡死 ——
> 其实构建早就结束了。要么直接跑（让输出打到终端），要么用 `Start-Process` 重定向到文件。

## 安装与使用

1. 装 APK，在 LSPosed 里勾选模块、作用域选「抖音」，**重启抖音**
2. 打开模块界面（桌面图标「星火燎原」，或 **抖音 → 我 → 设置 → 续火花**；
   抖音侧会弹一次「是否跳转」，**3 秒内点「继续」**）
3. 设置 → 打开总开关 → 点一次「刷新会话」
4. 模板页给会话选模板（或用内置模板一键复制 / 继承），宏与函数在「宏/函数」页里加

没有给会话选模板的会话不会发消息 —— 这是有意的（不偷偷套用内置文案）。

## 目录

```
app/src/main/java/com/worldmargin/autospark/
  ModuleMain.kt                 模块入口：Application.attach 拦截、设置页注入、广播注册、热重载
  core/                         宿主 Context / 反射 / 日志（libxposed 通道）
  features/autospark/
    bridge/                     宿主侧：扫会话写快照、渲染脚本发消息、手动发送、宿主简报
    model/                      配置模型 + 编解码 + 模板 / 宏 / 函数组装
    provider/                   跨进程配置通道（ContentProvider）+ 唤醒广播
    script/                     Rhino 沙盒引擎 + Monaco 用的类型声明生成
    store/                      配置读写（带缓存）
    ui/                         管理界面（纯代码 View）：主界面 / 模板 / 会话 / 宏 / 函数 / 文档 / 编辑器抽象
  features/im/, features/account/  上游保留的会话与账号能力
app/src/main/assets/monaco/     Monaco 0.52.2 裁剪版（离线，约 9.7 MB）
app/src/main/assets/fonts/      VSCode codicon 图标字体
app/src/main/assets/docs/       使用文档（构建时从仓库同步）
```

## 许可

沿用上游的 **Apache License 2.0**（见 `LICENSE`）。二次分发请保留许可与上述署名。
