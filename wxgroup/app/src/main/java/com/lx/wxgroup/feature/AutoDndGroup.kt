package com.lx.wxgroup.feature

import com.lx.wxgroup.core.ConvReader
import com.lx.wxgroup.data.GroupDb
import com.lx.wxgroup.data.Keys
import com.lx.wxgroup.data.Kv
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 新加群自动免打扰（6.1 第四条：被拉进新群时自动把该群设为免打扰）。
 *
 * ## 取证依据（全部来自 docs/取证结论.md 已实证章节，不猜 API）
 *
 * 1. **「新群聊会话」从哪来** —— 会话列表结构是 8.0.48/2588 实证结论
 *    （ConvFilter 类注释 + ConvReader 实测记录）：
 *    `ConversationListView → HeaderViewListAdapter.mAdapter`，item 是
 *    `com.tencent.mm.storage.r4`，`field_username` 就是 wxid，群聊以 `@chatroom` 结尾。
 *    → 模块启动时 hook `ListView.setAdapter`（Android 公开 API，锚点与 ConvFilter 不同
 *    功能共用同一处），首次拿到会话列表 adapter 后后台枚举一次，得到**存量群快照**。
 * 2. **「出现了一个没见过的群聊」从哪来** —— 通知链路实证结论（docs/取证结论.md
 *    「通知静音」章节）：每条微信通知都构造 `NotificationItem`，字段 `h`=talker。
 *    QuietNotificationBridge 已 hook 该方法并暴露 talker 观察者 → 本类 registered
 *    observer 被动接收。
 * 3. **「置免打扰」怎么落** —— 同样取自「通知静音」章节实证：响铃/震动由系统按
 *    channel 播放，正解是把通知的 `mChannelId` 反射改写成自建静音通道
 *    （IMPORTANCE_HIGH + setSound(null) + enableVibration(false)），通知栏/角标原样。
 *    → 本类只做「登记」，静音改写完全复用 QuietNotificationBridge（与 DND 时段
 *    / 被@静音同一条通道），**不写微信任何数据、不调任何未实证的微信 API**。
 *
 * ## 降级与已知边界（宁可不做，不猜）
 *
 * - **「入群事件」无锚点**：取证结论里没有进群回调/事件的定位。按批准的退路实现为
 *   「检测到未见过的新群聊会话时置免打扰」：存量靠启动快照区分，快照未就绪前
 *   观察到的群一律按存量（宁漏设，也不把老群静音了）。
 * - **微信自身的「消息免打扰」开关状态未动**：写微信库/调微信设置接口没有锚点，
 *   本类的「免打扰」= 该群通知走静音通道（用户侧效果：不响铃不震动）。
 * - **「自建群不设」暂不生效**：判定群主需要 chatroom owner 锚点（NotificationItem /
 *   r4 均无此字段），取证结论未覆盖 → 待取证后补（[applyOne] 的 owner 参数预留）。
 * - **手动关掉后不再自动打开**：靠 GroupDb.auto_dnd_done 台账（重启仍有效）。
 */
object AutoDndGroup {

    /** 锚点同 ConvFilter（docs/取证结论.md 会话列表结论）：微信会话列表 ListView。 */
    private const val CONV_LIST_CLASS = "com.tencent.mm.ui.conversation.ConversationListView"

    /** setAdapter 后等微信把 2900+ 会话填完再枚举，快照才准（毫秒）。 */
    private const val SNAPSHOT_DELAY_MS = 2500L

    @Volatile
    private var installed = false

    /** 存量群快照（模块启动时枚举会话列表得到）；快照就绪前一律按存量处理 */
    private val baseline: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile
    private var baselineReady = false

    /** 已登记/已记账的群（含从 GroupDb 台账恢复的），内存态去重用 */
    private val handled: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** 本次进程内新登记过的群数（observer 可能在多线程回调，必须原子） */
    private val joined = AtomicInteger(0)

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // 重启恢复：台账里的群直接灌回 bridge 的静音集合，不用等它们再发通知
            GroupDb.current()?.listDndHandled()?.forEach {
                handled.add(it)
                QuietNotificationBridge.markAutoDndTalker(it)
            }
            QuietNotificationBridge.addTalkerObserver(::onTalkerObserved)
            hookConversationList(lpparam)
            installed = true
            LogX.i(
                "QUIET ADND installed restored=${handled.size} " +
                    "enabled=${Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)}"
            )
        }.onFailure {
            installed = false
            LogX.i("QUIET ADND install failed: ${it.javaClass.simpleName}")
        }
    }

    /**
     * 会话列表首次拿到 adapter 时，后台枚举一次存量群。
     *
     * 枚举按 Android 公开 API（ListAdapter.getCount/getItem）+ ConvReader（实证字段）。
     * header/footer 行的 getItem 返回 null → ConvReader.username("") 安全跳过。
     */
    private fun hookConversationList(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.widget.ListView",
                lpparam.classLoader,
                "setAdapter",
                android.widget.ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val list = param.thisObject as? android.widget.ListView ?: return
                        if (list.javaClass.name != CONV_LIST_CLASS) return
                        if (baselineReady) return
                        val adapter = runCatching { list.adapter }.getOrNull() ?: return
                        // HeaderViewListAdapter 的 count 含 header/footer，getItem 越界返回 null，均可安全跳过
                        Thread({
                            runCatching { snapshotBaseline(adapter) }
                                .onFailure {
                                    LogX.i("QUIET ADND snapshot failed: ${it.javaClass.simpleName}")
                                }
                        }, "wxgroup-adnd-snap").start()
                    }
                }
            )
            LogX.i("QUIET ADND hooked ListView.setAdapter (baseline 快照)")
        }.onFailure { LogX.i("QUIET ADND hook setAdapter failed: ${it.javaClass.simpleName}") }
    }

    private fun snapshotBaseline(adapter: android.widget.ListAdapter) {
        Thread.sleep(SNAPSHOT_DELAY_MS) // 等微信把数据填完再快照
        val count = runCatching { adapter.count }.getOrDefault(0)
        val found = LinkedHashSet<String>()
        for (i in 0 until count) {
            val item = runCatching { adapter.getItem(i) }.getOrNull() ?: continue
            val username = ConvReader.username(item)
            if (QuietLogic.isGroupTalker(username)) found.add(username)
        }
        // ⚠️ 快照不完整比没有快照更危险：一旦 baselineReady=true，所有不在快照里的
        // 老群都会被 classifyGroup 判成 NEW → 第一条通知就把老群静音并写进台账
        // （重启后仍静音，用户侧不可逆）。所以「一个群都没枚举到」时**不置就绪**，
        // 让 classifyGroup 继续按「存量群」处理（宁漏设也不错设老群）。
        if (count == 0 || found.isEmpty()) {
            LogX.i("QUIET ADND baseline NOT ready (count=$count groups=${found.size}) — 保持存量假设")
            return
        }
        synchronized(baseline) {
            if (baselineReady) return
            baseline.addAll(found)
            baselineReady = true
        }
        LogX.i("QUIET ADND baseline ready groups=${found.size} (of $count convs)")
    }

    /**
     * 被动观察：每条带 talker 的通知过一遍（bridge 回调，可能高频 → 只做内存判定）。
     * 命中新群才落库/登记；写库丢后台线程，绝不卡通知路径。
     */
    private fun onTalkerObserved(talker: String) {
        if (!installed) return
        val verdict = runCatching {
            QuietLogic.classifyGroup(talker, baseline, handled, baselineReady)
        }.getOrDefault(QuietLogic.GroupDndVerdict.EXISTING)
        if (verdict != QuietLogic.GroupDndVerdict.NEW) return
        applyOne(talker, ownerWxid = null)
    }

    /**
     * 判定 + 登记 + 记账；失败静默放弃（绝不崩微信）。
     *
     * @param ownerWxid 群主 wxid。**当前无锚点可取，调用方恒传 null**；
     *   取证到 chatroom owner 锚点后按「群主是自己不设」补上（需求 6.1 第四条）。
     *   自身 wxid 同样未定位（取证结论 S8），两侧都无数据时该判定整体跳过。
     */
    private fun applyOne(chatroomId: String, ownerWxid: String?) {
        runCatching {
            if (!Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)) return
            if (!QuietLogic.isGroupTalker(chatroomId)) return
            // 自建群不设 —— 暂不可用：ownerWxid / 自身 wxid 均无锚点（见 KDoc）
            val selfWxid: String? = null
            if (!ownerWxid.isNullOrBlank() && !selfWxid.isNullOrBlank() && ownerWxid == selfWxid) {
                LogX.i("QUIET ADND skip own group $chatroomId")
                return
            }
            handled.add(chatroomId)
            QuietNotificationBridge.markAutoDndTalker(chatroomId)
            joined.incrementAndGet()
            // 记账落后台线程：通知路径上不能做磁盘 IO
            Thread({
                runCatching { GroupDb.current()?.markDndHandled(chatroomId) }
                    .onFailure { LogX.i("QUIET ADND ledger failed: ${it.javaClass.simpleName}") }
            }, "wxgroup-adnd-ledger").start()
            LogX.i("QUIET ADND muted ${chatroomId.take(24)} (new group → silent channel)")
        }.onFailure { LogX.i("QUIET ADND apply failed: ${it.javaClass.simpleName}") }
    }

    fun probe(sub: String): String = runCatching {
        when (sub.lowercase()) {
            "status" ->
                "installed=$installed enabled=${Kv.getBool(Keys.CHAT_AUTO_DND_NEW_GROUP)} " +
                    "baselineReady=$baselineReady baseline=${baseline.size} handled=${handled.size} joined=$joined"
            "anchor" ->
                "convList=$CONV_LIST_CLASS(list.setAdapter+ConvReader.username) " +
                    "observe=NotificationItem.h(bridge observer) " +
                    "setter=QuietNotificationBridge(mChannelId rewrite) " +
                    "joinEvent=无锚点-降级为新群会话检测 owner=无锚点-自建群判定暂缺"
            "applied" -> "handled=${handled.toList()}"
            else -> "subs=status,anchor,applied"
        }
    }.getOrElse { "error=${it.javaClass.simpleName}" }
}
