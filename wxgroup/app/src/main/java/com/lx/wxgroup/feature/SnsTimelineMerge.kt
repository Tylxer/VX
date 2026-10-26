package com.lx.wxgroup.feature

import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicInteger

/**
 * 方案 A（Response-Merge）—— 把「服务端已不返回、本地还在」的动态**注入时间线响应列表**。
 *
 * ## 为什么必须做 B 之后再做 A
 * 方案 B 只把 DB 里的 `sourceType` 补回 2，能保证**断网/冷启**（读库路径）可见；
 * 但**联网首屏/刷新**是拿响应内存对象渲染的，根本不读库
 * （见 `docs/conclusions/moments.md` R27 §3、真机对照实验 `docs/_r27_clear_api_forensics.md` §11.4）。
 * ⇒ 要联网可见，只能把本地行塞回响应列表。
 *
 * ## 链路（静态 dex 取证 + 已复核，详见 `.tmp/design_a.md`）
 * ```
 * c3.N(IILjava/lang/String;Lq54/zb5;)V        ← hook before，第 4 参数就是响应体
 *   ├─ 遍历 zb5.f（LinkedList<SnsObject>）→ 逐条 l5.o 写库
 *   ├─ 收集 Id → SnsTimelineRespListNotifyEvent
 *   └─ IEvent.d() 发布 → UI 渲染
 * ```
 * 在 `N` 执行**前**把对象 `add` 进 `zb5.f`，它就会随「写库 + 事件 + 渲染」全链路走完。
 * `c3` = `NetSceneSnsTimeLine`（getType=211），个人相册走 `h3`(212) / `k3`(5203)，
 * **根本不经过 `c3.N`**，所以天然只命中时间线、不会误伤相册。
 *
 * ## ⭐ 对象不用手拼：`attrBuf` 就是 SnsObject 的序列化
 * 微信自己在 `l5.f` / `l5.e`（读）里就是 `new SnsObject().parseFrom(attrBuf)`，
 * `l5.o`（写）是 `toByteArray() → setAttrBuf()`。所以我们只需
 * `new SnsObject().parseFrom(attrBuf)` 一条，**不必碰那 53 个字段**。
 *
 * ## ⛔ 判据纪律（本项目栽过三次，照做）
 * - 每个失败分支都打日志，带 snsId + 原因 + 当时 `f.size()`，**绝不允许静默失效**。
 * - 注入日志**不去重**（历史事故：`n == 1` 去重把第 2 条起吞掉，结论完全反掉）。
 * - 分层判据：L1（add 进列表）∧ L2（落库 sourceType=2）∧ L3（UI 渲染命中）才算成功，
 *   本类只负责 L1，L2/L3 由 `SnsGuard` 与 UI 探针对账。
 */
object SnsTimelineMerge {

    private const val TAG = "SNS-INJECT"
    private const val NET_SCENE = "com.tencent.mm.plugin.sns.model.c3"
    private const val RESP_CLS = "q54.zb5"
    private const val OBJ_CLS = "com.tencent.mm.protocal.protobuf.SnsObject"

    /** 一轮同步最多注入几条（防御：台账变大、或某轮响应异常小） */
    private const val MAX_PER_ROUND = 12

    @Volatile
    private var respCls: Class<*>? = null

    @Volatile
    private var objCls: Class<*>? = null

    private val attempt = AtomicInteger(0)
    private val ok = AtomicInteger(0)
    private val parseFail = AtomicInteger(0)
    private val emptyBuf = AtomicInteger(0)
    private val noDb = AtomicInteger(0)

    /** 首次命中 `c3.N` 时打一条日志（区分「钩子没触发」与「无需注入」） */
    private val firstHit = java.util.concurrent.atomic.AtomicBoolean(false)

    // ------------------------------------------------------------- 安装

    // 判据在 [SnsTimelineMergeLogic]（纯 JVM 可测，与 Xposed 隔离）

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cl = lpparam.classLoader
        val scene = runCatching { XposedHelpers.findClass(NET_SCENE, cl) }.getOrNull()
        val resp = runCatching { XposedHelpers.findClass(RESP_CLS, cl) }.getOrNull()
        val obj = runCatching { XposedHelpers.findClass(OBJ_CLS, cl) }.getOrNull()
        if (scene == null || resp == null || obj == null) {
            // ⛔ 绝不允许静默：类名漂移时这里必须看得见
            LogX.w("$TAG install: class missing scene=$scene resp=$resp obj=$obj (merge disabled)")
            return
        }
        respCls = resp
        objCls = obj
        runCatching {
            XposedHelpers.findAndHookMethod(
                scene, "N",
                java.lang.Integer.TYPE, java.lang.Integer.TYPE, String::class.java, resp,
                mergeGuard,
            )
        }.onSuccess { LogX.i("$TAG hook c3.N installed") }
            .onFailure { LogX.e("$TAG hook c3.N failed", it) }
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
        val a = param.args ?: return
        if (a.size < 4) {
            LogX.w("$TAG args=${a.size} (expected 4)")
            return
        }
        val resp = a[3] ?: return
        val list = runCatching { XposedHelpers.getObjectField(resp, "f") }.getOrNull()
            as? MutableList<Any>
        if (list == null) {
            LogX.w("$TAG no list field f on ${resp.javaClass.name}")
            return
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
        // ⛔ 反静默失效：首次命中必须留下痕迹，否则「钩子没触发」和「触发了但无需注入」无法区分
        if (firstHit.compareAndSet(false, true)) {
            LogX.i("$TAG first hit: respIds=${respIds.size} times=${times.size}")
        }
        // ② 候选 = 台账里被清过位、我们补回来的，且这一轮响应没有的
        val gdb = GroupDb.current() ?: run {
            noDb.incrementAndGet()
            LogX.w("$TAG no module db")
            return
        }
        val missing = SnsTimelineMergeLogic.pickMissing(gdb.listSrcFixedIds(200), respIds, MAX_PER_ROUND)
        if (missing.isEmpty()) return
        // ③ attrBuf 在微信自己的 SnsInfo 行里（好友删除不删行，只是 sourceType=0）
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
            // l5.f 的做法：Id 为空时用 DB 的 snsId 兜底
            if (XposedHelpers.getLongField(o, "Id") == 0L) {
                XposedHelpers.setLongField(o, "Id", id)
            }
            val ct = XposedHelpers.getIntField(o, "CreateTime")
            val idx = SnsTimelineMergeLogic.insertIndexDesc(times, ct)
            val added = runCatching { list.add(idx, o); true }.getOrDefault(false)
            if (!added) {
                LogX.w("$TAG-FAIL snsId=$id reason=add-false size=${list.size}")
                continue
            }
            times.add(idx, ct)
            ok.incrementAndGet()
            LogX.i("$TAG snsId=$id parse=ok buf=${buf.size} ct=$ct idx=$idx size=${list.size}")
        }
        LogX.i(
            "$TAG-STAT attempt=${attempt.get()} ok=${ok.get()} parseFail=${parseFail.get()} " +
                "emptyBuf=${emptyBuf.get()} noDb=${noDb.get()} resp=${respIds.size}"
        )
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
    fun stat(): List<String> = listOf(
        "$TAG-STAT attempt=${attempt.get()} ok=${ok.get()} parseFail=${parseFail.get()} " +
            "emptyBuf=${emptyBuf.get()} noDb=${noDb.get()} switch=${enabled()}"
    )
}
