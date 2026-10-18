# 星火燎原 · 使用文档

抖音自动续火花（LSPosed 模块）。**第五节是「用 AI 写文案脚本」的完整提示词，整段复制给 AI 即可**；第六节起是脚本 API 参考。

---

## 一、安装与开启

1. 安装 APK，在 LSPosed 里勾选模块、作用域选「抖音」，然后**重启抖音**。
2. 打开模块界面：桌面图标「星火燎原」，或 **抖音 → 我 → 设置 → 续火花**。
   抖音里点这个入口会弹两个选项：
   - **立即续火花（就地发送）**：不跳出抖音，直接给「已选目标」发一轮，结果用提示告诉你；
   - **跳转 APP（打开管理界面）**：打开模块界面 —— 这一条是跨应用跳转，抖音会再弹一次「是否跳转」，
     **3 秒内点「继续」**才进得去。
3. 到 **设置** 页打开 **总开关** —— 打开后才会自动发送，默认是关闭的。
4. 到 **设置** 页点一次 **刷新会话**，让抖音把会话列表、头像、火花天数写进模块。

> **抖音没在运行时会怎样**：模块和抖音是两个进程，续火花、刷新会话这些指令都要抖音进程来执行。
> 所以界面在发送/刷新前会先「探活」（握手）：抖音不在就不发指令，而是提示你
> **先打开抖音**（可以直接点「打开抖音」），打开并等它就绪后会自动把刚才那件事做完。

## 二、界面结构

底部四个标签：

| 标签 | 内容 |
| --- | --- |
| **续火花** | 内分「已选目标 / 未选目标」两页，每页里再按 **单聊 / 群聊** 分成两张卡片，**点标题即可折叠/展开**（带过渡动画）。顶部有**搜索框**，按昵称 / 备注过滤，匹配不到的整组卡片会自动收起来。上排是待发 / 待对方回复 / 待重燃的会话，下排是今日已续或无火花的会话；`＋` 把会话移到「已选目标」，`－` 移出去。点会话行进入单会话文案编辑 |
| **模板** | 顶部可**搜索**模板名 / 内容。一级模板（内置 + 自定义）都可折叠展开，展开后显示它的**派生模板**（继承它的会话）；派生项**点一下**进对应会话 / 模板的编辑页，**长按**可以选「更改模板」或「编辑模板变量」。内置模板默认折叠。保存父模板时，如果有的派生模板**改过内容又没开跟随更新**，会先把它们列出来问你要不要覆盖 |
| **宏/函数** | 顶部可**搜索**宏 / 函数名与内容。内分「宏 / 函数」两页：宏在模板脚本之后执行，函数执行前注入成 `ctx.functions.<名字>` |
| **导出 / 导入配置** | 在「设置 → 配置」里进二级页。导出可**逐项勾选**（模板 / 会话文案 / 宏 / 函数），快照与今日已发记录整类开关；**勾模板会自动带上它的父模板**、挂在上面的**宏**、以及脚本里 `ctx.functions.名字` 用到的**函数**（自动勾的宏 / 函数还能再手动取消）。导入是**合并**：先选文件，再挑要合并进来的条目，没勾的部分一个字都不动 |
| **设置** | 只放开关键开关与入口：**总开关**、刷新会话、使用文档、复制全文，以及「更多」里的**概览 / 手动归属 / 编辑器 / 宿主状态 / 关于**（点进去是二级页面，顶部返回图标回上一级） |

## 三、什么时候会发

- 抖音启动后约 **30 秒**开始扫描（给 IM 初始化留时间），数据没好会自动重试；
- 每天每个会话**只发一次**；总开关关着时只扫描不发送；
- 发送对象 = 「已选目标」= 抖音判定**今天还没互动**的会话（待发 / 待对方回复 / 待重燃），手动归属优先；
- **待发 vs 待对方回复**：今天还没发的叫「待发」；我们已经发了、对方还没回的叫「待对方回复」
  （抖音还算「今天没互动」，但今天不会再重复发）。概览页两个数字分开算
- **群聊默认不发**：群聊在界面里单独一张卡片，只有你把它**手动移进「已选目标」**（或勾选后点「发送勾选」）才会发；
- **没写文案的会话不发**；空白模板（= 不用模板、自己写）照常发你写的那段。
- 抖音启动、跨天补跑、手动刷新之后，**快照会自动写回模块 App**（不用打开 App 也能看到最新数据）。

## 四、模板、继承与保留

- **空白模板不是「不发」**：它就是一个内容为空的普通模板 —— 会话跟随它、出现在它的「派生」列表里，只是没有模板文案，所以发的是会话自己写的那段。内置文案不会自动套用。
- **内置模板**（只是可选起点）：空白模板 / 按时问候 / 火花天数打卡 / 简短提醒。点开可以
  `添加为我的模板`（复制成可编辑的模板）、`选会话继承`、`克隆到会话`。
- **继承 vs 克隆**：
  - **选会话继承** = 跟随该模板：父模板内容改了可以同步过来（编辑页勾「自动同步」就是自动跟随）；
  - **克隆到会话** = 只把当前内容复制过去，之后**跟随空白模板**（不再跟原模板，也算继承自空白）。
- **改某个会话的来源**：点会话行 → 「继承来源」右边的 **更改** → 进**二级页面**按分类挑：
  空白模板（不用模板，自己写）/ 内置模板 / 自定义模板 / 内容固定的会话模板（克隆）。
  从别的模板切到空白模板时，**已写的文案会保留**，不会被清空；克隆来的会话也算继承自空白模板。
  一级模板行左边的箭头可以展开它的**派生模板**（已经继承它的会话）；点整行就是选中该来源。
- **派生自某个派生模板**（例如 A → B，C 想用 B 的文案）：
  C **挂在 A 上**（以后跟 A 同步），**内容复制 B 的当前内容**。
- **保留策略**：会话被移出「已选目标」后，它的模板与发送记录从移出那天起保留 **30 天**，
  到期清理；从没被选中过的立即清理。会话快照（昵称/头像/火花）不受影响。

## 五、用 AI 写文案脚本（整段贴给 AI）

> 复制下面「提示词」整段发给任意 AI（DeepSeek / ChatGPT / Claude 都行），把最后一行的需求换成你自己的，它就会给你一段可直接粘进模块的脚本。脚本 API 的完整参考在本节之后，AI 需要时也可以把第六、七节一起发给它。

### 5.1 提示词

```text
你是「星火燎原」抖音自动续火花模块的文案脚本作者。请只输出 JavaScript 代码，不要解释、不要 Markdown 代码块以外的文字。

【运行环境】
- JavaScript（Rhino 沙盒）：没有 DOM、没有网络、不能用任何 Java 类；单次执行限时 2 秒。
- 脚本里可以读变量、调用函数，全部挂在 ctx 上：内置能力在 ctx.builtin、抖音给的数据在 ctx.io、模板变量在 ctx.template、自定义函数在 ctx.functions。
- 发送内容放进固定变量 $spark_result$。它**永远是列表**：引擎在执行脚本前已初始化成 []，用 $spark_result$.push("每行内容") 逐行添加，一行一条。
- 不要给 $spark_result$ 赋字符串（`= "…"`）；引擎虽然兼容，但统一用 push，类型和补全都更准。
- $spark_result$ 为空列表 = 这条不发。除了它，脚本不需要 print / return。

【可用变量】
ctx.builtin.date        字符串  当天日期，yyyy-MM-dd
ctx.builtin.time        字符串  当前时间，HH:mm
ctx.builtin.weekday     字符串  星期几
ctx.builtin.timestamp   数字    当前毫秒时间戳
ctx.io.nickname         字符串  对方昵称
ctx.io.remark           字符串  备注名（可能为空）
ctx.io.name             字符串  显示名（备注优先，其次昵称）
ctx.io.days             数字    已连续聊天天数
ctx.io.state            数字    火花状态码：1=今天已互动 2=今天还没互动 3=待重燃
ctx.io.stateText        字符串  火花状态文本
ctx.io.peerUid          字符串  对方 UID
ctx.io.conversationId   字符串  会话 ID
ctx.template.vars.<名字>  任意    模板 / 会话变量（没定义就是 null）

【可用函数】
ctx.builtin.pick(list)               从数组里随机取一个
ctx.builtin.pad(n)                   数字补零到两位
ctx.builtin.repeat(s, n)             把字符串 s 重复 n 次
ctx.builtin.random(a, b)             [a, b) 区间内的随机整数
ctx.builtin.shuffle(list)            洗牌，返回新数组
ctx.builtin.countdown(target)        距目标日期还有几天（已过为负），target 形如 "2027-01-01"
ctx.builtin.nextNewYear()            下一个元旦的日期字符串
ctx.builtin.untilNewYear()           距下一个元旦还有几天
ctx.builtin.formatDate(date, pattern) 按 yyyy/MM/dd/HH/mm/ss 格式化日期

【写作要求】
- 文案要像真人发的：口语、简短、可以带 emoji，不要像系统通知。
- 不要每天一模一样：需要变化的地方用 ctx.builtin.pick([...]) 准备 3 个以上备选。
- $spark_result$ 是列表：多行内容就 push 多次，引擎按行拼成一条消息发送。

【我的需求】
（在这里写你的需求，例如：给每个人写一句带火花天数的早安，周五要提一句明天周末）
```

### 5.2 示例：AI 应该产出什么

按时间问候 + 火花天数：

```js
var 谁 = ctx.io.name || "朋友";
var 招呼 = ctx.builtin.pick(["夜深了", "早安", "午安", "下午好", "晚上好"]);
$spark_result$.push(招呼 + "，" + 谁 + "！第 " + ctx.io.days + " 天的火花，别忘了续上 🔥");
```

节日倒计时（配合宏使用）：

```js
var 到元旦 = ctx.builtin.countdown("2027-01-01");
$spark_result$.push(ctx.io.name + "，距离元旦还有 " + 到元旦 + " 天，先续个火花 ✨");
```

列表多行：

```js
$spark_result$.push(ctx.io.name + "，今天聊两句？");
$spark_result$.push("火花已经 " + ctx.io.days + " 天啦，别断了 🔥");
```

## 六、脚本 API 参考

### 6.1 上下文结构总表

脚本能见的东西全在这里（「预览」列：✅ = 正常生效，⚠️ = 预览 / 试算时**不生效**）。

| 路径 | 类型 | 说明 | 预览 |
| --- | --- | --- | --- |
| `$spark_result$` | string[] | 要发的文案，逐行 push；空列表 = 不发 | ✅ |
| `ctx.builtin.date` | string | 当天日期 `yyyy-MM-dd` | ✅ |
| `ctx.builtin.time` | string | 当前时间 `HH:mm` | ✅ |
| `ctx.builtin.weekday` | string | 星期几 | ✅ |
| `ctx.builtin.timestamp` | number | 当前毫秒时间戳 | ✅ |
| `ctx.builtin.pick(list)` | any | 从数组里随机取一个 | ✅ |
| `ctx.builtin.pad(n)` | string | 数字补零到两位 | ✅ |
| `ctx.builtin.repeat(s, n)` | string | 把字符串 s 重复 n 次 | ✅ |
| `ctx.builtin.random(a, b)` | number | `[a, b)` 区间内的随机整数 | ✅ |
| `ctx.builtin.shuffle(list)` | any[] | 洗牌，返回新数组 | ✅ |
| `ctx.builtin.countdown(target)` | number | 距目标日期还有几天（已过为负） | ✅ |
| `ctx.builtin.nextNewYear()` | string | 下一个元旦的日期字符串 | ✅ |
| `ctx.builtin.untilNewYear()` | number | 距下一个元旦还有几天 | ✅ |
| `ctx.builtin.formatDate(date, pattern)` | string | 按 `yyyy/MM/dd/HH/mm/ss` 格式化日期 | ✅ |
| `ctx.io.nickname` | string | 对方昵称 | ✅ |
| `ctx.io.remark` | string | 备注名（可能为空） | ✅ |
| `ctx.io.name` | string | 显示名（备注优先，其次昵称） | ✅ |
| `ctx.io.days` | number | 已连续聊天天数 | ✅ |
| `ctx.io.state` | number | 火花状态码（1 今天已互动 / 2 今天还没互动 / 3 待重燃） | ✅ |
| `ctx.io.stateText` | string | 火花状态文本 | ✅ |
| `ctx.io.peerUid` | string | 对方 UID | ✅ |
| `ctx.io.conversationId` | string | 会话 ID（挂载宏 / 定位会话用） | ✅ |
| `ctx.template.vars.<名字>` | any | 模板 / 会话变量（合并后）；没定义就是 `null` | ✅ |
| `ctx.template.template` | object | 当前模板：`{ id, name }` | ✅ |
| `ctx.template.parent` | object | 父模板：`{ id, name, childs: [{id,name}] }` | ✅ |
| `ctx.system.getConversation(id)` | object | 按会话 id 取信息：`{id,name,remark,days,state,stateText,isGroup,template,parent,macros}` | ✅ |
| `ctx.system.getTemplate(id)` | object | 按模板 id 取信息：`{id,name,body,childs,isBuiltin}` | ✅ |
| `ctx.system.getMacro(id)` | object | 按宏 id 取信息：`{id,name,body,templates,conversations}` | ✅ |
| `ctx.macro.id` | string | 当前宏的 id（模板段为空串） | ✅ |
| `ctx.macro.name` | string | 当前宏的名字（模板段为空串） | ✅ |
| `ctx.macro.getvar(name)` | any | 读自己的一个变量 | ✅ |
| `ctx.macro.setvar(name, value)` | void | 写一个变量（值按 JSON 存） | ⚠️ |
| `ctx.macro.deletevar(name)` | boolean | 删一个自己创建的变量 | ⚠️ |
| `ctx.system.getAllConversation()` | array | 本轮会话数组 `{id, name, remark, days, state, stateText, isGroup}` | ✅ |
| `ctx.system.mountMacro(id, target?)` | void | 按宏 id 挂到指定会话 / 模板 | ⚠️ |
| `ctx.system.unmountMacro(id, target?)` | void | 按宏 id 从指定目标摘掉 | ⚠️ |
| `ctx.system.unmountAll(target?)` | void | 摘掉目标上所有宏 | ⚠️ |
| `ctx.functions.<名字>(...)` | any | 自定义函数（「宏/函数」页里定义） | ✅ |

> 预览 / 试算只算文案，**不会改任何数据**：`setvar` / `deletevar` / `mountMacro` / `unmountMacro` / `unmountAll`
> 在预览时直接跳过（不报错、也不写盘），只有真正发送那一轮才会生效。

### 6.2 输出与契约

- 语言：JavaScript（Rhino 沙盒，无 Java / DOM / 网络，单次 2 秒超时）。
- **输出变量 `$spark_result$`（`string[]`）**：引擎在脚本执行前初始化为**空列表** `[]`，用 `$spark_result$.push("每行内容")` 逐行添加。
  - **发送时按行拼接**成一条消息；**空列表 = 这条不发**。
  - 兼容写法：`$spark_result$ = "早安"` 也能发（引擎会当成一行），但**推荐只用 push** —— 类型声明是 `string[]`，写字符串赋值在编辑器里会被标黄。
  - 每个**宏**执行前，引擎都会把结果变量归一成列表（字符串自动包成 `[字符串]`），所以宏里可以放心 `.push()`，不用判断类型。
  - 自己声明 `var / let / const $spark_result$` 也不会冲突（引擎用的是隐式全局赋值）。
- 结果是**列表**时按行拼接成一条消息；**空**表示这条不发。
- 脚本里不要做重活（2 秒上限，超时按失败处理）。
- 最后一句**不写分号也行**（引擎已经处理了自动分号的坑，以 `)` / `]` 结尾不会再报
  `N is not a function`），但建议还是写上，以后把脚本贴到别的地方跑也不会出问题。

### 6.3 变量

上下文按**来源**分几个命名空间：`ctx.builtin`（内置能力，引擎自己算的）、
`ctx.io`（抖音给的数据）、`ctx.template`（模板 / 会话变量）、`ctx.functions`（自定义函数）。

**`ctx.builtin` —— 内置变量**

| 变量 | 类型 | 说明 |
| --- | --- | --- |
| `ctx.builtin.date` | string | 当天日期 `yyyy-MM-dd` |
| `ctx.builtin.time` | string | 当前时间 `HH:mm` |
| `ctx.builtin.weekday` | string | 星期几 |
| `ctx.builtin.timestamp` | number | 当前毫秒时间戳 |

**`ctx.io` —— 从抖音取到的数据**

| 变量 | 类型 | 说明 |
| --- | --- | --- |
| `ctx.io.nickname` | string | 对方昵称 |
| `ctx.io.remark` | string | 备注名（可能为空） |
| `ctx.io.name` | string | 显示名（备注优先，其次昵称） |
| `ctx.io.days` | number | 已连续聊天天数 |
| `ctx.io.state` | number | 火花状态码（1 今天已互动 / 2 今天还没互动 / 3 待重燃） |
| `ctx.io.stateText` | string | 火花状态文本 |
| `ctx.io.peerUid` | string | 对方 UID |
| `ctx.io.conversationId` | string | 会话 ID |

### 6.4 模板变量与固定键

自己定义的变量（模板层 + 会话层合并后）都在 **`ctx.template.vars`** 下：`ctx.template.vars.名字`。
下面这几个是固定键，不会被变量名占用：

| 名字 | 类型 | 说明 |
| --- | --- | --- |
| `ctx.template.template` | object | 当前模板：`{ id, name }` |
| `ctx.template.parent` | object | 父模板：`{ id, name, childs }`；`childs` 是派生列表 `[{id,name}]` |

### 6.5 宏自己的变量（`ctx.macro`，只给宏用）

宏可以用 `ctx.macro` 读写**跨次运行保留**的数据，值按 JSON 存进配置（`sandbox` 段），归属就是宏自己（谁创建谁管理）。
`ctx.macro` 在模板段也存在，但**只有宏段能读写**：模板里调 `getvar` / `setvar` / `deletevar` 会报
「ctx.macro 的读写只能在宏脚本里用」。

| 调用 | 说明 |
| --- | --- |
| `ctx.macro.getvar(name)` | 读一个键；没有就是 `undefined` |
| `ctx.macro.setvar(name, value)` | 写一个键；`value` 可以是对象 / 数组 / 数字 / 字符串，存成 JSON |
| `ctx.macro.deletevar(name)` | 删一个键（只能删自己创建的） |

键空间按宏隔离：另一个宏读不到这个宏的键；删掉宏时它名下的键也一起清掉。

```js
// 宏里：记录这个宏跑过几次
var n = (ctx.macro.getvar("runs") || 0) + 1;
ctx.macro.setvar("runs", n);
$spark_result$.push("（第 " + n + " 次加这句）");
```

### 6.5.1 宏的挂载信息

当前宏的 id 在 `ctx.macro.id`，挂载情况用 `ctx.system.getMacro(ctx.macro.id)` 取：

```js
var me = ctx.system.getMacro(ctx.macro.id);   // { id, name, body, templates:[id], conversations:[id] }
```

### 6.6 系统接口（`ctx.system`）

| 调用 | 说明 |
| --- | --- |
| `ctx.system.getAllConversation()` | 本轮可见的会话数组：`{id, name, remark, days, state, stateText, isGroup}` |
| `ctx.system.getConversation(id)` | 按会话 id 取：`{id,name,remark,days,state,stateText,isGroup,template:{id,name,body},parent:{id,name,body},macros:[{id,name}]}` |
| `ctx.system.getTemplate(id)` | 按模板 id 取：`{id,name,body,childs:[{id,name}],isBuiltin}` |
| `ctx.system.getMacro(id)` | 按宏 id 取：`{id,name,body,templates:[id],conversations:[id]}` |
| `ctx.system.mountMacro(id, target?)` | 按宏 **id** 挂到指定目标（不传 = 当前会话） |
| `ctx.system.unmountMacro(id, target?)` | 按宏 **id** 从指定目标摘掉 |
| `ctx.system.unmountAll(target?)` | 把指定目标上的所有宏都摘掉 |

`target` 用对象传：

```js
// 第一个参数是宏的 id（宏编辑页里可复制，脚本里也能用 ctx.macro.id / getMacro(id).id）
ctx.system.mountMacro("宏 id", { conversation: "会话 id" });           // 挂到具体会话
ctx.system.mountMacro("宏 id", { template: "模板 id" });             // 挂到模板，跟着派生会话一起跑
ctx.system.mountMacro("宏 id", { template: "模板 id", inherit: false });   // 仅模板：不往派生会话传播
ctx.system.unmountAll();                                          // 把当前会话上的宏全摘掉
```

「仅模板」只在该模板自己的试算 / 预览里跑，派生会话不受影响。

会话列表来自本轮扫描的结果，不额外请求网络；宏挂载在这一轮跑完后统一写回配置，**下一轮生效**。
手动管理：宏编辑页里的「沙盒变量」一行点进去，可改名 / 改 JSON 值 / 删除 / 新增（单独一页，不和脚本编辑器挤在一起）。

### 6.7 内置函数（`ctx.builtin`）

| 调用 | 返回 | 说明 |
| --- | --- | --- |
| `ctx.builtin.pick(list)` | any | 从数组里随机取一个 |
| `ctx.builtin.pad(n)` | string | 数字补零到两位 |
| `ctx.builtin.repeat(s, n)` | string | 把字符串 s 重复 n 次 |
| `ctx.builtin.random(a, b)` | number | `[a, b)` 区间内的随机整数 |
| `ctx.builtin.shuffle(list)` | any[] | 洗牌，返回新数组 |
| `ctx.builtin.countdown(target)` | number | 距目标日期还有几天（已过为负） |
| `ctx.builtin.nextNewYear()` | string | 下一个元旦的日期字符串 |
| `ctx.builtin.untilNewYear()` | number | 距下一个元旦还有几天 |
| `ctx.builtin.formatDate(date, pattern)` | string | 按 `yyyy/MM/dd/HH/mm/ss` 格式化日期 |
| `ctx.template.vars.名字` | 模板变量（见下方「模板变量」）。值是 JS 表达式，未定义则取父模板的值 |

### 6.8 自定义函数

在 **宏/函数 → 函数** 页里**直接写完整函数声明**（只允许一个函数），保存时自动解析出函数名与形参，
注入成 `ctx.functions.<名字>`：

```js
// 函数页里就写这一段（原样，不要只写内部）
function daysTo(date) {
  const target = new Date(date + "T00:00:00");
  return Math.ceil((target - new Date()) / 86400000);
}
```

```js
// 定义好之后，模板/宏里这样调用
$spark_result$.push(ctx.io.name + "，距离元旦还有 " + ctx.functions.daysTo("2027-01-01") + " 天");
```

规则：

- **只能有一个函数**：声明之后再写第二个函数或别的语句，保存时会报错并告诉你是哪里不对；
- 名字与形参由声明决定，不用另外填；说明（可选）会出现在编辑器的补全提示里；
- 早期版本只存了「函数内部」，打开会自动补成完整声明，可以继续编辑。

自定义函数是全局的，和模板一样对所有会话可见；只在这个模块里生效，不会影响抖音。

## 七、宏

**宏 = 在模板脚本之后执行的独立小脚本**（结果变量在宏执行前已归一成列表，直接 `.push()` 即可），适合节日倒计时这类**临时代码**：用完把宏删掉，模板内容一个字都不动。

- 一个宏可以挂到**多个模板或具体会话**，一个模板也可以挂多个宏；
- 执行顺序：`自定义函数注入 → 模板脚本 → 宏（按顺序）`，所以宏可以改写模板已经算好的文案：

```js
// 宏里直接 push 就行：执行宏之前引擎已经保证 $spark_result$ 是列表
$spark_result$.push("（元旦还有 " + ctx.builtin.countdown("2027-01-01") + " 天）");
```

- 入口：**宏/函数 → 宏 → ＋ 新增宏**，或模板编辑页 / 会话编辑页的「宏」按钮。

## 八、编辑器

- **Monaco（默认）**：与 VSCode 同款编辑器，补全 `ctx.*`、自定义函数，以及 JavaScript 内置对象的成员（`Math.ceil`、`"abc".split`、`arr.push` …），并带类型检查与悬浮文档。
- **原生编辑器**：设置 → 编辑器 → 关掉 Monaco。WebView 里的输入法在个别机型上不够听话（键盘不弹、点不进光标），
  这种情况直接用原生编辑器，输入体验和系统一致。
- 「补全」按钮：手动唤出候选列表。
- **编辑器动作条用图标**（省地方）：`▷ 预览` / `📋 粘贴` / `💡 补全` / `🪄 格式化` /
  `🧊 挂载宏` / `↺ 重置` / `🗑 删除`；认不出就**长按**，会弹一次名称。
- **编辑器之外一律「图标 + 文字」**（设置页、配置页、模板卡片、文档页等空间够，不用只留图标）。
- 「粘贴」按钮：把剪贴板内容插到光标处 —— 编辑器里长按不再弹系统的选择 / 复制菜单（那会和编辑器自己的选区打架），
  所以从 AI、网页复制脚本来粘贴请走这个按钮。
- 「格式化」：清掉从 AI/网页复制带进来的零宽字符、不换行空格、智能引号、Markdown 代码围栏，
  再重排缩进（4 空格），粘进来的代码一眼就能看。
- **双指缩放**：编辑区里两指捏合改字号（10–26），记住上次的值；长行**不自动换行**，横向滚动查看。
- **键盘只在单击时弹出**：滑动（横向翻代码 / 纵向翻页）、长按都不会把键盘带出来；
  键盘已经弹出后再滑动也会自动收起，方便你先看代码再决定改哪里。
- 「预览」：用真实会话快照（昵称、天数、状态）渲染一遍，和实际发送走同一套组装（函数 → 模板 → 宏）。

## 九、常见问题

**Q：列表里全是「无名」？**
A：抖音还没把会话数据准备好。到设置页点「刷新会话」，或重启抖音后等 30 秒再刷新。

**Q：点了抖音设置页的「续火花」没反应？**
A：抖音会拦截外部跳转并弹一个确认框，**3 秒内点「继续」**才会打开；也可以用桌面图标进。

**Q：设置页「宿主状态」一直空着？**
A：说明模块没被注入抖音 —— 检查 LSPosed 里模块是否启用、作用域是否勾了「抖音」，然后重启抖音。

**Q：为什么有的会话不发？**
A：三种情况：① 它还没写文案（定了空白模板但表格为空）；② 抖音判定今天已经互动过；③ 总开关没开。

**Q：头像/备注不更新？**
A：头像与备注来自抖音的会话数据，重启抖音后点「刷新会话」即可刷新。


### 模板变量

变量**分两层**：模板（一级模板）有一套，每个会话还可以有**自己的一套**。脚本里都用
`ctx.template.vars.名字` 取值。

- **入口**：模板编辑页的「变量」入口 → 该模板的变量；会话那一层有两个入口 ——
  模板页**长按派生项**选「编辑模板变量」，或会话编辑页动作条上的「变量」按钮
- **两张卡片**：「继承」是父模板定义的（会话页才有），「自定义」是本层新增的
- **一行一个变量**：左边是名字，右边是 JS 表达式（Monaco 小输入框，JS 高亮、
  `ctx.` 与其它变量的补全）。框默认二三行高，写不下了会自己长高
- **继承的可以改、不能删**：改继承项的表达式就等于在这一层**覆盖**它（父模板不动），
  旁边会出现「⟲ 还原」把这一项恢复成父模板的值；想彻底不用它就改回父模板的值
- **值是 JS 表达式**（不是固定字符串）：能读完整的 `ctx`，例如
  `ctx.io.days > 100 ? "老朋友" : "朋友"`、`"宝" + (ctx.io.days % 2 ? "❤" : "")`
- **按上下顺序求值**：下面的表达式可以引用上面已经算出来的变量，
  如 `语气 = ctx.template.vars.称呼 + "，晚上好"`
- **会话的变量排在父模板之后**：会话没定义的用父模板的；**同名时以会话的为准**，
  父模板那份不动、也不影响别的会话
- **表达式为「空」= 没填**：会回落到父模板的同名变量；父模板也没有就是 `null`
- **出错不中断**：某条表达式写错（语法错 / 运行时报错），只让 `ctx.template.vars.该名字` 为 `null`，
  脚本照常执行
- **「试算」**（标题栏）：按当前会话快照把生效的整套变量跑一遍，列出「名字 = 值」，
  只算不发；算不出来的显示 `null`
- **变量名建议用字母 / 数字 / 下划线**：不是合法标识符（比如带空格或中文）时仍能保存，
  但脚本里只能写成 `ctx.template.vars["名字"]`，也补全不出来
- **空名字 / 重名**：保存时没写名字的行忽略、重名的只留最后一行
- 变量随模板 / 会话一起导出 / 导入，不用单独勾选

## 十、数据与文件位置

- 配置与快照：`/data/data/com.worldmargin.autospark/files/spark_config.json`（单文件，跨进程共享）
- 发送记录：配置里的 `sentDays`（每个会话记录最后一次发送日期，每天只发一次）
- 卸载模块 App 会一并清掉配置（抖音里的登录态不受影响）

## 十一、给 AI 的模块说明书

> 这一节写给 AI 看，也写给想弄清机制的人看：把**整篇文档**贴给 AI，它会先明白模块怎么工作，再动手写模板。
> 只想要提示词的，直接跳到 **11.9**。

### 11.1 一句话

LSPosed 模块注入抖音进程，按你配置的**模板 / 变量 / 宏 / 函数**跑一段 JavaScript，把结果拼成一条私信，
在「抖音判定今天还没互动」的会话里自动发出去 —— 每天每个会话最多一条。

### 11.2 一次发送的完整时序

```
抖音启动 / 跨天值守 / 手动点「发送」
  └─ 扫会话（宿主 IM）→ 生成快照：昵称、备注、天数、火花状态、会话 ID、是否群聊
      └─ 对每个会话：挑模板（会话自己的 > 继承的父模板）
          ├─ 合并变量：父模板变量 → 会话变量（同名覆盖、空值回落、都没有 = null）
          ├─ 注入自定义函数：ctx.functions.<名字>
          ├─ 执行模板脚本（Rhino 沙盒，2 秒上限）
          ├─ 依次执行挂上的宏（每次执行前结果变量都归一成列表）
          └─ $spark_result$（字符串列表）→ 逐行拼接成一条消息
              └─ 空列表 = 这条不发；今天已发过 = 跳过；模板是空的 = 跳过
                  └─ 发送成功后记录哪天发过
```

要点：

- **模板脚本只负责产出文案**：称呼、语气这些"人设"放变量，别写死在模板里 —— 换人设只改变量。
- **一条消息 ≠ 多条**：`$spark_result$` 里的多行会被拼成**一条**消息。
- **每天每会话只发一次**，手动发送可以强制再发。
- 变量、宏、函数都是发送前现场组装，不参与网络请求、不缓存上次结果。

### 11.3 数据模型（和脚本有关的那些）

| 概念 | 说明 | 脚本里怎么用 |
| --- | --- | --- |
| 一级模板 | 一段 JS，可被多个会话继承 | 直接就是脚本 |
| 派生模板（会话模板） | 会话自己的模板，可继承一级模板，也可克隆成独立一份 | 继承时跟随父模板；克隆是复制，之后各改各的 |
| 空白模板 | 内容为空的模板，等价于「不用模板、自己写」，照常发送 | 脚本为空就没产出 → 不发 |
| 模板变量 | 一级模板层的 `名字 → JS 表达式` | `ctx.template.vars.名字` |
| 会话变量 | 会话层的变量，覆盖同名模板变量 | 同上（已合并，脚本不用区分） |
| 宏 | 挂在模板或会话上的小脚本，模板之后执行 | 直接 `$spark_result$.push(...)` |
| 函数 | 全局函数声明（只允许一个），注入成 `ctx.functions.<名>` | `ctx.functions.名字(实参)` |

变量求值顺序：**父模板的按声明顺序 → 会话的按声明顺序**，后面的可以引用前面已算出的
（`ctx.template.vars.已算出的名字`）；同名时后者覆盖前者。

### 11.4 契约（写错这里最容易「发了但内容不对」）

- **输出**：`$spark_result$` 是**字符串列表**，执行前初始化为 `[]`。
  推荐 `$spark_result$.push("...")`；`$spark_result$ = "..."` 也兼容（当成一行）。**空列表 = 不发**。
- **语言与限制**：Rhino 沙盒（引擎 1.7.15），**没有** Java / DOM / 网络 / 文件 / 定时器；
  **单次 2 秒超时**，超时按失败处理。内置模板统一用 ES5 写法（`var` + 字符串拼接），照这个风格写最稳。
- **可用**：`ctx`（见第六节）、`Math`、`JSON`、`Date`、`Array` / `String` 常规方法、`parseInt / parseFloat / isNaN`。
- **别做**：死循环、几千层递归、假设有 `fetch` / `require` / `localStorage`。
- **容错**：变量表达式报错只让那个变量是 `null`，不中断；**模板脚本本身报错 = 这条发送失败**。

### 11.5 什么时候会发、什么时候不发

会发：会话被判定今天还没互动（`ctx.io.state` 为 `2` 或 `3`），且（有模板 或 手动选中），且今天没发过。

不发：

1. 模板脚本产出空列表（最常见：定了空白模板但没写内容）；
2. 今天已经发过 —— 手动发送可以忽略这条；
3. 总开关关闭（自动触发时）、未登录、会话列表没取到；
4. 群聊默认不参与自动发送（手动移进「已选目标」才发）。

### 11.6 怎么写才「贴合设计」

1. **正文只写正文**：`ctx.io.name + "，"` 这类前缀属于变量（`称呼`），语气属于变量（`语气`），模板只把句子说出来。
2. **多准备池子**：问候语、话题、表情用数组 + `ctx.builtin.pick()` 抽，避免每天一模一样。
3. **临时代码用宏**：节日倒计时、一次性祝福放宏里，用完删掉，模板一个字不动。
4. **能算的别写死**：天数用 `ctx.io.days`、日期用 `ctx.builtin.date` / `ctx.builtin.formatDate`、倒计时用 `ctx.builtin.countdown`。
5. **兜底**：`ctx.io.days` 可能是 0、`ctx.io.remark` 可能为空、变量可能是 `null` —— 用 `||` 或三元给默认值。
6. **控制在 1~2 行**：拼成一条消息，太长不像真人。
7. **别重复系统能力**：去重、每天只发一次已经做了，脚本里不用再判断今天发没发。

### 11.7 写完自检

- [ ] 语法是 ES5（`var`、字符串拼接），没有依赖箭头函数 / 模板字符串
- [ ] 只用 `ctx.*` 和 `Math / JSON / Date / Array / String`，没有网络 / DOM
- [ ] push 的最终都是字符串（数字先 `String(x)` 或 `+ ""`）
- [ ] `ctx.io.name`、`ctx.io.days`、变量都有兜底（不会输出 `undefined` / `null`）
- [ ] 不会超过 2 秒（没有大循环）
- [ ] 至少一条路径会 push（否则这条永远不发）
- [ ] 变量表达式只引用排在它前面的变量

### 11.8 例子：变量 + 模板 + 宏

**变量（模板层）**

| 名字 | 表达式 |
| --- | --- |
| `称呼` | `ctx.io.nickname.slice(0, 2)` |
| `语气` | `ctx.io.days > 100 ? "老朋友" : "朋友"` |
| `尾巴` | `ctx.builtin.pick(["✨", "🔥", "🌙"])` |

**模板**

```js
var hour = parseInt(ctx.builtin.time.split(":")[0], 10);
var hello =
  hour < 5 ? "夜深了" :
  hour < 11 ? "早" :
  hour < 13 ? "中午好" :
  hour < 18 ? "下午好" : "晚上好";

$spark_result$.push(ctx.template.vars.称呼 + "，" + hello + "，今天也要好好吃饭" + ctx.template.vars.尾巴);
$spark_result$.push(ctx.template.vars.语气 + "，火花 " + ctx.io.days + " 天啦，别断");
```

**宏（节日用，之后可删）**

```js
$spark_result$.push("（元旦还有 " + ctx.builtin.countdown("2027-01-01") + " 天）");
```

发出去是一条消息里的三行：

```
阿泽，晚上好，今天也要好好吃饭🔥
老朋友，火花 493 天啦，别断
（元旦还有 76 天）
```

### 11.9 可直接贴给 AI 的提示词

```
你在帮我写一个抖音自动续火花模块的模板脚本（JavaScript，Rhino 沙盒，2 秒上限，无网络/DOM/Java）。
模块工作方式：每次发送时先合并两层变量（父模板 → 会话，同名覆盖、空值回落、都没有为 null），
再注入自定义函数 ctx.functions.*，然后执行模板脚本，最后执行挂载的宏；
上下文按来源分命名空间：ctx.builtin（内置变量与函数）、ctx.io（抖音给的会话数据）、
ctx.template.vars（模板/会话变量），ctx.template.template / .parent（当前模板信息）、
ctx.macro（当前宏的变量与名字）、ctx.system（会话与挂载）、ctx.functions（自定义函数）—— 不要写成 ctx.name 这种旧写法。
脚本通过 $spark_result$.push("...") 逐行添加内容，多行会被拼成一条消息，空列表表示这条不发。
可用对象：ctx.builtin.date/time/weekday/timestamp，
ctx.io.nickname/remark/name/days/state/stateText/peerUid/conversationId，
ctx.template.vars.<变量名>，以及 ctx.builtin.pick/pad/repeat/random/shuffle/countdown/nextNewYear/untilNewYear/formatDate。
限制：只能用 var 与字符串拼接（ES5），只能用 Math/JSON/Date/Array/String 常规方法，不能有死循环。
要求：1~2 行、口语化、每天有变化（用 ctx.builtin.pick）、对 ctx.io.days 与变量做兜底、不写死昵称。
请先告诉我你打算放哪些变量（名字 + 表达式），再给出模板脚本与（可选的）宏脚本。
```
