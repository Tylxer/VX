package com.lx.wxgroup.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.JsonPrimitive
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * K5 备份与恢复：文件读写 + 落库（JSON 编解码在 [BackupCodec]）。
 *
 * 为什么要它：库在 /data/data/com.tencent.mm/files/wxgroup.db，
 * 卸载模块或换手机就没了，而分组是用户一条条加出来的、配置是一个个开关调出来的，
 * 丢了只能重来。
 *
 * 五条设计约束：
 * 1. **按分组名匹配，不按 id**：分组 id 是自增主键，换机后不可能对上；
 *    分组名有 UNIQUE 约束，是天然的业务主键。成员仍然以 wxid 为键。
 * 2. **默认合并**：分组按名取并集、配置只覆盖文件里有的项（决策 #10）。
 *    覆盖（replace）会删掉用户现有数据，必须由 UI 显式二次确认后才传 true。
 * 3. **损坏文件中止，不写半个**：解析失败 / 读不出分组 → 返回错误原因，
 *    UI 弹窗提示，一个字节都不写进库（B5：备份导入损坏文件的行为）。
 * 4. **主路径是 SAF**（用户自己选目录与文件名），目录导出只是 adb 调试
 *    与无 SAF 时的兜底 —— 后者一定可写，导出不会失败，只是文件不好找。
 * 5. 不备份 `friend_cache`（决策 #10）：昵称头像能在微信进程内重新扫描重建，
 *    带进文件只会让它变大、导入变慢。
 */

/** 扫描到的一个备份文件（已解析，列表上要显示组数/人数） */
data class BackupEntry(val file: File, val snap: Snapshot)

/** 导出结果。`file` 在 SAF 模式下是 Uri 的包装（仅用于显示），不代表磁盘文件 */
data class ExportResult(
    val file: File,
    val groups: Int,
    val members: Int,
    val settings: Int = 0,
)

data class RestoreStats(
    val groupsAdded: Int,
    val membersAdded: Int,
    val groupsInFile: Int,
    val settingsApplied: Int = 0,
    /** 库里已属于其他分组、按「以现有为准」跳过的人数（v6.4） */
    val membersSkipped: Int = 0,
)

object BackupIO {

    private const val DIR_NAME = "WXGroup"
    private const val FILE_PREFIX = "wxgroup-backup-"
    private const val MAX_SCAN = 30

    /** 上一次失败的原因。导出/导入返回 null 时 UI 拿它弹窗 */
    @Volatile
    var lastError: String? = null
        private set

    private fun fail(reason: String): Nothing? {
        lastError = reason
        return null
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 组装备份内容：分组 + 成员 + 全量配置。
     *
     * 配置走 `Kv.exportAll()` —— 它遍历 `Prefs.ALL` 而不是查 kv 全表，
     * 所以导出的 key 必然是当前版本已定义的，不会把废弃 key 带进文件。
     */
    fun buildPayload(db: GroupDb): BackupPayload {
        val now = System.currentTimeMillis()
        return BackupPayload(
            schema = BackupFormat.SCHEMA,
            format = BackupFormat.TAG,   // 保留旧标记，老版本模块也能认出这是自己的文件
            module = BackupFormat.MODULE,
            version = BackupFormat.SCHEMA,
            exported_at = JsonPrimitive(BackupCodec.isoTime(now)),
            kv = Kv.exportAll(),
            groups = db.listGroups().map { g ->
                BackupGroup(
                    name = g.name,
                    color = g.color,
                    sort_order = g.sortOrder,
                    pinned = if (g.pinned) 1 else 0,
                    members = db.membersOf(g.id).map { m ->
                        BackupMember(
                            wxid = m.wxid,
                            display_name = m.displayName,
                            wxid_type = m.wxidType,
                        )
                    },
                )
            },
        )
    }

    /** 导出到候选目录里第一个能写成功的目录（adb 调试 / 无 SAF 时的兜底路径） */
    fun export(ctx: Context, db: GroupDb): ExportResult? {
        val payload = buildPayload(db)
        val json = runCatching { BackupCodec.toJson(payload) }.getOrElse {
            LogX.e("BACKUP serialize failed", it)
            return fail("生成备份内容失败：${it.message}")
        }
        val dir = pickWritableDir(db) ?: run {
            LogX.e("BACKUP no writable dir")
            return fail("找不到可写入的目录")
        }
        val file = File(dir, suggestFileName(BackupCodec.parseExportedAt(payload.exported_at)))
        runCatching { file.writeText(json) }.onFailure {
            LogX.e("BACKUP write failed: ${file.absolutePath}", it)
            return fail("写入失败：${it.message}")
        }
        db.setPref(PrefKeys.BACKUP_DIR, dir.absolutePath)
        val snap = BackupCodec.hydrate(payload)
        lastError = null
        LogX.i("BACKUP export ${file.absolutePath} groups=${snap?.groupCount} members=${snap?.memberCount}")
        return ExportResult(
            file = file,
            groups = snap?.groupCount ?: 0,
            members = snap?.memberCount ?: 0,
            settings = payload.kv?.size ?: 0,
        )
    }

    /**
     * 导出到用户通过 SAF 选择的位置（ACTION_CREATE_DOCUMENT 拿到的 Uri）。
     *
     * 走的是 contentResolver，目的地可能是网盘、Downloads 或任意目录，
     * 所以这里不能做任何「这个目录能不能写」的猜测 —— 写失败就是失败，报出来。
     */
    fun exportToUri(ctx: Context, db: GroupDb, uri: Uri): ExportResult? {
        val payload = buildPayload(db)
        val json = runCatching { BackupCodec.toJson(payload) }.getOrElse {
            LogX.e("BACKUP serialize failed", it)
            return fail("生成备份内容失败：${it.message}")
        }
        return try {
            val out = ctx.contentResolver.openOutputStream(uri)
                ?: return fail("无法打开选择的位置")
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val snap = BackupCodec.hydrate(payload)
            lastError = null
            LogX.i("BACKUP export uri=$uri groups=${snap?.groupCount} members=${snap?.memberCount}")
            ExportResult(
                file = File(uri.toString()),
                groups = snap?.groupCount ?: 0,
                members = snap?.memberCount ?: 0,
                settings = payload.kv?.size ?: 0,
            )
        } catch (t: Throwable) {
            LogX.e("BACKUP export uri failed: $uri", t)
            fail("导出失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 建议的文件名，UI 传给 SAF 的 Intent 作为初始名 */
    fun suggestFileName(ms: Long = System.currentTimeMillis()): String =
        FILE_PREFIX + stamp(ms) + ".json"

    // ---------------------------------------------------------------- 扫描

    /** 列出所有候选目录里的备份文件，最新的排在最前 */
    fun scan(ctx: Context): List<BackupEntry> {
        val out = ArrayList<BackupEntry>()
        for (dir in candidateDirs(ctx)) {
            val files = runCatching { dir.listFiles() }.getOrNull() ?: continue
            for (f in files) {
                if (out.size >= MAX_SCAN) break
                if (!f.isFile || !f.name.startsWith(FILE_PREFIX) || !f.name.endsWith(".json")) continue
                val snap = read(f) ?: continue
                out.add(BackupEntry(f, snap))
            }
        }
        return out.sortedByDescending { it.snap.exportedAt }
    }

    /** 读一个备份文件；不是我们的格式（或坏了）就返回 null */
    fun read(file: File): Snapshot? = readDetailed(file).snapshot

    /** 读文件并带上失败原因，UI 用它决定弹什么 */
    fun readDetailed(file: File): ReadResult {
        val res = runCatching { BackupCodec.parse(file.readText()) }.getOrElse {
            LogX.e("BACKUP read failed: ${file.absolutePath}", it)
            return ReadResult(null, "读不到这个文件")
        }
        if (res.snapshot == null) lastError = res.error
        return res
    }

    /** 读 SAF 选中的文件（导入走 ACTION_OPEN_DOCUMENT） */
    fun readFromUri(ctx: Context, uri: Uri): Snapshot? = readDetailedFromUri(ctx, uri).snapshot

    fun readDetailedFromUri(ctx: Context, uri: Uri): ReadResult {
        val text = try {
            val stream = ctx.contentResolver.openInputStream(uri)
                ?: run {
                    lastError = "读不到这个文件"
                    return ReadResult(null, lastError)
                }
            stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            LogX.e("BACKUP read uri failed: $uri", t)
            val msg = "读取失败：${t.message ?: t.javaClass.simpleName}"
            lastError = msg
            return ReadResult(null, msg)
        }
        val res = BackupCodec.parse(text)
        if (res.snapshot == null) lastError = res.error
        return res
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 恢复。
     *
     * ⚠️ 实现已迁移到 [BackupFlow]：那里"先生成计划（[BackupMerge.plan]）再照计划落库"，
     * 计划的语义由单测锁住，执行层只做机械动作。本方法保留是为了兼容旧调用方，
     * 新代码请直接调 `BackupFlow.restore`（能拿到计划、对账结论和安全快照路径）。
     *
     * @param replace false=**合并**（默认）：同名分组补进缺的成员，
     *                现有成员、现有归属、以及文件里没有的配置项都不动；
     *                true=**覆盖**：先删光所有分组再按备份重建。会丢数据，
     *                必须由 UI 二次确认后才传。
     */
    fun restore(db: GroupDb, snap: Snapshot, replace: Boolean): RestoreStats {
        val ctx = runCatching { HostContext.app() }.getOrNull()
        return BackupFlow.restore(ctx, db, snap, replace).stats
    }

    // ---------------------------------------------------------------- 目录

    /**
     * 候选目录，按「用户最容易找到」排序。
     *
     * 前两个是公共目录 —— Android 11+ 上大概率写不进去（微信有没有
     * MANAGE_EXTERNAL_STORAGE 因版本而异），所以后面必须留 app 私有目录兜底：
     * 那两个目录**一定**可写，导出永远不会失败，只是用户得花点工夫找。
     *
     * 正常路径是 SAF（用户自己选位置），这里只是 adb 调试和无 SAF 时的兜底。
     */
    @Suppress("DEPRECATION")
    private fun candidateDirs(ctx: Context): List<File> {
        val out = LinkedHashMap<String, File>()
        val sd = Environment.getExternalStorageDirectory()
        out["sdDocuments"] = File(sd, "Documents/$DIR_NAME")
        out["sdDownload"] = File(sd, "Download/$DIR_NAME")
        ctx.getExternalFilesDir(DIR_NAME)?.let { out["appExternal"] = it }
        out["appInternal"] = File(ctx.filesDir, DIR_NAME)
        return out.values.toList()
    }

    /** 上次成功的目录优先（还能写就用），否则从头试 */
    private fun pickWritableDir(db: GroupDb): File? {
        val remembered = runCatching { db.getPref(PrefKeys.BACKUP_DIR, "") }.getOrNull()
        if (!remembered.isNullOrBlank()) {
            val d = File(remembered)
            if (isWritable(d)) return d
            LogX.w("BACKUP remembered dir not writable: $remembered")
        }
        val ctx = runCatching { HostContext.app() }.getOrNull()
        for (d in candidateDirs(ctx ?: return null)) {
            if (isWritable(d)) return d
        }
        return null
    }

    /** 试写一个探针文件 —— 只有真的能落盘才算数，mkdirs 成功不代表有写权限 */
    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                false
            } else {
                val probe = File(dir, ".wxgroup-write-probe")
                if (!probe.exists() && !probe.createNewFile()) {
                    false
                } else {
                    probe.delete()
                    true
                }
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 给用户看的：现在会写到哪个目录 */
    fun exportDirHint(): String = runCatching {
        GroupDb.current()?.getPref(PrefKeys.BACKUP_DIR, "")
    }.getOrNull().orEmpty()

    // ---------------------------------------------------------------- 内部

    fun stamp(ms: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(ms))

    fun stampHuman(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
