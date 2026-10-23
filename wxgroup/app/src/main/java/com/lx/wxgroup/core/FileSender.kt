package com.lx.wxgroup.core

import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * K8-file「不依赖 UI 发**图片**消息」的发送器。
 *
 * 群发与强制转发的降级重发共用。与 [TextSender] **同一条 builder 链路**，只是换枚举、
 * 多设几个字段 —— 这也是为什么本类不重新发明一套，而是**逐字段照抄**微信自己的写法。
 *
 * ## 取证依据（2026-10-20 静态定位，逐条反汇编，见 `docs/取证结论.md`「K8-file」段）
 *
 * 微信自己发图片走 `qf4/o4.O8(BuildContext, null, fileName, 0, null, toUser, m, scanInfo)`，
 * 字节码逐条是（地址见 `_cache/_r11_dex7_dis.txt`）：
 * ```
 * 84579a  sget-object  o1.e                    // enum IMAGE
 * 84579e  r1.a(o1)       -> q1                 // 按类别造 builder
 * 8457a8  q1.i = 4                             // ★ 见下「为什么要逐字段抄」
 * 8457ac  q1.g(fileName)  -> q1.b              // 只写「文件名」，不是路径
 * 8457b2  q1.h(toUser)    -> q1.a
 * 8457b8  iput q1.j = arg5                     // ★ 完整路径，走 iget 直写（q1 无 setter）
 * 8457bc  iput q1.k = arg3 (int)
 * 8457c0  iput q1.l = null
 * 8457c6  iput q1.n = 0
 * 8457d4  q1.p = true
 * 8457de  q1.o = 0x7f0803d6                    // ★ int，是字符串资源 id，不是 float
 * 8457f2  q1.f(m)                              // 转发信息，全默认值
 * 8457fc  q1.h = 13                            // ★ constructorId
 * 845800  iput q1.D = scanCodeInfo (null 可不设)
 * 845804  q1.a()        -> l1
 * 84580c  MsgRetransmitUI.g6(l1)               // PUBLIC STATIC，只读 l1
 * 845812  l1.a()        -> boolean             // 真正 execute
 * ```
 *
 * ## 为什么要逐字段抄（三条都踩过坑）
 *
 * 1. **`q1.h`（constructorId）必须设 13**。`t81/g.a` 按 `q1.h` 分派
 *    （`8bb7da iget q1.h` → 2/3/6/9/10/11/13/14/19/default），而 `q1.<init>` **不初始化 h**
 *    （只初始化 `a/b/c=""` `d=1` `j/r/s=""` `t=new m()`）。
 *    `h==0` 落到 default 分支（`8bbb14`），它会把 `q1.x`、`q1.A` 两个 **null StringBuilder**
 *    传进 `j2.<init>(19 参)`；而 `h==13` 分支传的是 `""`/`""`。
 * 2. **路径要写 `q1.j`，不是 `q1.b`**。`t81/g.a` 开头（`8bb794`）就拿 `q1.j` 去
 *    `com/tencent/mm/vfs/o6.l()` **取文件大小**，据此决定「要不要当大图不压缩」。
 *    `q1.b` 只是展示用的文件名。**`q1` 全类只有 10 个方法、没有写 `j` 的 setter**
 *    ⇒ 必须用 `XposedHelpers.setObjectField(q1, "j", path)`。
 * 3. **`q1.o` 是 `int`（资源 id），不是 float**。dexdump 把它注释成
 *    `#float 1.80795e+38` 是因为把 `0x7f0803d6` 这串比特按 float 解释了一下，
 *    那个注释会误导人。实测字段签名是 `Lhl0/q1;.o:I`。
 *
 * ## 只做 IMAGE（为什么砍掉 VIDEO）
 *
 * 视频**不走** `r1.a(o1.VIDEO)`：`q1.a()` 对 VIDEO 走 `t81/g.i(q1)`，而 `t81/g.i`
 * **第一句就是** `instance-of q1, com/tencent/mm/modelimage/o3`
 * （`8bbca6`，不是就直接 `8bbd72 return-object v0`），拿到的 `k1.a` 恒为 null，
 * 最后 `l1.a()` 里 `Objects.requireNonNull` 抛 NPE（`71039c`），被最外层 `runCatching`
 * 吞掉 ⇒ **永远返回 false，且只留一行 NullPointerException**。
 * 正解是 `modelimage/o3.j()` 工厂 + `o3.E/F/K/H` 字段 + `q1.h=20`，这套**尚未取证完整**，
 * 此刻接入等于赌微信不崩 ⇒ 先不做，等独立取证。
 *
 * 普通文件（appmsg type=6）的正解入口是 **`qf4/r5`**，不是 `qf4/o4`：
 * `r5.l(Context, Intent, app/m, WXMediaMessage, ...)` 是类型分派器，`getType()==6` 时
 * 造 `qf4/d5(WXFileObject)` 包成 Runnable 投池；执行体是 `r5.j(...)`（13 参）。
 * 它内部对每个 talker 调 `u0.A(WXMediaMessage, appId, appName, talker, 2, "", "", 0, app/a)`，
 * 而 `u0.A → u0.B → u0.D` 三层的寄存器映射与 `te0/q` 字段写入已全部审计完
 * （2026-10-23 R13，见 `docs/取证结论.md` §「补充闭合」）。
 *
 * ⚠️ 但**本类没有实现**它：`r5.j` 需要一个 `app/m` 实例（只读两个字段，可造），
 * 还要真机验证 `message` 表落库 `type=1090519089`。在 A 档（图片）真机通过之前不接，
 * 理由同全项目铁律「严禁猜微信内部 API」。
 * 探针已备好：`op=forward sub=fileb --es talker <wxid> --es val <path>`。
 *
 * ## 边界（诚实声明，与 [TextSender] 一致）
 * - `l1.a()` 返回的是「是否成功**入队**」，不是「对端已收到」；
 * - `g6(l1)` 返回 void，异步；
 * - 真机未验证 ⇒ 提前用 `op=forward sub=filerdy` 看就绪性，别把「没 prepare」
 *   误判成「发送失败」。
 */
object FileSender {

    /**
     * 可发送的媒体种类。
     *
     * ⚠️ 只有 [IMAGE] 是真能发的；[VIDEO] 保留枚举值**只为让上层日志/统计说得清**
     * （「知道是视频但没链路」≠「不认识的类型」），`send()` 对它**恒返回 false**，
     * 不构造任何反射对象 —— 见类注释「为什么砍掉 VIDEO」。
     */
    enum class Kind { IMAGE, VIDEO, UNSUPPORTED }

    /** `O8` 里 `q1.h` 的字面量：图片消息的 constructorId */
    private const val CONSTRUCTOR_ID_IMAGE = 13

    /** `O8` 里 `q1.i` 的字面量 */
    private const val Q1_I_DEFAULT = 4

    /** `O8` 里 `q1.o` 的字面量。**是 int（字符串资源 id），不要写成 float** */
    private const val Q1_O_RES_ID = 0x7f0803d6

    @Volatile
    private var loader: ClassLoader? = null

    /** 只保存 classLoader，不挂钩子 */
    fun prepare(lpparam: XC_LoadPackage.LoadPackageParam) {
        loader = lpparam.classLoader
    }

    /** 链路是否可用（模块是否已 prepare） */
    fun ready(): Boolean = loader != null

    /**
     * 发一个本地图片文件。
     *
     * @param kind  由调用方按消息种类判定（见 [kindOfFile]）
     * @param wxid  目标会话
     * @param path  微信自己的本地路径（`Snapshot.localPath`）
     * @return true=已成功**入队**（≠ 对端已收到）；false=不支持/链路缺失/反射失败
     */
    fun send(kind: Kind, wxid: String, path: String): Boolean {
        val cl = loader ?: run {
            LogX.w("FileSender no classLoader (模块未 prepare？)")
            return false
        }
        if (kind != Kind.IMAGE) {
            // VIDEO 未取证（见类注释），UNSUPPORTED 本来就不支持 —— 两类都只记账不构造反射，
            // 避免「造出半个 q1 却发不出去」那种只在真机上花十几分钟才看出来的静默失败。
            LogX.w("FileSender skip kind=$kind (仅 IMAGE 有已取证链路), wxid=${wxid.take(24)}")
            return false
        }
        if (wxid.isBlank()) {
            LogX.w("FileSender blank wxid")
            return false
        }
        if (path.isBlank()) {
            LogX.w("FileSender blank path (wxid=${wxid.take(24)})")
            return false
        }
        val f = File(path)
        // 文件不存在就别发：微信侧会静默失败，我们连日志都拿不到
        if (!f.exists()) {
            LogX.w("FileSender path missing: ${path.take(120)}")
            return false
        }
        return runCatching {
            val o1Cls = XposedHelpers.findClass("hl0.o1", cl)
            val r1Cls = XposedHelpers.findClass("hl0.r1", cl)
            val o1 = pickEnum(o1Cls, "IMAGE") ?: return@runCatching false

            val q1 = XposedHelpers.callStaticMethod(r1Cls, "a", o1)
                ?: run {
                    LogX.w("FileSender r1.a(o1=IMAGE) -> null")
                    return@runCatching false
                }

            // ---- 逐字段照抄 qf4/o4.O8（顺序对齐字节码，便于以后比对） ----
            XposedHelpers.setIntField(q1, "i", Q1_I_DEFAULT)      // 8457a8
            XposedHelpers.callMethod(q1, "g", f.name)             // 8457ac -> q1.b（文件名）
            XposedHelpers.callMethod(q1, "h", wxid)               // 8457b2 -> q1.a（目标会话）
            XposedHelpers.setObjectField(q1, "j", path)           // 8457b8 -> q1.j（★ 完整路径）
            XposedHelpers.setIntField(q1, "k", 0)                 // 8457bc
            XposedHelpers.setObjectField(q1, "l", null)           // 8457c0
            XposedHelpers.setIntField(q1, "n", 0)                 // 8457c6
            XposedHelpers.setBooleanField(q1, "p", true)          // 8457d4
            XposedHelpers.setIntField(q1, "o", Q1_O_RES_ID)       // 8457de（int！不是 float）

            // 转发信息对象：全默认值即可。`q1.<init>` 已 `new m()`，本条是「保持与 O8 同构」。
            runCatching {
                val mCls = XposedHelpers.findClass("hl0.m", cl)
                XposedHelpers.callMethod(q1, "f", XposedHelpers.newInstance(mCls))
            }.onFailure { LogX.w("FileSender q1.f(m) skipped: ${it.javaClass.simpleName}") }

            XposedHelpers.setIntField(q1, "h", CONSTRUCTOR_ID_IMAGE)  // 8457fc（★ constructorId）
            // q1.D（ScanCodeInfo）微信这里是 null，setObjectField 到 null 与之一致，可省略。

            val l1 = XposedHelpers.callMethod(q1, "a")                // 845804
                ?: run {
                    LogX.w("FileSender q1.a() -> null")
                    return@runCatching false
                }
            // ⚠️ 顺序照抄微信 O8：q1.a() → g6(l1) → l1.a()。
            // g6 全程只读 l1（`iget` + `getType()` + `S()` + `getMsgId()`，零 iput，见 62d0bc），
            // 不会消费/重置；它抛异常不该影响真正的 execute ⇒ 单独 runCatching。
            runCatching {
                val mrui = XposedHelpers.findClass(
                    "com.tencent.mm.ui.transmit.MsgRetransmitUI", cl
                )
                XposedHelpers.callStaticMethod(mrui, "g6", l1)
            }.onFailure { LogX.w("FileSender g6 skipped: ${it.javaClass.simpleName}") }

            // l1.a() 才是真正 execute（内部 modelbase/p1.g(cgi, 0)），返回是否入队成功
            val queued = XposedHelpers.callMethod(l1, "a") as? Boolean ?: false   // 845812
            if (!queued) {
                LogX.w("FileSender l1.a()=false kind=$kind wxid=${wxid.take(24)}")
                return@runCatching false
            }
            LogX.i("FileSender queued kind=$kind wxid=${wxid.take(24)} path=${path.take(80)}")
            true
        }.onFailure {
            LogX.w("FileSender send failed kind=$kind ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 取 `hl0.o1` 的枚举常量。
     *
     * ⚠️ **不能用字段名**（`o1.e` / `o1.f` 是混淆后的字段名，且与常量名无对应关系）。
     * 两路取法，优先 name：
     * 1. `Enum.valueOf(cls, "IMAGE")` —— 枚举的 **name 字符串在 `<clinit>` 里是明文**
     *    （`const-string "IMAGE"` → `Enum.<init>(name, ordinal)`），所以这条路最稳；
     * 2. name 失败时退回 `values()[idx]`，索引取自 `<clinit>` 的声明顺序：
     *    `0=TEXT 1=IMAGE 2=VIDEO 3=CDN_IMAGE 4=EMOJI 5=APPMSG`。
     */
    private fun pickEnum(o1Cls: Class<*>, name: String): Any? {
        runCatching {
            val v = o1Cls.getMethod("valueOf", String::class.java).invoke(null, name)
            if (v != null) return v
        }.onFailure { LogX.w("FileSender o1.valueOf($name) failed: ${it.javaClass.simpleName}") }

        val arr = runCatching {
            XposedHelpers.callStaticMethod(o1Cls, "values") as? Array<*>
        }.getOrNull()
        if (arr == null || arr.size < 3) {
            LogX.w("FileSender hl0.o1.values() unexpected: ${arr?.size}")
            return null
        }
        val idx = 1   // 只有 IMAGE 走这里；VIDEO 已在 send() 入口挡掉
        return arr[idx]?.also {
            LogX.w("FileSender o1.valueOf($name) 退回 values()[$idx]")
        }
    }

    /**
     * 按本地路径的扩展名猜可发送种类。
     *
     * ⚠️ 这是**兜底**：正常路径应由上层用 [com.lx.wxgroup.feature.ForwardLogic.Kind] 判定
     * （那是按微信 `message.type` 判的，比扩展名可靠）。仅当上层拿不到 kind 才用本函数。
     */
    fun kindOfFile(path: String): Kind {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif" -> Kind.IMAGE
            // 视频目前发不出去（见类注释「为什么砍掉 VIDEO」），但**要能认出来**，
            // 这样上层日志写的是 VIDEO 而不是 UNSUPPORTED，排查时不会误以为「类型判错」。
            "mp4", "mov", "m4v", "3gp", "avi", "mkv", "wmv", "flv", "ts" -> Kind.VIDEO
            else -> Kind.UNSUPPORTED
        }
    }
}
