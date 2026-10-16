package com.lx.wxgroup.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
        private const val DB_VERSION = 7

        private const val T_GROUP = "groups"
        private const val T_MEMBER = "group_member"
        private const val T_FRIEND = "friend_cache"
        private const val T_KV = "kv"
        private const val T_DND = "auto_dnd_done"
        private const val T_RESCUED = "rescued"
        private const val T_SNS = "sns_archive"

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

    /** 一条朋友圈动态的完整快照（含三个 BLOB） */
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

    fun listSnsArchive(limit: Int = 20): List<SnsRow> {
        val out = mutableListOf<SnsRow>()
        readableDatabase.query(
            T_SNS, null, null, null, null, null, "create_time DESC", limit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += SnsRow(
                    snsId = c.getLong(c.getColumnIndexOrThrow("sns_id")),
                    userName = c.getString(c.getColumnIndexOrThrow("user_name")),
                    createTime = c.getLong(c.getColumnIndexOrThrow("create_time")),
                    type = c.getInt(c.getColumnIndexOrThrow("type")),
                    sourceType = c.getInt(c.getColumnIndexOrThrow("source_type")),
                    localFlag = c.getInt(c.getColumnIndexOrThrow("local_flag")),
                    subType = c.getInt(c.getColumnIndexOrThrow("sub_type")),
                    serverExtFlag = c.getInt(c.getColumnIndexOrThrow("server_ext_flag")),
                    head = c.getString(c.getColumnIndexOrThrow("head")),
                    stringSeq = c.getString(c.getColumnIndexOrThrow("string_seq")),
                    content = c.getBlob(c.getColumnIndexOrThrow("content")),
                    attrBuf = c.getBlob(c.getColumnIndexOrThrow("attr_buf")),
                    postBuf = c.getBlob(c.getColumnIndexOrThrow("post_buf")),
                    at = c.getLong(c.getColumnIndexOrThrow("at")),
                )
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

    fun deleteSnsArchive(snsId: Long) {
        writableDatabase.delete(T_SNS, "sns_id=?", arrayOf(snsId.toString()))
    }

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

    /** 记下「已经给这个群设过免打扰」。之后用户手动关掉，模块不再自动打开 */
    fun markDndHandled(chatroomId: String) {
        if (chatroomId.isBlank()) return
        val values = ContentValues().apply {
            put("chatroom_id", chatroomId)
            put("handled_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(T_DND, null, values, SQLiteDatabase.CONFLICT_IGNORE)
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
