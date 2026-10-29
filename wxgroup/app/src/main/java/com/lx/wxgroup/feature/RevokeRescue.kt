package com.lx.wxgroup.feature

import android.content.ContentValues
import android.os.SystemClock
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.HintTextLogic
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.data.MediaGuardLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.io.File
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
                // ⚠️ imgPath 必打：图片/视频**没有** imgPath 时 protectMedia 是空操作，
                //    而「被撤回的图片打不开」正是靠这一行区分「没救到文件」和「救到了还打不开」
                "imgPath=${orig.imgPath} " +
                "extra=${extra != null} origExtra=${orig.extra != null}")
            // ⛔ 台账一变就让 VoiceGuard 的受保护集合缓存失效。
            // 否则 3 秒内连撤两条语音时，第二条的 voiceinfo DELETE 会命中第一条建下的
            // 缓存（不含第二条）⇒ 放行 ⇒ 第二条被删 ⇒ 播放失败
            // （详见 VoiceGuardLogic.cacheUsable 的 KDoc）。
            if (ok) VoiceGuard.invalidate()
            // ⛔ R21c：**必须在这里备份语音文件**，而且必须在 `record` 这同一次拦截里做。
            //    撤回链是「UPDATE 被拦 → executor.submit(vj0.p) → File.delete(音频)」，
            //    此刻文件还在（异步删除还没跑），晚一步就永远拿不到了。
            //    详见 VoiceBackup 的 KDoc（含 20827 目录递归扫描的实证）。
            // ⚠️ 放在台账写成功之后：没记账就说明这次撤回没救下来，不必浪费一次拷贝。
            if (ok && orig.imgPath != null) {
                val isVoice = orig.type == HintTextLogic.TYPE_VOICE
                // ⭐ R30：图片 / 视频**与语音同构**。撤回时微信一样会删磁盘文件
                //    （`image2/<桶>/<imgPath>`、`<imgPath>hd`），行和索引都还在、
                //    文件没了 ⇒ 用户点开直接失败（报障：「被撤回的图片无法打开」）。
                //    这里只「拦删除」，不做 md5 分桶、不算路径 —— 判据是文件名含 imgPath。
                if (orig.type == HintTextLogic.TYPE_IMAGE || orig.type == HintTextLogic.TYPE_VIDEO) {
                    runCatching { VoiceFileGuard.protectMedia(orig.imgPath) }
                        .onFailure { LogX.w("RRV media protect failed: ${it.javaClass.simpleName}") }
                    // ⭐ R35-2：把「这次到底救没救到文件」查清楚并打一行。
                    //   这是唯一能自证的判据 —— 见 [auditImage] 的 KDoc。
                    runCatching { auditImage(msgId, orig.imgPath) }
                        .onFailure { LogX.w("RRV imgAudit failed: ${it.javaClass.simpleName}") }
                }
                if (isVoice) {
                    // ① 登记「别删这个文件」（拦 File.delete，见 VoiceFileGuard）
                    runCatching { VoiceFileGuard.protect(orig.imgPath) }
                        .onFailure { LogX.w("RRV voice protect failed: ${it.javaClass.simpleName}") }
                    // ② 同时留一份备份（兜底：万一 File.delete 走的是 native unlink，
                    //    hook 拦不到，还有文件可以搬回来）
                    runCatching { VoiceBackup.backup(orig.type, orig.imgPath) }
                        .onFailure { LogX.w("RRV voice backup failed: ${it.javaClass.simpleName}") }
                    // ③ 排一个「删除链跑完后」的幂等还原 —— **这一步才是兜底**。
                    //    ①可能拦不住（native unlink），②只是留了个副本；
                    //    真正决定「能不能播放」的是删除动作发生之后文件是否还在原位。
                    //    ⚠️ 必须在 ② 之后：restore 读的是 backup 写出来的文件。
                    //    为什么不能靠 VoiceGuard.onDelete 里那次 restore，见
                    //    VoiceBackup.scheduleRestore 的 KDoc（早触发 ⇒ 恒 no-op）。
                    runCatching { VoiceBackup.scheduleRestore(orig.imgPath) }
                        .onFailure { LogX.w("RRV voice scheduleRestore failed: ${it.javaClass.simpleName}") }
                }
            }
            ok
        }.onFailure {
            LogX.w("RRV record failed: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * 库里那一行此刻的样子（撤回被拦下了，所以还是原消息）。
     *
     * @param imgPath 语音消息的音频文件名（26 位 hex）。**必须在备份之前取到** ——
     *                撤回链里 `vj0.p`(deleteLocalFile) 是异步删磁盘文件的，
     *                等它跑完就查不到了（也可能查得到但文件已经没了）。
     */
    private class OrigRow(
        val type: Int,
        val content: String?,
        val extra: String?,
        val imgPath: String?,
    )

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
                    "select type,content,isSend,lvbuffer,reserved,imgPath from $TABLE_MESSAGE where msgId=?",
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
                // imgPath 单独取：语音文件的压缩包名，VoiceBackup 要靠它算路径。
                // 老库没有这一列时 idx<0，返回 null，备份侧会安全跳过。
                val imgIdx = XposedHelpers.callMethod(cur, "getColumnIndex", "imgPath") as? Int ?: -1
                val imgPath = if (imgIdx >= 0) {
                    XposedHelpers.callMethod(cur, "getString", imgIdx) as? String
                } else {
                    null
                }
                OrigRow(type, content, CvCodec.encode(snap), imgPath)
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrNull()
    }

    // ------------------------------------------------- 图片文件审计（R35-2）

    /**
     * ⭐ **「撤回的图片到底能不能点开」的唯一可自证判据**。
     *
     * ## 为什么必须是这个形状
     * R21c 语音侧的教训是「拦 SQL ≠ 拦住删除」：行在、索引在、文件没了 ⇒ 点开无反应。
     * R34 又定案了 `svc.c()` 返回 true 只 = 任务受理，**不是下载成功**。
     * ⇒ 判据既不能是「某次调用返回 true」，也不能是「行还在」，只能是
     * **「磁盘上那个字节文件存在且非空」这个物理事实**。
     *
     * ## 查什么
     * 1. `ImgInfo2`（8.0.48 的真实表名，① dex DDL；**不是** `ImgInfo`）里取
     *    `bigImgPath` / `thumbImgPath` / `status` / `iscomplete` / `hashdthumb`；
     *    关联式 `ImgInfo2.msglocalid = message.msgId`（① dex，微信自己的 SQL）。
     * 2. 对拿到的路径直接 `File.exists()` + `length()`。
     *
     * ## 输出（一行，真机 grep `RRV imgAudit`）
     * ```
     * RRV imgAudit msgId=19384736 md5=0c947a1d..(32) row=1 status=0 iscomplete=1
     *   hdThumb=0 big=OK(456789) thumb=OK(12345) hd=MISS rawBig=/…/image2/0c/94/…
     * ```
     *
     * ## ⚠️ 标注清楚的不确定性
     * - `NA` = 路径不是绝对路径（8.0.48 的 `bigImgPath` 可能是 `wcf://image2/…`
     *   虚拟路径，① dex 有 `wcf://image2/` 这个串，② 但它是否就是 `bigImgPath` 的形态**未取证**）。
     *   ⇒ 出现 `NA` 时看 `rawBig=` 打出来的真实形态，据此改解析。
     * - `hd` 的取法是 `bigImgPath + "hd"` —— **③ 假设**（R34 描述的原图文件名是 `<md5>hd`，
     *   dex 里有 `image2_thumb_hd` 这个内部名，但「hd 文件名 = 大图名 + hd」没有指令级证据）。
     * - 本函数**只读**，不写微信库、不改任何状态。
     */
    private fun auditImage(msgId: Long, imgPath: String?) {
        val md5 = MediaGuardLogic.hexToken(imgPath)
        if (!MediaGuardLogic.isMd5(md5)) {
            // 与 VoiceFileGuard.protectMedia 同一口径：token 不是 32 位 ⇒ 保护池里的 key
            // 命中不了文件名 ⇒ 这张图**实际没被保护**，必须留痕（否则是静默失效）
            LogX.w("RRV imgAudit BAD md5 len=${md5?.length} msgId=$msgId imgPath=${imgPath?.take(48)}")
        }
        var big: String? = null
        var thumb: String? = null
        var status = -999
        var iscomplete = -999
        var hdThumb = -999
        var row = 0
        val h = AntiRevoke.msgQueryHandle()
        if (h == null) {
            LogX.w("RRV imgAudit no db handle msgId=$msgId (ImgInfo2 查不了，只有 md5)")
        } else {
            val (db, m) = h
            runCatching {
                // svrId 从「到达锚点」拿（R35-2 新增）；拿不到就只按 msglocalid 查。
                // ⚠️ msglocalid 有索引（`CREATE INDEX msgLocalIdIndex ON ImgInfo2 (msglocalid)`，① dex），
                //    这条查询跑在微信的 DB 线程上，必须便宜。
                val svr = AntiRevoke.imgAnchorOf(msgId)?.svrId ?: 0L
                val sql = AntiRevokeHint.BYPASS + if (svr > 0) {
                    "select bigImgPath,thumbImgPath,status,iscomplete,hashdthumb " +
                        "from ImgInfo2 where msglocalid=? or msgSvrId=? limit 1"
                } else {
                    "select bigImgPath,thumbImgPath,status,iscomplete,hashdthumb " +
                        "from ImgInfo2 where msglocalid=? limit 1"
                }
                val args: Any = if (svr > 0) argsFor2(m, msgId.toString(), svr.toString())
                else argsFor2(m, msgId.toString())
                val cur = m.invoke(db, sql, args) ?: return@runCatching
                try {
                    if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return@runCatching
                    row = 1
                    big = XposedHelpers.callMethod(cur, "getString", 0) as? String
                    thumb = XposedHelpers.callMethod(cur, "getString", 1) as? String
                    status = XposedHelpers.callMethod(cur, "getInt", 2) as? Int ?: -999
                    iscomplete = XposedHelpers.callMethod(cur, "getInt", 3) as? Int ?: -999
                    hdThumb = XposedHelpers.callMethod(cur, "getInt", 4) as? Int ?: -999
                } finally {
                    runCatching { XposedHelpers.callMethod(cur, "close") }
                }
            }.onFailure {
                LogX.w("RRV imgAudit query failed msgId=$msgId: ${it.javaClass.simpleName}")
            }
        }
        // ③ 假设（见 KDoc）：原图文件名 = 大图文件名 + "hd"
        val hd = if (!big.isNullOrBlank() && big!!.startsWith("/")) fileState(big + "hd") else "NA"
        LogX.i(
            "RRV imgAudit msgId=$msgId md5=${md5?.take(10)}..(${md5?.length}) row=$row " +
                "status=$status iscomplete=$iscomplete hdThumb=$hdThumb " +
                "big=${fileState(big)} thumb=${fileState(thumb)} hd=$hd " +
                "rawBig=${big?.take(40)} rawThumb=${thumb?.take(40)}"
        )
    }

    /** `OK(字节数)` / `MISS` / `NA`（非绝对路径，stat 不了）/ `NULL`（列为空）/ `ERR` */
    private fun fileState(p: String?): String {
        if (p.isNullOrBlank()) return "NULL"
        if (!p.startsWith("/")) return "NA"
        return runCatching {
            val f = File(p)
            if (f.exists()) "OK(${f.length()})" else "MISS"
        }.getOrDefault("ERR")
    }

    /** 两个绑定参数，按反射方法的数组形参类型构造（写死下标会恒拿到 Object[] 而抛 IllegalArgumentException） */
    private fun argsFor2(m: Method, a: String, b: String? = null): Any {
        val wantString = m.parameterTypes.any { it == Array<String>::class.java }
        return if (b == null) {
            if (wantString) arrayOf(a) else arrayOf<Any>(a)
        } else {
            if (wantString) arrayOf(a, b) else arrayOf<Any>(a, b)
        }
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
     * 由微信原本要写的撤回文案 + 被救消息的原样，推出「已阻止」角标的文案。
     *
     * 微信要写的是 `"昵称" 撤回了一条消息`，我们只要把中间那截换掉就能带上昵称：
     * `"昵称" 尝试撤回一条消息（已阻止）`。比自己拼 `("xx")` 更准（群聊里是群昵称）。
     *
     * @param origType / [origContent] 被救消息的**原本** type / content（来自台账），
     *        由 [HintTextLogic.summary] 折成内容摘要接在句尾（空摘要则不接）。
     *
     * 📌 R21d 起这条文案只给**角标**用（[com.lx.wxgroup.feature.HintBadge] 不直接用，
     * 它只用 `HintTextLogic.summary`）；独立提示行的插入路径已废除。
     */
    fun hintTextFrom(revokeContent: String?, origType: Int, origContent: String?): String? {
        val who = HintTextLogic.whoFrom(revokeContent) ?: return null
        return HintTextLogic.buildHint(who, HintTextLogic.summary(origType, origContent))
    }

    // ------------------------------------------------------------------ 启动自检

    /**
     * ⛔ **已废（2026-10-25 R21d，方案 1）—— 不许再往里写。**
     *
     * 曾经的做法：往微信 `message` 表插一条 `type=10000` 的「已阻止」提示行，
     * msgId 走负数区间（`-1`、`-2`…）以避开与真实消息撞号。
     *
     * ## 为什么废掉（两次独立定案，都不是猜的）
     *
     * **① 会让微信闪退。** 微信 `h9.convertFrom`（消息行反序列化）里校验
     * `msgId` 必须 ≥ 一个下界（静态解析 `h9.x1`：`const-wide/16 0xE100`(=57600)
     * + `cmp-long` + `if-lez` 放行 + `throw fr4.b`），**负数一律被拦**。
     * 崩溃链：`ChattingUIFragment.V → s2.a → v2.k0 → j9.F8 → h9.convertFrom → h9.x1
     * → fr4.a.g → fr4.a.h`，抛 `fr4.b: msgId not in the reasonable scope`。
     * ⇒ 进任何含负数 msgId 行的会话**必崩**。详见 `docs/_r21d_crash_root.md`。
     * ⚠️ 原注释里「微信永远不会生成负数 msgId，因此不可能撞号」这半句是对的，
     * **但推论错**：微信不「生成」不代表它「肯读」。
     *
     * **② 位置必然错位。** 提示行 `createTime = System.currentTimeMillis()`
     * （= 撤回那一刻），而原文 `createTime` 是**发出那一刻**。微信按 `createTime`
     * 排序 ⇒ 提示行永远排在最后，与原文隔几十条消息。
     * 这是「另起一条记录」这个做法本身的固有缺陷，改任何字段都治不好。
     *
     * ## 现在怎么做
     * 改画**角标**：不插任何行，靠 `HintBadge` 挂 `onBindViewHolder`，
     * 在**原文那个 itemView** 上画一个小标签 ⇒ 物理上不可能与原文分离。
     *
     * @return **恒为 0**（永远插不进去）。保留签名只为让 `arins` 调试 op 不炸。
     */
    @Deprecated("R21d 方案 1：不再往 message 表插行（负数 msgId 会让微信闪退 + 位置错位）")
    fun insertHint(talker: String, hintText: String): Long {
        LogX.w("RRV hint DISABLED (R21d 方案1): talker=${talker.take(12)} text=${hintText.take(20)}")
        return 0L
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

    /**
     * ⛔ **已删（R21d 方案 1）** 原来的 `insertMethod()` / `insertRef`：
     * 它们是 `insertHint()` 的反射依赖，而 `insertHint()` 已废（不再往 `message` 表插行）。
     * 现在全模块对微信 `message` 表**只读**（`rawQuery`）+ **只 update**（已有行），
     * **零 insert** ⇒ 这条数据丢失/闪退路径从根上消失。
     */

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

    // ------------------------------------------------------------------ 启动自检

    /** [selfCheckNegRows] 是否已经跑过（每个微信进程只跑一次） */
    @Volatile
    private var selfChecked = false

    /**
     * 启动自检：**清理历史遗留的负数 msgId 行**。
     *
     * ## 为什么必须有这一步
     * 方案 1 之前的版本会往 `message` 表插负数 msgId 的提示行，而那些行是**写进微信库的**
     * —— 卸载模块、清数据、换 APK 都不会让它们消失。它们留在库里就会让微信在
     * **进入那个会话时闪退**（`fr4.b: msgId not in the reasonable scope`，
     * 见 `docs/_r21d_crash_root.md` §1-§3）。
     *
     * 所以每次微信进程起来、拿到消息库句柄之后，必须主动扫一次：只要还有
     * `msgId < 0` 的行就全删掉，并留一条 `LogX.w`。
     *
     * ## 为什么删「所有 `msgId < 0`」是安全的
     * 微信**不会生成**负数 msgId（`msgId` 是自增主键的正整数区间）。所以负数行
     * 只可能是**我们自己插的**。这条判据已由真机 dump 交叉验证：
     * 当时库里恰好 10 条负数行，逐列 dump 后三重校验全过（`msgId<0` + `type=10000`
     * + `content` 含阻止文案），见 `tools/_r21d_dump_neg_rows.py` 的产物
     * `_r21d_neg_rows.json`。
     *
     * ⚠️ 但**仍然先查后删**，不做「无条件 DELETE」：查得到条数才能写进日志，
     * 而「删了几条」是事后判断「这次自检到底干了什么」的唯一线索。
     *
     * ⚠️ 会写微信的库 ⇒ **必须在后台线程调**（`EntryReceiver` / bootstrap 里都是 io）。
     * 内部自己 catch 全部异常，绝不牵连微信启动。
     */
    fun selfCheckNegRows() {
        if (selfChecked) return
        selfChecked = true
        runCatching {
            val db = AntiRevoke.wxDb()
            if (db == null) {
                // 句柄还没抓到：把标志复位，让下一次有机会再试。
                // ⚠️ 不复位的话，这次没库就永久不再自检 —— 而负数行正是「进会话必崩」，
                // 用户会以为模块坏了。
                selfChecked = false
                return
            }
            val n = countNegRows(db)
            if (n <= 0) {
                LogX.i("RRV selfcheck: no negative msgId rows (ok)")
                return
            }
            LogX.w("RRV selfcheck: found $n negative msgId rows -> deleting (they crash wx)")
            val r = deleteHints(0)
            LogX.w("RRV selfcheck: cleanup result $r (was $n)")
        }.onFailure {
            LogX.w("RRV selfcheck failed: ${it.javaClass.simpleName}")
        }
    }

    /** `select count(*) from message where msgId < 0`；-1 = 查不到（不是「没有」） */
    private fun countNegRows(db: Any): Long {
        val m = rawQueryMethod(db) ?: return -1
        return runCatching {
            val cur = m.invoke(
                db,
                AntiRevokeHint.BYPASS +
                    "select count(*) from $TABLE_MESSAGE where msgId < 0",
                null
            ) ?: return -1
            try {
                if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean != true) return -1
                (XposedHelpers.callMethod(cur, "getLong", 0) as? Long) ?: -1
            } finally {
                runCatching { XposedHelpers.callMethod(cur, "close") }
            }
        }.getOrDefault(-1)
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
