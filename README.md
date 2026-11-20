# 星火燎原（抖音自动续火花）

一个 LSPosed 模块：注入抖音进程，**自动给「今天还没互动」的会话续火花**（发一条你自己写的文案）。
文案是一段 JavaScript，可以用模板、变量、宏和自定义函数组合出来 —— 在续火花的同时拥有更高的自定义能力。

> **文档**：[`docs/manual.md`](docs/manual.md) —— 安装、界面、模板 / 变量 / 宏 / 函数、
> **完整的脚本 API**（`ctx.result`、会话筛选、发送结果回调），以及一节**可直接贴给 AI 的提示词**。
> **要写模板的 AI 请先读这份。**

> **仓库**：[Gitee（主）](https://gitee.com/WorldMargin/SparkFlame) · [GitHub（镜像）](https://github.com/WorldMargin/SparkFlame)

> **安全提示（使用前请读）**
>
> 注意到近期下游仓库推出使用 root 权限进行的定时续火花；同时出现许多第三方下载站对本仓库 APK 进行分发。
> 对于可能注入恶意代码的情况，授权 root 可能造成多平台的信息泄露。
>
> 为减少因第三方分发改包注入恶意代码造成的损失和信息泄露，本仓库自 **1.5.9 之后**加入签名校验：
> 签名校验不通过的包需要您**手动授权**，否则宿主侧代码不会运行。
> 如果您需要使用下游仓库的 root 功能，请仔细校验是否为原仓库分发，或自行下载编译源代码。
>
> 校验的**文件读取在 native 层用裸 syscall 完成**（见下文「native 层」），不经过 libc 的 `open`，
> 因此能挡住「签名杀手」一类把文件读取重定向到原始包的改包工具；签名块、资源哈希、内容签名清单
> 三层校验都基于这份在内存里的字节，不信任可被 hook 的 `PackageManager` 返回值。
>
> 如您确有改包需求，**不建议直接改包**，而是 fork 或下载源代码自行更改编译。

## 下载

- **Release 页（推荐）**：[gitee.com/WorldMargin/SparkFlame/releases](https://gitee.com/WorldMargin/SparkFlame/releases)
  - 按 ABI 分包（`arm64-v8a` / `armeabi-v7a`），release 构建（R8 压缩 + 资源裁剪 + release 签名），
    约 **1.9 MB**，可直接覆盖安装 debug 包、不丢配置。
  - 仓库 HEAD 可能比最新 release 新，新增 API 以仓库里的文档为准。
- 自己构建：

  ```powershell
  .\build_doumcp.ps1 -Variant release   # R8 优化包（app\build\outputs\apk\release\）
  .\build_doumcp.ps1                    # debug 包（默认）
  ```

**自行编译需要改动的文件**（其余源码不用动，包名 / 宿主 / 维护者 / 仓库 / 指纹都从 `BuildConfig` 读）：

| 文件 | 改什么 |
| --- | --- |
| `gradle.properties` | `spark.modulePackage`（同时决定 applicationId 和 provider authority）、`spark.hostPackage`、`spark.maintainer`、`spark.repoUrl`；`spark.signatureSha256` 留空，构建时从 keystore 自动算 |
| `release-signing.properties` + 自己的 `.keystore` | release 签名。两者都不入库，fork 必须自备；缺失时签名闸门与内容签名清单会跳过，只适合本机调试 |
| `local.properties` | 本机 Android SDK 路径（不入库） |
| `app/build.gradle.kts` | 只在发版时改 `versionCode` / `versionName`；内容哈希与签名清单由构建任务自动生成 |
| `docs/manual.md` | 改了功能就同步这里，构建时复制进 App 内文档 |

### native 层（`app/src/main/jni/`）

`libsparkfile.so` 只做一件事：**用裸 `syscall`（`svc #0` 直调 `openat`/`read`/`close`）把 APK 读成字节**，
不经过 libc 的 `open`，从而绕开 xhook 一类的 PLT/GOT hook。签名块解析、内容哈希、清单验签
仍由 Kotlin 在这份内存字节上完成，全程不再调用 `File` / `ZipFile` / `PackageManager` 取签名。

导出符号只有 `JNI_OnLoad`：JNI 方法在运行时用 `RegisterNatives` 动态绑定到 Kotlin 侧固定为 `r0` 的
native 方法（`proguard-rules.pro` 保留该类名与方法名），so 的符号表里看不到「哪个 Java 方法对应哪段
native 代码」。两个 ABI（`arm64-v8a` / `armeabi-v7a`）各自编译一份，因此分包不再字节相同。

**fork / 换机构建须知**：

- ndk-build 不接受**带空格**的 NDK 路径。把 NDK 放在无空格目录（如 `D:\android-ndk\27.2.12479018`），
  并在 `local.properties` 写 `ndk.dir=...`；`app/build.gradle.kts` 的 `ndkVersion` 要与之一致。
- 项目路径含中文时，ndk-build 会把路径打成乱码（报 `APP_BUILD_SCRIPT points to an unknown file`）。
  可用目录 junction 指到纯 ASCII 路径再构建，例如 `D:\spark-build` → 项目目录，然后从这个 ASCII 路径跑 gradlew。
- 若读不到 APK 字节，校验按**不通过**处理，不会降级回 `File` 读取（降级等于给 hook 留后门）。

## 署名与来源

本模块 fork 自开源项目 **[yfishyon/doumcp](https://github.com/yfishyon/doumcp)**（Apache-2.0 许可），
只保留「续火花」这一条链路；**界面、脚本引擎、模板 / 变量 / 宏 / 函数体系、CodeMirror 编辑器接入都是重写的**。

思路上的致谢：

- **yfishyon/doumcp** —— 抖音逆向与注入能力的基础（会话、IM 发送、火花字段等）
- **WorldMargin** —— 模板 / 宏 / 函数 这套组合思路，以及 **DouYinSpark 油猴脚本原作**
- **DeepSeek** —— 把 DouYinSpark 油猴脚本的完整逻辑迁移至安卓

## 功能

- **续火花**：抖音判定「今天还没互动」的会话自动发一条私信；每天每个会话只发一次
  - 「待续」分两种：**待发**（今天还没发）与**待对方回复**（今天发过了、火花还是灰的，不再重复发）
  - 群聊默认不参与自动发送（手动移进「已选目标」才发）；随时可以给选中的会话**手动发送**
  - **跨天值守**：23:30 之后加密检查，抖音开着跨到第二天会自动补一轮，白天几乎零开销
- **模板**：内置模板（空白 / 按时问候 / 火花天数打卡 / 简短提醒）+ 自定义模板；
  一级模板可折叠展开看**派生模板**（继承它的会话）；长按派生项可以「更改模板 / 编辑模板变量」
- **空白模板 = 不用模板、自己写**：和别的模板一样是跟随关系，只是父模板内容是空的，所以发的是会话自己写的那段；
  「克隆」也算继承自空白模板
- **变量（两层）**：模板一套变量，每个会话还可以有**自己的一套**
  - 变量页两张折叠卡片：「继承」（父模板的变量，名字只读、表达式可改，改了就覆盖，带「⟲ 还原」）与「自定义」（本层新增，可增删）
  - 值是 JS 表达式，能读 `ctx`，也能引用排在它前面的变量；表达式为空回落父模板，再没有就是 `null`
  - 标题栏「试算」按当前会话快照跑一遍整套变量
- **宏**：一段独立小脚本，可挂到多个模板或具体会话，在模板之后执行；用完即删，不影响模板内容
  - 脚本里也能动态挂载 / 摘掉：`ctx.system.mountMacro(id, target)` / `unmountMacro` / `unmountAll`，
    `target` 可以是具体会话，也可以是模板（`inherit: false` = 仅模板、不往派生会话传播）
  - **宏自己的变量**：`ctx.macro.getvar / setvar / deletevar`，值按 JSON 存进配置、跨次保留，
    归属就是宏自己；宏编辑页有独立入口可视化改 / 删 / 新增，删宏时连带清掉
- **自定义函数**：全局定义（只允许一个），执行前注入成 `ctx.functions.<名字>`，供模板 / 宏 / 变量调用
- **脚本上下文**（按来源分命名空间）：
  - `ctx.result`：要发的文案列表
  - `ctx.builtin`：日期时间 + 内置函数；`ctx.io`：抖音给的会话数据；`ctx.template.vars`：模板 / 会话变量
  - `ctx.system`：`getAllConversation(filter?)`（filter 传函数逐项判断，标签用 `c.tags.some(...)`）、
    `getConversation/getTemplate/getMacro(id)`、
    `mountMacro / unmountMacro / unmountAll`
  - `ctx.registerCallback(fn)`：会话处理结束后回调 `{ conversationId, result: "Succeed"|"Skipped"|"Failed", reason? }`
  - `ctx.macro`：宏自己的变量；`ctx.functions`：自定义函数；都有类型声明与补全
  - 预览 / 试算只算文案：写变量、挂载宏这类会改数据的调用在预览时自动跳过
- **编辑器**：**CodeMirror 6**（离线内置）—— 输入时自动补全 `ctx.*` 与 JS 内置成员（类型推断、悬浮文档）；
  **粘贴 / 格式化**（清理零宽字符、智能引号、代码围栏并重排缩进）；用真实会话快照**预览**；
  长按走系统原生选区，动作条是图标（长按看名称）
- **标签**：给会话打多个标签（家人 / 同事 / 待续……），只用于整理和筛选，不参与发送
  - 底部「标签」页：新增 / 改名 / 换色 / 删除（删除会从所有会话上摘掉）
  - **归属在标签页管**：点标签卡片 → 会话选择页，勾选这个标签包含哪些会话（确定即替换）
  - 单个会话也能选：会话编辑页顶部直接点标签小药丸开 / 关（不用弹窗）
  - 列表与选择页都有「标签 / 来源」两排标记：标签可多选（并集，选中项背景变色），来源单选；
    按标签筛选时命中越多越靠前；行内色块与 `继承：X` 也能直接点
  - **会话选择页**（选会话继承 / 克隆到会话）独立成页：按昵称 / 备注搜索，按标签（多选）或来源筛选，多选后确定
- **快照与生命周期**：打开抖音、跨天补跑、手动发送之后都会把会话快照写回 App；
  连续 3 次没出现的会话收进「被隐藏」折叠卡片，满 30 天连快照一起删除
- **头像**：模块不申请网络权限，头像由抖音进程从它自己的缓存里读出后推给模块，断网也能显示；
  缓存按 `peerUid` 对应（不按会变的头像 URL），换头像或 URL 参数变化都能正确更新，没缓存的显示名字首字
- **配置按项导出 / 导入**：勾模板自动带上父模板、宏、用到的函数；导入是合并，没勾的部分不动

## 文档

- **[使用文档](docs/manual.md)** —— 安装、界面、模板 / 变量 / 宏 / 函数、脚本 API、编辑器、常见问题。
  App 内「使用文档」读的是同一份（构建时同步），也能在 App 里复制 / 导出。
- 第八节是**「用 AI 写脚本」**：一段可直接贴给 AI 的提示词、写作建议、示例与自检清单。
  把整篇文档丢给 AI，它就能照着这套 API 写模板。

## 构建

需要 JDK 21 + Android SDK（`local.properties` 指向 SDK）+ **Android NDK 27.2.12479018**（native 读文件库，见下文「native 层」）。

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
4. 模板页给会话选模板（或用内置模板一键复制 / 继承），变量在模板 / 会话的「变量」入口里加，
   宏与函数在「宏/函数」页里加

**模板不会自动套用**：没选模板的会话不会发消息；选了「空白模板」就等于不用模板、自己写文案。

## 目录

```
app/src/main/java/com/worldmargin/autospark/
  ModuleMain.kt                 模块入口：Application.attach 拦截、设置页注入、广播注册、热重载
  WakeActivity.kt               透明空页面：模块被系统停用时把进程拉起来（宿主侧唤醒兜底）
  SettingsInjector.kt           抖音设置页注入「续火花」入口
  core/                         宿主 Context / 反射 / 日志（libxposed 通道）/ 提示
    NativeFileReader.kt         native 读 APK 字节的 JNI 入口（注册名 r0）
  host/                         跑在抖音进程
    bridge/                     扫会话写快照、渲染脚本发消息、手动发送、跨天值守、宿主简报
    im/                         会话列表 + 消息发送（IM 能力桥）+ 头像缓存推送
    account/                    当前账号
  app/                          管理界面
    config/                     配置模型 + 编解码 + 模板 / 变量 / 宏 / 函数组装
    script/                     Rhino 沙盒引擎 + 类型声明生成 + 预览数据
    editor/                     WebView + CodeMirror 6 编辑器
    provider/                   跨进程配置通道（ContentProvider）+ 唤醒广播
    ui/                         设置 / 模板 / 会话 / 宏 / 函数 / 变量 / 文档等页面
app/src/main/jni/                native 读文件库（Android.mk / Application.mk / spark_file.c）
app/src/main/assets/cm6/        CodeMirror 6 离线资源（editor.js / ts.js / tslibs.js / completions.js）
app/src/main/assets/docs/       使用文档（构建时从仓库同步）
docs/manual.md                  使用文档唯一入库来源
tools/                          补全表 / tslibs 生成脚本
```

编辑器前端源码在 `tmp/cm6/src/editor.js`（工作目录，不入库），改完要用 esbuild 重新打包进 `assets/cm6/editor.js`。

## 许可

沿用上游的 **Apache License 2.0**（见 `LICENSE`）。二次分发请保留许可与上述署名。
