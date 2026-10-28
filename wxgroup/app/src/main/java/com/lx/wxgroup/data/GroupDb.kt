package com.lx.wxgroup.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lx.wxgroup.util.LogX

/**
 * 分组数据落地。
 *
 * 关键约束：
 * 1. 数据库必须建在宿主进程可写的目录下。本类的 Context 来自微信，
 *    所以 filesDir 就是 /data/data/com.tencent.mm/files/ —— 不要换成模块自己的包名目录，
 *    那是另一个 uid，hook 代码读不到。
 * 2. 主键必须是 wxid（Contact.username）。昵称会重复、备注会被改，都不可靠。
 * 3. group_member 以 wxid 为**单列主键** —— 一个好友只能属于一个分组（成员独占）。
 *    v3 及以前是 (group_id, wxid) 复合主键（允许一人多组），v4 迁移时已重建表收敛为一组。
 * 4. 所有 SQL 一律参数绑定。
 */
/**
 * 界面偏好的 key。
 *
 * 放库里而不是 SharedPreferences：SP 文件在宿主私有目录，adb 验证时 pull 不出来，
 * 出了问题只能靠猜。kv 表跟分组数据在同一份 wxgroup.db 里，一眼就能看到当前状态。
 */
object PrefKeys {
    const val BAR_STYLE = "bar_style"          // chip | row（M3 早期样式，已废弃）
    const val BAR_COLLAPSED = "bar_collapsed"  // 0 | 1（已废弃）
    const val BAR_MODE = "bar_mode"            // bar（顶部横条）| list（插进聊天列表，默认）
    const val FILTER_GROUP = "filter_group"    // 0 = 不筛选；>0 = 只显示该分组的成员会话
    const val BACKUP_DIR = "backup_dir"        // M5：上次导出成功的目录，下次优先写这里
}

class GroupDb private constructor(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "wxgroup.db"
        private const val DB_VERSION = 9

        private const val T_GROUP = "groups"
        private const val T_MEMBER = "group_member"
        private const val T_FRIEND = "friend_cache"
        private const val T_KV = "kv"
        private const val T_DND = "auto_dnd_done"
        private const val T_SEEN = "group_seen"
        private const val T_RESCUED = "rescued"
        private const val T_SNS = "sns_archive"
        private const val T_SRC = "sns_visible_src"

        @Volatile
        private var instance: GroupDb? = null

        /** 用宿主的 Context 初始化，幂等 */
        fun open(context: Context): GroupDb {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: GroupDb(context.applicationContext).also { created ->
                    created.writableDatabase // 触发 onCreate
                    instance = created
                }
            }
        }

        fun current(): GroupDb? = instance
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_GROUP (
                id         INTEGER PRIMARY KEY AUTOINCREMENT,
                name       TEXT    NOT NULL UNIQUE,
                color      INTEGER NOT NULL DEFAULT 0,
                sort_order INTEGER NOT NULL DEFAULT 0,
                pinned     INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_MEMBER (
                group_id     INTEGER NOT NULL,
                wxid         TEXT    NOT NULL PRIMARY KEY,
                wxid_type    INTEGER NOT NULL DEFAULT 0,
                display_name TEXT,
                added_at     INTEGER NOT NULL
            )
            """.trimIndent()
        )
        // wxid 已是主键（自带索引），不再单独建 idx_member_wxid
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_name ON $T_MEMBER(display_name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_group ON $T_MEMBER(group_id)")
        createFriendTable(db)
        createKvTable(db)
        createDndTable(db)
        createRescuedTable(db)
        createSnsTable(db)
        createSeenTable(db)
        createSrcTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 升级策略必须是「增量迁移」。任何 drop-recreate 都会让用户分组数据全部消失。
        if (oldVersion < 2) {
            createFriendTable(db)
        }
        if (oldVersion < 3) {
            createKvTable(db)
        }
        if (oldVersion < 4) {
            // ① 分组置顶字段
            db.execSQL("ALTER TABLE $T_GROUP ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
            // ② 成员独占：主键由 (group_id, wxid) 改为 wxid
            //    SQLite 改不了主键，只能重建表。一人多组的旧数据用 OR IGNORE 只留第一条，
            //    其余自动移出 —— 这正是「一个好友只能属于一个分组」要的效果。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS ${T_MEMBER}_v4 (
                    group_id     INTEGER NOT NULL,
                    wxid         TEXT    NOT NULL PRIMARY KEY,
                    wxid_type    INTEGER NOT NULL DEFAULT 0,
                    display_name TEXT,
                    added_at     INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO ${T_MEMBER}_v4
                    (group_id, wxid, wxid_type, display_name, added_at)
                SELECT group_id, wxid, wxid_type, display_name, added_at FROM $T_MEMBER
                """.trimIndent()
            )
            db.execSQL("DROP TABLE IF EXISTS $T_MEMBER")
            db.execSQL("ALTER TABLE ${T_MEMBER}_v4 RENAME TO $T_MEMBER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_name ON $T_MEMBER(display_name)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_member_group ON $T_MEMBER(group_id)")
            // ③ 已自动设过免打扰的群
            createDndTable(db)
        }
        if (oldVersion < 5) {
            createRescuedTable(db)
        }
        if (oldVersion < 6) {
            // rescued 表补两列快照：
            // - revoke_extra：微信撤回写入的完整字段（含 lvbuffer / isSend / reserved），
            //   伪装时完整重放 —— 只写 type+content 真机实测会整页空白。
            // - orig_extra：拦截瞬间该行原状的完整字段（isSend / lvbuffer / reserved），
            //   还原时写回 —— 不然伪装写进去的 lvbuffer/reserved/isSend 残留在行上清不掉。
            // v5 时代建的表没有这两列，用 ALTER 补上（runCatching 兜底，重复执行也不会炸）。
            runCatching {
                db.execSQL("ALTER TABLE $T_RESCUED ADD COLUMN revoke_extra TEXT")
                db.execSQL("ALTER TABLE $T_RESCUED ADD COLUMN orig_extra TEXT")
            }
        }
        if (oldVersion < 7) {
            createSnsTable(db)
        }
        if (oldVersion < 8) {
            createSeenTable(db)
        }
        if (oldVersion < 9) {
            // R27：朋友圈「曾经可见」台账。纯新增表，老库直接补建即可（CREATE IF NOT EXISTS，
            // 重复执行无害）。**没有** ALTER 老表 ⇒ 不存在丢列/重建风险。
            createSrcTable(db)
        }
    }

    /**
     * 被防撤回救下来的消息。
     *
     * 「恢复默认显示」要靠它才能来回切：
     * - [orig_type] / [orig_content]：消息**原本**的样子（撤回被我们拦下，所以库里一直是原样）
     * - [revoke_content]：微信**本来打算**写进去的撤回文案（`'"昵称" 撤回了一条消息'`）
     * - [hint_msg_id]：我们额外插入的「已阻止」提示行（0 = 没插）
     *
     * ⚠️ 这里是模块自己的库（AOSP SQLite），**不走 wcdb**，所以读写它不会触发
     * 我们挂在微信库上的任何钩子。
     */
    private fun createRescuedTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_RESCUED (
                msg_id         INTEGER NOT NULL PRIMARY KEY,
                talker         TEXT    NOT NULL,
                orig_type      INTEGER NOT NULL,
                orig_content   TEXT,
                revoke_content TEXT,
                revoke_extra   TEXT,
                orig_extra     TEXT,
                hint_msg_id    INTEGER NOT NULL DEFAULT 0,
                at             INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rescued_at ON $T_RESCUED(at)")
    }

    /**
     * 朋友圈动态归档（[com.lx.wxgroup.feature.SnsGuard]）。
     *
     * 微信删动态是**物理 DELETE 整行**，拦住只能保住「微信库里这行还在」；
     * 一旦微信换条路清理掉、或者模块被卸掉，内容就没了。
     * 所以拦的同时把整行（含 content / attrBuf / postBuf 三个 BLOB）复制一份到这里。
     * 这也是 6.4「自动导出朋友圈动态」的数据源。
     */
    private fun createSnsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_SNS (
                sns_id          INTEGER NOT NULL PRIMARY KEY,
                user_name       TEXT,
                create_time     INTEGER NOT NULL DEFAULT 0,
                type            INTEGER NOT NULL DEFAULT 0,
                source_type     INTEGER NOT NULL DEFAULT 0,
                local_flag      INTEGER NOT NULL DEFAULT 0,
                sub_type        INTEGER NOT NULL DEFAULT 0,
                server_ext_flag INTEGER NOT NULL DEFAULT 0,
                head            TEXT,
                string_seq      TEXT,
                content         BLOB,
                attr_buf        BLOB,
                post_buf        BLOB,
                at              INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sns_time ON $T_SNS(create_time)")
    }

    /**
     * 朋友圈「曾经时间线可见」台账（[com.lx.wxgroup.feature.SnsTimelineSourceFix]）。
     *
     * 为什么需要它：好友删动态时微信只把 `SnsInfo.sourceType` 的 bit1 清掉（`& -3`），
     * 行还在、`content`/`localFlag` 全不变。要区分「被清成 0」与「本来就不是 2」，
     * 唯一可信的信号是「这行**曾经** `& 2 != 0`」—— 那就得跨轮、跨重启记下来。
     *
     * 列语义：
     * - `source_type`：**最近一次**亲眼见到它可见时的值（不是当前库里的值，当前值要现查微信库）
     * - `first_seen` / `last_seen`：首次 / 最近一次见到可见的毫秒时间戳
     * - `fixed_orig`：最近一次补位**之前**的原值（回滚用）；null = 没补过 / 已回滚
     *
     * ⚠️ 这是**模块自己的库**（AOSP SQLite），写它不触发挂在微信库上的任何钩子。
     */
    private fun createSrcTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_SRC (
                sns_id      INTEGER NOT NULL PRIMARY KEY,
                source_type INTEGER NOT NULL DEFAULT 0,
                first_seen  INTEGER NOT NULL DEFAULT 0,
                last_seen   INTEGER NOT NULL DEFAULT 0,
                fixed_orig  INTEGER
            )
            """.trimIndent()
        )
    }

    // ---------------------------------------------------------------- 防撤回：被救下的消息

    data class Rescued(
        val msgId: Long,
        val talker: String,
        val origType: Int,
        val origContent: String?,
        val revokeContent: String?,
        /** 微信撤回写入的**完整字段快照**（[com.lx.wxgroup.feature.CvCodec] 编码），null = 老记录没存 */
        val revokeExtra: String?,
        /** 拦截瞬间该行**原状**的完整字段快照（isSend/lvbuffer/reserved），还原时写回 */
        val origExtra: String?,
        val hintMsgId: Long,
    )

    fun hasRescued(msgId: Long): Boolean {
        if (msgId <= 0) return false
        readableDatabase.query(
            T_RESCUED, arrayOf("msg_id"), "msg_id=?", arrayOf(msgId.toString()),
            null, null, null
        ).use { return it.moveToFirst() }
    }

    /**
     * 登记一条被救下的消息，**已存在就不覆盖**。
     *
     * ⚠️ 这里必须是「首记录为准」，绝不能用 CONFLICT_REPLACE：
     * 拦下撤回后微信会重试同一条 update 上千次，超过 `BLOCK_TTL` 后会**二次完整判定**
     * 并再登记一次；而那时库里那行可能已经被「恢复默认显示」伪装成撤回文案了。
     * 用 REPLACE 会把**真原文**冲成撤回文案 —— 消息原文就此永久丢失。
     *
     * @return true = 台账里现在有这条的可信备份（新插入的，或本来就有的）
     */
    fun recordRescued(
        msgId: Long,
        talker: String,
        origType: Int,
        origContent: String?,
        revokeContent: String?,
        revokeExtra: String? = null,
        origExtra: String? = null,
    ): Boolean {
        if (msgId <= 0) return false
        if (hasRescued(msgId)) return true
        val values = ContentValues().apply {
            put("msg_id", msgId)
            put("talker", talker)
            put("orig_type", origType)
            put("orig_content", origContent)
            put("revoke_content", revokeContent)
            put("revoke_extra", revokeExtra)
            put("orig_extra", origExtra)
            put("at", System.currentTimeMillis())
        }
        return writableDatabase.insert(T_RESCUED, null, values) > 0
    }

    /**
     * @param limit 条数上限。⚠️ 默认取大值：这个列表直接决定「恢复默认显示」关掉时
     *              能还原多少条，取小了会有消息**永远卡在撤回状态**。
     */
    fun listRescued(limit: Int = 5000): List<Rescued> {
        val out = ArrayList<Rescued>()
        readableDatabase.query(
            T_RESCUED, null, null, null, null, null, "at DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Rescued(
                        msgId = c.getLong(c.getColumnIndexOrThrow("msg_id")),
                        talker = c.getString(c.getColumnIndexOrThrow("talker")) ?: "",
                        origType = c.getInt(c.getColumnIndexOrThrow("orig_type")),
                        origContent = c.getString(c.getColumnIndexOrThrow("orig_content")),
                        revokeContent = c.getString(c.getColumnIndexOrThrow("revoke_content")),
                        revokeExtra = c.getString(c.getColumnIndexOrThrow("revoke_extra")),
                        origExtra = c.getString(c.getColumnIndexOrThrow("orig_extra")),
                        hintMsgId = c.getLong(c.getColumnIndexOrThrow("hint_msg_id")),
                    )
                )
            }
        }
        return out
    }

    fun getRescued(msgId: Long): Rescued? {
        if (msgId <= 0) return null
        readableDatabase.query(
            T_RESCUED, null, "msg_id=?", arrayOf(msgId.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Rescued(
                msgId = c.getLong(c.getColumnIndexOrThrow("msg_id")),
                talker = c.getString(c.getColumnIndexOrThrow("talker")) ?: "",
                origType = c.getInt(c.getColumnIndexOrThrow("orig_type")),
                origContent = c.getString(c.getColumnIndexOrThrow("orig_content")),
                revokeContent = c.getString(c.getColumnIndexOrThrow("revoke_content")),
                revokeExtra = c.getString(c.getColumnIndexOrThrow("revoke_extra")),
                origExtra = c.getString(c.getColumnIndexOrThrow("orig_extra")),
                hintMsgId = c.getLong(c.getColumnIndexOrThrow("hint_msg_id")),
            )
        }
    }

    /** 记下我们为这条消息插入的「已阻止」提示行，关掉「显示撤回提示」时要靠它隐藏 */
    fun setRescuedHint(msgId: Long, hintMsgId: Long) {
        val values = ContentValues().apply { put("hint_msg_id", hintMsgId) }
        writableDatabase.update(T_RESCUED, values, "msg_id=?", arrayOf(msgId.toString()))
    }

    fun deleteRescued(msgId: Long) {
        writableDatabase.delete(T_RESCUED, "msg_id=?", arrayOf(msgId.toString()))
    }

    // ---------------------------------------------------------------- 朋友圈动态归档

    /**
     * 一条朋友圈动态的完整快照（含三个 BLOB）。
     *
     * ## ⛔ 只有 `at` 有默认值 —— 其余 13 个字段**必须显式写**
     * 这容易踩：想构造一个「只查了 7 列」的半行（只读导出需要的列，避免把
     * `content`/`attrBuf`/`postBuf` 三个 BLOB 全捞进内存）时，直觉是「没查的列留默认值」，
     * 但**它们没有默认值**，编译会直接报 `No value passed for parameter 'subType'` 等 **6 条**。
     * 正确写法是显式填 `0` / `null`（语义等价，读法本来就不碰它们）。
     *
     * 📌 R20 实例：`SnsGuard.liveRows()` 构造半行时漏填，`compileDebugKotlin` 报了 5 条，
     * 属两个 lane 接口脱节（`docs/_r20_sample_src.md` §7 有记录）。
     *
     * ⛔ 也别指望用 `copy()` 补：`copy()` 只能改已有实例的字段，
     * 而「缺列的完整快照」根本不该被造出来 —— 拿半行去 `archiveSns` 会用残缺快照
     * 覆盖掉 `readRow` 存下的完整快照，而 `archiveSns` 是「首条为准」，覆盖不回来。
     */
    data class SnsRow(
        val snsId: Long,
        val userName: String?,
        val createTime: Long,
        val type: Int,
        val sourceType: Int,
        val localFlag: Int,
        val subType: Int,
        val serverExtFlag: Int,
        val head: String?,
        val stringSeq: String?,
        val content: ByteArray?,
        val attrBuf: ByteArray?,
        val postBuf: ByteArray?,
        val at: Long = System.currentTimeMillis(),
    )

    /**
     * 归档一条动态。
     *
     * ⚠️ **首条为准**：已经有就不覆盖。理由跟 `recordRescued` 一样 ——
     * 覆盖会把「删之前那一版」冲掉，而删之前的才是我们要留的原文。
     */
    fun archiveSns(row: SnsRow): Boolean {
        if (row.snsId == 0L) return false
        if (hasSns(row.snsId)) return true
        val values = ContentValues().apply {
            put("sns_id", row.snsId)
            put("user_name", row.userName)
            put("create_time", row.createTime)
            put("type", row.type)
            put("source_type", row.sourceType)
            put("local_flag", row.localFlag)
            put("sub_type", row.subType)
            put("server_ext_flag", row.serverExtFlag)
            put("head", row.head)
            put("string_seq", row.stringSeq)
            put("content", row.content)
            put("attr_buf", row.attrBuf)
            put("post_buf", row.postBuf)
            put("at", row.at)
        }
        // CONFLICT_IGNORE：并发下撞主键就放弃，绝不覆盖已有归档
        return writableDatabase.insertWithOnConflict(
            T_SNS, null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    private fun hasSns(snsId: Long): Boolean {
        readableDatabase.query(
            T_SNS, arrayOf("sns_id"), "sns_id=?", arrayOf(snsId.toString()), null, null, null
        ).use { c -> return c.moveToFirst() }
    }

    /** 按 snsId 读一条归档（反插恢复用；读整行含三个 BLOB，后台线程调） */
    fun snsArchived(snsId: Long): SnsRow? {
        readableDatabase.query(
            T_SNS, null, "sns_id=?", arrayOf(snsId.toString()), null, null, null, "1"
        ).use { c -> return if (c.moveToFirst()) c.toSnsRow() else null }
    }

    fun listSnsArchive(limit: Int = 20): List<SnsRow> {
        val out = mutableListOf<SnsRow>()
        readableDatabase.query(
            T_SNS, null, null, null, null, null, "create_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += c.toSnsRow()
            }
        }
        return out
    }

    /**
     * 朋友圈归档的**键集分页**（自动导出的遍历方式）。
     *
     * 为什么不能直接 `listSnsArchive(大 limit)` 一把梭：它把 `content` / `attrBuf` /
     * `post_buf` 三个 BLOB 一起读进内存（朋友圈 content 动辄几十 KB），归档一多就是
     * 几百 MB —— 后台线程 OOM 会把微信带走。这里一次只读一页。
     *
     * 游标是 `(create_time, sns_id)` 复合键：createTime 相同的相邻行不会漏
     * （单纯 `create_time < ?` 会在页边界丢掉同一秒的行）。首次调用传
     * `(Long.MAX_VALUE, Long.MAX_VALUE)` 即从最新一页开始。
     */
    fun listSnsArchiveBefore(createTime: Long, snsId: Long, limit: Int = 100): List<SnsRow> {
        val out = mutableListOf<SnsRow>()
        readableDatabase.query(
            T_SNS, null,
            "create_time < ? OR (create_time = ? AND sns_id < ?)",
            arrayOf(createTime.toString(), createTime.toString(), snsId.toString()),
            null, null, "create_time DESC, sns_id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += c.toSnsRow()
            }
        }
        return out
    }

    /**
     * 已归档动态的 snsId 全集（给「[已删除]标签」判定用）。
     *
     * ⚠️ **绝不能**用 [listSnsArchive] 代替：它把 `content` / `attr_buf` / `post_buf`
     * 三个 BLOB 一起读出来（朋友圈 content 动辄几十 KB），而这里只需要一个 id 集合，
     * 而且调用方跑在**主线程**（View.setTag 钩子里 / 滚动过程中）。
     * 不 ORDER BY：只需覆盖索引扫一遍，不必排序。
     */
    fun snsArchivedIds(): Set<Long> {
        val out = HashSet<Long>(256)
        // ⚠️ 不加 LIMIT：无 ORDER BY 时 LIMIT 取的是「任意 N 行」，归档一多就会
        // 静默漏掉早期被删的动态（标签凭空消失且没有任何日志）。这里只读一个
        // INTEGER 列，全量扫的代价远小于漏标。
        readableDatabase.query(
            T_SNS, arrayOf("sns_id"), null, null, null, null, null
        ).use { c ->
            val i = c.getColumnIndexOrThrow("sns_id")
            while (c.moveToNext()) out.add(c.getLong(i))
        }
        return out
    }

    /**
     * 「已删动态」判据 —— **只认物理删除归档 [T_SNS]**（R31h 方案 A 收窄）。
     *
     * ⛔⛔ **这里有一起真实事故，改回并集前必读。**
     * R30 时这里写的是 `T_SNS ∪ (T_SRC WHERE fixed_orig IS NOT NULL)`，理由是「微信删动态有两条
     * 互斥路径，只查一边会漏标」。当时 `fixed_orig` 只有 4 条，所以「补过位」≈「被删过」碰巧成立。
     * 但 R31 把补位范围扩到全量可见动态后，`fixed_orig` 涨到 **3447 条**，而它**只写不删**：
     * ⇒ 3447 条**正常动态**全部被判成「已删除」，正常朋友圈被插上「已阻止删除」灰标，误伤真实用户。
     *
     * 根因不是 SQL 写错，而是 **`fixed_orig` 这一个字段背了两个语义**：
     * ① 补位台账（「这条我动过 sourceType」，每条可见动态都会写，量级 = 全部动态）；
     * ② 已删判据（「这条被删了」，应该只有个位数）。
     * 只要两者共用一个字段，下游任何一处「顺手记一笔」都会污染判据。
     *
     * ⇒ 现在拆成两个方法，语义分开：
     * - **本方法 [snsTagIds]**：已删判据，只查 [T_SNS]（`SnsGuard` 拦住的物理 DELETE）。
     * - **[snsLedgerIds]**：补位台账并集，只给「按台账回捞动态」这类用途（`SnsAlbumMerge`）。
     *
     * ⚠️ 代价（已知并接受）：走 `UPDATE sourceType=0` 那条路径被删的动态不再有灰标。
     * 想补回来必须按 R31h 方案 B 做 —— 给 [T_SRC] 加一列记录「该 snsId 在不在最近一次
     * 服务端回包里」，只有**不在**的才算真删，**绝不能再拿 `fixed_orig` 当判据**。
     */
    fun snsTagIds(): Set<Long> = runCatching {
        val out = HashSet<Long>(64)
        readableDatabase.query(
            T_SNS, arrayOf("sns_id"), null, null, null, null, null,
        ).use { c ->
            val i = c.getColumnIndexOrThrow("sns_id")
            while (c.moveToNext()) out.add(c.getLong(i))
        }
        out
    }.onFailure { LogX.w("GroupDb snsTagIds failed: ${it.javaClass.simpleName}") }
        .getOrDefault(emptySet())

    /**
     * 朋友圈时间线注入的候选 snsId（新的在前）。
     *
     * ⛔ **只能用这个，绝不能用 [listSrcFixedIds]。** R31h 真机铁证：
     * 用补位台账当候选时 `SNS-INJECT-STAT attempt=24 ok=24 emptyBuf=0` ——
     * 24 条全部能从微信 `SnsInfo` 里读回 attrBuf，说明它们**全是还好好存在的正常动态**，
     * 没有一条被删。根因：时间线**分页**（一页 10 条），台账 200 条里必然有 190 条
     * 不在当前页 ⇒ 「不在响应里」被判成「被删」⇒ 每轮往列表底部重复注入 12 条。
     *
     * 本方法只取 [T_SNS]（`SnsGuard` 拦下的物理 `DELETE`），那才是真被删的。
     */
    fun listArchiveIds(limit: Int = 200): List<Long> = runCatching {
        val out = ArrayList<Long>(limit.coerceAtMost(256))
        readableDatabase.query(
            T_SNS, arrayOf("sns_id"), null, null, null, null, "create_time DESC", limit.toString()
        ).use { c ->
            val i = c.getColumnIndexOrThrow("sns_id")
            while (c.moveToNext()) out.add(c.getLong(i))
        }
        out
    }.onFailure { LogX.w("GroupDb listArchiveIds failed: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    /**
     * 按 snsId 读归档的 `attr_buf`（时间线注入用）。
     *
     * 为什么不复用 [snsArchived]：那个读整行，`content` / `post_buf` 一起出来（动辄几十 KB），
     * 而注入每轮最多 12 条、跑在 worker 上，只需要这一个 BLOB。
     *
     * ⚠️ **BLOB 必须 `getType()==4` + `getBlob`**：`getString` 读 BLOB 返回 null，
     * 本项目为此误判过两次（以为「正文永久丢失」，其实是取值方式错）。
     */
    fun snsArchivedAttrBuf(snsId: Long): ByteArray? = runCatching {
        readableDatabase.query(
            T_SNS, arrayOf("attr_buf"), "sns_id=?", arrayOf(snsId.toString()),
            null, null, null, "1",
        ).use { c -> if (c.moveToFirst() && c.getType(0) == 4) c.getBlob(0) else null }
    }.onFailure { LogX.w("GroupDb snsArchivedAttrBuf failed: ${it.javaClass.simpleName}") }
        .getOrNull()

    /**
     * 补位台账的**并集清单**（[T_SNS] ∪ `T_SRC WHERE fixed_orig IS NOT NULL`）。
     *
     * ⛔ **绝不能拿它当「已删除」判据**（见 [snsTagIds] 的事故说明）——它包含**所有补过位
     * 的正常动态**，量级是全部可见动态。
     *
     * 唯一合法用途：按台账把「微信本地已经不返回、但我们有记录」的动态回捞出来（相册注入）。
     * 目前只有 `SnsAlbumMerge`（**默认停用**）用它。
     *
     * ⚠️ **不加 LIMIT**：`UNION` 没有 `ORDER BY` 时 LIMIT 取的是「任意 N 行」，
     * 会让早期记录**静默漏掉**（与 [snsArchivedIds] 同一理由）。这里只读一个
     * INTEGER 列，全量扫的代价远小于漏。
     */
    fun snsLedgerIds(): Set<Long> = runCatching {
        val out = HashSet<Long>(4096)
        readableDatabase.rawQuery(
            "SELECT sns_id FROM $T_SNS UNION SELECT sns_id FROM $T_SRC WHERE fixed_orig IS NOT NULL",
            null,
        ).use { c ->
            val i = c.getColumnIndexOrThrow("sns_id")
            while (c.moveToNext()) out.add(c.getLong(i))
        }
        out
    }.onFailure { LogX.w("GroupDb snsLedgerIds failed: ${it.javaClass.simpleName}") }
        .getOrDefault(emptySet())

    fun deleteSnsArchive(snsId: Long) {
        writableDatabase.delete(T_SNS, "sns_id=?", arrayOf(snsId.toString()))
    }

    // ---------------------------------------------------------------- 朋友圈「曾经可见」台账

    /**
     * 台账一行（[T_SRC]）。`source_type` 是**最近一次见到可见时**的值，
     * 不是库里当前的值 —— 后者每次都要现查微信库，别混。
     */
    data class VisibleSrc(
        val snsId: Long,
        val sourceType: Int,
        val firstSeen: Long,
        val lastSeen: Long,
        /** 最近一次补位前的原值；null = 没补过 / 已回滚 */
        val fixedOrig: Int?,
    )

    /**
     * 批量登记「见过它可见」。
     *
     * 语义与 `recordRescued` 的「首条为准」不同，这里要**更新** `source_type` / `last_seen`：
     * 台账答的是「它最近一次可见时是什么值」，用旧值会让补位回滚写出过期数值。
     * 但 `first_seen` **不覆盖**（它答的是「第一次见到它的时间」，覆盖等于丢失历史）。
     *
     * 主键是 `sns_id` ⇒ 先 `CONFLICT_IGNORE` 插入，插不进（已存在）再 update 一次。
     * 走单事务：一轮快照几千行，逐条提交会产生几千次 journal。
     *
     * @return 新增（此前不在台账里）的行数
     */
    fun upsertVisibleSrc(rows: Collection<Pair<Long, Int>>, now: Long = System.currentTimeMillis()): Int {
        if (rows.isEmpty()) return 0
        return runCatching {
            writableDatabase.run {
                beginTransaction()
                try {
                    val values = ContentValues()
                    var added = 0
                    for ((snsId, src) in rows) {
                        if (snsId == 0L) continue
                        values.clear()
                        values.put("sns_id", snsId)
                        values.put("source_type", src)
                        values.put("first_seen", now)
                        values.put("last_seen", now)
                        if (insertWithOnConflict(T_SRC, null, values, SQLiteDatabase.CONFLICT_IGNORE) > 0) {
                            added++
                        } else {
                            values.clear()
                            values.put("source_type", src)
                            values.put("last_seen", now)
                            update(T_SRC, values, "sns_id=?", arrayOf(snsId.toString()))
                        }
                    }
                    setTransactionSuccessful()
                    added
                } finally {
                    endTransaction()
                }
            }
        }.onFailure { LogX.w("GroupDb upsertVisibleSrc failed: ${it.javaClass.simpleName}") }
            .getOrDefault(0)
    }

    /**
     * 台账全量（`last_seen DESC` = 最近还看得见的排前面）。
     *
     * ⚠️ 有 `LIMIT`：台账会随「见过多少条可见动态」线性增长，探针不该把它一把拖出来。
     * 默认 5000 与 `listRescued` 同一个量级口径。
     */
    fun listVisibleSrc(limit: Int = 5000): List<VisibleSrc> {
        val out = ArrayList<VisibleSrc>()
        readableDatabase.query(
            T_SRC, null, null, null, null, null, "last_seen DESC, sns_id DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val origIdx = c.getColumnIndexOrThrow("fixed_orig")
                out.add(
                    VisibleSrc(
                        snsId = c.getLong(c.getColumnIndexOrThrow("sns_id")),
                        sourceType = c.getInt(c.getColumnIndexOrThrow("source_type")),
                        firstSeen = c.getLong(c.getColumnIndexOrThrow("first_seen")),
                        lastSeen = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                        fixedOrig = if (c.isNull(origIdx)) null else c.getInt(origIdx),
                    )
                )
            }
        }
        return out
    }

    fun visibleSrc(snsId: Long): VisibleSrc? {
        if (snsId == 0L) return null
        readableDatabase.query(
            T_SRC, null, "sns_id=?", arrayOf(snsId.toString()), null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            val origIdx = c.getColumnIndexOrThrow("fixed_orig")
            return VisibleSrc(
                snsId = c.getLong(c.getColumnIndexOrThrow("sns_id")),
                sourceType = c.getInt(c.getColumnIndexOrThrow("source_type")),
                firstSeen = c.getLong(c.getColumnIndexOrThrow("first_seen")),
                lastSeen = c.getLong(c.getColumnIndexOrThrow("last_seen")),
                fixedOrig = if (c.isNull(origIdx)) null else c.getInt(origIdx),
            )
        }
    }

    /** 记下本次补位前的原值（回滚依据）。只在真的写成功了才调 */
    fun markSrcFixed(snsId: Long, orig: Int) {
        val values = ContentValues().apply { put("fixed_orig", orig) }
        runCatching { writableDatabase.update(T_SRC, values, "sns_id=?", arrayOf(snsId.toString())) }
            .onFailure { LogX.w("GroupDb markSrcFixed failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 回滚成功后把 `fixed_orig` 清空。
     *
     * 必须清：不清的话下一轮 `srcstat/fix` 会把「已经回滚过的行」当成「补过位的行」，
     * 反复出现在回滚清单里。
     */
    fun clearSrcFixed(snsId: Long) {
        val values = ContentValues().apply { putNull("fixed_orig") }
        runCatching { writableDatabase.update(T_SRC, values, "sns_id=?", arrayOf(snsId.toString())) }
            .onFailure { LogX.w("GroupDb clearSrcFixed failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 清洗「误登记」的 `fixed_orig`（2026-10-28 事故善后）。
     *
     * ## 为什么可以用 `fixed_orig & 8` 当判据
     * `fixed_orig` 的**合法语义**只有一个：`fixAlbum` 真的执行了 `UPDATE sourceType = src|8`
     * 时记下的「补位前原值」。既然原值都需要补 bit8，那它**必然不含 bit8**
     * （含了就不需要补，走的是 already 分支、不记账）。
     *
     * ⇒ `(fixed_orig & 8) != 0` 的行在合法流程下**不存在**，只可能来自
     *   2026-10-27 晚那次「already 分支也 markSrcFixed」的错误改动。
     *
     * ## 为什么必须清
     * `fixed_orig IS NOT NULL` 在 R31h 之前同时是「已删除」判据（当时见 [snsTagIds]，现已收窄）。
     * 被误登记的正常动态会被 SnsTag 当成已删，在时间线/好友主页上被标 `[删除已拦截]`（真机报障原样）。
     * ⚠️ 判据收窄后误标已止血，但**台账里的污染行仍在**（计入 [snsLedgerIds]，会虚增注入候选），
     * 所以清洗仍必须保留、且事后要复跑一次。
     *
     * @return 清掉的行数；-1 = 写库失败
     */
    fun cleanSrcFixedWithAlbumBit(): Int = runCatching {
        writableDatabase.update(
            T_SRC,
            ContentValues().apply { putNull("fixed_orig") },
            "fixed_orig IS NOT NULL AND (fixed_orig & 8) != 0",
            null
        )
    }.onFailure { LogX.w("GroupDb cleanSrcFixedWithAlbumBit failed: ${it.javaClass.simpleName}") }
        .getOrDefault(-1)

    /** 诊断用：当前有多少行 `fixed_orig` 含 bit8（= 待清洗的误登记） */
    fun countSrcFixedWithAlbumBit(): Int = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_SRC WHERE fixed_orig IS NOT NULL AND (fixed_orig & 8) != 0",
            null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.onFailure { 0 }.getOrDefault(0)

    fun countVisibleSrc(): Int = runCatching {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_SRC", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }.onFailure { 0 }.getOrDefault(0)

    /** 台账里「曾经可见」（`source_type & 2 != 0`）的行数 */
    fun countEverVisibleSrc(): Int = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_SRC WHERE (source_type & 2) != 0", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.onFailure { 0 }.getOrDefault(0)

    /** 台账里「补过位且还没回滚」的行数（`fixed_orig IS NOT NULL`） */
    fun countSrcFixed(): Int = runCatching {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_SRC WHERE fixed_orig IS NOT NULL", null
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }.onFailure { 0 }.getOrDefault(0)

    /**
     * 「被微信清过位、我们补回来过」的 snsId（`fixed_orig IS NOT NULL`，最近补的在前）。
     *
     * ## 合法用途只有两个，都跟「补位」本身有关
     * ① `SnsTimelineSourceFix` 自己的补位目标表；
     * ② 人工 `setsrc` 复核。
     *
     * ## ⛔ 绝不能当「已删除」或「注入候选」用（R31h 事故）
     * 老 KDoc 曾写「这些条目服务端已经不返回」—— **这句是错的，且代价很大**：
     * 时间线是**分页**的，台账 200 条里绝大部分只是「不在当前这一页」，
     * 拿它当「被删/缺失」判据会做两件坏事：
     * - 灰标：把正常动态标成「已阻止删除」（用户真实报障）；
     * - 注入：把正常动态重复塞回时间线底部（`attempt=24 ok=24 emptyBuf=0` 铁证）。
     *
     * ⇒ 已删判据用 [snsTagIds]，注入候选用 [listArchiveIds]，两者都只认 [T_SNS]。
     *
     * @param limit 上限，防止台账变大后每轮同步都拖一大把 id 出来
     */
    fun listSrcFixedIds(limit: Int = 200): List<Long> = runCatching {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT sns_id FROM $T_SRC WHERE fixed_orig IS NOT NULL ORDER BY last_seen DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { c ->
            val i = c.getColumnIndexOrThrow("sns_id")
            while (c.moveToNext()) out.add(c.getLong(i))
        }
        out
    }.onFailure { LogX.w("GroupDb listSrcFixedIds failed: ${it.javaClass.simpleName}") }
        .getOrDefault(emptyList())

    /**
     * 已自动设过免打扰的群。
     *
     * 用于「新加群自动免打扰」只设一次：用户手动关掉某个群的免打扰后，
     * 模块下次扫描到它时不该再给打开，否则就是跟用户对着干。
     */
    private fun createDndTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_DND (
                chatroom_id TEXT    PRIMARY KEY,
                handled_at  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createKvTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_KV (
                key   TEXT PRIMARY KEY,
                value TEXT
            )
            """.trimIndent()
        )
    }

    private fun createFriendTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_FRIEND (
                wxid       TEXT    PRIMARY KEY,
                nickname   TEXT,
                remark     TEXT,
                alias      TEXT,
                wxid_type  INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_friend_type ON $T_FRIEND(wxid_type)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_friend_name ON $T_FRIEND(remark, nickname)")
    }

    // ---------------------------------------------------------------- 分组

    fun createGroup(name: String, color: Int = 0): Long {
        val clean = name.trim()
        if (clean.isEmpty()) return -1L
        val values = ContentValues().apply {
            put("name", clean)
            put("color", color)
            put("sort_order", nextSortOrder())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            T_GROUP, null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    fun renameGroup(id: Long, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty()) return false
        val values = ContentValues().apply { put("name", clean) }
        return writableDatabase.update(T_GROUP, values, "id=?", arrayOf(id.toString())) > 0
    }

    /** 删组同时清掉成员关系，避免出现孤儿记录 */
    fun deleteGroup(id: Long): Boolean = writableDatabase.run {
        beginTransaction()
        try {
            delete(T_MEMBER, "group_id=?", arrayOf(id.toString()))
            val rows = delete(T_GROUP, "id=?", arrayOf(id.toString()))
            setTransactionSuccessful()
            rows > 0
        } finally {
            endTransaction()
        }
    }

    fun listGroups(): List<Group> {
        readableDatabase.query(
            T_GROUP, null, null, null, null, null, "pinned DESC, sort_order ASC, id ASC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toGroup())
            }
        }
    }

    fun countGroups(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $T_GROUP", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** 置顶 / 取消置顶。排序时置顶的分组排在前面（见 listGroups 的 ORDER BY） */
    fun setGroupPinned(id: Long, pinned: Boolean): Boolean {
        val values = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
        return writableDatabase.update(T_GROUP, values, "id=?", arrayOf(id.toString())) > 0
    }

    // ------------------------------------------------- 新加群自动免打扰（只设一次）

    /** 这个群是否已经自动设过免打扰 */
    /**
     * ▲▼ 排序：与相邻分组交换 sort_order（v6.2 定的手动排序方式，比拖拽好实现得多）。
     *
     * 不跨置顶边界 —— pinned 不同的两组交换 sort_order 界面上看不出变化
     * （listGroups 是 pinned 优先），所以直接拒绝，免得用户以为按钮坏了。
     */
    fun moveGroup(id: Long, up: Boolean): Boolean {
        val list = listGroups()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val to = if (up) idx - 1 else idx + 1
        if (to < 0 || to >= list.size) return false
        val a = list[idx]
        val b = list[to]
        if (a.pinned != b.pinned) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update(
                T_GROUP,
                ContentValues().apply { put("sort_order", b.sortOrder) },
                "id=?", arrayOf(a.id.toString())
            )
            db.update(
                T_GROUP,
                ContentValues().apply { put("sort_order", a.sortOrder) },
                "id=?", arrayOf(b.id.toString())
            )
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun isDndHandled(chatroomId: String): Boolean {
        readableDatabase.query(
            T_DND, arrayOf("chatroom_id"), "chatroom_id=?", arrayOf(chatroomId),
            null, null, null
        ).use { return it.moveToFirst() }
    }

    /** 全部已记账的群（模块重启后恢复内存态用；顺带按记账时间排序便于排查） */
    fun listDndHandled(): List<String> {
        readableDatabase.query(
            T_DND, arrayOf("chatroom_id"), null, null, null, null, "handled_at ASC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
    }

    /** 记下「已经给这个群设过免打扰」。之后用户手动关掉，模块不再自动打开 */
    fun markDndHandled(chatroomId: String) {
        if (chatroomId.isBlank()) return
        val values = ContentValues().apply {
            put("chatroom_id", chatroomId)
            put("handled_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(T_DND, null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    // ------------------------------------------------- 「见过的群」（新群判定的基线）

    /**
     * 建表：新加群自动免打扰的**持久化基线**。
     *
     * 为什么必须持久化（2026-10-18 真机踩坑）：原来基线只放内存，每次微信重启都
     * 用当前会话列表重新快照 ⇒ 用户被拉进的新群在下一次启动时已经躺在列表里，
     * 被当成"存量群"永远判 EXISTING，功能**重启即失效**。
     */
    private fun createSeenTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $T_SEEN (
                chatroom_id TEXT    PRIMARY KEY,
                first_seen  INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** 已见过的群（跨重启的基线）。空集合 = 首次运行，调用方要做冷启动处理。 */
    fun listSeenGroups(): Set<String> {
        return runCatching {
            readableDatabase.query(T_SEEN, arrayOf("chatroom_id"), null, null, null, null, null)
                .use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
        }.onFailure { LogX.w("GroupDb listSeen failed: ${it.javaClass.simpleName}") }
            .getOrDefault(emptySet())
    }

    /** 见过的群数量（判「是不是首次运行」用，比 list 全量再 size 省） */
    fun seenCount(): Int {
        return runCatching {
            readableDatabase.query(T_SEEN, arrayOf("COUNT(*)"), null, null, null, null, null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.onFailure { 0 }.getOrDefault(0)
    }

    /**
     * 批量登记"见过"。用事务 + CONFLICT_IGNORE，重复登记无害。
     * 只在真正需要写时开事务，避免每次刷新都产生一次磁盘 IO。
     */
    fun markSeen(chatroomIds: Collection<String>) {
        if (chatroomIds.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            writableDatabase.beginTransaction()
            try {
                val values = ContentValues()
                for (id in chatroomIds) {
                    if (id.isBlank()) continue
                    values.clear()
                    values.put("chatroom_id", id)
                    values.put("first_seen", now)
                    writableDatabase.insertWithOnConflict(
                        T_SEEN, null, values, SQLiteDatabase.CONFLICT_IGNORE
                    )
                }
                writableDatabase.setTransactionSuccessful()
            } finally {
                writableDatabase.endTransaction()
            }
        }.onFailure { LogX.w("GroupDb markSeen failed: ${it.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- 成员

    fun addMember(
        groupId: Long,
        wxid: String,
        displayName: String?,
        wxidType: Int = WxidType.FRIEND,
    ): Boolean {
        if (wxid.isBlank()) return false
        val values = ContentValues().apply {
            put("group_id", groupId)
            put("wxid", wxid)
            put("wxid_type", wxidType)
            put("display_name", displayName)
            put("added_at", System.currentTimeMillis())
        }
        val rowId = writableDatabase.insertWithOnConflict(
            T_MEMBER, null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
        return rowId > 0
    }

    fun removeMember(groupId: Long, wxid: String): Boolean =
        writableDatabase.delete(T_MEMBER, "group_id=? AND wxid=?", arrayOf(groupId.toString(), wxid)) > 0

    /**
     * 批量加人 —— M2-C 的批量勾选页用的就是它。
     *
     * 必须走事务：一次勾选几百人，逐条 insert 会写几百次 journal，
     * 慢不说，中途崩掉会留下「加了一半」的脏数据。
     *
     * 用 CONFLICT_IGNORE：重复勾选同一个人不该报错，静默跳过即可。
     *
     * @return 真正新增的行数（重复的不计）
     */
    fun addMembers(groupId: Long, friends: Collection<Friend>): Int {
        if (friends.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return writableDatabase.run {
            beginTransaction()
            try {
                // 成员独占：先把这批人从**任何**分组里摘掉，再插进目标组。
                // 不做这一步，已在别的组的人会因 wxid 主键冲突被静默跳过，
                // 用户会以为勾选没生效。
                val ids = friends.map { it.wxid }.filter { it.isNotBlank() }
                if (ids.isNotEmpty()) {
                    val ph = ids.joinToString(",") { "?" }
                    delete(T_MEMBER, "wxid IN ($ph)", ids.toTypedArray())
                }
                val values = ContentValues()
                var n = 0
                for (f in friends) {
                    if (f.wxid.isBlank()) continue
                    values.clear()
                    values.put("group_id", groupId)
                    values.put("wxid", f.wxid)
                    values.put("wxid_type", f.type)
                    values.put("display_name", f.displayName)
                    values.put("added_at", now)
                    if (insertWithOnConflict(
                            T_MEMBER, null, values, SQLiteDatabase.CONFLICT_IGNORE
                        ) > 0
                    ) n++
                }
                setTransactionSuccessful()
                n
            } finally {
                endTransaction()
            }
        }
    }

    fun removeMembers(groupId: Long, wxids: Collection<String>): Int {
        if (wxids.isEmpty()) return 0
        return writableDatabase.run {
            beginTransaction()
            try {
                var n = 0
                for (wxid in wxids) {
                    n += delete(T_MEMBER, "group_id=? AND wxid=?", arrayOf(groupId.toString(), wxid))
                }
                setTransactionSuccessful()
                n
            } finally {
                endTransaction()
            }
        }
    }

    fun clearMembers(groupId: Long): Int =
        writableDatabase.delete(T_MEMBER, "group_id=?", arrayOf(groupId.toString()))

    /** 只要 wxid 集合，判断勾选状态时用，避免把整行读出来建对象 */
    fun memberWxids(groupId: Long): Set<String> {
        readableDatabase.query(
            T_MEMBER, arrayOf("wxid"), "group_id=?", arrayOf(groupId.toString()),
            null, null, null
        ).use { cursor ->
            val out = LinkedHashSet<String>()
            while (cursor.moveToNext()) out.add(cursor.getString(0))
            return out
        }
    }

    /**
     * 已经被分进**任何**分组的 wxid 全集。
     *
     * 备份合并时要靠它判断"这个人已经属于别的组了" —— 成员独占下，
     * 一次查询比逐组问快得多，也避免漏判。
     */
    fun allMemberWxids(): Set<String> {
        readableDatabase.query(T_MEMBER, arrayOf("wxid"), null, null, null, null, null)
            .use { cursor ->
                val out = HashSet<String>()
                while (cursor.moveToNext()) out.add(cursor.getString(0))
                return out
            }
    }

    fun membersOf(groupId: Long): List<GroupMember> {
        readableDatabase.query(
            T_MEMBER, null, "group_id=?", arrayOf(groupId.toString()),
            null, null, "added_at DESC"
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toMember())
            }
        }
    }

    /**
     * 按 wxid 反查 `group_member` 里快照的备注名（`wxid` 是主键 ⇒ 单行命中）。
     *
     * 用途：[com.lx.wxgroup.feature.ConvFilter] 算分组行摘要时，会话的发送者
     * 未必是好友（群聊成员、已删好友都可能是"已归组但不在通讯录"的人）。
     * `findFriend` 查不到就回落成裸 `wxid`，显示成 `wxid_abc123` 很难看 ——
     * 用加人那一刻存下的备注名兜底。
     *
     * ⚠️ **调用顺序必须是 `findFriend` 优先**：`friend_cache` 是通讯录实时快照，
     * 用户改备注立刻反映；这里的 `display_name` 只是 `addMember` 时的快照
     * （见 `addMember`）。反过来会一直显示旧备注。
     */
    fun findMember(wxid: String): GroupMember? {
        if (wxid.isBlank()) return null
        readableDatabase.query(
            T_MEMBER, null, "wxid=?", arrayOf(wxid), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.toMember() else null
        }
    }

    /** 反查：某个好友属于哪些分组 */
    fun groupsOf(wxid: String): List<Group> {
        readableDatabase.rawQuery(
            """
            SELECT g.* FROM $T_GROUP g
            INNER JOIN $T_MEMBER m ON g.id = m.group_id
            WHERE m.wxid = ?
            ORDER BY g.sort_order ASC, g.id ASC
            """.trimIndent(),
            arrayOf(wxid)
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toGroup())
            }
        }
    }

    fun countMembers(groupId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $T_MEMBER WHERE group_id=?", arrayOf(groupId.toString())
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    // ---------------------------------------------------------------- 好友缓存

    /**
     * 增量合并好友快照。
     *
     * 这里必须是 upsert 而不是「先 delete 再 insert」：
     * 通讯录列表是懒加载的，一次只能读到几百条，全量覆盖会让上一次的结果被清空。
     * 每次同步只把新扫到的合并进来，微信自己的 rcontact 才是权威数据源。
     *
     * @return 本次实际写入的条数
     */
    fun upsertFriends(friends: List<Friend>): Int {
        if (friends.isEmpty()) return 0
        val now = System.currentTimeMillis()
        return writableDatabase.run {
            beginTransaction()
            try {
                val values = ContentValues()
                var written = 0
                for (f in friends) {
                    values.clear()
                    values.put("wxid", f.wxid)
                    values.put("nickname", f.nickname)
                    values.put("remark", f.remark)
                    values.put("alias", f.alias)
                    values.put("wxid_type", f.type)
                    values.put("updated_at", now)
                    val row = insertWithOnConflict(
                        T_FRIEND, null, values, SQLiteDatabase.CONFLICT_REPLACE
                    )
                    if (row > 0) written++
                }
                setTransactionSuccessful()
                written
            } finally {
                endTransaction()
            }
        }
    }

    /** 全量覆盖。只在确定拿到了完整数据集时才用，否则会丢数据。 */
    fun replaceFriends(friends: List<Friend>): Int {
        val now = System.currentTimeMillis()
        return writableDatabase.run {
            beginTransaction()
            try {
                delete(T_FRIEND, null, null)
                val values = ContentValues()
                for (f in friends) {
                    values.clear()
                    values.put("wxid", f.wxid)
                    values.put("nickname", f.nickname)
                    values.put("remark", f.remark)
                    values.put("alias", f.alias)
                    values.put("wxid_type", f.type)
                    values.put("updated_at", now)
                    insertWithOnConflict(T_FRIEND, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                setTransactionSuccessful()
                friends.size
            } finally {
                endTransaction()
            }
        }
    }

    fun countFriends(): Int = rawCount("SELECT COUNT(*) FROM $T_FRIEND")

    /** 按类型分组统计：验证「通讯录里到底有多少真好友」 */
    fun friendTypeCounts(): Map<Int, Int> {
        readableDatabase.rawQuery(
            "SELECT wxid_type, COUNT(*) FROM $T_FRIEND GROUP BY wxid_type", null
        ).use { cursor ->
            val out = LinkedHashMap<Int, Int>()
            while (cursor.moveToNext()) out[cursor.getInt(0)] = cursor.getInt(1)
            return out
        }
    }

    /** 真好友（排除公众号/群聊/系统号），给 M2-B 的选人列表用 */
    fun allRealFriends(): List<Friend> = queryFriends(
        selection = "wxid_type=?",
        args = arrayOf(WxidType.FRIEND.toString()),
    )

    fun allFriends(): List<Friend> = queryFriends(null, null)

    /**
     * 群聊（`wxid_type = CHATROOM = 2`），给 R24 的「群聊」Tab 用。
     *
     * ⚠️ 这些行**不是 `ContactSync` 扫出来的** —— 通讯录 tab 里没有群聊，
     * 由 [com.lx.wxgroup.core.ChatroomSource] 直接扫 `rcontact` 的 `@chatroom` 补进来。
     * 所以这里为空时先怀疑 ChatroomSource 没跑成功，不要怀疑「用户没群」。
     *
     * R29-4：源表里大量群 `remark`/`nickname` 都是空串，而 queryFriends 的
     * `ORDER BY remark, nickname` 让空串排最前 → 一进 Tab 满屏数字群号。
     * 这里在 **Kotlin 侧**把「无名群」稳定地沉到末尾（判据见 [ChatroomSortLogic]），
     * ⛔ 不动 queryFriends 的共享 ORDER BY —— 它被好友列表 / findByDisplayName 共用。
     */
    fun allChatrooms(): List<Friend> = ChatroomSortLogic.sortNamedFirst(
        queryFriends(
            selection = "wxid_type=?",
            args = arrayOf(WxidType.CHATROOM.toString()),
        ),
        remarkOf = { it.remark },
        nicknameOf = { it.nickname },
    )

    fun findFriend(wxid: String): Friend? =
        queryFriends("wxid=?", arrayOf(wxid)).firstOrNull()

    /**
     * 按「微信列表里显示出来的名字」反查好友。
     *
     * 微信的显示规则是有备注显示备注、否则显示昵称，所以两个都要比。
     * 昵称会重复（实测 2245 个好友里有 28 组重名），所以返回的是**列表**，
     * 由调用方判断歧义，绝不能悄悄取第一个。
     */
    fun findByDisplayName(name: String): List<Friend> {
        if (name.isBlank()) return emptyList()
        return queryFriends(
            selection = "wxid_type=? AND (remark=? COLLATE NOCASE OR nickname=? COLLATE NOCASE)",
            args = arrayOf(WxidType.FRIEND.toString(), name.trim(), name.trim()),
        )
    }

    /** 昵称/备注模糊搜索，M2-B 的搜索框要用 */
    fun searchFriends(keyword: String, limit: Int = 200): List<Friend> {
        if (keyword.isBlank()) return emptyList()
        val like = "%${keyword.trim()}%"
        return queryFriends(
            selection = "wxid_type=? AND (remark LIKE ? OR nickname LIKE ? OR alias LIKE ? OR wxid LIKE ?)",
            args = arrayOf(WxidType.FRIEND.toString(), like, like, like, like),
            limit = limit.toString(),
        )
    }

    private fun queryFriends(
        selection: String?,
        args: Array<String>?,
        limit: String? = null,
    ): List<Friend> {
        readableDatabase.query(
            T_FRIEND, null, selection, args, null, null,
            "wxid_type ASC, remark COLLATE NOCASE ASC, nickname COLLATE NOCASE ASC",
            limit,
        ).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(cursor.toFriend())
            }
        }
    }

    // ---------------------------------------------------------------- 偏好

    fun getPref(key: String, default: String): String {
        readableDatabase.query(
            T_KV, arrayOf("value"), "key=?", arrayOf(key), null, null, null
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) ?: default else default
        }
    }

    fun setPref(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(T_KV, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getPrefBool(key: String, default: Boolean = false): Boolean =
        getPref(key, if (default) "1" else "0") == "1"

    fun setPrefBool(key: String, value: Boolean) = setPref(key, if (value) "1" else "0")

    // ---------------------------------------------------------------- 内部

    private fun rawCount(sql: String): Int {
        readableDatabase.rawQuery(sql, null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /**
     * 诊断用：执行一条只读 SQL 并把每行拼成字符串。
     *
     * ⚠️ 仅供探针（`op=room sub=list`）使用，**不要**用它承载业务读取 ——
     * 返回的是已格式化的字符串，类型信息全丢了。
     * 存在的理由：排查「DB 里存的到底是什么」时，走 [queryFriends] → `Friend`
     * 会经过列映射，一旦怀疑映射有问题就需要看到**原始列值**才能定性。
     */
    fun debugRawRows(sql: String, maxRows: Int = 20): List<String> = runCatching {
        readableDatabase.rawQuery(sql, null).use { cursor ->
            val cols = cursor.columnNames
            val out = ArrayList<String>(maxRows)
            while (out.size < maxRows && cursor.moveToNext()) {
                out += cols.joinToString(" ") { c ->
                    "$c=${runCatching { cursor.getString(cursor.getColumnIndex(c)) }.getOrNull() ?: "null"}"
                }
            }
            out
        }
    }.getOrDefault(emptyList())

    private fun nextSortOrder(): Int {
        readableDatabase.rawQuery("SELECT MAX(sort_order) FROM $T_GROUP", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) + 1 else 0
        }
    }

    private fun Cursor.toGroup(): Group = Group(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        color = getInt(getColumnIndexOrThrow("color")),
        sortOrder = getInt(getColumnIndexOrThrow("sort_order")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        pinned = getInt(getColumnIndexOrThrow("pinned")) != 0,
    )

    private fun Cursor.toMember(): GroupMember = GroupMember(
        wxid = getString(getColumnIndexOrThrow("wxid")),
        wxidType = getInt(getColumnIndexOrThrow("wxid_type")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        addedAt = getLong(getColumnIndexOrThrow("added_at")),
    )

    private fun Cursor.toFriend(): Friend = Friend(
        wxid = getString(getColumnIndexOrThrow("wxid")),
        nickname = getString(getColumnIndexOrThrow("nickname")) ?: "",
        remark = getString(getColumnIndexOrThrow("remark")) ?: "",
        alias = getString(getColumnIndexOrThrow("alias")) ?: "",
        type = getInt(getColumnIndexOrThrow("wxid_type")),
    )

    private fun Cursor.toSnsRow(): SnsRow = SnsRow(
        snsId = getLong(getColumnIndexOrThrow("sns_id")),
        userName = getString(getColumnIndexOrThrow("user_name")),
        createTime = getLong(getColumnIndexOrThrow("create_time")),
        type = getInt(getColumnIndexOrThrow("type")),
        sourceType = getInt(getColumnIndexOrThrow("source_type")),
        localFlag = getInt(getColumnIndexOrThrow("local_flag")),
        subType = getInt(getColumnIndexOrThrow("sub_type")),
        serverExtFlag = getInt(getColumnIndexOrThrow("server_ext_flag")),
        head = getString(getColumnIndexOrThrow("head")),
        stringSeq = getString(getColumnIndexOrThrow("string_seq")),
        content = getBlob(getColumnIndexOrThrow("content")),
        attrBuf = getBlob(getColumnIndexOrThrow("attr_buf")),
        postBuf = getBlob(getColumnIndexOrThrow("post_buf")),
        at = getLong(getColumnIndexOrThrow("at")),
    )

    object WxidType {
        const val FRIEND = 0
        const val OFFICIAL_ACCOUNT = 1
        const val CHATROOM = 2
        const val UNKNOWN = 9
    }
}

data class Group(
    val id: Long,
    val name: String,
    val color: Int,
    val sortOrder: Int,
    val createdAt: Long,
    /** 手动置顶。置顶的分组排在会话列表最前（见 listGroups 的 ORDER BY） */
    val pinned: Boolean = false,
)

data class GroupMember(
    val wxid: String,
    val wxidType: Int,
    val displayName: String?,
    val addedAt: Long,
)
