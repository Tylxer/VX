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
        private const val DB_VERSION = 4

        private const val T_GROUP = "groups"
        private const val T_MEMBER = "group_member"
        private const val T_FRIEND = "friend_cache"
        private const val T_KV = "kv"
        private const val T_DND = "auto_dnd_done"

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
