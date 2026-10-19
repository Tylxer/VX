package com.lx.wxgroup.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 宿主 Activity 的 `onActivityResult` 中继。
 *
 * ## 为什么必须有它
 * 模块的 UI 跑在**微信进程里**，但模块 APK 没有自己的 Activity（它是 Xposed 模块，
 * 清单里连 `<activity>` 都没有）。而 SAF 选目录/选文件（`ACTION_CREATE_DOCUMENT` /
 * `ACTION_OPEN_DOCUMENT`）**必须由 Activity 发起、结果回到那个 Activity 的
 * `onActivityResult`**。所以只能借微信当前的 Activity 发起，再把结果截出来。
 *
 * ## 为什么要 hook 三个方法，而不是只 hook 一个
 * `onActivityResult` 在 8.0.48 上有三条路径可能被走到：
 * 1. `Activity.onActivityResult(int,int,Intent)` —— 经典路径；
 * 2. `Activity.onActivityResult(int,int,Intent)` 的 **`androidx` 版本**同名不同参
 *    （微信重打包了 androidx，`requestCode` 可能被塞进别的签名里）—— 不猜，只按
 *    「方法名 + 参数里有 Intent」泛化匹配；
 * 3. 微信自己的 Activity 覆写了 `onActivityResult` 且**不调 super** —— 这时挂基类
 *    方法是死钩（本项目踩过：`LauncherUI` 覆写 `onResume` 导致基类 hook 不触发）。
 *    所以额外挂 `onActivityResult` 的**具体覆写方法**：遍历 `Activity` 子类不现实
 *    （几千个类），改为**在发起前记录当前 Activity 的具体类**，然后对那个类挂一次，
 *    带已挂集合去重。
 *
 * ## 三条硬约束（都踩过）
 * - **必须主线程发起**：`startActivityForResult` 在非主线程调不报错但窗口不弹。
 * - **回调只消费一次**：微信可能立刻又发别的 Intent 复用同一个 requestCode，
 *   所以拿到就清空，且 `requestCode` 用模块专属区间避开微信自己的值。
 * - **不能在回调里做重活**：这里跑在微信主线程，写库/写大文件必须甩到后台线程。
 */
object ActivityResultRelay {

    private const val TAG = "SAFR"

    /**
     * 模块专属 requestCode 区间。
     *
     * 取 0x5758（"WX"）打头，和微信自己常用的小整数（1/2/100/1000…）拉开距离；
     * 只需两个值：导出（CREATE_DOCUMENT）与导入（OPEN_DOCUMENT）。
     */
    const val REQ_EXPORT = 0x575801
    const val REQ_IMPORT = 0x575802

    private fun isOurs(code: Int) = code in REQ_EXPORT..REQ_IMPORT

    /** 一次性回调：拿到结果就清空，避免同一个 requestCode 被复用后串号 */
    @Volatile
    private var pending: ((Int, Int, Uri?) -> Unit)? = null

    @Volatile
    private var launched = false

    /** 已经挂过 `onActivityResult` 的具体类名（避免反复 hook） */
    private val hookedClasses = java.util.Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var installed = false

    /**
     * 装钩。由 [com.lx.wxgroup.HookEntry] 在 bootstrap 里调用一次。
     *
     * 挂的是 `android.app.Activity` 基类方法。具体子类覆写不调 super 的情况，
     * 由 [launch] 在发起前对当前 Activity 的具体类补挂（见 [hookConcreteClass]）。
     */
    fun install(classLoader: ClassLoader) {
        if (installed) return
        installed = true

        val activity = runCatching { Class.forName("android.app.Activity", false, classLoader) }
            .getOrElse {
                LogX.w("$TAG Activity not found: ${it.javaClass.simpleName}")
                return
            }

        // ---- 1. 基类：所有参数组合里只要「第 1 个 int + 第 2 个 int + Intent」就挂 ----
        var baseHooks = 0
        for (m in activity.declaredMethods) {
            if (m.name != "onActivityResult") continue
            val types = m.parameterTypes
            if (types.size != 3) continue
            if (types[0] != Int::class.javaPrimitiveType) continue
            if (types[1] != Int::class.javaPrimitiveType) continue
            if (types[2] != Intent::class.java) continue
            baseHooks += runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, relayHook).let { 1 }
            }.getOrElse {
                LogX.w("$TAG base hook failed: ${it.javaClass.simpleName}")
                0
            }
        }

        // ---- 2. androidx/fragment 的花式签名（名字对、参数里有 Intent、参数数 3~4）----
        val fuzzyHooks = hookFuzzy(activity)

        LogX.i("$TAG installed base=$baseHooks fuzzy=$fuzzyHooks")
    }

    /**
     * 泛化匹配：方法名含 `onActivityResult`、参数里**有且只有一个** Intent。
     *
     * 为什么放开参数个数：微信重打包的 androidx 把这套签名改过（本项目在
     * `WxRecyclerView` 上踩过「`getChildAdapterPosition` 报 NoSuchMethodException」
     * 的重打包坑）。宁可多挂一个无害的，也不要少挂。
     */
    private fun hookFuzzy(clazz: Class<*>): Int {
        var n = 0
        for (m in runCatching { clazz.declaredMethods }.getOrDefault(emptyArray())) {
            if (!m.name.contains("onActivityResult")) continue
            val types = m.parameterTypes
            if (types.count { it == Intent::class.java } != 1) continue
            if (types.size !in 3..4) continue
            if (types[0] != Int::class.javaPrimitiveType) continue
            // 精确签名已经在上面挂过，这里跳过免得重复（重复 hook 会回调两次）
            if (types.size == 3 && types[1] == Int::class.javaPrimitiveType) continue
            n += runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, relayHook).let { 1 }
            }.getOrDefault(0)
        }
        return n
    }

    private val relayHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            runCatching {
                val args = param.args ?: return
                val idx = args.indexOfFirst { it is Intent }
                if (idx < 0) return
                val intent = args[idx] as? Intent

                // requestCode：优先取第一个 int 参数；泛化签名里 int 也可能在后面
                val requestCode = args.filterIsInstance<Int>().firstOrNull() ?: return
                val resultCode = args.filterIsInstance<Int>().getOrNull(1) ?: Activity.RESULT_OK
                if (!isOurs(requestCode)) return

                val cb = pending ?: return
                pending = null          // 先清，保证只消费一次
                val uri: Uri? = intent?.data
                LogX.i("$TAG result req=$requestCode code=$resultCode uri=${uri?.host ?: "-"}")

                // 回调在主线程；调用方自己决定要不要甩后台
                runCatching { cb(requestCode, resultCode, uri) }
                    .onFailure { LogX.w("$TAG callback threw: ${it.javaClass.simpleName}") }
            }.onFailure { LogX.w("$TAG relay failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * 用宿主 Activity 发起一个「要结果」的 Intent。
     *
     * @param onResult 主线程回调。`uri` 在用户取消时为 null（resultCode != RESULT_OK）
     * @return 是否成功发起。false 表示拿不到 Activity（微信不在前台）或发起抛异常
     */
    fun launch(requestCode: Int, intent: Intent, onResult: (Int, Int, Uri?) -> Unit): Boolean {
        if (!isOurs(requestCode)) {
            LogX.w("$TAG refuse foreign requestCode=$requestCode")
            return false
        }
        val activity = HostContext.activity()
        if (activity == null) {
            LogX.w("$TAG launch: no host activity (微信不在前台？先切回微信再试)")
            return false
        }
        // 具体类覆写且不调 super → 基类钩子是死钩，这里补挂一次
        hookConcreteClass(activity.javaClass)

        pending = onResult
        launched = true
        return runCatching {
            activity.startActivityForResult(intent, requestCode)
            LogX.i("$TAG launched req=$requestCode via ${activity.javaClass.simpleName}")
            true
        }.getOrElse {
            pending = null
            launched = false
            LogX.w("$TAG launch failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /**
     * 对当前 Activity 的**具体类**补挂 `onActivityResult`。
     *
     * 只挂该类的 `declaredMethods`，且只挂它自己声明的（基类的已经挂过）。
     * 用类名去重：微信 Activity 类数量有限，一个进程内最多挂几十次。
     */
    private fun hookConcreteClass(clazz: Class<*>) {
        val key = clazz.name
        if (!hookedClasses.add(key)) return
        var n = 0
        for (m in runCatching { clazz.declaredMethods }.getOrDefault(emptyArray())) {
            if (m.name != "onActivityResult") continue
            n += runCatching {
                m.isAccessible = true
                XposedBridge.hookMethod(m, relayHook).let { 1 }
            }.getOrDefault(0)
        }
        if (n > 0) LogX.i("$TAG hooked concrete $key methods=$n")
    }

    /** 有没有正在等结果的请求（UI 用它决定要不要提示「等待选择」） */
    fun waiting(): Boolean = pending != null

    /** 探针用：一行状态 */
    fun stat(): String =
        "installed=$installed launched=$launched waiting=${pending != null} " +
            "concreteHooked=${hookedClasses.size} reqs=${REQ_EXPORT},${REQ_IMPORT}"

    /** 清掉挂起回调（用户中途退出页面时用，避免回调打到已销毁的 UI） */
    fun cancel() {
        if (pending != null) LogX.i("$TAG cancel pending")
        pending = null
    }
}
