package com.lx.wxgroup.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import com.lx.wxgroup.data.Friend
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.SyncStats
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * M2-A：把通讯录里的好友读全、落进本地库，并打统计日志。
 *
 * 这一步的核心目的不是「有数据」，而是验证三个假设：
 *   1. wxid 全局唯一（可以做主键）
 *   2. 数量级和预期一致（通讯录显示多少，这里就该扫出多少）
 *   3. 类型可区分（好友 / 公众号 / 群聊不会混在一起）
 * 任何一条不成立，后面的分组 UI 都是白做。
 *
 * 全程在后台线程：扫描是对象图反射遍历，几千个节点在主线程上会掉帧。
 */
object ContactSync {

    private const val MAX_RUNS = 5
    private const val SAMPLE_SIZE = 15

    // 通讯录是懒加载的：一次只有几百条，真实好友数 2245。
    // 反复把列表滚到底部，逼微信把剩下的加载出来，边滚边收。
    private const val MAX_CRAWL_ROUNDS = 30
    private const val CRAWL_WAIT_MS = 700L

    // 滚动爬取会真的把用户的列表滚一遍，不能每次进通讯录都来一次。
    // 平时只静默收集当前已加载的部分，离上次全量超过这个间隔才爬。
    private const val PREFS = "wxgroup_sync"
    private const val KEY_LAST_FULL = "last_full_sync"
    private const val FULL_INTERVAL_MS = 30 * 60 * 1000L

    private val runCount = AtomicInteger(0)
    private val touched = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    @Volatile
    private var appContext: Context? = null

    /** 在 bootstrap 里用宿主的 Context 调一次，否则冷却时间没法持久化 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 由 UiProbe 在抓到通讯录列表时回调 */
    fun onContactList(listView: View, adapter: Any) {
        LogX.i("SYNC trigger adapter=${adapter.javaClass.name}")
        if (!touched.add(adapter)) {
            LogX.i("SYNC trigger: adapter already seen, skip")
            return
        }
        if (runCount.get() >= MAX_RUNS) {
            LogX.i("SYNC trigger: run limit reached, skip")
            return
        }
        runCount.incrementAndGet()

        Thread({
            runCatching { doSync(listView, adapter) }
                .onFailure { LogX.e("SYNC failed", it) }
        }, "wxgroup-sync").start()
    }

    private fun prefs() = runCatching {
        appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()

    /** 库里几乎是空的、或离上次全量超过 30 分钟，才值得打扰用户滚一遍列表 */
    private fun shouldCrawl(db: GroupDb?): Boolean {
        if ((db?.countFriends() ?: 0) < 200) return true
        val last = prefs()?.getLong(KEY_LAST_FULL, 0L) ?: 0L
        return System.currentTimeMillis() - last > FULL_INTERVAL_MS
    }

    private fun markFullSync() {
        // 用 commit 而不是 apply：微信进程随时可能被杀，apply 的异步落盘可能来不及
        runCatching {
            prefs()?.edit()?.putLong(KEY_LAST_FULL, System.currentTimeMillis())?.commit()
        }.onFailure { LogX.w("SYNC markFullSync failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 反复把通讯录滚到底部，逼微信加载后续分页，每轮扫一次增量。
     *
     * 之所以必须这么做：通讯录一次只加载几百条，而真实好友数是 2245。
     * 没有别的干净路子 —— 全量数据在加密的 rcontact 表里，
     * 而内存里只有已经渲染过、或即将渲染的那一批对象。
     */
    private fun crawl(listView: View, adapter: Any): List<Friend> {
        val handler = Handler(Looper.getMainLooper())
        val collected = LinkedHashMap<String, Friend>()
        var lastCount = -1

        for (round in 1..MAX_CRAWL_ROUNDS) {
            val before = itemCountOf(adapter)
            scrollTo(handler, listView, before - 1)
            Thread.sleep(CRAWL_WAIT_MS)

            // 每轮全扫再合并：不确定微信是「累加加载」还是「窗口回收」，
            // 合并最保险，重复 wxid 会被 putIfAbsent 吃掉
            ContactScanner.scan(adapter).forEach { collected.putIfAbsent(it.wxid, it) }

            val after = itemCountOf(adapter)
            LogX.i("CRAWL r=$round itemCount=$before->$after collected=${collected.size}")
            if (round > 1 && after <= lastCount) break
            lastCount = after
            if (after <= 0) break
        }

        // 滚回顶部，别把用户的界面停在列表最底下
        scrollTo(handler, listView, 0)
        return collected.values.toList()
    }

    /** RecyclerView 只能主线程操作 */
    private fun scrollTo(handler: Handler, listView: View, position: Int) {
        if (position < 0) return
        val latch = CountDownLatch(1)
        handler.post {
            runCatching {
                listView.javaClass
                    .getMethod("scrollToPosition", Int::class.javaPrimitiveType)
                    .invoke(listView, position)
            }
            latch.countDown()
        }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    private fun itemCountOf(adapter: Any): Int = runCatching {
        adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int
    }.getOrNull() ?: 0

    private fun doSync(listView: View, adapter: Any) {
        val t0 = System.currentTimeMillis()

        val db0 = GroupDb.current()
        val full = shouldCrawl(db0)
        LogX.i("SYNC start mode=${if (full) "full(crawl)" else "visible-only"}")
        val all = if (full) crawl(listView, adapter) else ContactScanner.scan(adapter)
        val elapsed = System.currentTimeMillis() - t0
        LogX.i("SYNC collected=${all.size} in ${elapsed}ms")

        val friends = all.filter { it.isRealFriend }
        val stats = SyncStats(
            scanned = all.size,
            unique = all.size, // scan() 内已按 wxid 去重
            blank = 0,
            friend = friends.size,
            official = all.count { it.type == GroupDb.WxidType.OFFICIAL_ACCOUNT },
            chatroom = all.count { it.type == GroupDb.WxidType.CHATROOM },
            unknown = all.count { it.type == GroupDb.WxidType.UNKNOWN },
            elapsedMs = elapsed,
        )

        LogX.i(
            "SYNC scan: contacts=${stats.scanned} friends=${stats.friend} " +
                "gh=${stats.official} chatroom=${stats.chatroom} other=${stats.unknown} " +
                "in ${stats.elapsedMs}ms"
        )

        val db = GroupDb.current()
        if (db == null) {
            LogX.w("SYNC db not ready, skip persist")
        } else {
            val before = db.countFriends()
            val saved = db.upsertFriends(all)
            val after = db.countFriends()
            val counts = db.friendTypeCounts()
            LogX.i(
                "SYNC upsert=$saved db: $before -> $after types=" +
                    counts.entries.joinToString { "${typeName(it.key)}=${it.value}" }
            )
        }
        if (full) markFullSync()

        // 样本：确认字段没读错（备注为空是正常的，大多数人没设备注）
        friends.take(SAMPLE_SIZE).forEachIndexed { i, f ->
            LogX.i("SYNC#${i + 1} ${f.wxid} | ${f.displayName} | remark=${f.remark.ifEmpty { "-" }}")
        }
    }

    private fun typeName(type: Int): String = when (type) {
        GroupDb.WxidType.FRIEND -> "friend"
        GroupDb.WxidType.OFFICIAL_ACCOUNT -> "gh"
        GroupDb.WxidType.CHATROOM -> "chatroom"
        else -> "other"
    }

    /** 给后续 UI 用：当前缓存的真好友 */
    fun cachedFriends(): List<Friend> =
        runCatching { GroupDb.current()?.allRealFriends() ?: emptyList() }
            .getOrDefault(emptyList())
}
