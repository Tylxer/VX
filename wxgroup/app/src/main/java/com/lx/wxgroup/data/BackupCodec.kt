package com.lx.wxgroup.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSyntaxException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * 备份文件的**编解码**（K5 的一半）：内存对象 <-> JSON 文本。
 *
 * 这一类故意**不碰任何 Android API**（没有 Context / Uri / SQLite），
 * 因此可以在 JVM 上直接跑单元测试 —— 版本兼容这种"写错了要等换机才发现"的逻辑，
 * 必须能在没有手机的时候验证。文件读写与落库在 [BackupIO]。
 *
 * 三条兼容规则（决策 #10）：
 * 1. 缺 `schema` 的旧文件视为 **v0**：只恢复分组与成员，配置一律不动。
 * 2. 高于当前版本的备份 **尽力解析、忽略未知字段**，不拒绝导入
 *    （Gson 本来就忽略未声明字段，这里只需保证已知字段照常读出）。
 * 3. 解析不出分组数据 → 返回错误原因，调用方一个字节都不写库。
 */
object BackupFormat {
    /** 旧格式的自描述标记（v3 时代的文件），读取时仍认 */
    const val TAG = "wxgroup-backup"
    const val MODULE = "wxgroup"

    /** 当前写出的 schema 版本。1 = 分组 + 配置 */
    const val SCHEMA = 1

    /** 从这个版本起文件里带 `kv` 配置段 */
    const val SCHEMA_WITH_SETTINGS = 1

    /** 文件里没写 wxid_type 时的默认值：普通好友（对齐 GroupDb.WxidType.FRIEND） */
    const val DEFAULT_WXID_TYPE = 0
}

/**
 * 备份文件的原始模型（磁盘形态）。
 *
 * 字段全部**可空**：Gson 反序列化走 Unsafe 分配、不调构造器，
 * Kotlin 的非空默认值（`= ""`）根本不会生效，文件里缺字段时就是 null。
 * 声明成可空再统一清洗（见 [BackupCodec.hydrate]），比到处加判空可靠得多。
 *
 * 兼容两套字段命名：`wxid`（v3 起）与 `username`（草案命名）、
 * `kv` 与 `settings` —— 读取时都认，写出时只写一套。
 */
data class BackupPayload(
    val schema: Int? = null,
    val format: String? = null,
    val module: String? = null,
    val version: Int? = null,
    val exported_at: JsonElement? = null,
    val kv: Map<String, String?>? = null,
    val settings: Map<String, String?>? = null,
    val groups: List<BackupGroup>? = null,
)

data class BackupGroup(
    val name: String? = null,
    val color: Int? = null,
    val sort_order: Int? = null,
    val pinned: Int? = null,
    val members: List<BackupMember>? = null,
)

data class BackupMember(
    val wxid: String? = null,
    val username: String? = null,
    val display_name: String? = null,
    val wxid_type: Int? = null,
    val type: Int? = null,
)

/** 清洗后的备份内容，字段全部非空，业务代码只认这个 */
data class Snapshot(
    val schema: Int,
    val exportedAt: Long,
    val groups: List<GroupSnap>,
    /** 配置项。v0 旧备份里没有这一段，此时为空表 */
    val settings: Map<String, String>,
) {
    val groupCount: Int get() = groups.size
    val memberCount: Int get() = groups.sumOf { it.members.size }

    /** 这个文件里到底有没有配置段。没带就一个配置都不动（决策 #10） */
    val hasSettings: Boolean
        get() = schema >= BackupFormat.SCHEMA_WITH_SETTINGS && settings.isNotEmpty()
}

data class GroupSnap(
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val pinned: Boolean,
    val members: List<MemberSnap>,
)

data class MemberSnap(
    val wxid: String,
    val displayName: String,
    val wxidType: Int,
)

/** 读取结果。`snapshot` 为 null 时 `error` 是给弹窗用的中文原因 */
data class ReadResult(val snapshot: Snapshot?, val error: String?)

object BackupCodec {

    private val gson by lazy { Gson() }

    // ---------------------------------------------------------------- 写

    fun toJson(p: BackupPayload): String = gson.toJson(p)

    /** 带时区偏移的 ISO 时间，人能直接读懂（"2026-10-07T00:20:31+08:00"） */
    fun isoTime(ms: Long): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    )

    // ---------------------------------------------------------------- 读

    /** 解析文本。任何一步不对都返回中文原因，绝不返回半成品 */
    fun parse(text: String): ReadResult {
        if (text.isBlank()) return ReadResult(null, "文件是空的")
        val payload = try {
            gson.fromJson(text, BackupPayload::class.java)
        } catch (t: JsonSyntaxException) {
            return ReadResult(null, "文件不是有效的 JSON，可能已损坏")
        } catch (t: Throwable) {
            return ReadResult(null, "解析失败：${t.message ?: t.javaClass.simpleName}")
        }
        if (payload == null) return ReadResult(null, "文件是空的")
        val isOurs = payload.module == BackupFormat.MODULE || payload.format == BackupFormat.TAG
        if (!isOurs) return ReadResult(null, "这不是本模块的备份文件")
        val snap = hydrate(payload) ?: return ReadResult(null, "备份文件里没有分组数据")
        return ReadResult(snap, null)
    }

    /** 把「可能缺字段」的解析结果洗成字段全非空的快照；groups 缺失返回 null */
    fun hydrate(p: BackupPayload): Snapshot? {
        val groups = p.groups ?: return null
        // schema 缺失 = 旧版本文件 = v0：只认分组，配置段一律当作不存在
        val schema = p.schema ?: 0
        return Snapshot(
            schema = schema,
            exportedAt = parseExportedAt(p.exported_at),
            groups = groups.mapNotNull { g ->
                val name = g.name?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                GroupSnap(
                    name = name,
                    color = g.color ?: 0,
                    sortOrder = g.sort_order ?: 0,
                    pinned = (g.pinned ?: 0) != 0,
                    members = (g.members ?: emptyList()).mapNotNull { m ->
                        val wxid = (m.wxid ?: m.username)?.trim().orEmpty()
                        if (wxid.isEmpty()) return@mapNotNull null
                        MemberSnap(
                            wxid = wxid,
                            // 名字只是给人看的，丢了就退回 wxid，不影响分组关系
                            displayName = m.display_name?.trim().orEmpty().ifEmpty { wxid },
                            wxidType = m.wxid_type ?: m.type ?: BackupFormat.DEFAULT_WXID_TYPE,
                        )
                    },
                )
            },
            settings = if (schema >= BackupFormat.SCHEMA_WITH_SETTINGS) {
                (p.kv ?: p.settings ?: emptyMap())
                    .mapNotNull { (k, v) ->
                        val key = k.trim()
                        if (key.isEmpty() || v == null) null else key to v
                    }
                    .toMap()
            } else emptyMap(),
        )
    }

    /** `exported_at` 可能是毫秒数（v3 旧文件）也可能是 ISO 字符串（当前格式） */
    fun parseExportedAt(el: JsonElement?): Long {
        val p = el?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return 0L
        if (p.isNumber) return p.asLong
        val s = p.asString
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)?.time ?: 0L
        }
        return 0L
    }
}
