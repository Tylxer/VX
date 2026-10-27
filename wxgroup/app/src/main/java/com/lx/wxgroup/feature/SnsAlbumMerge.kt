package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 方案 A（Response-Merge）—— 好友个人相册页也显示被删动态。
 *
 * 与时间线版 [SnsTimelineMerge] 同一套机制，只改三处（dex 取证见
 * `docs/_r30_lane2_album_page.md` §7）：
 * 1. 钩子类：`h3`（`NetSceneSnsUserPage`，getType=212，CGI `mmsnsuserpage`）；
 * 2. 钩子方法 `N(Lq54/jc5;Ljava/lang/String;)V` 的 before —— args[1] 是响应 `jc5`，
 *    主列表字段同叫 `f`（`LinkedList<SnsObject>`）；args[2] 是 **stringSeq**（分页游标），
 *    ⛔ **不是 wxid，绝不能用它过滤**；
 * 3. wxid 判据：目标 wxid 在实例字段 `h3.e`（`getUserName()` 直接返回它），反射读 `this.e`。
 *
 * ## ⭐ 对象不用手拼：`attrBuf` 就是 SnsObject 的序列化
 * 微信自己读库就是 `new SnsObject().parseFrom(attrBuf)`，写库走 `l5.o`（与时间线同一条），
 * 所以只需 `new SnsObject().parseFrom(attrBuf)` 一条，不必碰 SnsObject 那 52 个字段。
 *
 * ## ⛔ 判据纪律（照 [SnsTimelineMerge] 同款，本项目栽过三次）
 * - 每个失败分支都打日志，带 snsId + 原因 + 当时 `f.size()`，**绝不允许静默失效**。
 * - 注入日志**不去重**（历史事故：`n == 1` 去重把第 2 条起吞掉，结论完全反掉）。
 * - 空响应直接 return 并计数（时间线教训：`c3.N` 每秒被反复调用、`f` 为空，
 *   往空列表注入白烧 CPU）。
 */
object SnsAlbumMerge {

    private const val TAG = "SNS-ALBUM"
    private const val NET_SCENE = "com.tencent.mm.plugin.sns.model.h3"
    private const val RESP_CLS = "q54.jc5"
    private const val OBJ_CLS = "com.tencent.mm.protocal.protobuf.SnsObject"

    /**
     * 一轮同步最多注入几条。
     *
     * ⚠️ 候选来源 `GroupDb.snsTagIds()` 返回的是 `Set<Long>`（HashSet），`.toList()` 后
     * 顺序不确定；`pickForAlbum` 的 limit 截断会按这个随机顺序先到先得。所以这里必须
     * **远大于**预期台账规模，否则「同一批候选、每轮注入不同子集」会漂移。当前预期
     * 台账 12 条 < 32，取 32 兜底不触发截断；台账若再涨，优先调大这里而不是去给集合排序。
     */
    private const val MAX_PER_ROUND = 32

    @Volatile
    private var respCls: Class<*>? = null

    @Volatile
    private var objCls: Class<*>? = null

    private val attempt = AtomicInteger(0)
    private val ok = AtomicInteger(0)

    /**
     * owner 判据不匹配（= 这条动态不是当前相册 owner 的）被跳过的次数。
     *
     * ⚠️ 性能提示：owner 判据在 `readAttrBuf` + `parse` **之后**才做，所以这里每 +1 都意味着
     * 已白烧了 1 次 SQL + 1 次 protobuf 解析（每轮对全部候选跑，绝大多数被 owner 挡掉）。
     * 正确性优先不优化，但真机若相册页滚动卡，先看这里 —— 这是第一嫌疑点。
     */
    private val skipOwner = AtomicInteger(0)

    private val parseFail = AtomicInteger(0)
    private val emptyBuf = AtomicInteger(0)
    private val noDb = AtomicInteger(0)

    /** 空响应被跳过的次数（真机上 `h3.N` 会被反复调用，正常现象） */
    private val emptyResp = AtomicInteger(0)

    /** 首次命中 `h3.N` 时打一条日志（区分「钩子没触发」与「无需注入」） */
    private val firstHit = AtomicBoolean(false)

    // ------------------------------------------------------------- 安装

    // 判据在 [SnsAlbumMergeLogic]（纯 JVM 可测，与 Xposed 隔离）

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val scene = runCatching { XposedHelpers.findClass(NET_SCENE, cl) }.getOrNull()
        val resp = runCatching { XposedHelpers.findClass(RESP_CLS, cl) }.getOrNull()
        val obj = runCatching { XposedHelpers.findClass(OBJ_CLS, cl) }.getOrNull()
        if (scene == null || resp == null || obj == null) {
            // ⛔ 绝不允许静默：类名漂移时这里必须看得见
            LogX.w("$TAG install: class missing scene=$scene resp=$resp obj=$obj (album merge disabled)")
            return
        }
        respCls = resp
        objCls = obj
        runCatching {
            XposedHelpers.findAndHookMethod(
                scene, "N", resp, String::class.java, mergeGuard,
            )
        }.onSuccess { LogX.i("$TAG hook h3.N installed") }
            .onFailure { LogX.e("$TAG hook h3.N failed", it) }
    }

    private val mergeGuard = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching { onBeforeResp(param) }
                .onFailure { LogX.w("$TAG before err: ${it.javaClass.simpleName}") }
        }
    }

    // ------------------------------------------------------------- 注入

    @Suppress("UNCHECKED_CAST")
    private fun onBeforeResp(param: XC_MethodHook.MethodHookParam) {
        if (!enabled()) return
        // ⚠️ Xposed 里实例方法的 `param.args` **不含 this**：`this` 在 `param.thisObject`。
        //    `h3.N(Lq54/jc5;Ljava/lang/String;)V` 只有 2 个声明参数：
        //    args[0] = q54/jc5 响应；args[1] = String stringSeq（分页游标）。
        val a = param.args ?: return
        if (a.size < 2) {
            LogX.w("$TAG args=${a.size} (expected 2)")
            return
        }
        val scene = param.thisObject ?: return
        val resp = a[0] ?: return
        // ⛔ 目标 wxid 在实例字段 h3.e（getUserName() 返回它）；args[1] 是 stringSeq 不是 wxid
        val targetWxid = runCatching { XposedHelpers.getObjectField(scene, "e") }.getOrNull()
            as? String
        val list = runCatching { XposedHelpers.getObjectField(resp, "f") }.getOrNull()
            as? MutableList<Any>
        if (list == null) {
            LogX.w("$TAG no list field f on ${resp.javaClass.name}")
            return
        }
        // ⛔ 空响应不注入。相册路径的 `h3.N` 与时间线 `c3.N` 一样会被反复调用，
        //    增量/心跳类请求的 `jc5.f` 是空列表，往空列表注入白烧 CPU。首屏/刷新
        //    的正常响应一定非空，这条闸不会误伤主路径。
        if (list.isEmpty()) {
            emptyResp.incrementAndGet()
            return
        }
        // ⛔ 反静默失效：首次命中必须留下痕迹，否则「钩子没触发」和「触发了但无需注入」无法区分
        if (firstHit.compareAndSet(false, true)) {
            LogX.i("$TAG first hit: wxid=$targetWxid size=${list.size}")
        }
        // ① 响应里已有哪些；顺带记下时间序，用于后面按 CreateTime 定位插入位
        // ⚠️ 两个值**必须各自兜底**：任一抛异常都会让 times 与 list 错位，
        //    插入下标就会算错（把动态插到错误位置，看起来像排序 bug）
        val respIds = HashSet<Long>()
        val times = ArrayList<Int>()
        for (o in list) {
            respIds.add(runCatching { XposedHelpers.getLongField(o, "Id") }.getOrDefault(0L))
            times.add(runCatching { XposedHelpers.getIntField(o, "CreateTime") }.getOrDefault(0))
        }
        // ② 候选 = 台账并集（归档 ∪ src 补位），这一轮响应没有的
        val gdb = GroupDb.current() ?: run {
            noDb.incrementAndGet()
            LogX.w("$TAG no module db")
            return
        }
        val missing = SnsAlbumMergeLogic.pickForAlbum(gdb.snsTagIds().toList(), respIds, MAX_PER_ROUND)
        if (missing.isEmpty()) return
        // ③ attrBuf 在微信自己的 SnsInfo 行里（好友删除不删行）
        val db = SnsGuard.snsDbHandle() ?: run {
            noDb.incrementAndGet()
            LogX.w("$TAG no sns db handle (skipped ${missing.size})")
            return
        }
        val cls = objCls ?: return
        for (id in missing) {
            attempt.incrementAndGet()
            val buf = readAttrBuf(db, id)
            if (buf == null || buf.isEmpty()) {
                emptyBuf.incrementAndGet()
                LogX.w("$TAG-FAIL snsId=$id reason=empty-buf size=${list.size}")
                continue
            }
            val o = parse(cls, buf)
            if (o == null) {
                parseFail.incrementAndGet()
                LogX.w("$TAG-FAIL snsId=$id reason=parse-ex size=${list.size}")
                continue
            }
            // ⛔ 防串号核心闸门：注入对象必须属于当前相册的 owner，否则会张冠李戴
            // ⚠️ 代价：owner 判据在 readAttrBuf + parse 之后（必须先解析出 Username 才能判）。
            //    每轮对全部候选跑 SQL + parse，绝大多数被 owner 挡掉。正确性优先，不前置优化。
            val owner = runCatching { XposedHelpers.getObjectField(o, "Username") }.getOrNull()
                as? String
            if (!SnsAlbumMergeLogic.ownerMatches(owner, targetWxid)) {
                skipOwner.incrementAndGet()
                LogX.w("$TAG-FAIL snsId=$id reason=owner-mismatch obj=$owner target=$targetWxid size=${list.size}")
                continue
            }
            // l5.f 的做法：Id 为空时用 DB 的 snsId 兜底
            if (XposedHelpers.getLongField(o, "Id") == 0L) {
                XposedHelpers.setLongField(o, "Id", id)
            }
            val ct = XposedHelpers.getIntField(o, "CreateTime")
            val idx = SnsAlbumMergeLogic.insertIndexDesc(times, ct)
            val added = runCatching { list.add(idx, o); true }.getOrDefault(false)
            if (!added) {
                LogX.w("$TAG-FAIL snsId=$id reason=add-false size=${list.size}")
                continue
            }
            times.add(idx, ct)
            ok.incrementAndGet()
            LogX.i("$TAG snsId=$id parse=ok buf=${buf.size} ct=$ct idx=$idx size=${list.size}")
        }
        LogX.i(statLine())
    }

    /** `SELECT attrBuf FROM SnsInfo WHERE snsId=?` —— 走 wcdb 的 rawQuery，反射兼容 wcdb Cursor */
    private fun readAttrBuf(db: Any, snsId: Long): ByteArray? {
        val cur = runCatching {
            XposedHelpers.callMethod(
                db, "rawQuery", "SELECT attrBuf FROM SnsInfo WHERE snsId=$snsId", null
            )
        }.onFailure { LogX.w("$TAG rawQuery ex: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") != true) {
                null
            } else {
                val idx = XposedHelpers.callMethod(cur, "getColumnIndex", "attrBuf") as? Int
                if (idx == null || idx < 0) null
                else XposedHelpers.callMethod(cur, "getBlob", idx) as? ByteArray
            }
        } catch (t: Throwable) {
            LogX.w("$TAG cursor ex: ${t.javaClass.simpleName}")
            null
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    /**
     * `new SnsObject().parseFrom(attrBuf)`。
     *
     * `parseFrom` 定义在 protobuf 基类 `com.tencent.mm.protobuf.a` 上（SnsObject 本身只有
     * `<init>` / `compareContent` / `op`），返回基类类型 → 实际就是同一个实例。
     */
    private fun parse(cls: Class<*>, buf: ByteArray): Any? = runCatching {
        val o = XposedHelpers.newInstance(cls)
        val r = XposedHelpers.callMethod(o, "parseFrom", buf)
        if (cls.isInstance(r)) r else o
    }.getOrNull()

    private fun enabled(): Boolean = runCatching { Kv.getBool(Keys.ANTIREVOKE_SNS) }
        .getOrDefault(false)

    /** 探针：注入统计（`op=snsa sub=stat`） */
    fun stat(): List<String> = listOf(statLine())

    private fun statLine(): String =
        "$TAG-STAT attempt=${attempt.get()} ok=${ok.get()} skipOwner=${skipOwner.get()} " +
            "parseFail=${parseFail.get()} emptyBuf=${emptyBuf.get()} emptyResp=${emptyResp.get()} " +
            "noDb=${noDb.get()} switch=${enabled()}"
}
