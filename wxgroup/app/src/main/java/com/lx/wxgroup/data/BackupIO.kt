package com.lx.wxgroup.data

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * M5 备份与恢复：分组数据 <-> JSON 文件。
 *
 * 为什么要它：库在 /data/data/com.tencent.mm/files/wxgroup.db，
 * 卸载模块或换手机就没了，而分组是用户一条条加出来的，丢了只能重来。
 *
 * 三条设计约束：
 * 1. **格式自描述**：文件带 format/version 字段，扫描时靠它认文件，
 *    免得把目录下无关的 json 也当备份列出来。
 * 2. **按分组名匹配，不按 id**：分组 id 是自增主键，换机后不可能对上；
 *    分组名有 UNIQUE 约束，是天然的业务主键。成员仍然以 wxid 为键。
 * 3. **默认合并、覆盖要显式选**：覆盖会删掉用户现有数据，必须二次确认。
 */
object BackupFormat {
    const val TAG = "wxgroup-backup"
    const val VERSION = 1
}

/**
 * 备份文件的数据模型。
 *
 * 字段全部声明成**可空**：Gson 反序列化走的是 Unsafe 分配、不调构造器，
 * Kotlin 的非空默认值（`= ""`）根本不会生效，文件里缺字段时就是 null。
 * 声明成可空再统一清洗（见 [BackupIO.hydrate]），比到处加判空可靠得多。
 */
data class BackupPayload(
    val format: String? = null,
    val version: Int? = null,
    val exported_at: Long? = null,
    val groups: List<BackupGroup>? = null,
)

data class BackupGroup(
    val name: String? = null,
    val color: Int? = null,
    val sort_order: Int? = null,
    val members: List<BackupMember>? = null,
)

data class BackupMember(
    val wxid: String? = null,
    val display_name: String? = null,
    val wxid_type: Int? = null,
)

/** 清洗后的备份内容，字段全部非空，业务代码只认这个 */
data class Snapshot(
    val exportedAt: Long,
    val groups: List<GroupSnap>,
) {
    val groupCount: Int get() = groups.size
    val memberCount: Int get() = groups.sumOf { it.members.size }
}

data class GroupSnap(
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val members: List<MemberSnap>,
)

data class MemberSnap(
    val wxid: String,
    val displayName: String,
    val wxidType: Int,
)

/** 扫描到的一个备份文件（已解析，列表上要显示组数/人数） */
data class BackupEntry(val file: File, val snap: Snapshot)

data class ExportResult(val file: File, val groups: Int, val members: Int)

data class RestoreStats(val groupsAdded: Int, val membersAdded: Int, val groupsInFile: Int)

object BackupIO {

    private const val DIR_NAME = "WXGroup"
    private const val FILE_PREFIX = "wxgroup-backup-"
    private const val MAX_SCAN = 30

    private val gson by lazy { Gson() }

    // ---------------------------------------------------------------- 导出

    /**
     * 导出到第一个能写成功的目录，并把该目录记下来（下次优先写它，
     * 免得每次都在几个候选目录里试一遍）。
     */
    fun export(ctx: Context, db: GroupDb): ExportResult? {
        val groups = db.listGroups()
        val payload = BackupPayload(
            format = BackupFormat.TAG,
            version = BackupFormat.VERSION,
            exported_at = System.currentTimeMillis(),
            groups = groups.map { g ->
                BackupGroup(
                    name = g.name,
                    color = g.color,
                    sort_order = g.sortOrder,
                    members = db.membersOf(g.id).map { m ->
                        BackupMember(m.wxid, m.displayName, m.wxidType)
                    },
                )
            },
        )
        val json = runCatching { gson.toJson(payload) }.getOrNull() ?: run {
            LogX.e("BACKUP serialize failed")
            return null
        }
        val dir = pickWritableDir(db) ?: run {
            LogX.e("BACKUP no writable dir")
            return null
        }
        val file = File(dir, FILE_PREFIX + stamp(payload.exported_at ?: 0L) + ".json")
        runCatching { file.writeText(json) }.onFailure {
            LogX.e("BACKUP write failed: ${file.absolutePath}", it)
            return null
        }
        db.setPref(PrefKeys.BACKUP_DIR, dir.absolutePath)
        val snap = hydrate(payload) ?: Snapshot(0L, emptyList())
        LogX.i("BACKUP export ${file.absolutePath} groups=${snap.groupCount} members=${snap.memberCount}")
        return ExportResult(file, snap.groupCount, snap.memberCount)
    }

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
    fun read(file: File): Snapshot? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val payload = runCatching { gson.fromJson(text, BackupPayload::class.java) }.getOrNull()
            ?: return null
        if (payload.format != BackupFormat.TAG) return null
        return hydrate(payload)
    }

    /** 把「可能缺字段」的解析结果洗成字段全非空的快照 */
    private fun hydrate(p: BackupPayload): Snapshot? {
        val groups = p.groups ?: return null
        return Snapshot(
            exportedAt = p.exported_at ?: 0L,
            groups = groups.mapNotNull { g ->
                val name = g.name?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                GroupSnap(
                    name = name,
                    color = g.color ?: 0,
                    sortOrder = g.sort_order ?: 0,
                    members = (g.members ?: emptyList()).mapNotNull { m ->
                        val wxid = m.wxid?.trim().orEmpty()
                        if (wxid.isEmpty()) return@mapNotNull null
                        MemberSnap(
                            wxid = wxid,
                            // 名字只是给人看的，丢了就退回 wxid，不影响分组关系
                            displayName = m.display_name?.trim().orEmpty().ifEmpty { wxid },
                            wxidType = m.wxid_type ?: GroupDb.WxidType.FRIEND,
                        )
                    },
                )
            },
        )
    }

    // ---------------------------------------------------------------- 恢复

    /**
     * 恢复。
     *
     * @param replace false=合并（同名分组补进缺的成员，现有数据不动）；
     *                true=覆盖（先删光所有分组再按备份重建）
     */
    fun restore(db: GroupDb, snap: Snapshot, replace: Boolean): RestoreStats {
        if (replace) {
            for (g in db.listGroups()) db.deleteGroup(g.id)
        }
        var groupsAdded = 0
        var membersAdded = 0
        val byName = db.listGroups().associateBy { it.name }.toMutableMap()

        for (gs in snap.groups) {
            var gid = byName[gs.name]?.id ?: -1L
            if (gid <= 0) {
                gid = db.createGroup(gs.name, gs.color)
                if (gid <= 0) {
                    LogX.w("BACKUP create group failed: ${gs.name}")
                    continue
                }
                groupsAdded++
                byName[gs.name] = Group(gid, gs.name, gs.color, gs.sortOrder, System.currentTimeMillis())
            }
            val have = db.memberWxids(gid)
            val need = gs.members.filter { it.wxid !in have }
            if (need.isNotEmpty()) {
                // nickname 承载显示名：Friend.displayName 的取值顺序是 备注>昵称>别名>wxid
                membersAdded += db.addMembers(
                    gid,
                    need.map { Friend(wxid = it.wxid, nickname = it.displayName, type = it.wxidType) },
                )
            }
        }
        LogX.i("BACKUP restore replace=$replace groupsAdded=$groupsAdded membersAdded=$membersAdded")
        return RestoreStats(groupsAdded, membersAdded, snap.groupCount)
    }

    // ---------------------------------------------------------------- 目录

    /**
     * 候选目录，按「用户最容易找到」排序。
     *
     * 前两个是公共目录 —— Android 11+ 上大概率写不进去（微信有没有
     * MANAGE_EXTERNAL_STORAGE 因版本而异），所以后面必须留 app 私有目录兜底：
     * 那两个目录**一定**可写，导出永远不会失败，只是用户得花点工夫找。
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
