package com.lx.wxgroup.probe

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import com.lx.wxgroup.util.LogX
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「消息免打扰」开关 setter 栈回溯探针。
 *
 * ## 为什么要有这个（取证背景）
 * 本地 SQLite（rconversation 30 列 / chatroom 33 列 / userinfo2）+ MMKV + shared_prefs
 * 已全部排除（见 docs/取证结论.md「群消息免打扰」章节），剩余唯一有价值的路是
 * docs/免打扰落点候选分析.md §3.7 的**最终兜底**：hook 公开 Android API 抓 setter，
 * 定位微信设置页点击「消息免打扰」时的处理类，直接调它的 setter。
 *
 * ## 已取证结论（2026-10-18 00:40 真机）
 * - 开关组件 = `com.tencent.mm.ui.widget.MMSwitchBtn`，`super=android.view.View`
 *   （**不是 CompoundButton**，所以 uiautomator 拿不到 checked、setChecked hook 不命中）。
 * - 它自己重写 onTouchEvent，状态切换在 `onTouchEvent` 里。
 * - 关键方法：`setCheck(boolean)`（状态设置器）、`setSwitchListener(n1)`（业务回调）、
 *   `onClick(View)`、`onTouchEvent(MotionEvent)`、`a(boolean)`/`b(boolean)`（疑似状态/动画）。
 *
 * ## 用法
 * ```
 * # 1. 打开某个群的「聊天信息」页，停在三个开关那一屏
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op mute --es sub on --ei sec 300
 * # 2. 手动点一下「消息免打扰」开关
 * python tools/dev.py q 'MUTE_SET'     # 看 SWITCH 栈，定位业务回调类
 * # 3. （可选）反射 dump 组件方法/字段
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op mute --es sub dump
 * # 4. 关掉
 * adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
 *      --es op mute --es sub off
 * ```
 *
 * ## 约束
 * - 取证工具不是功能：默认不挂，只有广播 `sub=on` 才装，`off` 全卸载；
 * - 触摸兜底按 this 类名去重（滑动不刷屏）；MMSwitchBtn 专项挂钩**不去重**（每次点击都打栈）；
 * - 全部 try/catch，hook 失败不影响宿主。
 */
object MuteSetterProbe {

    private const val FRAMES = 16
    private const val MAX_PARENT_UP = 3

    private val NOISE = arrayOf(
        "de.robv.android.xposed",
        "com.lx.wxgroup",
        "java.lang.reflect",
        "dalvik.system",
        "android.os.Handler",
        "android.view.View",
        "android.widget.CompoundButton",
        "android.view.ViewGroup",
    )

    @Volatile
    private var loader: ClassLoader? = null

    /** 主线程 handler：发 CGI 必须切到主线程（见 setMute 注释） */
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    @Volatile
    private var until = 0L

    /** 已打过的调用点（去重用），key = 方法签名 + this类名 + 前3帧 */
    private val seen = Collections.synchronizedSet(LinkedHashSet<String>())

    /** 本次窗口命中计数（原子，多线程回调） */
    private val hits = AtomicInteger(0)

    private val unhooks = Collections.synchronizedList(mutableListOf<XC_MethodHook.Unhook>())

    private val seq = AtomicInteger(0)

    /** 只保存 classLoader，不打钩子 —— 平时零开销 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    fun command(sub: String, sec: Int, arg: String? = null, sub2: String? = null) {
        when (sub.lowercase()) {
            "on" -> install(sec)
            "off" -> uninstall()
            "stat" -> own(
                "stat hooks=${unhooks.size} hits=${hits.get()} seen=${seen.size} " +
                    "windowLeft=${(until - System.currentTimeMillis()).coerceAtLeast(0)}ms"
            )
            "dump" -> dumpSwitchClass()
            // 真免打扰 setter 验证（core.RealMuteSetter，复刻微信自己的开关）
            "dndread" -> readMute(arg)
            "dndset" -> setMute(arg, sub2)
            "dndhook" -> hookFactoryC()
            else -> own("unknown sub=$sub (on|off|stat|dump|dndread|dndset|dndhook)")
        }
    }

    /**
     * 只读：验证「读免打扰状态」链路。两种读法都打，因为 `ChatroomInfoUI.I` 实证有
     * **两个来源**（`j!=0` 读 `contact.S`，`j==0` 才读 `vj0.e2.K`），要先确定哪个
     * 跟 UI 开关一致，功能侧才敢用它做"要不要发"的判据。
     *   `--es op mute --es sub dndread --es val <chatroomId>`
     */
    private fun readMute(chatroomId: String?) {
        val cl = loader ?: run { own("dndread: no classLoader"); return }
        if (chatroomId.isNullOrBlank()) {
            own("dndread: need --es val <chatroomId>")
            return
        }
        val rm = com.lx.wxgroup.core.RealMuteSetter
        // 反射读库可能慢，丢后台（广播主线程不能卡）
        Thread({
            val k = rm.isMuted(cl, chatroomId)
            val s = rm.isMutedByField(cl, chatroomId)
            own("DNDREAD id=${chatroomId.take(24)} K(vj0.e2)=$k S(field.S)=$s")
        }, "wxgroup-dndread").start()
    }

    /**
     * 真调用：发 oplog CGI（复刻微信开关点击）。
     *   `--es op mute --es sub dndset --es val <chatroomId> [--es sub2 off|f0|f1]`
     *   - 不带 sub2：开启免打扰（走"读状态→传操作前状态"的安全路径）
     *   - sub2=off：关闭免打扰
     *   - sub2=f0 / f1：**取证用**，跳过状态判定直接传 0 / 1
     * 判成败：日志 ok=true 且 factory 不是 q64.g（兜底空实现），再看 UI 开关是否变。
     */
    private fun setMute(chatroomId: String?, sub2: String?) {
        val cl = loader ?: run { own("dndset: no classLoader"); return }
        if (chatroomId.isNullOrBlank()) {
            own("dndset: need --es val <chatroomId>")
            return
        }
        val rm = com.lx.wxgroup.core.RealMuteSetter
        val mute = sub2?.lowercase() != "off"
        val force = when (sub2?.lowercase()) {
            "f0" -> 0
            "f1" -> 1
            else -> null
        }
        own("DNDSET begin id=${chatroomId.take(24)} mute=$mute force=$force")
        // ⚠️ 必须在主线程发：微信 CGI 链路依赖主线程上下文，后台线程调 b() 不报错
        //    但很可能根本没发出去（02:26 那轮就是后台线程发的，UI 开关没变）。
        //    contactOf 的反射读库有耗时，先放后台读，再切主线程发。
        Thread({
            val contact = rm.contactOf(cl, chatroomId)
            own("DNDSET contact=${contact?.javaClass?.simpleName ?: "NULL"}")
            mainHandler.post {
                val r = rm.setMuted(cl, chatroomId, mute, force)
                own(
                    "DNDSET ok=${r.ok} stateInt=${r.stateInt} factory=${r.factoryClass} " +
                        "before=${r.before} ${r.detail}"
                )
                // 实测 sync 延迟约数十秒（3s 复查必然还是旧值），这里等 25s 再复查
                Thread({
                    Thread.sleep(25000)
                    own(
                        "DNDSET after25s S=${rm.isMutedByField(cl, chatroomId)} " +
                            "K=${rm.isMutedByK(cl, chatroomId)}"
                    )
                }, "wxgroup-dndset-recheck").start()
            }
        }, "wxgroup-dndset").start()
    }

    /**
     * 取证：hook 工厂实现 `ln0/a.c` / `uj/a.c`，打印微信**自己点击开关时**传给
     * `c(chatroomId, int)` 的真实 int。这直接定死"开启免打扰该传几"，不依赖任何
     * 状态读取是否正确。
     *   `--es op mute --es sub dndhook` 然后去点「消息免打扰」开关，看 FACTC 日志
     */
    private fun hookFactoryC() {
        val cl = loader ?: run { own("dndhook: no classLoader"); return }
        var n = 0
        for (clsName in listOf("ln0.a", "uj.a")) {
            runCatching {
                val u = XposedHelpers.findAndHookMethod(
                    clsName, cl, "c",
                    String::class.java, java.lang.Integer.TYPE,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val id = param.args[0] as? String
                            own(
                                "FACTC ${param.thisObject?.javaClass?.name}" +
                                    ".c(id=${id?.take(24)}, state=${param.args[1]})"
                            )
                        }
                    }
                )
                unhooks.add(u)
                n++
            }.onFailure { own("dndhook: $clsName -> ${it.javaClass.simpleName}") }
        }
        own("dndhook installed=$n — 现在去点「消息免打扰」开关，然后看 FACTC 日志")
    }

    /**
     * 反射枚举关键类的方法 + 字段（含父类上溯）。
     * 已锁定链路：MMSwitchBtn → CheckBoxPreference → Preference（业务回调在这一层）。
     */
    private fun dumpSwitchClass() {
        val cl = loader ?: run { own("dump: no classLoader"); return }
        val names = listOf(
            "com.tencent.mm.ui.widget.MMSwitchBtn",
            "com.tencent.mm.ui.base.preference.CheckBoxPreference",
            "com.tencent.mm.ui.base.preference.Preference",
            "com.tencent.mm.ui.base.preference.c",
        )
        for (name in names) {
            val cls = runCatching { XposedHelpers.findClass(name, cl) }.getOrNull()
            if (cls == null) {
                own("dump: class not found $name")
                continue
            }
            own("=== dump $name super=${cls.superclass?.name}")
            var c: Class<*>? = cls
            var depth = 0
            while (c != null && depth < 3) {
                val methods = runCatching { c.declaredMethods }.getOrNull() ?: break
                methods.sortedBy { it.name }.forEach { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    own("  m[$depth] ${m.name}($params) -> ${m.returnType.simpleName}")
                }
                c = c.superclass
                depth++
            }
            val fields = runCatching { cls.declaredFields }.getOrNull().orEmpty()
            fields.sortedBy { it.name }.forEach { f ->
                own("  f ${f.type.simpleName} ${f.name}")
            }
        }
    }

    private fun install(sec: Int) {
        val cl = loader
        if (cl == null) {
            own("install failed: no classLoader (模块未 prepare？)")
            return
        }
        uninstall()
        val ms = sec.coerceIn(5, 600) * 1000L
        until = System.currentTimeMillis() + ms

        // 1. CompoundButton.setChecked —— 覆盖 Switch/CheckBox 等标准开关类 View
        hook("android.widget.CompoundButton", "setChecked", booleanCls(cl))
        // 2~4. 触摸兜底：开关是自绘 View（非 CompoundButton），光挂 setChecked 不命中。
        hookTouch("android.view.View", "performClick")
        hookTouch("android.view.View", "onTouchEvent", android.view.MotionEvent::class.java)
        hookTouch("android.view.View", "dispatchTouchEvent", android.view.MotionEvent::class.java)
        // 5. 已取证锚点：微信开关组件 MMSwitchBtn（super=View，非 CompoundButton）。
        //    关键两个点：
        //    setSwitchListener(n1) → 打印 n1 业务 listener 的实际类名（核心，找到它
        //      就知道「点开关后谁真正执行打开免打扰」）；
        //    onTouchEvent → 只打 ACTION_UP（手指抬起才触发切换，过滤 press/move）。
        hookSwitch("com.tencent.mm.ui.widget.MMSwitchBtn", "setSwitchListener")
        hookSwitch("com.tencent.mm.ui.widget.MMSwitchBtn", "onTouchEvent",
            android.view.MotionEvent::class.java)
        // 6. 业务回调入口：点击开关后最终执行「打开/关闭免打扰」的回调。
        //    打印 flag + 反射读 CheckBoxPreference 的 key 字符串 + 调用栈（上游）。
        hookStatusChange("com.tencent.mm.ui.base.preference.c", "onStatusChange")
        // 7. 最终业务承载类（已取证 2026-10-18）：ChatroomInfoUI（群聊天信息页）。
        //    onNotifyChange(String,u0) —— 自研 Preference 框架「值变化」回调，key=开关 id；
        //    onPreferenceTreeClick(q,Preference) —— 点击树回调。
        //    命中即拿到 key 字符串 + 谁调用了它（下游 setter 的调用点）。
        hookSwitch("com.tencent.mm.chatroom.ui.ChatroomInfoUI", "onNotifyChange")
        hookSwitch("com.tencent.mm.chatroom.ui.ChatroomInfoUI", "onPreferenceTreeClick")
        // 7b. 真免打扰 setter 候选：onPreferenceTreeClick 内部最终会调这些带 boolean
        //     参数的混淆方法。hook 后点击开关，栈里会出现 onPreferenceTreeClick -> 具体
        //     setter，即可锁定「执行免打扰」的方法。
        //     候选依据（ChatroomInfoUI 方法清单）：G6(boolean)/K6(boolean)/S6(boolean)/
        //     x6(Boolean)/p6(ChatroomInfoUI,boolean)。
        //     ⚠️ S6 是**已锁定**的免打扰 setter（2026-10-18 真机实锤），单独用
        //     [hookS6] 挂钩：命中时额外 dump ChatroomInfoUI 的「微信自定义类」字段
        //     运行时类名，暴露 S6 内部将要调用的底层 logic 类（脱离 UI，可后台复用）。
        for (name in listOf("G6", "K6", "x6", "p6")) {
            hookSwitch("com.tencent.mm.chatroom.ui.ChatroomInfoUI", name)
        }
        hookS6("com.tencent.mm.chatroom.ui.ChatroomInfoUI", "S6")

        own("on window=${ms}ms hooks=${unhooks.size}")
    }

    private fun booleanCls(cl: ClassLoader): Class<*> =
        java.lang.Boolean.TYPE

    private fun hook(clsName: String, method: String, vararg argTypes: Class<*>) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName.$method")
            return
        }
        val set = runCatching {
            if (argTypes.isEmpty()) {
                XposedBridge.hookAllMethods(cls, method, callback("$clsName.$method"))
                    .toCollection(mutableListOf())
            } else {
                val m = cls.getDeclaredMethod(method, *argTypes)
                listOf(XposedBridge.hookMethod(m, callback("$clsName.$method")))
            }
        }.onFailure {
            own("hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    /**
     * 触摸兜底的挂钩：`View#onTouchEvent` / `dispatchTouchEvent` 是**热路径**（滑动列表
     * 每秒几十次），所以回调里**先按 this 类名做一次廉价去重**再进 [record]，避免滑动
     * 把日志刷爆。
     */
    private fun hookTouch(clsName: String, method: String, vararg argTypes: Class<*>) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName.$method")
            return
        }
        val set = runCatching {
            if (argTypes.isEmpty()) {
                XposedBridge.hookAllMethods(cls, method, touchCallback("$clsName.$method"))
                    .toCollection(mutableListOf())
            } else {
                val m = cls.getDeclaredMethod(method, *argTypes)
                listOf(XposedBridge.hookMethod(m, touchCallback("$clsName.$method")))
            }
        }.onFailure {
            own("hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    private fun touchCallback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                val c = param.thisObject?.javaClass?.name ?: return
                val slot = "$sig:$c"
                if (!touchSeen.add(slot)) return
                record(sig, param)
            }
        }
    }

    /**
     * MMSwitchBtn 专项挂钩：已取证的微信开关锚点，命中 = 用户真的在点「消息免打扰」。
     * 所以**不去重**（每次点击都打栈），便于定位业务回调类。
     */
    private fun hookSwitch(clsName: String, method: String, vararg argTypes: Class<*>) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName.$method")
            return
        }
        val set = runCatching {
            if (argTypes.isEmpty()) {
                // 参数类型未知（如 setSwitchListener(n1)）→ 按方法名挂全部重载
                XposedBridge.hookAllMethods(cls, method, switchCallback("$clsName.$method"))
                    .toCollection(mutableListOf())
            } else {
                val m = cls.getDeclaredMethod(method, *argTypes)
                listOf(XposedBridge.hookMethod(m, switchCallback("$clsName.$method")))
            }
        }.onFailure {
            own("hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    /**
     * 业务回调 hook：`c.onStatusChange(boolean)`（点击开关后的最终业务入口）。
     * - 打印 flag（true=打开免打扰 / false=关闭）；
     * - 反射读 `c.a`（CheckBoxPreference）的 key 字符串（Preference.getKey()），
     *   拿到 key 后可直接 grep 微信资源定位业务类；
     * - 打印调用栈（上游，谁调了这个回调 → 通常是真正的 setter）。
     */
    private fun hookStatusChange(clsName: String, method: String) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName")
            return
        }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, statusCallback("$clsName.$method"))
                .toCollection(mutableListOf())
        }.onFailure {
            own("hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    private fun statusCallback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                val flag = param.args?.getOrNull(0)
                val thisObj = param.thisObject
                val pref = readPref(thisObj)
                own("STATUS $sig flag=$flag key=${pref?.first} this=${thisObj?.javaClass?.name ?: "?"}")
                pref?.second?.let { dumpListener(it) }
                val fs = frames()
                fs.forEachIndexed { i, f -> own("  #$i $f") }
            }
        }
    }

    /**
     * 从 listener(`c`) 读回 CheckBoxPreference：
     * - `c.a` 字段（CheckBoxPreference）→ 读字段 `Q`(String key)；
     * - 返回 (key, CheckBoxPreference 实例) 供 [dumpListener] 反射业务 listener。
     */
    private fun readPref(obj: Any?): Pair<String?, Any?>? {
        if (obj == null) return null
        val a = runCatching { XposedHelpers.findField(obj.javaClass, "a").get(obj) }.getOrNull()
        if (a == null) return null
        val key = runCatching {
            val v = XposedHelpers.findField(a.javaClass, "Q").get(a)
            if (v is String && v.isNotEmpty()) v else null
        }.getOrNull()
        return key to a
    }

    /**
     * 反射业务 listener：CheckBoxPreference 的 `d` 字段（类型 V，混淆）——它就是
     * 「点击开关后真正执行免打扰」的类。打印其运行时类名 + declared 方法 + 字段，
     * 再 dump 它的父类，便于下一步直接 hook 它。
     */
    private fun dumpListener(pref: Any?) {
        if (pref == null) return
        val d = runCatching { XposedHelpers.findField(pref.javaClass, "d").get(pref) }.getOrNull()
            ?: run { own("  listener: 无 d 字段"); return }
        val cls = d.javaClass
        own("  listenerClass=${cls.name} super=${cls.superclass?.name}")
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 2) {
            runCatching { c.declaredMethods }.getOrNull().orEmpty()
                .sortedBy { it.name }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    own("    lm[$depth] ${m.name}($params) -> ${m.returnType.simpleName}")
                }
            c = c.superclass
            depth++
        }
        runCatching { cls.declaredFields }.getOrNull().orEmpty()
            .sortedBy { it.name }
            .forEach { f -> own("    lf ${f.type.simpleName} ${f.name}") }
    }

    /**
     * 已锁定的免打扰 setter `S6(boolean)` 专项挂钩：
     * 命中时（点击打开/关闭免打扰）打印 flag + 调用栈 + **dump ChatroomInfoUI 里
     * 所有「微信自定义类」字段的运行时类名**，暴露 S6 内部将要调用的底层 logic 类。
     */
    private fun hookS6(clsName: String, method: String) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName")
            return
        }
        val set = runCatching {
            XposedBridge.hookAllMethods(cls, method, s6Callback("$clsName.$method"))
                .toCollection(mutableListOf())
        }.onFailure {
            own("hook-fail $clsName.$method ${it.javaClass.simpleName}")
        }.getOrNull()
        if (set.isNullOrEmpty()) {
            own("no-method $clsName.$method")
            return
        }
        synchronized(unhooks) { unhooks.addAll(set) }
    }

    private fun s6Callback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                val flag = param.args?.getOrNull(0)
                val thisObj = param.thisObject
                own("S6HIT $sig flag=$flag this=${thisObj?.javaClass?.name ?: "?"}")
                dumpBizFields(thisObj)
                // 最后一跳：dump 字段 `o`（com.tencent.mm.chatroom.ui.r，底层业务类）的方法，
                // 定位 S6 内部真正调用的「设置免打扰」方法。
                dumpFieldMethods(thisObj, "o")
                val fs = frames()
                fs.forEachIndexed { i, f -> own("  #$i $f") }
            }
        }
    }

    /** dump 指定字段的运行时类方法清单（含父类一层），定位底层 setter。 */
    private fun dumpFieldMethods(obj: Any?, fieldName: String) {
        if (obj == null) return
        val v = runCatching {
            val f = XposedHelpers.findField(obj.javaClass, fieldName)
            f.isAccessible = true
            f.get(obj)
        }.getOrNull() ?: return
        val cls = v.javaClass
        own("  field.$fieldName class=${cls.name} super=${cls.superclass?.name}")
        var c: Class<*>? = cls
        var depth = 0
        while (c != null && depth < 2) {
            runCatching { c.declaredMethods }.getOrNull().orEmpty()
                .sortedBy { it.name }
                .forEach { m ->
                    val params = m.parameterTypes.joinToString(",") { it.simpleName }
                    own("    om[$depth] ${m.name}($params) -> ${m.returnType.simpleName}")
                }
            c = c.superclass
            depth++
        }
    }

    /** 反射 dump this 里所有「微信自定义类（com.tencent.mm. 开头）」字段的运行时类名。 */
    private fun dumpBizFields(obj: Any?) {
        if (obj == null) return
        var c: Class<*>? = obj.javaClass
        var depth = 0
        while (c != null && depth < 3) {
            runCatching { c.declaredFields }.getOrNull().orEmpty()
                .sortedBy { it.name }
                .forEach { f ->
                    val v = runCatching {
                        f.isAccessible = true
                        f.get(obj)
                    }.getOrNull() ?: return@forEach
                    val rc = v.javaClass.name
                    if (rc.startsWith("com.tencent.mm.")) {
                        own("    biz[$depth] ${f.name}: ${f.type.name} -> $rc")
                    }
                }
            c = c.superclass
            depth++
        }
    }

    /** 一次性 hook 某个类的**全部 declared 方法**（保留，取证需要时可手动改 install 调用）。 */
    @Suppress("unused")
    private fun hookSwitchAll(clsName: String) {
        val cl = loader ?: return
        val cls = runCatching { XposedHelpers.findClass(clsName, cl) }.getOrNull()
        if (cls == null) {
            own("no-class $clsName")
            return
        }
        var n = 0
        val methods = runCatching { cls.declaredMethods }.getOrNull().orEmpty()
        for (m in methods) {
            if (m.name == "<init>" || m.name == "<clinit>" || m.name == "finalize") continue
            val sig = "$clsName.${m.name}" +
                "(${m.parameterTypes.joinToString(",") { it.simpleName }})"
            runCatching {
                val u = XposedBridge.hookMethod(m, switchCallback(sig))
                synchronized(unhooks) { unhooks += u }
                n++
            }.onFailure {
                own("hook-fail $sig ${it.javaClass.simpleName}")
            }
        }
        own("switch-all $clsName +$n methods")
    }

    private fun switchCallback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                // onTouchEvent：只打 ACTION_UP（手指抬起触发切换），过滤 press/move
                if (sig.endsWith("onTouchEvent(MotionEvent)")) {
                    val ev = param.args?.getOrNull(0) as? android.view.MotionEvent
                    val act = ev?.actionMasked
                    if (act != android.view.MotionEvent.ACTION_UP) return
                }
                val args = param.args?.joinToString(",") { a ->
                    when (a) {
                        is Boolean -> if (a) "true" else "false"
                        is View -> a.javaClass.name
                        is String -> "\"${a.take(64)}\""
                        is CharSequence -> "\"${a.take(64)}\""
                        null -> "null"
                        else -> a.javaClass.name
                    }
                } ?: ""
                val thisObj = param.thisObject
                own("SWITCH $sig args=($args) this=${thisObj?.javaClass?.name ?: "?"}")
                if (thisObj is View) own(parentChain(thisObj))
                val fs = frames()
                fs.forEachIndexed { i, f -> own("  #$i $f") }
            }
        }
    }

    /** 触摸兜底专用的 this 类名去重集合（与 [record] 里的调用点去重互补） */
    private val touchSeen = Collections.synchronizedSet(LinkedHashSet<String>())

    private fun callback(sig: String) = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            runCatching {
                if (System.currentTimeMillis() > until) return
                record(sig, param)
            }
        }
    }

    private fun record(sig: String, param: XC_MethodHook.MethodHookParam) {
        val thisObj = param.thisObject
        val thisCls = thisObj?.javaClass?.name ?: "?"
        val frames = frames()
        val key = "$sig $thisCls <- " + frames.take(3).joinToString(" <- ")
        if (!seen.add(key)) return
        hits.incrementAndGet()
        own("HIT $sig this=$thisCls")
        if (thisObj is View) own(parentChain(thisObj))
        frames.forEachIndexed { i, f -> own("  #$i $f") }
    }

    private fun parentChain(v: View): String {
        val sb = StringBuilder("  parent: ")
        var cur: View? = v
        var depth = 0
        while (cur != null && depth <= MAX_PARENT_UP) {
            val parent = cur.parent
            if (parent is ViewGroup) {
                sb.append(parent.javaClass.name).append('#').append(idHex(parent))
                if (depth < MAX_PARENT_UP) sb.append(" <- ")
            }
            cur = parent as? View
            depth++
        }
        return sb.toString()
    }

    private fun idHex(v: View): String {
        val id = v.id
        return if (id == View.NO_ID) "-" else "0x" + Integer.toHexString(id)
    }

    private fun frames(): List<String> {
        val out = mutableListOf<String>()
        for (st in Throwable().stackTrace) {
            if (out.size >= FRAMES) break
            val s = st.toString()
            if (NOISE.any { s.contains(it) }) continue
            out.add(s)
        }
        return out
    }

    private fun uninstall() {
        synchronized(unhooks) {
            for (u in unhooks) runCatching { u.unhook() }
            unhooks.clear()
        }
        seen.clear()
        touchSeen.clear()
        hits.set(0)
        until = 0L
        own("off unhooked, state cleared")
    }

    private fun own(msg: String) {
        LogX.i("MUTE_SET ${seq.incrementAndGet()} $msg")
    }
}
