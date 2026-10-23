package com.lx.wxgroup.core

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.LinkedList

/**
 * K8-file **B 档探针**：不依赖 UI 发**普通文件**（appmsg type=6）。
 *
 * 与 [FileSender]（A 档，图片/视频降级）**刻意分开**：
 * - [FileSender] 走 `hl0/q1` builder，已逐字段对齐微信 `qf4/o4.O8`，是**正式链路**；
 * - 本类走 `qf4/r5.j`，是**探针**，只用来在真机上验「普通文件能不能发、落库 type 是几」。
 *
 * 默认**不接入任何业务流程**，只能由 `op=forward sub=fileb` 手动触发。
 *
 * ## 取证依据（2026-10-23 R13，逐条反汇编，见 `docs/取证结论.md` §「补充闭合」）
 *
 * ```
 * qf4/r5            单例：static Lqf4/r5; a（在 <clinit> 里建，**没有 a() 工厂**）
 *   j(app/m, WXMediaMessage, LinkedList, String, String, I, Z, Z, String, String, Object, a5, b5)V
 *       PUBLIC FINAL ⇒ ins=14 含隐式 this ⇒ this=v17，形参 v18..v30
 *     若 WXMediaMessage.getType()==6：
 *       验 WXFileObject.fileData / filePath 非空（都空则 checkArgs fail + return，静默丢弃）
 *       a8.F0(path) 判存在；不存在则 copy 到 u0.i() 附件目录
 *     然后对每个 talker：
 *       u0.A(WXMediaMessage, appId, appName, talker, 2, s1, s2, i2, app/a)
 *         → u0.B 补 5 个 null
 *         → 建 te0/q，只写 q.d=appId / q.F=appName / q.r=2
 *         → u0.D：先 u0.r（填 38 字段，含 i), 后 MsgInfo.setType(u0.l(q))
 *           而 u0.l 在 q.i==6 时返回 0x41000031（= 1090519089）
 * ```
 *
 * ## 第 7 / 第 8 个形参（`v24` / `v25`）—— ⚠️ 这段我写反过两次，以本节为准
 *
 * ```
 * 0183  iget-object v4, v1, Ljj/k0;.field_appId      ; v4 = appInfo.field_appId
 * 0187  o.f(v4, "field_appId")                       ; Kotlin 非空断言
 * 018a  if-nez   v2, 0190    ; v2 = p7 ⇒ p7 == 0 时跳 0190 【保留 v4】
 * 018c  if-eqz   v25, 0190   ; v25 = p8 ⇒ p8 == 0 时跳 0190 【保留 v4】
 * 018e  const-string v4, ""  ; ★ 只有【p7 != 0 且 p8 != 0】才清空
 * 0190  move-object v5, v4   ; v5 = 最终 appId → u0.A 第 2 参
 * ```
 *
 * ⇒ **清空条件是 `p7 && p8`。任一为 false 都保留 appId。**
 * 两句跳转目标相同 ⇒ 是 **OR 关系**，别只读一句就下结论（我踩过）。
 *
 * `p7` 的真实身份（4 跳追出来的，见 `docs/取证结论.md` §「p7 / p8 的真实语义」）：
 * ```
 * SendAppMessageWrapperUI.onCreate 639fbe  Bundle "SendAppMessageWrapper_TokenValid"
 *                                  639fca  iput-boolean → UI.p:Z   ★ getBoolean 默认值 = true
 *   → s6 63acb6 iget UI.p:Z → r5.l p7
 *   → r5.d p7 → n5.h 844f44 → r5.j p7
 * ```
 * ⇒ `p7` = **`TokenValid`**，微信自己给的默认值就是 `true`。
 * 它被搬进 `app/a.a`（`app/b3.doScene` → `q54/x6.v` → 上行 proto），`app/a.<init>` 的默认值也是 1，
 * 两条独立证据都指向 `true`，且**没有别的副作用**。
 *
 * `p8` 在本链路上**没有任何字段读点**，只参与 `018c`。`p7 = true` 时 `018a` 直接跳走，
 * `018c` 根本不执行 ⇒ p8 取任意值都不影响结果，给 `false` 只是双保险。
 *
 * ## 判据（真机执行，按顺序）
 * 1. 日志出现 `FILEB r5=...` 且无 `checkArgs fail` / `copyFile failed`；
 * 2. 日志 `MicroMsg.AppMsgLogic` 出现 `summerbig content url:`（说明 `u0.D` 走到了）；
 * 3. `select type,isSend from message where talker=? order by createTime desc limit 1`
 *    ⇒ `type=1090519089`、`isSend=1`；
 * 4. 对端能正常显示并下载。
 *
 * ## ⛔ 不要做的事
 * - 不要在 A 档真机验证通过之前把它接进 `ForwardLogic` 的正式重发路径。
 * - 不要用 `WXFileObject.fileData`（全量读进内存）：大文件会 OOM，且微信自己优先用 `filePath`。
 */
object FileSenderB {

    /** `u0.l` 在 `te0/q.i == 6` 时返回的魔数；也等于微信原生发文件的 `MsgInfo.setType` 实参 */
    const val TYPE_FILE_APPMSG = 0x41000031   // = 1090519089，注意**没超** Int.MAX_VALUE

    @Volatile
    private var loader: ClassLoader? = null

    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    fun ready(): Boolean = loader != null

    /**
     * 探针：把一个本地文件发给 [talker]。
     *
     * @return 人类可读的结果串（直接进日志，不含异常栈）
     */
    fun probe(talker: String, path: String): String {
        val cl = loader ?: return "no-classLoader"
        if (talker.isBlank()) return "blank-talker"
        if (path.isBlank()) return "blank-path"
        val f = File(path)
        if (!f.exists()) return "missing-file:${path.take(80)}"
        if (!f.canRead()) return "unreadable:${path.take(80)}"

        return runCatching {
            // ---- 1. WXFileObject：只 setFilePath，不碰 fileData ----
            val fileObjCls = XposedHelpers.findClass(
                "com.tencent.mm.opensdk.modelmsg.WXFileObject", cl
            )
            val fileObj = XposedHelpers.newInstance(fileObjCls)
            XposedHelpers.callMethod(fileObj, "setFilePath", path)
            val typeOk = (XposedHelpers.callMethod(fileObj, "type") as? Int) == 6
            if (!typeOk) {
                return@runCatching "unexpected-type:${XposedHelpers.callMethod(fileObj, "type")}"
            }

            // ---- 2. WXMediaMessage：mediaObject + title + description ----
            val msgCls = XposedHelpers.findClass(
                "com.tencent.mm.opensdk.modelmsg.WXMediaMessage", cl
            )
            val msg = XposedHelpers.newInstance(msgCls)
            XposedHelpers.setObjectField(msg, "mediaObject", fileObj)
            // 原生路径 title = 路径 basename；description = 可读文件大小（可省，不影响发送）
            XposedHelpers.setObjectField(msg, "title", f.name)
            XposedHelpers.setObjectField(msg, "description", "")
            // sdkVer 会被 u0.r 搬进 te0/q.e；留默认 0
            runCatching { XposedHelpers.setIntField(msg, "sdkVer", 0) }
            // mediaTagName / messageAction / messageExt 不设（null），u0.r 原样搬进 q.V/W/X

            // ---- 3. app/a：纯 POJO（r5.h 的产物），字段留 null 即可 ----
            //      r5.j 内部会自己调 r5.h(...) 造一个，这里不需要造。
            //      只在 app/a 类缺失时提前报错，便于区分「类名变了」和「参数错了」。
            XposedHelpers.findClass("com.tencent.mm.pluginsdk.model.app.a", cl)

            // ---- 4. app/m：只读 field_appId / field_appName ----
            //
            // ✅ `app/m` 实测**有无参构造 `<init>()V`**（classes4.dex，`access=0x0001`，
            //    9 个方法），可以直接 `newInstance`。
            //    （曾误判成「只有 `<init>(B)V`」—— 那是 `tools/_r13_findclass.py` 早期版本
            //     把 class_defs 下标与 type_idx 搞混导致的假结论，已修，别再照那个写。）
            //
            // 两个字段读点：`0183: iget-object v4, v1, Ljj/k0;.field_appId`
            //              `0191: iget-object v6, v1, Ljj/k0;.field_appName`
            // ⇒ 字段声明在**父类 `jj/k0`** 上，但实例是 `app/m` 时 `setObjectField` 会沿继承链找到。
            val appMCls = XposedHelpers.findClass(
                "com.tencent.mm.pluginsdk.model.app.m", cl
            )
            val appM = XposedHelpers.newInstance(appMCls)
            XposedHelpers.setObjectField(appM, "field_appId", "wx0probe00000000")
            XposedHelpers.setObjectField(appM, "field_appName", "probe")

            // ---- 5. 取 r5 单例 + 调 j(...) ----
            val r5Cls = XposedHelpers.findClass("qf4.r5", cl)

            // ⚠️ 取单例只有一条路：读**静态字段 `a`**。
            //    `qf4/r5` 里**没有无参的 `a()` 工厂**！它的 Direct methods 只有四个：
            //      <clinit>()V
            //      a(Lqf4/r5;Lte0/q;Lq54/vb6;)V      ← 3 参、返回 void，跟"工厂"毫无关系
            //      e(...)  i(...)  l(...)
            //    单例是在 `<clinit>` 里 `new-instance + <init>()V + sput-object a` 建好的
            //    （`846a84`~`846aa2`）。所以只能 getStaticObjectField。
            //    （曾写成 `callStaticMethod(r5Cls,"a")` —— 会 NoSuchMethodException，
            //      且因为是先 `?:` 判空再报错，症状会误导成"工厂返回 null"。）
            val inst = XposedHelpers.getStaticObjectField(r5Cls, "a")
                ?: return@runCatching "r5.<clinit>未执行:静态字段a为null"

            val talkers = LinkedList<String>().apply { add(talker) }

            // ⚠️ 不用 `XposedHelpers.callMethod(inst, "j", ...)`：
            //    它是按**实参类型**去 `getMethod` 挑重载的，而 `j` 的签名里
            //    `String/I/Z` 与 Kotlin 传进去的 `String/Int/Boolean` 靠自动装箱容易挑错，
            //    且共有 13 个参数、任何一处类型推断偏差都会 ClassNotFound。
            //    这里显式 `getDeclaredMethod` + `invoke`，签名逐字照抄 dex，最稳。
            val jm = r5Cls.getDeclaredMethod(
                "j",
                appMCls,                                        // 1  app/m
                msgCls,                                         // 2  WXMediaMessage
                LinkedList::class.java,                         // 3  LinkedList
                String::class.java,                             // 4
                String::class.java,                             // 5
                Int::class.javaPrimitiveType,                   // 6  I
                Boolean::class.javaPrimitiveType,               // 7  Z
                Boolean::class.javaPrimitiveType,               // 8  Z
                String::class.java,                             // 9
                String::class.java,                             // 10
                Any::class.java,                                // 11 Object
                XposedHelpers.findClass("qf4.a5", cl),          // 12 a5
                XposedHelpers.findClass("qf4.b5", cl)           // 13 b5
            ).apply { isAccessible = true }

            jm.invoke(
                inst,
                appM,          // 1  app/m
                msg,           // 2  WXMediaMessage
                talkers,       // 3  LinkedList
                "",            // 4  → u0.A p6
                "",            // 5  → u0.A p7
                0,             // 6  → u0.A p8
                true,          // 7  ★ TokenValid；true ⇒ 018a 直接跳 0190，保留 appId
                false,         // 8  只在 p7 为 false 时才被 018c 读到，这里给 false 是双保险
                "",            // 9  → r5.h p2
                "",            // 10 → r5.h p3
                null,          // 11 Object
                null,          // 12 a5
                null           // 13 b5 ⇒ app/a 的 d/e/f 全 null
            )
            "sent talker=${talker.take(24)} size=${f.length()} name=${f.name.take(40)} " +
                "expectType=$TYPE_FILE_APPMSG"
        }.getOrElse { "error=${it.javaClass.simpleName}:${it.message?.take(120)}" }
    }
}
