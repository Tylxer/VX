package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.SystemClock
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method

/**
 * 「恢复默认显示」+ 被救下消息的台账（需求 6.2 的辅助项）。
 *
 * ## 它做什么
 * 防撤回把消息救下来之后，消息原文一直躺在微信的 `message` 表里（撤回写入被我们拦了）。
 * 本类负责记一本台账 [GroupDb.Rescued]，然后按「恢复默认显示」开关把那些行
 * **改写成微信原生撤回的样子**，或者**改回原文**：
 *
 * | 开关 | 库里那一行 | 界面上 |
 * |------|-----------|--------|
 * | 关（默认） | `type`=原文类型、`content`=原文 | 正常气泡 |
 * | 开 | `type`=`0x10000000 or 10000`、`content`=`"昵称" 撤回了一条消息` | 灰色撤回条 |
 *
 * ⚠️ **只改显示，不改事实**：关掉开关就原样写回，消息内容不会丢。
 * 「恢复默认显示」开启期间新拦下的消息也一样（拦下后立刻按当前开关写一次）。
 *
 * ## ⚠️ 两个必须绕开的自己人
 * 1. **改写会被 [AntiRevoke] 当成真撤回拦掉** —— 我们写的正是 `type=10000` + 撤回文案，
 *    跟撤回写入一模一样。所以用 [AntiRevoke.selfWrite] 标记放行，否则等于自己拦自己。
 * 2. **查库要走 [AntiRevokeHint.BYPASS]** —— 否则「隐藏提示条」的 SQL 改写会把
 *    诊断查询也改掉（模块自己的取证工具会瞎）。
 *
 * ## 为什么用反射调 wcdb
 * 微信的消息库是 `com.tencent.wcdb.database.SQLiteDatabase`，模块编译期拿不到这个类型。
 * 而表名 / 字段名（message / msgId / type / content）都是未混淆的，反射调用足够稳。
 * 全部包在 `runCatching` 里 —— 写不进去只记一行日志，绝不牵连微信。
 */
object RevokeRescue {

    private const val TABLE_MESSAGE = "message"

    /** 撤回提示的 type 低 16 位 */
    private const val SYS_HINT = 10000

    /** 微信给撤回消息加的高位标志（真机实测：`268445456 = 0x10000000 or 10000`） */
    private const val REVOKE_FLAG = 0x10000000

    private const val CONFLICT_NONE = 0

    /** 插入提示行时用：msgId 万一撞了就静默放弃，好过抛异常 */
    private const val CONFLICT_IGNORE = 4

    /**
     * 「取 id 失败」的哨兵值。
     *
     * ⚠️ 不能是 0，更不能靠 `<= 0` 判断：提示行的 msgId 走**负数区间**，
     * -1 / -2 都是合法的成功结果。
     */
    private const val BAD_ID = Long.MIN_VALUE

    /**
     * 最近一次真正对齐过的开关状态。`null` = 还一次都没同步过。
     *
     * ⚠️ 三态是必须的：压成 Boolean 就没法区分「没同步过」和「同步成了关」，
     * 后者会导致「恢复默认显示」开启后再关掉时不会把原文写回来。
     */
    @Volatile
    private var lastApplied: Boolean? = null

    /** [syncIfNeeded] 的节流时间戳 */
    @Volatile
    private var syncAt = 0L

    // ------------------------------------------------------------------ 台账

    /**
     * 拦下一次撤回后登记：把「原本的样子」和「微信本来想写的撤回样子」都存下来。
     *
     * @param revokeContent 微信原本要写进去的 content（`'"昵称" 撤回了一条消息'`），
     *                      恢复默认显示时直接用它，界面就跟微信原生撤回完全一致。
     * @param extra 微信撤回写入的**完整字段快照**（[CvCodec] 编码，除 msgId 外）。
     *              真机实测：真撤回除 type+content 外还会写 `lvbuffer` 和 `isSend`/`reserved`，
     *              只重放 type+content 会让那行处于混合态，微信渲染成**整页空白**。
     */
    fun record(msgId: Long, talker: String, revokeContent: String?, extra: String? = null): Boolean {
        if (msgId <= 0) return false
        return runCatching {
            val orig = queryOriginal(msgId)
            // ⚠️ 查不到这一行就**不许伪装**：改写会覆盖 content，没有备份等于永久丢消息。
            // 注意是判断「行不存在」而不是「content 为 null」—— 图片 / 语音 / 视频这些
            // 消息的 content 本来就是 NULL（数据在别的字段里），那是**合法的原样**，
            // 必须照样记下来，否则「恢复默认显示」对非文本消息静默失效。
            if (orig == null) {
                LogX.w("RRV record skipped: row not found (msgId=$msgId)")
                return false
            }
            val ok = GroupDb.current()?.recordRescued(
                msgId = msgId,
                talker = talker,
                origType = orig.type,
                origContent = orig.content,
                revokeContent = revokeContent,
                revokeExtra = extra,
                origExtra = orig.extra,
            ) ?: false
            LogX.i("RRV record msgId=$msgId talker=${talker.take(12)} ok=$ok " +
                "origType=${orig.type} revoke=${revokeContent?.take(20)} " +
                "extra=${extra != null} origExtra=${orig.extra != null}")
            ok
        }.onFailure {
            LogX.w("RRV record failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /** 库里那一行此刻的样子（撤回被拦下了，所以还是原消息） */
    private class OrigRow(val type: Int, val content: String?, val extra: String?)

    private fun queryOriginal(msgId: Long): OrigRow? {
        val db = AntiRevoke.wxDb() ?: return null
        val m = rawQueryMethod(db) ?: return null
        return runCatching {
            val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
                arrayOf(msgId.toString())
            } else {
                arrayOf<Any>(msgId.toString())
            }
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS +
                    "select type,content,isSend,lvbuffer,reserved from $TABLE_MESSAGE where msgId=?",
                args
            ) ?: return null
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return null
                val type = XposedHelpers.callMethod(cur, "getInt", 0) as? Int ?: return null
                val content = XposedHelpers.callMethod(cur, "getString", 1) as? String
                // ⚠️ 原状快照：isSend / lvbuffer / reserved 这些字段一旦被伪装写进去，
                // 之后再查到的就是伪装值了 —— 必须趁现在（还没动库）存下来，
                // 「恢复默认显示」关掉时原样写回，否则自己发的消息会永久显示成对方发的。
                val snap = LinkedHashMap<String, Any?>()
                for (name in listOf("isSend", "lvbuffer", "reserved")) {
                    val idx = XposedHelpers.callMethod(cur, "getColumnIndex", name) as? Int ?: -1
                    if (idx < 0) continue
                    snap[name] = when (name) {
                        "lvbuffer" -> XposedHelpers.callMethod(cur, "getBlob", idx)
                        "isSend" -> XposedHelpers.callMethod(cur, "getInt", idx)
                        else -> XposedHelpers.callMethod(cur, "getString", idx)
                    }
                }
                OrigRow(type, content, CvCodec.encode(snap))
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 显示切换

    /**
     * 开关变化 / 模块起来后第一次有机会时调用：把台账里所有消息按开关写一遍。
     *
     * ⚠️ 会写微信的库，所以**必须放后台线程**（`kvset` 是在广播主线程里触发的）。
     *
     * @param done 写完之后回调，参数是「这一轮是否全部写成功」。[sync] 靠它决定
     *             要不要复位标志位重试（见那里的注释）。
     */
    fun applyView(on: Boolean, done: ((Boolean) -> Unit)? = null) {
        Thread {
            val ok = runCatching {
                val db = AntiRevoke.wxDb()
                if (db == null) {
                    LogX.w("RRV apply skipped: no wx db yet")
                    return@runCatching false
                }
                val list = GroupDb.current()?.listRescued().orEmpty()
                if (list.isEmpty()) return@runCatching true
                val m = updateMethod(db)
                if (m == null) {
                    LogX.w("RRV apply skipped: updateWithOnConflict not found")
                    return@runCatching false
                }

                var ok = 0
                var fail = 0
                var skip = 0
                // ⚠️ 放行标记：我们要写的正是「撤回形状」，不标记会被 AntiRevoke 自己拦掉
                AntiRevoke.selfWrite.set(true)
                try {
                    for (r in list) {
                        val cv = valuesFor(r, on)
                        // 空 ContentValues = 没有可用的伪装快照（10002 形态且快照缺失），
                        // 跳过比写出一行渲染不出来的混合态强
                        if (cv.size() == 0) {
                            skip++
                            continue
                        }
                        if (updateRow(m, db, r.msgId, cv)) ok++ else fail++
                    }
                } finally {
                    AntiRevoke.selfWrite.set(false)
                }
                LogX.i("RRV apply on=$on total=${list.size} ok=$ok fail=$fail skip=$skip")
                fail == 0
            }.onFailure {
                AntiRevoke.selfWrite.set(false)
                LogX.w("RRV apply failed: ${it.javaClass.simpleName}")
            }.getOrDefault(false)
            runCatching { done?.invoke(ok) }
        }.start()
    }

    /**
     * 把库里的显示状态对齐到开关（**两个方向都写**）。
     *
     * ⚠️ 早期版本只在「开关开着」时补写，关着就直接置位 return —— 于是
     * **关掉开关后伪装行永远不还原**，用户看到的就是「消息内容没了」。
     * 「恢复默认显示」的语义是显示层伪装、数据无损，所以关掉必须原样写回。
     *
     * ⚠️ 库还没拿到就**不要置位**：否则这一次写失败后永远不再重试，
     * 「恢复默认显示」在本次微信生命周期内就废了。
     */
    @Synchronized
    fun sync(on: Boolean) {
        if (lastApplied == on) return
        if (AntiRevoke.wxDb() == null) return
        lastApplied = on
        applyView(on) { ok ->
            // ⚠️ 写失败必须**复位**标志位，让 syncIfNeeded 下一轮再试一次。
            // 不复位的话，一次偶发失败（库被锁、反射没找到方法）就会让
            // 「恢复默认显示」在本次微信生命周期内永久失效，而日志里只有一行 warning
            // —— 正是上一轮刚修掉的那类问题。
            if (!ok) lastApplied = null
        }
    }

    /**
     * 定时对齐一次（3 秒节流）。
     *
     * 存在的理由：设置页的开关走 `Kv.putBool`，**没有任何回调**，
     * 而 adb 的 `kvset` 才会在 EntryReceiver 里调一次 [applyView]。
     * 只有定时把库状态和开关对齐，界面上切开关才真的有效。
     * 微信几乎一直在写库，所以最多延迟 3 秒就会被感知到。
     */
    fun syncIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - syncAt < 3000) return
        syncAt = now
        // 库还没抓到就等下一轮（sync 内部也会挡，这里提前返回省一次读配置）
        if (AntiRevoke.wxDb() == null) return
        runCatching { sync(Kv.getBool(Keys.ANTIREVOKE_RESTORE_VIEW)) }
            .onFailure { LogX.w("RRV sync failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 刚拦下一条：如果「恢复默认显示」正开着，立刻把它写成撤回的样子。
     *
     * 开关关着就什么都不做 —— 撤回被拦下了，库里本来就是原文，写不写都一样。
     *
     * ⚠️ 这里是在微信的 DB 线程上同步执行（拦截也是在这条线程上），
     * 只写一行，不要为了「异步」再开线程：开线程反而可能排在微信后续的重试写入之后。
     */
    fun applyOne(msgId: Long, revokeContent: String?, extra: String? = null) {
        if (!Kv.getBool(Keys.ANTIREVOKE_RESTORE_VIEW)) return
        val cv = maskCvFor(revokeContent, extra)
        if (cv.size() == 0) {
            LogX.w("RRV mask skipped: no revoke text/snapshot (msgId=$msgId)")
            return
        }
        runCatching {
            val db = AntiRevoke.wxDb() ?: return
            val m = updateMethod(db) ?: return
            AntiRevoke.selfWrite.set(true)
            try {
                if (updateRow(m, db, msgId, cv)) {
                    LogX.i("RRV mask msgId=$msgId as=${cv.getAsString("content")?.take(20)}")
                }
            } finally {
                AntiRevoke.selfWrite.set(false)
            }
        }.onFailure { LogX.w("RRV mask failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 由微信原本要写的撤回文案推出「已阻止」提示条的文案。
     *
     * 微信要写的是 `"昵称" 撤回了一条消息`，我们只要把中间那截换掉就能带上昵称：
     * `"昵称" 尝试撤回一条消息（已阻止）`。比自己拼 `("xx")` 更准（群聊里是群昵称）。
     */
    fun hintTextFrom(revokeContent: String?): String? {
        if (revokeContent.isNullOrBlank()) return null
        val who = revokeContent.substringBefore("撤回了一条消息").trim()
        if (who.isEmpty()) return null
        return "$who 尝试撤回一条消息（已阻止）"
    }

    /**
     * 往微信的 `message` 表插一条**我们自己生成的**「已阻止」提示行。
     *
     * 「显示撤回提示」打开时用：撤回被拦下了，但用户想知道「对方刚才想撤一条」。
     *
     * ⚠️ 这是往微信库里写东西，风险比只改写高，所以：
     * - 字段尽量给全，但**缺了也不会崩**（`message` 表没有必填约束，实测）；
     * - 全程 `runCatching`，写不进去就返回 0，只记一行日志，绝不牵连微信；
     * - 用 CONFLICT_IGNORE：万一 msgId 撞了，静默失败好过抛异常。
     *
     * @return 新行的 msgId（可能是**负数**，见 [nextMsgId]）；0 = 没插进去
     */
    fun insertHint(talker: String, hintText: String): Long {
        if (talker.isBlank() || hintText.isBlank()) return 0
        val db = AntiRevoke.wxDb()
        if (db == null) {
            LogX.w("RRV hint: no wx db yet")
            return 0
        }
        val m = insertMethod(db)
        if (m == null) {
            LogX.w("RRV hint: no insert method on ${db.javaClass.name}")
            return 0
        }
        val newId = nextMsgId(db)
        // ⚠️ 必须是 == BAD_ID，不能写 <= 0：新 id 本来就是负数
        if (newId == BAD_ID) {
            LogX.w("RRV hint: nextMsgId failed")
            return 0
        }
        return runCatching {
            val cv = ContentValues().apply {
                put("msgId", newId)
                // msgSvrId 微信用来做服务端去重。跟 msgId 一样走负数区间，
                // 既不可能跟真实消息撞，也不可能在 message 表若有唯一索引时互相撞。
                put("msgSvrId", newId)
                put("type", SYS_HINT)
                put("isSend", 0)
                put("talker", talker)
                put("content", hintText)
                put("createTime", System.currentTimeMillis())
                put("status", 3)
                put("flag", 0)
                put("talkerId", 0)
                put("msgSeq", 0)
                put("bizChatId", -1)
            }
            AntiRevoke.selfWrite.set(true)
            return try {
                // 4 参 = insertWithOnConflict（带冲突策略），3 参 = 普通 insert
                val row = if (m.parameterTypes.size == 4) {
                    m.invoke(db, TABLE_MESSAGE, null, cv, CONFLICT_IGNORE)
                } else {
                    m.invoke(db, TABLE_MESSAGE, null, cv)
                }
                // ⚠️ **不能靠返回值判成功**：msgId 是负数时 rowid 就是那个负数，
                // 而 -1 同时又代表「冲突/失败」——两种含义撞在一起，判不出来。
                // 直接回查一次这一行在不在，最稳（插入是低频操作，多一次查询无所谓）。
                if (rowExists(db, newId)) {
                    LogX.i("RRV hint inserted msgId=$newId (row=$row) text=${hintText.take(24)}")
                    newId
                } else {
                    LogX.w("RRV hint insert returned $row, row not found (msgId=$newId)")
                    0L
                }
            } finally {
                AntiRevoke.selfWrite.set(false)
            }
        }.onFailure {
            LogX.w("RRV hint insert failed: ${it.javaClass.simpleName}: ${it.message?.take(60)}")
        }.getOrDefault(0L)
    }

    /**
     * 插入「已阻止」提示行，并且**只插一次**。
     *
     * ⚠️ 判重是必须的：拦下一次撤回后微信会重试同一条 update 上千次
     * （实测 1966 次 / 7.5 分钟），不判重就会插出上千条一模一样的提示。
     */
    @Synchronized
    fun insertHintOnce(rescuedMsgId: Long, talker: String, revokeContent: String?): Long {
        val db = GroupDb.current() ?: return 0
        // ⚠️ 用 != 0L 判「已经插过」：提示行 msgId 是**负数**，写 > 0 会让判重永远不成立，
        // 于是微信的重试风暴（实测 1966 次）会插出上千条一模一样的提示行。
        val existing = db.getRescued(rescuedMsgId)?.hintMsgId ?: 0
        if (existing != 0L) return existing
        val text = hintTextFrom(revokeContent) ?: return 0
        val hintId = insertHint(talker, text)
        if (hintId != 0L) db.setRescuedHint(rescuedMsgId, hintId)
        return hintId
    }

    /**
     * 回查某条 message 行在不在。
     *
     * 存在的理由：插入的返回值判不了成功 —— msgId 用负数时 `rowid` 就是那个负数，
     * 而 `-1` 同时又表示「冲突 / 失败」，两种含义撞车。只能回查。
     */
    private fun rowExists(db: Any, msgId: Long): Boolean {
        val m = rawQueryMethod(db) ?: return false
        return runCatching {
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS +
                    "select 1 from $TABLE_MESSAGE where msgId=?",
                argsFor(m, msgId.toString())
            ) ?: return false
            try {
                XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean == true
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrDefault(false)
    }

    /**
     * 取下一个「已阻止」提示行专用的 msgId。
     *
     * ⚠️ **绝不能用 `max(msgId)+1`**：微信自己的 msgId 也是自增的，我们抢到 N 之后
     * 微信下一条真实消息就会拿到同一个 N，`CONFLICT_IGNORE` 让**用户的真实新消息被静默丢弃**
     * —— 这是本项目最硬的一条数据丢失路径。
     *
     * 所以提示行统一走**负数区间**（从 -1 往下）。微信永远不会生成负数 msgId，因此：
     * - 不可能跟真实消息撞号；
     * - 不会污染 `max(msgId)`，微信后续的自增完全不受影响。
     *
     * ⚠️⚠️ **负 id 会让所有「`> 0` 才算有效」的哨兵判据全部失效** —— 这是踩过一次的坑：
     * `insertHint` 里 `if (newId <= 0) return 0` 对新 id 恒真（根本不插）、
     * `row > 0` 判插入成功恒假、`hintMsgId > 0` 判重恒假（会重复插上千条）。
     * **凡是装提示行 msgId 的地方一律用 `!= 0L` 判有效，失败哨兵用 [BAD_ID]。**
     */
    private fun nextMsgId(db: Any): Long {
        val m = rawQueryMethod(db)
        if (m == null) {
            LogX.w("RRV nextMsgId: rawQuery not found")
            return BAD_ID
        }
        return runCatching {
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS +
                    "select min(msgId) from $TABLE_MESSAGE where msgId < 0",
                null
            )
            if (cur == null) {
                LogX.w("RRV nextMsgId: null cursor")
                return@runCatching BAD_ID
            }
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) {
                    LogX.w("RRV nextMsgId: no row")
                    return@runCatching BAD_ID
                }
                // 还没有任何负数行时 min() 是 NULL → getLong 得 0，从 -1 开始
                val min = (XposedHelpers.callMethod(cur, "getLong", 0) as? Long) ?: 0L
                if (min == 0L) -1L else min - 1L
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.onFailure {
            LogX.w("RRV nextMsgId err ${it.javaClass.simpleName}: " +
                "${(it.cause ?: it).message?.take(90)}")
        }.getOrDefault(BAD_ID)
    }

    /** 拦下当场就要写的伪装字段（与 [valuesFor] 的 on=true 分支同一套规则） */
    private fun maskCvFor(revokeContent: String?, extra: String?): ContentValues {
        if (!extra.isNullOrBlank()) {
            return ContentValues().also { CvCodec.decodeInto(extra, it) }
        }
        val text = revokeContent?.takeIf { it.isNotBlank() } ?: return ContentValues()
        return ContentValues().apply {
            put("type", REVOKE_FLAG or SYS_HINT)
            put("content", text)
        }
    }

    /**
     * 伪装（on=true）/ 还原（on=false）时要写进 message 表的字段。
     *
     * - on=true 优先**完整重放** [GroupDb.Rescued.revokeExtra]（微信撤回写入的原始快照，
     *   含 lvbuffer / isSend / reserved）—— 只写 type+content 真机实测会整页空白；
     * - 没有快照的老记录退回 type+content 两字段；连撤回文案都没有的（10002 形态、
     *   且快照也没存上）返回空 ContentValues，调用方**跳过**这条 —— 宁可不伪装，
     *   也不要写出一行微信渲染不出来的混合态。
     * - on=false 优先**完整写回** [GroupDb.Rescued.origExtra]（拦截瞬间该行的
     *   isSend/lvbuffer/reserved 原状快照）—— 伪装写进去的这些字段必须清掉，
     *   否则自己发的消息还原后会永久显示成对方发的（复核揪出，真机会发生）；
     * - 没有快照的老记录退回 type+content 两字段。
     */
    private fun valuesFor(r: GroupDb.Rescued, on: Boolean): ContentValues {
        if (!on) {
            return ContentValues().apply {
                // 先填原状快照的 isSend/lvbuffer/reserved，type/content 放最后写——
                // 这两个是还原的根，不能被快照里的任何字段覆盖
                if (!r.origExtra.isNullOrBlank()) CvCodec.decodeInto(r.origExtra, this)
                put("type", r.origType)
                put("content", r.origContent)
            }
        }
        if (!r.revokeExtra.isNullOrBlank()) {
            return ContentValues().also { CvCodec.decodeInto(r.revokeExtra, it) }
        }
        val text = r.revokeContent?.takeIf { it.isNotBlank() } ?: return ContentValues()
        return ContentValues().apply {
            put("type", REVOKE_FLAG or SYS_HINT)
            put("content", text)
        }
    }

    private fun updateRow(m: Method, db: Any, msgId: Long, cv: ContentValues): Boolean =
        runCatching {
            val args: Any = if (m.parameterTypes[3] == Array<String>::class.java) {
                arrayOf(msgId.toString())
            } else {
                arrayOf<Any>(msgId.toString())
            }
            m.invoke(db, TABLE_MESSAGE, cv, "msgId=?", args, CONFLICT_NONE)
            true
        }.getOrElse {
            LogX.w("RRV update failed msgId=$msgId: ${it.javaClass.simpleName}")
            false
        }

    // ------------------------------------------------------------------ 反射

    private var rawQueryRef: Method? = null

    private fun rawQueryMethod(db: Any): Method? {
        rawQueryRef?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()
        rawQueryRef = m
        return m
    }

    private var insertRef: Method? = null

    private fun insertMethod(db: Any): Method? {
        insertRef?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "insertWithOnConflict" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ContentValues::class.java
            } ?: db.javaClass.methods.firstOrNull {
                // 降级：wcdb 未必暴露 4 参版本，3 参的 insert 也一样能写进去
                it.name == "insert" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    it.parameterTypes[2] == ContentValues::class.java
            }
        }.getOrNull()
        insertRef = m
        return m
    }

    /**
     * 删掉我们自己插入的「已阻止」提示行 —— **安全阀**。
     *
     * ⚠️ 提示行是**写进微信库的**，卸载模块也不会消失，会一直留在聊天记录里。
     * 所以必须留一个手动清理的入口（`adb ... --es op ardel`）。
     *
     * @param id 非 0 时只删这一条（**可以是负数** —— 提示行全在负数区间）；
     *           传 0（或不传）则删所有 **msgId < 0** 的行
     */
    fun deleteHints(id: Long): String {
        val db = AntiRevoke.wxDb() ?: return "no wx db yet"
        val m = deleteMethod(db) ?: return "no delete method on ${db.javaClass.name}"
        return runCatching {
            // ⚠️ 用 != 0L 分支：提示行的 msgId 是负数，写 > 0 会导致「删单条」永远走不到
            val n = if (id != 0L) {
                m.invoke(db, TABLE_MESSAGE, "msgId=?", argsFor(m, id.toString()))
            } else {
                m.invoke(db, TABLE_MESSAGE, "msgId < 0", null)
            }
            val deleted = n as? Int ?: -1
            // 台账里的 hint_msg_id 也要清掉：否则「显示撤回提示」关掉时
            // 还会去隐藏一批已经不存在的行（SQL 白白变长）
            runCatching {
                val gdb = GroupDb.current() ?: return@runCatching
                for (r in gdb.listRescued(MAX_CLEAR)) {
                    // 同样不能用 > 0：负数的 hint_msg_id 才是常态
                    if (r.hintMsgId != 0L && (id == 0L || r.hintMsgId == id)) {
                        gdb.setRescuedHint(r.msgId, 0)
                    }
                }
            }
            "deleted=$deleted id=$id"
        }.getOrElse {
            "err ${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(90)}"
        }
    }

    private const val MAX_CLEAR = 2000

    /**
     * 按方法的**数组形参**构造绑定参数。
     *
     * ⚠️ 不能写死下标：`rawQuery(String, String[])` 的绑定参数在 [1]，
     * 而 `delete(String, String, String[])` 在 [2] —— 写死下标会让其中一个恒拿到
     * `Object[]`（类型不匹配 → `IllegalArgumentException` → 被 runCatching 吞掉，
     * 表现为「删不掉」却没有任何错误线索）。按类型找最稳。
     */
    private fun argsFor(m: Method, v: String): Any {
        for (t in m.parameterTypes) {
            if (t == Array<String>::class.java) return arrayOf(v)
            if (t == Array<Any>::class.java) return arrayOf<Any>(v)
        }
        return arrayOf<Any>(v)
    }

    private var deleteRef: Method? = null

    private fun deleteMethod(db: Any): Method? {
        deleteRef?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "delete" &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
            }
        }.getOrNull()
        deleteRef = m
        return m
    }

    private var updateRef: Method? = null

    private fun updateMethod(db: Any): Method? {
        updateRef?.let { return it }
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "updateWithOnConflict" &&
                    it.parameterTypes.size == 5 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ContentValues::class.java &&
                    it.parameterTypes[2] == String::class.java
            }
        }.getOrNull()
        updateRef = m
        return m
    }
}
