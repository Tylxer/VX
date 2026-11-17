package com.lx.wxgroup.feature

import android.os.SystemClock
import com.lx.wxgroup.core.AvatarLoader
import com.lx.wxgroup.data.GroupConvPlan
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.ui.UiKit
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * R59（需求改向）：让微信**原生渲染**的伪会话行（`wxgrp_<16hex>`，gconv 路径 1）显示
 * 「该组最后发消息的人」的**单张**头像 —— 不是四宫格、不合成 Bitmap，直接复用 lastSender
 * 自己的微信头像，观感与普通联系人头像一致。
 *
 * ## 取证结论（为什么 hook 这里）
 *
 * 会话列表头像的取图链（8.0.48 / classes4.dex + classes7.dex，dexdump 字面证据）：
 *
 * ```
 * p3.getView                                   // ConversationWithCacheAdapter（classes4）
 *   └─ setImageDrawable(n3.g, g41/e)           // n3 = ViewHolder，g = WeImageView 头像槽
 *        g41/e.draw()                          // 会话行圆角头像 BitmapDrawable（classes7）
 *          └─ f41/l.H8(username)               // AvatarImageLoader（classes7）
 *               └─ modelavatar.a1.z8().i(username)   // AvatarStorage 内存 LruCache 查表
 * ```
 *
 * 关键字节码（`_cache/_s9_classes7_dis.txt`，`f41/l.H8` 方法体）：
 *
 * ```
 * 69a798: invoke-static {}, Lcom/tencent/mm/modelavatar/a1;.z8:()...   // 取 AvatarStorage 单例
 * 69a7a0: invoke-virtual {v0, v2}, Lcom/tencent/mm/modelavatar/x;.i:(String)Bitmap
 * 69a7a8: return-object v2
 * ```
 *
 * `x.i(String)`（`_s9_classes7_dis.txt:189772`，PUBLIC 0x0001）就是 `AvatarLoader` 已在反射的
 * 同一个内存查表方法 —— **hook 这一个点，就等于接管了会话行头像要显示的那张 Bitmap**。
 * 我们在 before 里命中 `wxgrp_` 就替换返回值；非 `wxgrp_` 一律放行原方法，真实联系人零影响。
 *
 * ## 数据从哪来（方向 1：运行时查库 + 缓存，为什么不走方向 2）
 *
 * lastSender = 该组成员里 `rconversation.conversationTime` 最大的那个 wxid，与既有实现
 * 同一口径（`GroupConvStore.sync` 的 `members.mapNotNull { times[it] }.maxOrNull()`、
 * `ConvFilter.buildModel` 里 `snaps` 时间降序首个命中成员）。
 *
 * 方向 2（`ConvFilter.rebuild` 时把 gid→lastSenderWxid 写内存 Map）**不成立**：
 * `ConvFilter.rebuild()` 在 gconv=1 时走 `if (groups.isEmpty() || gconvEnabled)` 分支，
 * `models = emptyMap()`，`buildModel`/`rebuildModels` 一次都不会被调用（ConvFilter.kt:1332），
 * Map 恒空。故只能由本类自己按 username 反解 gid 后现查。
 *
 * 现查的成本控制在「绝不在 draw 热路径」：
 * - `x.i` 主线程 hook 里只做 `ConcurrentHashMap.get` + `AvatarLoader.getCached`（纯内存）；
 * - 反解 gid / 查成员 / 读 `rconversation` 全表 / 头像解码，全部丢到单一后台线程 `gconv-ava`；
 * - `rconversation` 全表只读一次（30s 节流），结果共享给所有分组行；lastSender 也带 30s 节流复核。
 *
 * ## 兜底链（无白洞/色块，直接回落微信默认）
 *
 * 1. 解不出 lastSender（无成员 / 全表读失败）→ 不设 `param.result`，放行原 `x.i` → 微信默认灰头像；
 * 2. lastSender 的头像还没进内存（WeChat LruCache 被 trim 且模块缓存 miss）→ 同上放行，后台补解码，
 *    下一帧命中后由 `publish` 触发刷新换上。
 *
 * ## 缓存版本号（lastSender 变化即换图）
 *
 * 键 = username（`wxgrp_<gid>` 已含 gid），值 = lastSenderWxid。后台每次复核（30s 节流）重新取
 * 成员集合 ∩ `rconversation` 时间，最大者即新 lastSender；变了就 `ConcurrentHashMap` 覆盖旧值，
 * 下一次 draw 自然换图。**不做** 4 成员集合的指纹比对（本轮已不需要拼图）。
 */
object NativeConvAvatar {

    const val TAG = "GCONV_AVA"

    private const val CLS_STORAGE = "com.tencent.mm.modelavatar.x"
    private const val CONV = "rconversation"

    /** 30s 复核一次 lastSender / `rconversation` 快照（绝不在 draw 热路径查库） */
    private const val REVALIDATE_MS = 30_000L

    /** username（`wxgrp_<gid>`）→ lastSenderWxid */
    private val lastSender = ConcurrentHashMap<String, String>()

    /** 已排进后台队列的 username（去重，防每帧重复排任务） */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** 每个 username 上次复核/解析时刻（节流） */
    private val lastCheck = ConcurrentHashMap<String, Long>()

    /** `rconversation` 全表 username→conversationTime 快照（多分组行共享一次读） */
    private val convTimes = ConcurrentHashMap<String, Long>()

    /** 上面快照的加载时刻（哨兵 Long.MIN_VALUE = 从未加载） */
    private val timesLoadedAt = AtomicLong(Long.MIN_VALUE)

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gconv-ava").apply { isDaemon = true }
    }

    /** 装机时读一次：本开关 ∧ gconv 总开关。gconv 关着时零生效（回滚生命线）。 */
    @Volatile
    private var enabled = false

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        enabled = runCatching {
            Kv.getBool(Keys.GCONV_AVATAR) && Kv.getBool(Keys.GCONV_MODE)
        }.getOrDefault(false)
        runCatching {
            XposedHelpers.findAndHookMethod(
                CLS_STORAGE,
                lpparam.classLoader,
                "i",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!enabled) return
                        val username = param.args[0] as? String ?: return
                        if (!GroupConvPlan.isOurs(username)) return
                        val sender = lastSender[username]
                        if (sender == null) {
                            schedule(username)      // 还没解出 lastSender → 本帧回落默认头像
                            return
                        }
                        val bm = AvatarLoader.getCached(sender)
                        if (bm != null && !bm.isRecycled) {
                            param.result = bm
                            schedule(username)      // 到点才后台复核（节流），lastSender 变了就换图
                        } else {
                            schedule(username)      // 头像还没进内存 → 后台补解码，本帧回落默认
                        }
                    }
                }
            )
            LogX.i("$TAG hooked $CLS_STORAGE.i enabled=$enabled")
        }.onFailure { LogX.w("$TAG hook failed: ${it.javaClass.simpleName}") }
    }

    /** 节流后把「解析 lastSender → 预取头像 → 发布刷新」排进后台单线程。 */
    private fun schedule(username: String) {
        val now = SystemClock.uptimeMillis()
        val last = lastCheck[username] ?: 0L
        if (now - last < REVALIDATE_MS) return
        if (!pending.add(username)) return
        lastCheck[username] = now
        io.execute {
            try {
                resolve(username)
            } catch (t: Throwable) {
                LogX.w("$TAG resolve failed: ${t.javaClass.simpleName}")
            } finally {
                pending.remove(username)
            }
        }
    }

    private fun resolve(username: String) {
        val gid = GroupConvPlan.groupIdOf(username) ?: return
        val db = GroupDb.current() ?: return
        val members = runCatching { db.memberWxids(gid) }.getOrDefault(emptySet())
        if (members.isEmpty()) {
            lastSender.remove(username)
            return
        }
        val times = ensureConvTimes()
        var sender: String? = null
        var best = Long.MIN_VALUE
        for (m in members) {
            val t = times[m] ?: continue
            if (t > best) {
                best = t
                sender = m
            }
        }
        if (sender == null) {
            lastSender.remove(username)
            return
        }
        lastSender[username] = sender
        val bm = AvatarLoader.prefetch(sender)
        if (bm != null) {
            // x.q = 写微信头像内存缓存 + 通知观察者 → 会话列表刷新，无需等用户滚动才见新头像
            UiKit.runOnMain { AvatarLoader.publish(username, bm) }
            LogX.i("$TAG resolved user=$username sender=$sender time=$best")
        } else {
            LogX.w("$TAG resolved user=$username sender=$sender avatar=null (保持默认头像)")
        }
    }

    /** 30s 节流的 `rconversation` 全表快照；读失败不更新节流时间，下次再试。 */
    private fun ensureConvTimes(): Map<String, Long> {
        val now = SystemClock.uptimeMillis()
        val last = timesLoadedAt.get()
        if (last != Long.MIN_VALUE && now - last < REVALIDATE_MS && convTimes.isNotEmpty()) {
            return convTimes
        }
        val db = AntiRevoke.wxDb()
        if (db != null) {
            val fresh = runCatching { readConvTimes(db) }.getOrDefault(emptyMap())
            if (fresh.isNotEmpty()) {
                convTimes.clear()
                convTimes.putAll(fresh)
                timesLoadedAt.set(now)
            }
        }
        return convTimes
    }

    /**
     * 反射读 `rconversation` 的 `username,conversationTime` 进 Map；排除伪会话前缀。
     *
     * 与 [GroupConvStore] 同一策略：wcdb 的 `rawQuery` 有两个重载，不缓存 Method、每次重扫；
     * 前缀用宽口径排除（`wxgrp_` / `wx__c_`），避免成员 wxid 撞上伪会话 username 时把时间顶住。
     */
    private fun readConvTimes(db: Any): Map<String, Long> {
        val out = HashMap<String, Long>()
        val cur = rawQuery(db, "select username,conversationTime from $CONV") ?: return out
        try {
            val cnt = XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            var uIdx = -1
            var tIdx = -1
            for (i in 0 until cnt) {
                when (XposedHelpers.callMethod(cur, "getColumnName", i) as? String) {
                    "username" -> uIdx = i
                    "conversationTime" -> tIdx = i
                }
            }
            if (uIdx < 0 || tIdx < 0) return out
            while (XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true) {
                val u = XposedHelpers.callMethod(cur, "getString", uIdx) as? String ?: continue
                if (u.isEmpty() || u.startsWith(GroupConvPlan.PREFIX) || u.startsWith("wx__c_")) continue
                val t = (XposedHelpers.callMethod(cur, "getString", tIdx) as? String)
                    ?.trim()?.toLongOrNull() ?: 0L
                out[u] = t
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
        return out
    }

    /** 反射 `rawQuery(String, [])`；wcdb 两个重载，精确匹配会找不到，逐次扫方法表。 */
    private fun rawQuery(db: Any, sql: String): Any? {
        val m = runCatching {
            db.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull() ?: return null
        return runCatching { m.invoke(db, sql, null) }.getOrNull()
    }

    /** 探针入口（`op=gconv sub=ava`）：现场重算每个分组的 lastSender，一行动一个事实。 */
    fun dump(): List<String> {
        val out = ArrayList<String>()
        out.add("$TAG dump enabled=$enabled cachedLastSender=${lastSender.size} cachedConvTimes=${convTimes.size}")
        val wx = AntiRevoke.wxDb()
        val times = wx?.let { runCatching { readConvTimes(it) }.getOrDefault(emptyMap()) } ?: emptyMap()
        val db = GroupDb.current()
        val groups = db?.let { runCatching { it.listGroups() }.getOrDefault(emptyList()) } ?: emptyList()
        if (wx == null) out.add("$TAG dump no wx db (先让微信写一次库)")
        for (g in groups) {
            val u = GroupConvPlan.usernameOf(g.id)
            val members = runCatching { db?.memberWxids(g.id) }.getOrNull().orEmpty()
            var sender: String? = null
            var best = Long.MIN_VALUE
            for (m in members) {
                val t = times[m] ?: continue
                if (t > best) {
                    best = t
                    sender = m
                }
            }
            out.add(
                "$TAG group gid=${g.id} user=$u members=${members.size} " +
                    "lastSender=$sender time=$best cached=${lastSender[u]}"
            )
            // R59 取证：lastSender 选对了但头像没换上，先分清是「微信内存缓存没这张」
            // 还是「磁盘也解不出来」。只读，不触发任何解码。
            for (m in members.take(6)) {
                val bm = runCatching { AvatarLoader.getCached(m) }.getOrNull()
                val disk = runCatching { AvatarLoader.prefetch(m) }.getOrNull()
                out.add("$TAG   member=$m memAvatar=${bm != null} diskAvatar=${disk != null}")
            }
        }
        return out
    }
}
