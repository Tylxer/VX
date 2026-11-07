package com.lx.wxgroup.feature

import com.lx.wxgroup.util.LogX
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 朋友圈视频解密探针（R45）。
 *
 * ## 背景（已确证，不在此重新验证）
 * 朋友圈视频 CDN 下载回来的是**密文**，仅前 131072 字节加密，之后明文。
 * 微信自己的解密 API：`com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI`（classes7.dex，
 * 宿主 ClassLoader 可加载），native 在 `libwechatcommon.so`。算法 ISAAC-64 XOR（对称）。
 *
 * ```java
 * public final class MMIMAGEENCJNI {
 *     public final long open(long key);              // open(J)J
 *     public final long open(String seed);           // open(Ljava/lang/String;)J
 *     public final byte[] readByte(long h, byte[] buf);  // readByte(J[B)[B
 *     public final long seek(long h, long pos, int whence);  // seek(JJI)J
 *     public final long transFor(long h, byte[] buf, long off, long len);  // transFor(J[BJJ)J
 *     public final long free(long h);                // free(J)J
 * }
 * ```
 *
 * key 来源：微信明文缓存文件名 `sight_<mediaId><32hex>&enc<十进制>[_V2]`，`enc` 后的数字即 key。
 *
 * ## ⚠️ 反射铁律
 * 1. 必须用**宿主的 ClassLoader**（[SnsGuard.hostLoaderHandle]），不能用模块自己的 loader
 *    （模块 loader 找不到宿主的类）。
 * 2. 方法全是**实例方法**（`public final` 非 `static`），invoke 的 receiver 要传实例对象；
 *    同时按 `Modifier.isStatic` 兜底 —— 万一某形态是 static，receiver 传 null。
 * 3. `open` 有 `(J)` / `(String)` 两个重载，必须按 `parameterTypes[0]` 精确挑，
 *    不能 `getMethod` 猜。
 * 4. 全部 `runCatching` 兜底，探针绝不能崩微信；`free(handle)` 必须 finally 调用。
 */
object SnsVideoDecProbe {

    private const val T = "VIDDEC"
    private const val CLASS_NAME = "com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI"
    private const val SO_NAME = "libwechatcommon.so"
    private const val DEF_LEN = 131072L

    /**
     * `long` 的原始类型 Class。
     *
     * ⛔ Kotlin 里**没有** `Long.TYPE`（那是 Java 的 `java.lang.Long.TYPE`）。
     * 写 `Long.TYPE` 会直接编译失败：`Unresolved reference 'TYPE'`（R45-01 已踩）。
     * Kotlin 的正确写法是 `Long::class.javaPrimitiveType`。
     */
    private val JLONG: Class<*> = Long::class.javaPrimitiveType!!

    /** 入口。sub ∈ api|dec|key|try；arg 见各 sub 注释；off/len 默认 0 / 131072 */
    fun run(sub: String, arg: String, off: Long, len: Long): List<String> =
        when (sub.lowercase()) {
            "api" -> api()
            "dec" -> dec(arg, off, len)
            "key" -> key(arg)
            "try" -> tryDec(arg, len)
            else -> listOf("$T unknown sub=$sub（可用 api|dec|key|try）")
        }

    // ---------------------------------------------------------------- api 能力探测

    /**
     * 只读能力探测：反射找 [CLASS_NAME]，列 public 方法签名，报 SO 是否已加载。
     * **不调用任何会改状态的方法**（不 open/free/transFor）。
     */
    private fun api(): List<String> {
        val out = ArrayList<String>()
        val cl = loader()
        if (cl == null) {
            out += "$T api no host ClassLoader（SnsGuard 未 install）"
            return out
        }
        out += "$T api so=$SO_NAME loaded=${soLoaded()}"
        val cls = findClass(cl)
        if (cls == null) {
            out += "$T api class=$CLASS_NAME found=false"
            return out
        }
        out += "$T api class=$CLASS_NAME found=true"
        val methods = cls.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .sortedBy { it.name }
        out += "$T api methods=${methods.size}"
        for (m in methods) out += "$T api method: ${sig(m)}"
        return out
    }

    // ---------------------------------------------------------------- dec 核心验证

    /**
     * arg = `密文路径|十进制key` 多条用 `;` 分隔。
     *
     * 对每条：读密文 → `open(key)` → 取 `[off, off+n)`（n = min(len, size-off)）字节 →
     * `transFor(handle, buf, 0, n)` → `free` → 解密段拼回 → 写 `原路径.dec`。
     * 报告前 8 字节 hex + 是否 ftyp 明文标志 `00 00 00 ?? 66 74 79 70`。
     */
    private fun dec(arg: String, off: Long, len: Long): List<String> {
        val out = ArrayList<String>()
        val cl = loader() ?: return listOf("$T dec no host ClassLoader")
        val cls = findClass(cl) ?: return listOf("$T dec class not found")
        val instance = newInstance(cls)
        val openM = findOpen(cls, JLONG) ?: return listOf("$T dec no open(J)J")
        val transM = findMethod(
            cls, "transFor", JLONG, ByteArray::class.java, JLONG, JLONG
        ) ?: return listOf("$T dec no transFor(J[BJJ)J")
        val freeM = findMethod(cls, "free", JLONG) ?: return listOf("$T dec no free(J)J")
        val effLen = if (len > 0) len else DEF_LEN

        for (entry in arg.split(";")) {
            if (entry.isBlank()) continue
            val p = entry.split("|")
            if (p.size < 2) {
                out += "$T dec bad-arg=[$entry]"
                continue
            }
            val path = p[0].trim()
            val key = p[1].trim().toLongOrNull()
            if (path.isEmpty() || key == null) {
                out += "$T dec bad-key=[$entry]"
                continue
            }
            val f = File(path)
            if (!f.exists()) {
                out += "$T dec missing=$path"
                continue
            }
            val bytes = runCatching { f.readBytes() }.getOrNull()
            if (bytes == null) {
                out += "$T dec read-fail=$path"
                continue
            }
            val size = bytes.size.toLong()
            val start = off.coerceAtLeast(0L)
            if (start >= size) {
                out += "$T dec off-oob path=$path size=$size off=$start"
                continue
            }
            val n = minOf(effLen, size - start).toInt().coerceAtLeast(0)
            if (n <= 0) {
                out += "$T dec empty=$path"
                continue
            }
            val buf = bytes.copyOfRange(start.toInt(), start.toInt() + n)
            val h = openHandle(instance, openM, key)
            if (h < 0) {
                out += "$T dec open-fail path=$path key=$key"
                continue
            }
            val r = try {
                transFor(instance, transM, h, buf, 0L, n.toLong())
            } finally {
                freeHandle(instance, freeM, h)
            }
            val decBytes = bytes.copyOf()
            System.arraycopy(buf, 0, decBytes, start.toInt(), n)
            val outPath = path + ".dec"
            val wrote = runCatching { File(outPath).writeBytes(decBytes); true }
                .getOrDefault(false)
            out += "$T dec path=$path key=$key size=$size n=$n r=$r ftyp=${isFtyp(buf)} " +
                "head8=${hex(buf, 8)} wrote=$wrote out=$outPath"
        }
        return out.ifEmpty { listOf("$T dec empty arg") }
    }

    // ---------------------------------------------------------------- key 文件名扫描

    /** arg = 目录。扫描其中 `sight_*`/`snst_*`/`snsu_*` 文件名，提取 mediaId 与 enc。只读。 */
    private fun key(arg: String): List<String> {
        val dir = File(arg)
        if (!dir.isDirectory) return listOf("$T key not-dir=$arg")
        val re = Regex("^(sight|snst|snsu)_(.+?)&enc(\\d+)(?:_V2)?$")
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val out = ArrayList<String>()
        for (f in files.sortedBy { it.name }) {
            val m = re.matchEntire(f.name) ?: continue
            out += "$T key name=${f.name} prefix=${m.groupValues[1]} " +
                "mediaId=${m.groupValues[2]} enc=${m.groupValues[3]}"
        }
        return out.ifEmpty { listOf("$T key no-match in $arg") }
    }

    // ---------------------------------------------------------------- try 明文比对

    /**
     * arg = `密文路径|明文路径|十进制key`，多条 `;` 分隔。
     *
     * 解密后与明文逐字节比对（前 n = min(len, 密文size) 字节）：
     * 报告前 256 字节命中数 / 首位不一致偏移 / 是否完全一致。最强判据。
     */
    private fun tryDec(arg: String, len: Long): List<String> {
        val out = ArrayList<String>()
        val cl = loader() ?: return listOf("$T try no host ClassLoader")
        val cls = findClass(cl) ?: return listOf("$T try class not found")
        val instance = newInstance(cls)
        val openM = findOpen(cls, JLONG) ?: return listOf("$T try no open(J)J")
        val transM = findMethod(
            cls, "transFor", JLONG, ByteArray::class.java, JLONG, JLONG
        ) ?: return listOf("$T try no transFor(J[BJJ)J")
        val freeM = findMethod(cls, "free", JLONG) ?: return listOf("$T try no free(J)J")
        val effLen = if (len > 0) len else DEF_LEN

        for (entry in arg.split(";")) {
            if (entry.isBlank()) continue
            val p = entry.split("|")
            if (p.size < 3) {
                out += "$T try bad-arg=[$entry]"
                continue
            }
            val encPath = p[0].trim()
            val plainPath = p[1].trim()
            val key = p[2].trim().toLongOrNull()
            if (encPath.isEmpty() || plainPath.isEmpty() || key == null) {
                out += "$T try bad-key=[$entry]"
                continue
            }
            val encF = File(encPath)
            val plainF = File(plainPath)
            if (!encF.exists()) {
                out += "$T try missing-cipher=$encPath"
                continue
            }
            if (!plainF.exists()) {
                out += "$T try missing-plain=$plainPath"
                continue
            }
            val encBytes = runCatching { encF.readBytes() }.getOrNull()
            val plainBytes = runCatching { plainF.readBytes() }.getOrNull()
            if (encBytes == null || plainBytes == null) {
                out += "$T try read-fail=$encPath"
                continue
            }
            val size = encBytes.size.toLong()
            val n = minOf(effLen, size).toInt().coerceAtLeast(0)
            if (n <= 0) {
                out += "$T try empty=$encPath"
                continue
            }
            val buf = encBytes.copyOfRange(0, n)
            val h = openHandle(instance, openM, key)
            if (h < 0) {
                out += "$T try open-fail=$encPath key=$key"
                continue
            }
            val r = try {
                transFor(instance, transM, h, buf, 0L, n.toLong())
            } finally {
                freeHandle(instance, freeM, h)
            }

            var hit256 = 0
            var firstDiff = -1
            var allSame = true
            val cmpN = minOf(n, plainBytes.size)
            for (i in 0 until cmpN) {
                if (buf[i] == plainBytes[i]) {
                    if (i < 256) hit256++
                } else {
                    if (firstDiff < 0) firstDiff = i
                    allSame = false
                }
            }
            if (cmpN < n) allSame = false // 明文比解密段短，视作不一致
            out += "$T try enc=$encPath key=$key n=$n r=$r hit256=$hit256 " +
                "firstDiff=$firstDiff allSame=$allSame head8=${hex(buf, 8)}"
        }
        return out.ifEmpty { listOf("$T try empty arg") }
    }

    // ---------------------------------------------------------------- 反射底层

    private fun loader(): ClassLoader? = SnsGuard.hostLoaderHandle()

    private fun findClass(cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(CLASS_NAME, false, cl) }
            .onFailure { LogX.w("$T no class $CLASS_NAME: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** 实例方法优先，构造拿不到就退无参；再不行拿第一个构造器用 null 填充 */
    private fun newInstance(cls: Class<*>): Any? =
        runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull()
            ?: runCatching {
                val c = cls.declaredConstructors.firstOrNull() ?: return@runCatching null
                c.isAccessible = true
                c.newInstance(*arrayOfNulls<Any?>(c.parameterTypes.size))
            }.getOrNull()

    /** 按名字 + 精确参数类型挑方法（open 的两个重载不能靠 getMethod 猜） */
    private fun findMethod(cls: Class<*>, name: String, vararg params: Class<*>): Method? =
        cls.declaredMethods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == params.size &&
                m.parameterTypes.zip(params).all { (a, b) -> a == b }
        }?.also { it.isAccessible = true }

    private fun findOpen(cls: Class<*>, keyType: Class<*>): Method? =
        cls.declaredMethods.firstOrNull {
            it.name == "open" && it.parameterTypes.size == 1 && it.parameterTypes[0] == keyType
        }?.also { it.isAccessible = true }

    private fun receiver(instance: Any?, m: Method): Any? =
        if (Modifier.isStatic(m.modifiers)) null else instance

    private fun openHandle(instance: Any?, openM: Method, key: Long): Long =
        runCatching { (openM.invoke(receiver(instance, openM), key) as? Long) ?: -1L }
            .onFailure { LogX.w("$T open fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}") }
            .getOrDefault(-1L)

    private fun transFor(
        instance: Any?, transM: Method, h: Long, buf: ByteArray, off: Long, len: Long
    ): Long =
        runCatching {
            (transM.invoke(receiver(instance, transM), h, buf, off, len) as? Long) ?: -1L
        }.onFailure {
            LogX.w("$T transFor fail: ${it.javaClass.simpleName}: ${it.message?.take(80)}")
        }.getOrDefault(-1L)

    private fun freeHandle(instance: Any?, freeM: Method, h: Long) {
        runCatching { freeM.invoke(receiver(instance, freeM), h) }
    }

    // ---------------------------------------------------------------- 生产入口（R45-02）

    /**
     * 生产入口：原地解密一个视频文件的前 [DEF_LEN] 字节。
     *
     * @param f      视频文件（**原地改写**，调用方传 tmp 文件）
     * @param key    SnsInfo content 里 `f8.f5.f25` 的解密 key（ISAAC-64 seed）
     * @param enclen 加密长度（CDN 响应头 `X-enclen`）；`<=0` 时回退 [DEF_LEN]=131072
     * @return 文件头已是明文（幂等短路，未二次改写）或成功改写 → true；
     *         key<=0 / 类找不到 / IO 失败 / 解密失败 → false
     *
     * ⛔ **绝不在导出线程上抛异常**：全 runCatching 兜底，任何失败 return false，
     *    调用方据此「宁可存密文，不能丢文件」。
     */
    fun decryptInPlace(f: File, key: Long, enclen: Long = DEF_LEN): Boolean {
        val t0 = System.currentTimeMillis()
        return runCatching {
            if (key <= 0L) {
                LogX.w("$T inplace path=${f.name} key=$key ok=false why=key-invalid ms=${System.currentTimeMillis() - t0}")
                return@runCatching false
            }
            if (!f.exists() || !f.isFile) {
                LogX.w("$T inplace path=${f.name} key=$key ok=false why=missing ms=${System.currentTimeMillis() - t0}")
                return@runCatching false
            }
            val size = f.length()
            if (size <= 0L) {
                LogX.w("$T inplace path=${f.name} key=$key ok=false why=empty ms=${System.currentTimeMillis() - t0}")
                return@runCatching false
            }
            // 只读前 min(enclen, size) 字节；加密区之后是明文，不动。
            // ⛔ enclen 来自网络响应头，属于**不可信输入**：钳到 DEF_LEN(131072)。
            //    全库 22377 条加密视频的 X-enclen 恒为 131072，超过就是异常/被篡改，
            //    按它去分配 ByteArray 只会白耗内存（极端值可能触发 OOM）。
            val effLen = (if (enclen > 0L) enclen else DEF_LEN).coerceAtMost(DEF_LEN)
            val n = minOf(effLen, size).toInt()
            // 12 = ftyp box 最小长度；更短的文件不可能有合法 mp4 头，直接放行（视为无需解密）
            if (n < 12) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=too-small ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            val buf = ByteArray(n)
            var got = 0
            try {
                java.io.RandomAccessFile(f, "r").use { raf ->
                    while (got < n) {
                        val r = raf.read(buf, got, n - got)
                        if (r < 0) break
                        got += r
                    }
                }
            } catch (t: Throwable) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=read-fail ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            // ⛔ 短读保护：`f.length()` 报 n，但并发截断 / IO 短读可能只拿到 got < n 字节。
            //    若不拦住，buf 尾部是零值，transFor 会把这段 0 XOR 成密钥流前段，
            //    再 write(buf, 0, n) 会把文件**撑大到 n 并写成垃圾**。
            //    宁可放弃这次解密（保留原密文），也不能写出一个结构损坏的文件。
            if (got < n) {
                LogX.w(
                    "$T inplace path=${f.name} key=$key n=$n ok=false why=short-read " +
                        "got=$got ms=${System.currentTimeMillis() - t0} size=$size"
                )
                return@runCatching false
            }
            val ftyp = isFtyp(buf)
            // 幂等：文件头已是明文（ftyp）就直接 return true，不二次解
            if (ftyp) {
                LogX.i(
                    "$T inplace path=${f.name} key=$key n=$n ok=true why=ftyp-already-plain " +
                        "ms=${System.currentTimeMillis() - t0} size=$size"
                )
                return@runCatching true
            }

            val cl = loader()
            if (cl == null) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=no-loader ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            val cls = findClass(cl)
            if (cls == null) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=no-class ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            val instance = newInstance(cls)
            val openM = findOpen(cls, JLONG)
            val transM = findMethod(cls, "transFor", JLONG, ByteArray::class.java, JLONG, JLONG)
            val freeM = findMethod(cls, "free", JLONG)
            if (openM == null || transM == null || freeM == null) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=no-method ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }

            val h = openHandle(instance, openM, key)
            if (h < 0) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=open-fail ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            val r = try {
                transFor(instance, transM, h, buf, 0L, n.toLong())
            } finally {
                freeHandle(instance, freeM, h)
            }
            if (r < 0) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=trans-fail r=$r ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            // 写回同一区段（原地改写），并 fd.sync() 落盘（防 rename 后极端掉电拿半截文件）
            try {
                java.io.RandomAccessFile(f, "rw").use { raf ->
                    raf.seek(0L)
                    raf.write(buf, 0, n)
                    raf.fd.sync()
                }
            } catch (t: Throwable) {
                LogX.w("$T inplace path=${f.name} key=$key n=$n ok=false why=write-fail ms=${System.currentTimeMillis() - t0} size=$size")
                return@runCatching false
            }
            val ok = true
            LogX.i(
                "$T inplace path=${f.name} key=$key n=$n ok=$ok why=decrypted " +
                    "ms=${System.currentTimeMillis() - t0} size=$size"
            )
            ok
        }.onFailure {
            LogX.w(
                "$T inplace path=${f.name} key=$key ok=false why=threw " +
                    "${it.javaClass.simpleName}: ${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 工具

    private fun soLoaded(): Boolean = runCatching {
        File("/proc/self/maps").readText().contains(SO_NAME)
    }.getOrDefault(false)

    private fun hex(b: ByteArray, n: Int): String {
        val c = minOf(n, b.size)
        return (0 until c).joinToString(" ") {
            String.format(java.util.Locale.US, "%02x", b[it].toInt() and 0xff)
        }
    }

    /**
     * ftyp 明文标志：**只看 `b[4..7] == "ftyp"`**。
     *
     * 前 4 字节是 box 长度（4 字节大端），任意值都正常（`00 00 00 1c` 是小 box，
     * `40 00 00 00` 这种大 box 会让 b[0]!=0，所以不能要求前 3 字节为 0）。
     */
    private fun isFtyp(b: ByteArray): Boolean =
        b.size >= 8 &&
            b[4] == 0x66.toByte() && b[5] == 0x74.toByte() &&
            b[6] == 0x79.toByte() && b[7] == 0x70.toByte()

    private fun sig(m: Method): String {
        val params = m.parameterTypes.joinToString(",") { it.simpleName }
        val mods = StringBuilder()
        if (Modifier.isPublic(m.modifiers)) mods.append("public ")
        if (Modifier.isStatic(m.modifiers)) mods.append("static ")
        if (Modifier.isFinal(m.modifiers)) mods.append("final ")
        if (Modifier.isNative(m.modifiers)) mods.append("native ")
        return "$mods${m.returnType.simpleName} ${m.name}($params)"
    }
}
// [DONE] R45-01
// [DONE] R45-02
