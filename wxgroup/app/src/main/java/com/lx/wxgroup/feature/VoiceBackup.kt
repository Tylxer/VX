package com.lx.wxgroup.feature

import android.os.Handler
import android.os.HandlerThread
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.data.VoiceBackupLogic
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * 语音文件的**备份 / 还原**（补 [VoiceGuard] 拦不住的那一步）。
 *
 * ## 为什么需要（真机实锤，2026-10-25 R21c，别再走回头路）
 *
 * 撤回语音时微信做三件事：
 * 1. `UPDATE message SET type=... WHERE msgId=?` —— [AntiRevoke] 拦住 ✅
 * 2. `DELETE FROM voiceinfo WHERE FileName= ?` —— [VoiceGuard] 拦住 ✅
 * 3. **`File.delete()` 删磁盘音频文件** —— 不经过 SQLite，**hook 完全看不到** ❌
 *
 * 只拦前两步时真机现象：`voiceinfo` 行完好、`message` 行仍是 `type=34`，
 * **但点播放没反应**。因为播放器要「表里有记录 + 磁盘有文件」两者齐备。
 *
 * 证据（不是推测）：
 * - 全树递归扫 `voice2/` 的 **20827 个目录**：对照组
 *   `msg_50095010242638d35faef1f106.amr` 命中 1 个；被撤回那条
 *   `4323041025263e6e1a32793103` **命中 0 个**。
 * - 被撤那条的桶目录 `voice2/01/70/` 存在但**空**，mtime = 撤回日志时刻 +4ms。
 *
 * ## 路径规则（[VoiceBackupLogic] 有 21 个真机样本单测）
 *
 * ```
 * voice2/<md5(imgPath)[0..2]>/<md5(imgPath)[2..4]>/msg_<imgPath>.amr
 * ```
 *
 * ⚠️ **必须算路径，不能全树扫**：全树扫实测 ~1.5s / 40967 节点，而备份的窗口
 *    （撤回拦截 → `vj0.p` 实际删文件）只隔着一次 `executor.submit`，几十毫秒。
 *    在 hook 线程扫 1.5 秒 = 文件早没了 + 微信卡住。
 *
 * ## 备份放哪
 *
 * `dataDir/MicroMsg/<hash>/voice_bak/<桶>/msg_<imgPath>.amr` —— 即微信自己目录下
 * （用户 2026-10-25 选的方案）。理由：省一次跨进程拷贝，且随微信数据一起被管理；
 * 代价是可能被微信的 GC 扫到，所以目录名用 `voice_bak` 且**不放在 `voice2` 里面**
 * （放里面会被 voice2 的递归清理连带删掉）。
 *
 * ## 线程纪律
 *
 * [backup] 由 `AntiRevoke` 的 update hook 调用 —— 那是**微信自己的线程**，不是主线程；
 * 单次文件拷贝 < 20KB，耗时微秒级，就地同步执行是可以接受的。绝不在这条路径上
 * 扫目录 / 查库。
 */
object VoiceBackup {

    /** 备份根目录名（相对账号目录，与 `voice2` 平级） */
    private const val BACKUP_DIR = "voice_bak"

    /** `voice2` 目录名 */
    private const val VOICE_DIR = "voice2"

    /**
     * 还原检查的时刻表（毫秒，相对 `record()` 那一刻）。
     *
     * ## 为什么是「多次」而不是一次（R21c-5 复核判 FAIL 的修复）
     *
     * 单次检查有真实缺口：`restore` 的闸门是 `shouldRestore(backupExists, targetExists)`，
     * 即「备份在 && 原位不在」才动手。如果那一刻文件**还没被删**（微信的删除任务在
     * `executor` 池里被 IO 密集任务堵住、排队超过 1s），这次检查就是 no-op；随后文件
     * 被删，**就再没人补了** —— 而本项目**没有播放路径 hook**，不存在「点播放前补一次」
     * 这种替代兜底。所以延时检查是**唯一**的还原点，漏一次 = 那条语音永久播不了。
     *
     * ## 时刻的取值依据
     *
     * | 时刻 | 覆盖什么 |
     * |---|---|
     * | 1s | 正常路径。真机实测桶目录 mtime = 撤回日志时刻 **+4ms**，量级是毫秒级 ⇒ 余量约 250 倍 |
     * | 5s | `executor` 排队到几百 ms ~ 几秒的情况 |
     * | 15s | 极端卡顿（用户同时在刷朋友圈/下载大文件）；也顺手覆盖 §5 说的 TOCTOU 窗口 |
     *
     * 每次成本 = 两次 `isFile`（O(1)，不扫目录、不查库），与前几个 60s 级的 TTL 同量级。
     * 幂等：文件在就 no-op，所以**多跑几次不会有副作用**。
     */
    private val RESTORE_DELAYS_MS = longArrayOf(1_000L, 5_000L, 15_000L)

    /**
     * 延时任务的调度线程（单线程、懒初始化）。
     *
     * ⚠️ 为什么不用主线程 `Handler(Looper.getMainLooper())`：这个还原是**补文件**，
     *    但每条被撤回的语音要查 3 次；项目里其它 `postDelayed` 全挂在主线程上，
     *    再往主线程堆文件 IO 没必要。单线程 HandlerThread 既保证「不并发拷贝同一个
     *    文件」，也不占用任何现有线程。
     * ⚠️ 线程常驻、无引用也不影响：运行中的 `HandlerThread` 自身就是 GC root。
     *    这里**刻意不保存 Thread 引用**（存了也只能是死字段，见 R21c-5 §8.1）。
     */
    @Volatile
    private var delayHandler: Handler? = null

    /**
     * 本次进程内已知的「账号目录」（`MicroMsg/<hash>`）。
     *
     * 从主库路径推出来（`.../MicroMsg/<hash>/EnMicroMsg.db`），缓存起来避免反复算。
     * 拿不到就退化成「按 dataDir 逐个 hash 目录试」——那种情况极少，且备份失败只是
     * 退化成当前行为（播放失败），不会更糟。
     */
    @Volatile
    private var accountDir: String? = null

    /**
     * 备份一条语音的音频文件。
     *
     * @param origType 原消息类型（只在 `type=34` 时动作，判据见 [VoiceBackupLogic.needsBackup]）
     * @param imgPath  `message.imgPath`，26 位 hex
     * @return 是否真的复制了文件（`false` 不代表出错，可能是本来就不需要备）
     *
     * ⚠️ 全程吞异常：这是「尽力而为」的补救路径，任何失败都不许影响撤回拦截本身。
     */
    fun backup(origType: Int, imgPath: String?): Boolean {
        if (!VoiceBackupLogic.needsBackup(origType)) return false
        if (!VoiceBackupLogic.isValidImgPath(imgPath)) return false
        val img = imgPath!!
        return runCatching {
            val acc = accountDir() ?: return false
            val md5 = VoiceBackupLogic.md5Hex(img)
            val bucket = VoiceBackupLogic.bucketOf(md5) ?: return false
            val name = VoiceBackupLogic.fileNameOf(img)

            val src = File(File(File(acc, VOICE_DIR), bucket), name)
            if (!src.isFile) {
                // 未下载 / 已过期 / 别的原因没有文件 —— 没东西可备，安静跳过
                LogX.i("VB skip: source missing img=$img")
                return false
            }

            val dstDir = File(File(acc, BACKUP_DIR), bucket)
            val dst = File(dstDir, name)
            if (dst.isFile && dst.length() == src.length()) {
                // 已备过且大小一致 ⇒ 不重复拷（微信可能对同一条重试）
                return false
            }
            // ⚠️ 0 字节不当备份（R21c-5 §8.3）：理论上语音下载完成后必非空，
            //    但若真碰上 0 字节占位，备它 = 之后 restore 补回一个空文件，
            //    看起来「补了」实际还是播不了 —— 那比不补更误导排查。
            if (src.length() <= 0) {
                LogX.w("VB skip: source empty img=$img")
                return false
            }
            if (!dstDir.isDirectory && !dstDir.mkdirs()) {
                LogX.w("VB mkdirs failed: ${dstDir.absolutePath}")
                return false
            }
            src.copyTo(dst, overwrite = true)
            val copied = dst.length()
            LogX.i("VB backup ok img=$img bytes=$copied dst=${bucket}/${name}")
            copied > 0
        }.onFailure {
            LogX.w("VB backup failed img=$imgPath ${it.javaClass.simpleName}: ${it.message?.take(60)}")
        }.getOrDefault(false)
    }

    /**
     * 把某条语音的备份搬回原位（原位置已存在则**不动**，见
     * [VoiceBackupLogic.shouldRestore]）。
     *
     * 什么时候调：
     * - **[scheduleRestore] 的延时任务**（主路径，删除链跑完之后）
     * - [VoiceGuard] 的 `delete` hook 命中时（免费早触发，文件通常还在 ⇒ 直接 no-op）
     * - `voicebak sub=restore` 探针手动触发
     *
     * @return 是否真的还原了
     */
    fun restore(imgPath: String?): Boolean {
        if (!VoiceBackupLogic.isValidImgPath(imgPath)) return false
        val img = imgPath!!
        return runCatching {
            val acc = accountDir() ?: return false
            val md5 = VoiceBackupLogic.md5Hex(img)
            val bucket = VoiceBackupLogic.bucketOf(md5) ?: return false
            val name = VoiceBackupLogic.fileNameOf(img)

            val src = File(File(File(acc, BACKUP_DIR), bucket), name)
            val dstDir = File(File(acc, VOICE_DIR), bucket)
            val dst = File(dstDir, name)

            if (!VoiceBackupLogic.shouldRestore(src.isFile, dst.isFile)) return false
            if (!dstDir.isDirectory && !dstDir.mkdirs()) {
                LogX.w("VB restore mkdirs failed: ${dstDir.absolutePath}")
                return false
            }
            src.copyTo(dst, overwrite = false)
            LogX.i("VB restore ok img=$img bytes=${dst.length()}")
            dst.isFile && dst.length() > 0
        }.onFailure {
            LogX.w("VB restore failed img=${imgPath} ${it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * 排「删除链跑完之后」的幂等还原检查（**唯一兜底，别删**）。
     *
     * ## 为什么不能只靠 `VoiceGuard.onDelete` 里那次 restore
     *
     * 已定案的删除顺序是 `voiceinfo` 的 `DELETE` **在前**、`File.delete`(磁盘) **在后**。
     * 所以在 `onDelete` 命中那一刻，**文件还在** ⇒
     * `shouldRestore(backup=true, target=true)` 恒为 false ⇒ 那次 restore 是空操作；
     * 随后文件被删，就再没人补了。**它只能算「免费早触发」，不能当兜底。**
     *
     * ## 为什么是 3 次而不是 1 次
     *
     * 见 [RESTORE_DELAYS_MS] 的 KDoc：单次检查在「删除排队 > 1s」时会 no-op 后漏掉，
     * 而本项目没有播放路径 hook，漏了就真的没法补。
     *
     * 幂等 + 只做文件级判据，所以**重复调用、晚调用、乱序调用都安全**。
     *
     * ⚠️ 开销：每次撤回一条语音 = 3 次 `postDelayed` + 3 次 `isFile`。
     *    绝不在本方法里扫目录或查库。
     */
    fun scheduleRestore(imgPath: String?) {
        if (!VoiceBackupLogic.isValidImgPath(imgPath)) return
        val img = imgPath!!
        val h = handler()
        if (h == null) {
            LogX.w("VB scheduleRestore: no handler img=$img")
            return
        }
        for (delay in RESTORE_DELAYS_MS) {
            // restore() 内部已经把全部逻辑包进 runCatching 且永不抛异常，
            // 这里**不需要**再套一层（R21c-5 §8.2 指出的冗余）。
            h.postDelayed({ restore(img) }, delay)
        }
    }

    /** 懒建单线程 HandlerThread，建不起来就返回 null（调用方安静放弃）。 */
    private fun handler(): Handler? {
        delayHandler?.let { return it }
        return runCatching {
            synchronized(this) {
                delayHandler?.let { return it }
                val t = HandlerThread("VoiceBak").apply { start() }
                Handler(t.looper).also { delayHandler = it }
            }
        }.onFailure {
            LogX.w("VB handler init failed: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    /**
     * 一次性打印整条链的关键判据（探针 `op=voicebak sub=chain`）。
     *
     * 用途：**不依赖撤回动作**就能验证「路径规则 + 账号目录推导」对不对。
     * 撤回只能请人配合，代价高；而路径规则错了会让 backup/restore 全部静默失败，
     * 那时只看到 `VB skip: source missing` 会误判成「文件本来就不存在」。
     *
     * 输出形如：
     * ```
     * acc=/data/.../MicroMsg/<hash> bucket=01/70
     * src(exists=false) bak(exists=true,20KB) -> restore(ok=false)
     * ```
     */
    fun probeChain(imgPath: String?): String {
        if (!VoiceBackupLogic.isValidImgPath(imgPath)) return "bad-imgPath"
        val img = imgPath!!
        return runCatching {
            val acc = accountDir() ?: return "no account dir"
            val md5 = VoiceBackupLogic.md5Hex(img)
            val bucket = VoiceBackupLogic.bucketOf(md5) ?: return "bad-md5"
            val name = VoiceBackupLogic.fileNameOf(img)
            val src = File(File(File(acc, VOICE_DIR), bucket), name)
            val bak = File(File(File(acc, BACKUP_DIR), bucket), name)
            val restored = restore(img)
            "acc=$acc bucket=$bucket " +
                "src(exists=${src.isFile},${src.length()}B) " +
                "bak(exists=${bak.isFile},${bak.length()}B) " +
                "restore(ok=$restored) md5=$md5"
        }.getOrElse { "error: ${it.javaClass.simpleName}" }
    }

    /**
     * 备份目录概况（探针用）：文件数 / 总字节 / 最近 8 个。
     *
     * 判据：`backup ok` 之后这里必须能数出对应文件 —— 只看 `backup ok=true`
     * 不够，那只是「copyTo 没抛异常」。
     */
    fun stat(): String {
        val acc = accountDir()
        if (acc == null) return "stat: no account dir"
        val root: File = File(acc, BACKUP_DIR)
        if (!root.isDirectory) return "stat: no backup dir (${root.absolutePath})"
        val files = ArrayList<File>()
        root.walkTopDown().forEach { if (it.isFile) files.add(it) }
        val total = files.sumOf { it.length() }
        val recent = files.sortedByDescending { it.lastModified() }.take(8)
            .joinToString(" ; ") { "${it.parentFile?.name}/${it.name}=${it.length()}B" }
        return "stat: files=${files.size} bytes=$total root=${root.absolutePath}" +
            if (recent.isEmpty()) "" else " recent[ $recent ]"
    }

    /**
     * 账号目录 `dataDir/MicroMsg/<hash>`。
     *
     * 优先从主库当前路径推（最准，微信多账号时也对）；拿不到就退化成扫
     * `MicroMsg/` 下第一个含 `EnMicroMsg.db` 的目录。
     */
    private fun accountDir(): String? {
        accountDir?.let { if (File(it).isDirectory) return it }

        // ① 从主库实例的真实路径反推：.../MicroMsg/<hash>/EnMicroMsg.db
        val fromDb = runCatching {
            val db = AntiRevoke.wxDb() ?: return@runCatching null
            val p = XposedHelpers.callMethod(db, "getPath") as? String
            if (p.isNullOrBlank()) null else File(p).parentFile?.absolutePath
        }.getOrNull()
        if (fromDb != null && File(fromDb).isDirectory) {
            accountDir = fromDb
            return fromDb
        }

        // ② 退化：扫 MicroMsg/ 下带 EnMicroMsg.db 的目录
        val base = HostContext.app()?.dataDir ?: return null
        val micro = File(base, "MicroMsg")
        val hit = micro.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "EnMicroMsg.db").isFile }
            ?.absolutePath
        if (hit != null) accountDir = hit
        return hit
    }
}
