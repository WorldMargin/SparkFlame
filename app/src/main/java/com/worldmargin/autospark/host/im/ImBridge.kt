package com.worldmargin.autospark.host.im

import com.worldmargin.autospark.core.HostRuntime
import com.worldmargin.autospark.core.Reflect
import com.worldmargin.autospark.host.account.AccountBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * IM 能力桥：会话列表 + 火花（亲密度火焰）信息。
 *
 * 实例链（全部未混淆语义方法）：
 * `SDKManager.INSTANCE.getImSdkClient().getIIMSdkModelService().getConversationListModel()`
 * → `getAllConversationSync()` 返回全部会话。
 *
 * 会话对象同样是语义 getter（getConversationId / getCoreInfo / getUnreadCount / …），
 * 直接调用。火花数据源（逆向确认）：会话 coreInfo.ext 里的
 * `a:consecutive_chat_data` JSON（flame_infos / effect_list / consecutive_relation_info），
 * 小火人则是 ext 里的 pet 相关键。
 */
object ImBridge {
    private const val SDK_MANAGER = "com.bytedance.ies.im.core.sdk.SDKManager"

    /** 会话 ext 里的火花数据键与小火人键（服务端协议字符串，语义稳定）。 */
    private const val EXT_KEY_SPARK = "a:consecutive_chat_data"
    private val EXT_KEYS_PET =
        listOf(
            "a:pet_elf_avatar",
            "a:pet_elf_parenting",
            "a:pet_v2_closeness",
            "a:pet_v2_deploy_status",
        )

    /**
     * 获取当前账号的会话列表（阻塞走网络/数据库，调用方负责后台线程）。
     *
     * @param limit 最多返回的会话数
     * @param withSpark 是否附带火花/小火人状态
     */
    fun getConversationsJson(
        limit: Int,
        withSpark: Boolean,
        keyword: String = "",
        isGroup: Boolean? = null,
    ): String {
        if (AccountBridge.getCurrentUserId().isNullOrBlank()) return errorJson("未登录")

        val listModel =
            resolveConversationListModel()
                ?: return errorJson("会话列表模型获取失败（IM 未初始化或链路变动）")

        val conversations =
            Reflect.getter(listModel, "getAllConversationSync") as? List<*>
                ?: return errorJson("会话列表读取失败")

        val ownerUid = currentUid()
        val result = JSONArray()
        for (conv in conversations) {
            conv ?: continue
            if (result.length() >= limit) break
            // 群/单聊先过滤：类型读取不涉及对方资料，不匹配的会话零成本跳过
            if (isGroup != null) {
                val convIsGroup = conversationIsGroup(conv) ?: continue
                if (convIsGroup != isGroup) continue
            }
            runCatching { extractConversation(conv, ownerUid, withSpark) }
                .getOrNull()
                ?.let { json ->
                    if (matchesConversation(json, keyword, isGroup)) result.put(json)
                }
        }

        return JSONObject()
            .put("ok", true)
            .put("total", conversations.size)
            .put("conversations", result)
            .toString()
    }

    /** SDKManager 语义链获取会话列表模型（宿主未就绪/链路变动时返回 null）。 */
    internal fun resolveConversationListModel(): Any? =
        runCatching {
            val sdkClass = Class.forName(SDK_MANAGER, false, HostRuntime.requireClassLoader())
            val instance = sdkClass.getDeclaredField("INSTANCE").get(null)!!
            val client = Reflect.getter(instance, "getImSdkClient") ?: return null
            val service = Reflect.getter(client, "getIIMSdkModelService") ?: return null
            Reflect.getter(service, "getConversationListModel")
        }.getOrNull()

    /** 单个会话 → JSON（身份 + 状态 + 最后消息 + 火花）。 */
    private fun extractConversation(
        conv: Any,
        ownerUid: String?,
        withSpark: Boolean,
    ): JSONObject {
        val json = JSONObject()
        json.put("conversationId", Reflect.getter(conv, "getConversationId")?.toString() ?: "")
        json.put("conversationShortId", readLong(conv, "getConversationShortId"))
        val type = (Reflect.getter(conv, "getConversationType") as? Number)?.toInt() ?: 0
        json.put("conversationType", type)
        val isGroup = (Reflect.getter(conv, "isGroupChat") as? Boolean) ?: (type == 2)
        json.put("isGroup", isGroup)

        // 对方 UID（单聊）：成员列表第一个非本人 uid（模型上没有对方 ID 的直接访问器）
        if (!isGroup) {
            val memberIds =
                (Reflect.getter(conv, "getMemberIds") as? List<*>)
                    ?.mapNotNull { it?.toString() }
                    .orEmpty()
            val peer = memberIds.firstOrNull { it != ownerUid && it.isNotBlank() && it != "0" } ?: ""
            json.put("peerUid", peer)
            // 成员只有本人：抖音的「我自己」会话，没有对方可查
            if (peer.isEmpty() && memberIds.isNotEmpty() && memberIds.all { it == ownerUid }) {
                json.put("name", "我自己")
            }
        }

        // 会话信息（名字/头像/置顶/免打扰/未读）。这里的名字只有群聊有值
        Reflect.getter(conv, "getCoreInfo")?.let { coreInfo ->
            (Reflect.getter(coreInfo, "getName") as? String)?.takeIf { it.isNotEmpty() }?.let {
                json.put("name", it)
            }
            if (!json.has("peerAvatar")) {
                (Reflect.getter(coreInfo, "getIcon") as? String)?.let { json.put("peerAvatar", it) }
            }
        }

        // 单聊的会话模型不存昵称/备注/头像：即便 coreInfo 里带了名字（可能只是旧昵称），
        // 也要按对方 uid 去用户仓库查一次，否则备注和头像永远是空的。
        if (!isGroup) {
            json.optString("peerUid").takeIf { it.isNotBlank() }?.let { peer ->
                ImUserBridge.resolve(peer)?.let { user ->
                    if (user.nickname.isNotBlank()) json.put("name", user.nickname)
                    if (user.remark.isNotBlank()) json.put("remarkName", user.remark)
                    if (user.avatar.isNotBlank()) json.put("peerAvatar", user.avatar)
                }
            }
        }
        (Reflect.getter(conv, "getUnreadCount") as? Number)?.let { json.put("unreadCount", it.toInt()) }
        (Reflect.getter(conv, "getMuted") as? Boolean)?.let { json.put("muted", it) }
        (Reflect.getter(conv, "getStickTop") as? Boolean)?.let { json.put("stickTop", it) }

        // 最后一条消息（时间 + 摘要文本）
        (Reflect.getter(conv, "getLastMessageCreateTime") as? Number)?.takeIf { it.toLong() > 0 }?.let {
            json.put("lastMessageTime", formatTime(it.toLong() / 1000))
        }
        Reflect.getter(conv, "getLastHintMessage")?.let { hint ->
            val text = hint?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            if (text != null) json.put("lastMessage", text)
        }

        // 会话扩展（服务端 ext / 本地 localExt）
        mapToJson(Reflect.getter(conv, "getExt"))?.let { json.put("ext", it) }
        mapToJson(Reflect.getter(conv, "getLocalExt"))?.let { json.put("localExt", it) }

        if (withSpark) {
            extractSpark(conv)?.let { json.put("spark", it) }
        }
        return json
    }

    /**
     * 解析火花状态。
     *
     * 数据源：coreInfo.ext["a:consecutive_chat_data"]，形如
     * `{expire_time, can_recover_days, flame_infos:[{start,end,state,days,real_days,level,text,
     * simplify_text,recover_days,recover_need_days}...], biz_flame_infos:[{text:"可点亮"}...]}`。
     *
     * `flame_infos` 是一条**时间轴**，每段描述那段时间里火苗的状态，取「覆盖当前时刻」的那段：
     * - `1`：今天已互动，火苗是亮的（今天不用再发）
     * - `2`：今天还没互动，火苗变灰（今天必须发，否则进入待重燃）
     * - `3`：待重燃（火苗已断），要靠连续互发 `recover_need_days` 天恢复，`recover_days` 是已恢复进度
     *
     * 没有 `flame_infos`（只有 `biz_flame_infos` 的「可点亮」引导）就是没有火苗。
     */
    private fun extractSpark(conv: Any): JSONObject? {
        val coreInfo = Reflect.getter(conv, "getCoreInfo") ?: return null
        val ext = Reflect.getter(coreInfo, "getExt") as? Map<*, *> ?: return null

        val hasPetElf = EXT_KEYS_PET.any { ext[it]?.toString()?.isNotBlank() == true }
        val raw = ext[EXT_KEY_SPARK]?.toString()
        if (raw.isNullOrBlank()) {
            // 无火花数据但有小火人
            if (!hasPetElf) return null
            return JSONObject().put("hasSpark", false).put("petElf", true)
        }

        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val out = JSONObject()
        out.put("petElf", hasPetElf)
        out.put("relationType", root.optJSONObject("consecutive_relation_info")?.optString("type") ?: "")
        // 原始负载（服务端协议串），宿主简报会原样带出来对照语义
        out.put("raw", raw)

        val nowSec = System.currentTimeMillis() / 1000
        val expireTime = root.optLong("expire_time")
        val current = root.optJSONArray("flame_infos")?.let { segmentAt(it, nowSec) }
        if (current == null || (expireTime > 0 && nowSec >= expireTime)) {
            out.put("hasSpark", false)
            root.optJSONArray("biz_flame_infos")?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
                out.put("stateText", it)
            }
            return out
        }

        val state = current.optInt("state", -1)
        out.put("hasSpark", state >= 1)
        out.put("state", state)
        out.put("days", current.optInt("real_days").takeIf { it > 0 } ?: current.optInt("days"))
        out.put("stateText", current.optString("simplify_text").ifBlank { current.optString("text") })
        out.put("level", current.optString("level"))
        out.put("start", current.optLong("start"))
        out.put("end", current.optLong("end"))
        out.put("recoverDays", current.optInt("recover_days"))
        out.put("recoverNeedDays", current.optInt("recover_need_days", root.optInt("can_recover_days")))
        out.put("expireTime", expireTime)
        // 今天还没互动（火苗灰 / 待重燃）就要发；今天已互动则今天不用再发
        out.put("needsRenewal", state == 2 || state == 3)
        return out
    }

    /** 时间轴上覆盖 [nowSec] 的那一段；都不覆盖时取第一段（时间轴以最近一次互动为起点）。 */
    private fun segmentAt(
        segments: JSONArray,
        nowSec: Long,
    ): JSONObject? {
        for (index in 0 until segments.length()) {
            val segment = segments.optJSONObject(index) ?: continue
            if (segment.optLong("start") <= nowSec && nowSec < segment.optLong("end")) return segment
        }
        return segments.optJSONObject(0)
    }

    /** Map<String,String> → JSON 对象（空返回 null）。 */
    private fun mapToJson(map: Any?): JSONObject? {
        val source = map as? Map<*, *> ?: return null
        val json = JSONObject()
        for ((k, v) in source) {
            k ?: continue
            json.put(k.toString(), v?.toString() ?: "")
        }
        return json.takeIf { it.length() > 0 }
    }

    private fun currentUid(): String? =
        runCatching {
            AccountBridge.getCurrentUserId()
        }.getOrNull()

    private fun readLong(
        target: Any,
        getter: String,
    ): Long =
        when (val v = Reflect.getter(target, getter)) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun formatTime(seconds: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(seconds * 1000))

    /** 会话群/单聊标记（仅读类型字段，不涉及对方资料） */
    private fun conversationIsGroup(conv: Any): Boolean? =
        runCatching {
            val type = (Reflect.getter(conv, "getConversationType") as? Number)?.toInt() ?: 0
            (Reflect.getter(conv, "isGroupChat") as? Boolean) ?: (type == 2)
        }.getOrNull()

    /** 会话过滤：keyword 按名字/备注/会话ID 模糊；isGroup 精确匹配群/单聊 */
    private fun matchesConversation(
        json: JSONObject,
        keyword: String,
        isGroup: Boolean?,
    ): Boolean {
        if (isGroup != null && json.optBoolean("isGroup") != isGroup) return false
        if (keyword.isEmpty()) return true
        val text =
            buildString {
                append(json.optString("name")).append(' ')
                append(json.optString("remarkName")).append(' ')
                append(json.optString("conversationId"))
            }
        return text.contains(keyword, ignoreCase = true)
    }

    private fun errorJson(message: String): String = JSONObject().put("ok", false).put("error", message).toString()
}

/**
 * IM 用户资料桥：会话模型不含昵称/备注，单聊只能按对方 uid 查。
 *
 * 链路（与宿主自己的取法一致，取自上游 doumcp 已验证的实现）：
 * `IMUserRepository` 上 `(请求参数, Function1) -> IMUser` 的两参方法，结果可能直接返回、
 * 也可能走回调，两条路都要认；请求参数里 String 字段填 uid、枚举字段填查询场景
 * `CACHE_DB_NET`（缓存 → DB → 网络）。取回的用户用 `getUid()` 校验：uid 字段填错时请求会被
 * 忽略并返回「当前登录用户」，表现就是所有会话昵称都变成自己（历史事故）。
 */
private object ImUserBridge {
    private const val REPO = "com.ss.android.ugc.aweme.im.sdk.core.IMUserRepository"
    private const val IM_USER = "com.ss.android.ugc.aweme.im.service.model.IMUser"
    private const val FUNCTION1 = "kotlin.jvm.functions.Function1"

    /** 请求参数里承载 uid 的字段（实测优先试它，其余 String 字段作兜底）。 */
    private const val UID_FIELD = "LIZ"

    /** 查询场景枚举常量名：缓存 → DB → 网络。 */
    private const val SCENE = "CACHE_DB_NET"

    /** 回调等待上限（超时就算这个 uid 没取到，下次刷新再试）。 */
    private const val CALLBACK_TIMEOUT_SEC = 3L

    private const val MAX_CACHE = 128

    /** IMUser 上可能返回头像的访问器。 */
    private val AVATAR_GETTERS =
        listOf("getDisplayAvatar", "getAvatarThumb", "getAvatar", "getAvatarPreview", "getLocalAvatar")

    class Info(
        val nickname: String,
        val remark: String,
        val avatar: String,
    )

    /** uid → 资料缓存（刷新快照会对每个会话各查一次，命中就不重复走网络）。 */
    private val cache =
        java.util.Collections.synchronizedMap(
            object : LinkedHashMap<String, Info>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Info>): Boolean = size > MAX_CACHE
            },
        )

    private class Handles(
        val repositories: List<Any>,
        val fetchers: List<Method>,
        val requestClass: Class<*>,
        val uidFields: List<Field>,
        val sceneField: Field?,
        val getUid: Method?,
    )

    @Volatile
    private var handles: Handles? = null

    /** 链路不可用时置位，避免每个会话都重试一次反射。 */
    @Volatile
    private var broken = false

    fun resolve(uid: String): Info? {
        if (uid.isBlank()) return null
        cache[uid]?.let { return it }
        val h = handles() ?: return null
        // uid 字段名被混淆过：按「先 UID_FIELD、再其余 String 字段」逐个试，每次都校验返回的 uid
        for (field in h.uidFields) {
            fetch(h, uid, field)?.let {
                cache[uid] = it
                return it
            }
        }
        return null
    }

    /** 用指定 uid 字段查一次：候选方法 × 仓库实例 逐个组合试。 */
    private fun fetch(
        h: Handles,
        uid: String,
        uidField: Field,
    ): Info? {
        for (fetcher in h.fetchers) {
            for (repository in h.repositories) {
                val request =
                    runCatching {
                        h.requestClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                    }.getOrNull() ?: continue
                runCatching { uidField.set(request, uid) }
                h.sceneField?.let { field ->
                    field.type.enumConstants?.firstOrNull { (it as Enum<*>).name == SCENE }?.let { scene ->
                        runCatching { field.set(request, scene) }
                    }
                }
                val user = call(fetcher, repository, request) ?: continue
                val gotUid = h.getUid?.let { runCatching { it.invoke(user) as? String }.getOrNull() }
                if (!gotUid.isNullOrBlank() && gotUid != uid) continue
                val nickname = Reflect.getter(user, "getNickname") as? String ?: ""
                val remark = Reflect.getter(user, "getRemarkName") as? String ?: ""
                val avatar = avatarOf(user)
                if (nickname.isBlank() && remark.isBlank() && avatar.isBlank()) continue
                return Info(nickname, remark, avatar)
            }
        }
        return null
    }

    /** 调一次请求方法：返回值与回调结果两条路都认。 */
    private fun call(
        fetcher: Method,
        repository: Any,
        request: Any,
    ): Any? {
        val function1 =
            runCatching { HostRuntime.requireClassLoader().loadClass(FUNCTION1) }.getOrNull() ?: return null
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Any>(1)
        val callback =
            Proxy.newProxyInstance(function1.classLoader, arrayOf(function1)) { _, method, args ->
                if (method.name == "invoke") {
                    box[0] = args?.firstOrNull { it != null }
                    latch.countDown()
                }
                null
            }
        runCatching { fetcher.invoke(repository, request, callback) }.getOrNull()?.let { return it }
        if (!latch.await(CALLBACK_TIMEOUT_SEC, TimeUnit.SECONDS)) return null
        return box[0]
    }

    /** 取用户头像地址：返回头像对象时从 urlList 取第一个，字符串则容错抽第一个 http 链接。 */
    private fun avatarOf(user: Any): String {
        for (name in AVATAR_GETTERS) {
            val value = Reflect.getter(user, name) ?: continue
            if (value is String) {
                firstUrl(value).takeIf { it.isNotEmpty() }?.let { return it }
                continue
            }
            val urls =
                (Reflect.field(value, "urlList") as? List<*>)
                    ?: (Reflect.getter(value, "getUrlList") as? List<*>)
            urls?.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    /** 头像字段可能是 URL、JSON 数组或 {"url_list":[...]}，统一抽第一个 http 地址。 */
    private fun firstUrl(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        if (raw.startsWith("http")) return raw
        return Regex("""https?://[^"'\s,\]}]+""").find(raw)?.value.orEmpty()
    }

    private fun handles(): Handles? {
        handles?.let { return it }
        if (broken) return null
        synchronized(this) {
            handles?.let { return it }
            if (broken) return null
            val built = runCatching { build() }.getOrNull()
            if (built == null) {
                broken = true
                return null
            }
            handles = built
            return built
        }
    }

    private fun build(): Handles {
        val classLoader = HostRuntime.requireClassLoader()
        val repoClass = Class.forName(REPO, false, classLoader)
        val repositories =
            buildList {
                runCatching { repoClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null) }
                    .getOrNull()?.let { add(it) }
                runCatching { repoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }
                    .getOrNull()?.let { add(it) }
            }
        if (repositories.isEmpty()) error("IMUserRepository 实例获取失败")

        // 请求方法：返回 IMUser、「(请求参数, Kotlin 回调)」两参
        val userClass = Class.forName(IM_USER, false, classLoader)
        val fetchers =
            repoClass.declaredMethods.filter {
                it.returnType.name == IM_USER && it.parameterCount == 2 && it.parameterTypes[1].name == FUNCTION1
            }
        if (fetchers.isEmpty()) error("IMUserRepository 未找到 (请求, 回调) 形式的查询方法")
        val requestClass = fetchers.first().parameterTypes[0]

        val fields = Reflect.fieldsOf(requestClass).values.toList()
        val uidFields =
            fields.filter { it.type == String::class.java }.sortedBy { if (it.name == UID_FIELD) 0 else 1 }
        if (uidFields.isEmpty()) error("请求参数没有 String 字段")
        val sceneField =
            fields.firstOrNull { field ->
                field.type.isEnum && field.type.enumConstants?.any { (it as Enum<*>).name == SCENE } == true
            }

        return Handles(
            repositories = repositories,
            fetchers = fetchers,
            requestClass = requestClass,
            uidFields = uidFields,
            sceneField = sceneField,
            getUid = runCatching { userClass.getMethod("getUid") }.getOrNull(),
        )
    }
}
