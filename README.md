# 星火燎原（抖音自动续火花）

一个 LSPosed 模块：注入抖音进程，**自动给「今天还没互动」的会话续火花**（发一条你自己写的文案）。
文案是一段 JavaScript，可以用模板、变量、宏和自定义函数组合出来 —— 目标就是「每天自动续，但话说得像真人说的」。

> **文档**：[`docs/manual.md`](docs/manual.md) —— 使用说明 + 「给 AI 的模块说明书」（发送时序、输出契约、
> `ctx` API、变量合并规则、自检清单、可直接贴给 AI 的提示词）。**要写模板的 AI 请先读这份**。

## 下载

- **Release 页（推荐）**：[gitee.com/WorldMargin/SparkFlame/releases](https://gitee.com/WorldMargin/SparkFlame/releases)
  最新直链：`https://gitee.com/WorldMargin/SparkFlame/releases/download/v1.3.0/app-arm64-v8a-release.apk`
  （arm64-v8a，约 3.2 MB，**release 构建**：R8 压缩 + 资源裁剪 + release 签名，可直接覆盖安装 debug 包、不丢配置）
- 自己构建：
  ```powershell
  .\build_doumcp.ps1 -Variant release   # R8 优化包（app\build\outputs\apk\release\）
  .\build_doumcp.ps1                    # debug 包（默认）
  ```

## 署名与来源

本模块 fork 自开源项目 **[yfishyon/doumcp](https://github.com/yfishyon/doumcp)**（Apache-2.0 许可），
只保留「续火花」这一条链路；**界面、脚本引擎、模板 / 变量 / 宏 / 函数体系、Monaco 编辑器接入都是重写的**。

思路上的致谢：

- **yfishyon/doumcp** —— 抖音逆向与注入能力的基础（会话、IM 发送、火花字段等）
- **WorldMargin** —— 模板 / 宏 / 函数 这套组合思路，以及 **DouYinSpark 油猴脚本原作**
- **DeepSeek** —— 把 DouYinSpark 油猴脚本的完整逻辑迁移至安卓

## 功能

- **续火花**：抖音判定「今天还没互动」的会话自动发一条私信；每天每个会话只发一次

  - 「待续」分两种：**待发**（今天还没发）与**待对方回复**（今天发过了、火花还是灰的，不再重复发）
  - 群聊默认不参与自动发送（手动移进「已选目标」才发）；随时可以给选中的会话**手动发送**
  - **跨天值守**：抖音开着跨到第二天会自动补一轮，白天几乎零开销（23:30 之后才加密检查）

- **模板**：内置模板（空白 / 按时问候 / 火花天数打卡 / 简短提醒）+ 自定义模板；
  一级模板可折叠展开看**派生模板**（继承它的会话）；长按派生项可以「更改模板 / 编辑模板变量」
- **空白模板 = 不用模板、自己写**：和别的模板一样是跟随关系，只是父模板内容是空的，所以发的是会话自己写的那段；
  「克隆」也算继承自空白模板（不再有「内容固定 / 不跟随」状态）
- **变量（两层）**：模板有一套变量，每个会话还可以有**自己的一套**
  - 变量页两张折叠卡片：「继承」（父模板的变量，名字只读、表达式可改，改了就覆盖，带「⟲ 还原」）与「自定义」（本层新增，可增删）
  - 每行左边名字、右边两三行高的小 Monaco，按内容行数自动长高；标题栏「试算」看整套变量的当前值
  - 值是 JS 表达式，能读 `ctx`，也能引用排在它前面的变量；表达式为空回落父模板，再没有就是 `null`
- **宏**：一段独立小脚本，可挂到多个模板或具体会话，在模板之后执行；用完即删，不影响模板内容
  - 脚本里也能动态挂载 / 摘掉：`ctx.system.mountMacro(name, target)` / `unmountMacro` / `unmountAll`，
    `target` 可以是具体会话，也可以是模板（`inherit: false` = 仅模板、不往派生会话传播）
  - **宏自己的变量**：`ctx.macro.getvar / setvar / deletevar`，值按 JSON 存进配置、跨次保留，
    归属就是宏自己；宏编辑页有独立入口可视化改 / 删 / 新增，删宏时连带清掉
- **自定义函数**：全局定义，执行前注入成 `ctx.functions.<名字>`，供模板 / 宏 / 变量调用
- **脚本上下文**（按来源分命名空间）：
  `ctx.builtin`（日期时间 + 内置函数）、`ctx.io`（抖音给的会话数据）、
  `ctx.template.vars`（模板 / 会话变量）、`ctx.template.template` / `.parent`（当前模板：`{id,name}`）、
  `ctx.macro`（宏自己的变量 + `name` + 挂载查询）、
  `ctx.system`（`getAllConversation()`、`getConversation/getTemplate/getMacro(id)`、`mountMacro/unmountMacro/unmountAll`）、
  `ctx.functions`（自定义函数）；都有类型声明与补全
  预览 / 试算只算文案：写变量、挂载宏这类会改数据的调用在预览时自动跳过
- **编辑器**：Monaco（VSCode 同款，离线内置）—— `ctx.*` 与 JS 内置成员的补全、类型检查、
  悬浮文档、格式化、双指缩放字号；长按弹**悬浮图标条**（全选 / 复制 / 粘贴 / 撤销）；也可在设置里切回原生编辑器
- **预览**：用真实会话快照渲染一遍，与实际发送走同一套组装
- **使用文档**：App 内可看 / 可导出；文档里带**可直接贴给 AI 的提示词**，让 AI 照着 API 写脚本
- **配置按项导出 / 导入**：勾模板自动带上父模板、宏、用到的函数；导入是合并，没勾的部分不动
- **数据自动化**：打开抖音、跨天补跑、手动发送之后都会**把会话快照自动写回 App**（不打开 App 也能看到最新数据）

## 文档

- **[使用文档](docs/manual.md)** —— 安装、界面、模板 / 变量 / 宏 / 函数、编辑器、常见问题。
  App 内「使用文档」读的是同一份（构建时同步），也能在 App 里复制 / 导出。
- 文档**最后一节是「给 AI 的模块说明书」**：一次发送的完整时序、输出契约与沙盒限制、
  变量两层怎么合并、怎么写才贴合设计、自检清单，末尾是一段**可直接贴给 AI 的提示词**。
  把整篇文档丢给 AI，它就能照着这个设计写模板。

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
4. 模板页给会话选模板（或用内置模板一键复制 / 继承），变量在模板 / 会话的「变量」入口里加，
   宏与函数在「宏/函数」页里加

**模板不会自动套用**：没选模板的会话不会发消息；选了「空白模板」就等于不用模板、自己写文案。

## 目录

```
app/src/main/java/com/worldmargin/autospark/
  ModuleMain.kt                 模块入口：Application.attach 拦截、设置页注入、广播注册、热重载
  WakeActivity.kt               透明空页面：模块被系统停用时把进程拉起来（Host 侧唤醒兜底）
  core/                         宿主 Context / 反射 / 日志（libxposed 通道）
  features/autospark/
    bridge/                     宿主侧：扫会话写快照、渲染脚本发消息、手动发送、跨天值守、宿主简报
    model/                      配置模型 + 编解码 + 模板 / 变量 / 宏 / 函数组装
    provider/                   跨进程配置通道（ContentProvider）+ 唤醒广播
    script/                     Rhino 沙盒引擎 + 模板变量前导 + Monaco 用的类型声明生成
    store/                      配置读写（带缓存）
    ui/                         管理界面（纯代码 View）：主界面 / 模板 / 变量 / 会话 / 宏 / 函数 / 文档 / 编辑器抽象
  features/im/, features/account/  上游保留的会话与账号能力
app/src/main/assets/monaco/     Monaco 0.52.2 裁剪版（离线，约 9.7 MB）
app/src/main/assets/fonts/      VSCode codicon 图标字体（@vscode/codicons 0.0.46-24）
app/src/main/assets/docs/       使用文档（构建时从仓库同步）
```

## 许可

沿用上游的 **Apache License 2.0**（见 `LICENSE`）。二次分发请保留许可与上述署名。
