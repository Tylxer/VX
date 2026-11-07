package com.lx.wxgroup.feature

import android.content.ContentValues
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 朋友圈「[已删除]」前缀最小验证探针（R42 prefix）。
 *
 * 目的：在真机上把 `[已删除]\n` 写进一条动态 `content` 的 protobuf 字段 5
 * （`ContentDesc`，正文），并支持还原。**只做探针**，不动现有灰标逻辑、不动开关。
 *
 * ## 为什么不能反射 `SnsInfoStorage.update`（混淆名 `d2`）
 * 那是**整实体覆盖**：字段拼不全就会清掉别的列，风险高。这里改用 wcdb 的标准
 * `update("SnsInfo", ContentValues{content=…}, "snsId=?", [id])` 只改 `content` 一列。
 *
 * ## ⚠️ db 是 wcdb 的 `SQLiteDatabase`，不是 AOSP 的
 * 所以**不能** `as? android.database.sqlite.SQLiteDatabase` 强转（wcdb 类
 * super=`SQLiteClosable`，与 AOSP 无继承关系，强转必为 null）。read/update 都走
 * 反射，签名见 `docs/_r21c_voice_sqlform.md` §3.1：
 *   `update(String, ContentValues, String, String[])I`
 *   `rawQuery(String, String[])` / `rawQuery(String, Object[])`
 * `ContentValues` 是 **android.content.ContentValues**（`SnsCommentGuard` 真机同库
 * 同表已用同一写法，见 `docs/_r18_snscomment_backup/SnsCommentGuard.kt`）。
 *
 * ## 前缀常量
 * `[已删除]\n`（半角方括号 + 单个 LF，**不是** 全角【】、不是 `\r\n`），共 12 字节。
 */
object SnsPrefixProbe {

    private const val PREFIX = "[已删除]\n"

    private const val TLO_CLASS = "com.tencent.mm.protocal.protobuf.TimeLineObject"
    private const val FIELD_DESC = "ContentDesc"

    private const val TABLE = "SnsInfo"
    private const val COL_CONTENT = "content"
    private const val BACKUP_DIR_NAME = "sns_prefix_backup"

    /** 入口。sub ∈ probe|prefix|strip|restore|stat|simdrop|setsrc2|self，id = snsId（可为负，adb 用 `--el` 传） */
    fun run(sub: String, id: Long): List<String> = when (sub.lowercase()) {
        "probe" -> probe(id)
        "prefix" -> prefix(id)
        "strip" -> strip(id)
        "restore" -> restore(id)
        "stat" -> stat()
        "simdrop" -> simdrop(id)
        "setsrc2" -> setsrc2(id)
        "self" -> self()
        else -> listOf(
            "PREFIX unknown sub=$sub（可用 probe|prefix|strip|restore|stat|simdrop|setsrc2|self）"
        )
    }

    /**
     * 给 [SnsGuard] 删除拦截 before 同步复用：读 content → parse → 加前缀 → toByteArray。
     *
     * ⚠️ 这是微信写库线程上的同步调用。整段 `runCatching` 兜底，任何失败（读不到 / 解析失败 /
     * 字段缺失 / set 失败 / serialize 失败）一律返回 **null**，绝不上抛 —— 调用方拿到 null
     * 就跳过本次附加，**不影响保位逻辑**。
     *
     * 幂等：`ContentDesc` 已以 [PREFIX] 开头 ⇒ 返回 null（调用方不写 content，重复删除同一
     * 条不会写成 `[已删除][已删除]`）。
     *
     * ⚠️ 只读 + 内存改造，**不落备份**：备份/还原仍是探针 `prefix`/`restore` 自己的职责，
     * 生产删除路径不写文件（避免在微信写库线程上做文件 IO）。
     *
     * @param db wcdb 的 SQLiteDatabase（[SnsGuard.snsDbHandle] / updateWithOnConflict 的 thisObject）
     * @param id snsId（0 = 无 id，直接 null）
     * @return 加了前缀的新 blob；null = 无需写 / 无法写
     */
    /** 生产删除路径上 [buildPrefixedBlob] 意外抛异常的告警只打一次（防系统性失败刷屏） */
    private val buildFailWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    fun buildPrefixedBlob(db: Any?, id: Long): ByteArray? = runCatching {
        if (id == 0L) return@runCatching null
        val d = db ?: return@runCatching null
        val cl = loader() ?: return@runCatching null
        val orig = readBlob(d, id) ?: return@runCatching null
        val tlo = parseTlo(cl, orig) ?: return@runCatching null
        val f = descField(cl) ?: return@runCatching null
        val before = readDesc(f, tlo) ?: return@runCatching null
        if (before.startsWith(PREFIX)) return@runCatching null
        val setOk = runCatching { f.set(tlo, PREFIX + before) }.isSuccess
        if (!setOk) return@runCatching null
        serialize(cl, tlo)
    }.onFailure {
        if (buildFailWarned.compareAndSet(false, true)) {
            LogX.w("PREFIX build fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                "${(it.cause ?: it).message?.take(80)}")
        }
    }.getOrNull()

    // ---------------------------------------------------------------- 六个子命令

    /** 只读：读 blob → 解析 → 取 ContentDesc 前 30 字，**不写库** */
    private fun probe(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX probe id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX no db（先让微信刷一次朋友圈拿到句柄）")
        val cl = loader() ?: return listOf("PREFIX no host ClassLoader（SnsGuard 未 install）")
        val blob = readBlob(d, id) ?: return listOf("PREFIX probe id=$id len=-1 read-fail")
        val desc = parseDesc(cl, blob)
            ?: return listOf("PREFIX probe id=$id len=${blob.size} parse-fail")
        val has = desc.startsWith(PREFIX)
        return listOf(
            "PREFIX probe id=$id len=${blob.size} desc=[${show(desc)}] hasPrefix=$has"
        )
    }

    /** 先备份 → 加前缀 → `update` 单列。已带前缀则跳过（幂等） */
    private fun prefix(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX write id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX no db")
        val cl = loader() ?: return listOf("PREFIX no host ClassLoader")
        val orig = readBlob(d, id) ?: return listOf("PREFIX write id=$id read-fail")

        val tlo = parseTlo(cl, orig) ?: return listOf("PREFIX write id=$id parse-fail")
        val f = descField(cl) ?: return listOf("PREFIX write id=$id no-field")
        val before = readDesc(f, tlo) ?: return listOf("PREFIX write id=$id field-read-fail")
        if (before.startsWith(PREFIX)) {
            return listOf("PREFIX write id=$id skipped=already")
        }

        // 备份落实：已存在不覆盖（保留最早的原始版本）
        val bk = writeBackup(id, orig)

        val setOk = runCatching { f.set(tlo, PREFIX + before) }.isSuccess
        if (!setOk) return listOf("PREFIX write id=$id field-set-fail")
        val nb = serialize(cl, tlo) ?: return listOf("PREFIX write id=$id serialize-fail")

        val rows = updateContent(d, id, nb)
        return listOf(
            "PREFIX write id=$id before=${show(before)} after=${show(PREFIX + before)} " +
                "rows=$rows backup=$bk"
        )
    }

    /**
     * 剥掉 `ContentDesc` 开头的 [PREFIX]（不影响其它任何字节）。
     *
     * 与 [prefix] 严格对称，但**不依赖备份**：`simdrop` 走的是生产路径
     * [buildPrefixedBlob]，设计上明确不落备份（微信写库线程不做文件 IO），
     * 所以那些被自动化测出来的前缀只能靠这个逆运算清掉。
     *
     * 幂等：开头不是 [PREFIX] ⇒ 直接返回 `skipped=none`，不写库。
     */
    private fun strip(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX strip id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX strip no db")
        val cl = loader() ?: return listOf("PREFIX strip no host ClassLoader")
        val orig = readBlob(d, id) ?: return listOf("PREFIX strip id=$id read-fail")

        val tlo = parseTlo(cl, orig) ?: return listOf("PREFIX strip id=$id parse-fail")
        val f = descField(cl) ?: return listOf("PREFIX strip id=$id no-field")
        val before = readDesc(f, tlo) ?: return listOf("PREFIX strip id=$id field-read-fail")
        if (!before.startsWith(PREFIX)) {
            return listOf("PREFIX strip id=$id skipped=none desc=${show(before)}")
        }
        val after = before.removePrefix(PREFIX)

        val setOk = runCatching { f.set(tlo, after) }.isSuccess
        if (!setOk) return listOf("PREFIX strip id=$id field-set-fail")
        val nb = serialize(cl, tlo) ?: return listOf("PREFIX strip id=$id serialize-fail")

        val rows = updateContent(d, id, nb)
        return listOf(
            "PREFIX strip id=$id before=${show(before)} after=${show(after)} rows=$rows"
        )
    }

    /** 从备份文件读回原始 blob → `update` 单列 */
    private fun restore(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX restore id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX no db")
        val f = backupFile(id)
        val blob = if (f != null && f.exists()) {
            runCatching { f.readBytes() }.getOrNull()
        } else {
            null
        }
        if (blob == null) {
            return listOf(
                "PREFIX restore id=$id no-backup（${f?.absolutePath ?: "backup dir n/a"}）"
            )
        }
        val rows = updateContent(d, id, blob)
        return listOf("PREFIX restore id=$id rows=$rows from=${f?.absolutePath ?: "n/a"}")
    }

    /** 列出备份目录里的 snsId 与字节数 */
    private fun stat(): List<String> {
        val dir = backupDir()
        if (dir == null || !dir.exists()) return listOf("PREFIX backups=[] (dir n/a)")
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".bin") }
            ?: emptyList()
        if (files.isEmpty()) return listOf("PREFIX backups=[]")
        val s = files.sortedBy { it.name }
            .joinToString(",") { "${it.name.removeSuffix(".bin")}:${it.length()}" }
        return listOf("PREFIX backups=[$s]")
    }

    /**
     * 模拟一次「好友删除动态」：写 `sourceType=0`，触发挂在 `updateWithOnConflict` 上的
     * `snsBlockGuard`（before），走真实的 `onSnsUpdateBefore → applyBlock`（keep = old|10）
     * + `attachPrefix`（把 `[已删除]\n` 塞进同一次 update 的 content）。
     *
     * ⚠️ 这是验证钩子路径用的模拟探针，生产不用。
     *
     * ## 重入风险判断（wcdb）
     * 这里在 io 线程调 `update`，钩子在同一线程同步执行；钩子里的 `buildPrefixedBlob`
     * 会再对同一个 db 做 `rawQuery`。判断为**安全**：
     * 1. 钩子是 `updateWithOnConflict` 的 **before** 半场，Xposed 在**原方法体执行之前**
     *    调用它 —— 此刻原方法尚未编译 SQL、尚未从连接池拿写锁，没有任何锁被持有；
     * 2. 嵌套的 `rawQuery` 走线程本地 session 独立拿连接、用完即还，不构成同线程重入死锁；
     * 3. 这条「update 钩子内 rawQuery」正是 [SnsGuard.attachPrefix] 的生产路径，真机已跑；
     *    即便某形态 wcdb 真有问题，`buildPrefixedBlob`/`readBlob` 全程 `runCatching`，
     *    失败只返回 null（跳过前缀），保位逻辑不受影响。
     */
    private fun simdrop(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX simdrop id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX simdrop no db（先让微信刷一次朋友圈拿到句柄）")
        val m = updateMethod(d) ?: return listOf("PREFIX simdrop no-update-method")
        val rows = runCatching {
            val cv = ContentValues().apply { put("sourceType", 0) }
            m.invoke(d, TABLE, cv, "snsId=?", arrayOf(id.toString())) as? Int ?: -1
        }.onFailure {
            LogX.w(
                "PREFIX simdrop update fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrDefault(-1)

        // 回读验证：src 复用钩子同一套 readBits 读 sourceType；desc 用 readBlob + parseDesc
        val src = SnsBlockProbe.readBits(d, id)?.src
        val cl = loader()
        val desc = if (cl != null) readBlob(d, id)?.let { parseDesc(cl, it) } else null
        val has = desc?.startsWith(PREFIX) ?: false
        return listOf(
            "PREFIX simdrop id=$id rows=$rows",
            "PREFIX simdrop-verify id=$id src=${src ?: "?"} " +
                "desc=[${desc?.let { show(it) } ?: ""}] hasPrefix=$has"
        )
    }

    /**
     * 把一条恢复成 `sourceType=2`（正常可见）。恢复用，不要求触发拦截。
     *
     * ⚠️ 这是验证钩子路径用的模拟探针，生产不用。
     * ⚠️ 值非 0 ⇒ 钩子走 `st != 0` 分支：`needsOld(2)=true` 会进降级判定。若旧值是 10，
     *    `decide(10,2)=BLOCK` 会被写回 keep=10，恢复**不会落地** —— 到底落没落地看回读 src。
     */
    private fun setsrc2(id: Long): List<String> {
        if (id == 0L) return listOf("PREFIX setsrc2 id=0（--el id <snsId> 必填）")
        val d = db() ?: return listOf("PREFIX setsrc2 no db")
        val m = updateMethod(d) ?: return listOf("PREFIX setsrc2 no-update-method")
        val rows = runCatching {
            val cv = ContentValues().apply { put("sourceType", 2) }
            m.invoke(d, TABLE, cv, "snsId=?", arrayOf(id.toString())) as? Int ?: -1
        }.onFailure {
            LogX.w(
                "PREFIX setsrc2 update fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrDefault(-1)
        val src = SnsBlockProbe.readBits(d, id)?.src
        return listOf("PREFIX setsrc2 id=$id rows=$rows src=${src ?: "?"}")
    }

    /**
     * 取证：当前登录 wxid 是怎么读出来的（逐候选）+ 自己发的动态清单。
     *
     * 这是「排除自己」唯一的可观测证据。`isSelfAuthor` 取不到 self 时**恒不排除**，
     * 所以必须能区分三种状态：真的取到了 / 4 条候选全 null / 进程还没 attach。
     * 只靠「删除自己动态后没出现前缀」推不出结论 —— 那可能是排除生效，也可能是
     * 前缀写入本身失败（两条路径的出口都是「没加前缀」）。
     *
     * 输出不含 wxid 明文：命中只给长度，自己的动态靠**库内等值匹配**列出 snsId。
     */
    private fun self(): List<String> {
        val out = ArrayList<String>()
        out += SnsGuard.selfWxidProbe()
        val me = SnsGuard.selfWxidOrNull()
        if (me == null) {
            out += "SELF verdict=NO_SELF（排除自己不会生效）"
            return out
        }
        val d = db() ?: run {
            out += "SELF no db（先进一次朋友圈让 SnsGuard 拿到句柄）"
            return out
        }
        val rows = queryRows(
            d,
            "select snsId,sourceType,localFlag from $TABLE where userName=? " +
                "order by snsId desc limit 8",
            arrayOf(me)
        )
        if (rows.isEmpty()) {
            out += "SELF mine=0（自己没发过 / userName 列与 wxid 不等）"
        } else {
            out += "SELF mine=${rows.size} (limit 8)"
            for (r in rows) out += "SELF item $r"
        }
        out += "SELF hint=拿上面某个 snsId 跑 sub=simdrop，自作者应**不出现** hasPrefix=true"
        return out
    }

    // ---------------------------------------------------------------- 反射底层

    private fun db(): Any? = SnsGuard.snsDbHandle()
    private fun loader(): ClassLoader? = SnsGuard.hostLoaderHandle()

    private fun tloClass(cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(TLO_CLASS, false, cl) }
            .onFailure { LogX.w("PREFIX no TimeLineObject: ${it.javaClass.simpleName}") }
            .getOrNull()

    private fun descField(cl: ClassLoader): Field? =
        runCatching { tloClass(cl)?.getField(FIELD_DESC) }
            .onFailure { LogX.w("PREFIX no field $FIELD_DESC: ${it.javaClass.simpleName}") }
            .getOrNull()

    /** 反序列化。⚠️ 返回 `parseFrom` 的返回值（可能是基类实例也可能是自身），
     *  绝不拿 newInstance 出来的空 `tlo` 当结果。 */
    private fun parseTlo(cl: ClassLoader, blob: ByteArray): Any? {
        val cls = tloClass(cl) ?: return null
        val tlo = runCatching { cls.getDeclaredConstructor().newInstance() }.getOrNull()
            ?: return null
        return runCatching {
            cls.getMethod("parseFrom", ByteArray::class.java).invoke(tlo, blob)
        }.onFailure {
            LogX.w(
                "PREFIX parseFrom fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrNull()
    }

    private fun parseDesc(cl: ClassLoader, blob: ByteArray): String? {
        val tlo = parseTlo(cl, blob) ?: return null
        val f = descField(cl) ?: return null
        return readDesc(f, tlo)
    }

    private fun readDesc(f: Field, tlo: Any): String? =
        runCatching { f.get(tlo) as? String ?: "" }
            .onFailure {
                LogX.w(
                    "PREFIX read $FIELD_DESC fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull()

    private fun serialize(cl: ClassLoader, tlo: Any): ByteArray? {
        val cls = tloClass(cl) ?: return null
        return runCatching { cls.getMethod("toByteArray").invoke(tlo) as? ByteArray }
            .onFailure {
                LogX.w(
                    "PREFIX toByteArray fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                        "${(it.cause ?: it).message?.take(80)}"
                )
            }
            .getOrNull()
    }

    private fun rawQueryMethod(d: Any): Method? =
        runCatching {
            d.javaClass.methods.firstOrNull {
                it.name == "rawQuery" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Array<String>::class.java ||
                        it.parameterTypes[1] == Array<Any>::class.java)
            }
        }.getOrNull()

    private fun readBlob(d: Any, id: Long): ByteArray? {
        val m = rawQueryMethod(d)
            ?: run { LogX.w("PREFIX no rawQuery on ${d.javaClass.name}"); return null }
        val args: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            arrayOf(id.toString())
        } else {
            arrayOf<Any>(id.toString())
        }
        val cur = runCatching {
            m.invoke(d, "select $COL_CONTENT from $TABLE where snsId=?", args)
        }.onFailure {
            LogX.w(
                "PREFIX read fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrNull() ?: return null
        return try {
            if (XposedHelpers.callMethod(cur, "moveToFirst") as? Boolean == true) {
                XposedHelpers.callMethod(cur, "getBlob", 0) as? ByteArray
            } else {
                null
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
    }

    /**
     * 多列只读查询，每行拼成 `col=val,col=val`。只给探针用（[self]）。
     *
     * 值一律先试 `getString`（wcdb/AOSP 的 Cursor 会把 INTEGER 列转字符串），失败再退
     * `getLong`；两者都不行就写 `?`，绝不因为某列类型异常丢掉整行。
     */
    private fun queryRows(d: Any, sql: String, args: Array<String>): List<String> {
        val m = rawQueryMethod(d) ?: run {
            LogX.w("PREFIX no rawQuery on ${d.javaClass.name}")
            return emptyList()
        }
        val a: Any = if (m.parameterTypes[1] == Array<String>::class.java) {
            args
        } else {
            Array<Any>(args.size) { args[it] }
        }
        val cur = runCatching { m.invoke(d, sql, a) }.onFailure {
            LogX.w(
                "PREFIX queryRows fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        try {
            val n = runCatching {
                XposedHelpers.callMethod(cur, "getColumnCount") as? Int ?: 0
            }.getOrDefault(0)
            val names = (0 until n).map {
                runCatching {
                    XposedHelpers.callMethod(cur, "getColumnName", it) as? String ?: "c$it"
                }.getOrDefault("c$it")
            }
            while (runCatching {
                    XposedHelpers.callMethod(cur, "moveToNext") as? Boolean == true
                }.getOrDefault(false)
            ) {
                out += names.mapIndexed { i, name ->
                    val v = runCatching {
                        XposedHelpers.callMethod(cur, "getString", i) as? String
                    }.getOrNull()
                        ?: runCatching {
                            (XposedHelpers.callMethod(cur, "getLong", i) as? Long)?.toString()
                        }.getOrNull()
                        ?: "?"
                    "$name=$v"
                }.joinToString(",")
            }
        } finally {
            runCatching { XposedHelpers.callMethod(cur, "close") }
        }
        return out
    }

    private fun updateMethod(d: Any): Method? =
        runCatching {
            d.javaClass.methods.firstOrNull {
                it.name == "update" &&
                    it.parameterTypes.size == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == ContentValues::class.java &&
                    it.parameterTypes[2] == String::class.java &&
                    it.parameterTypes[3] == Array<String>::class.java
            }
        }.getOrNull()

    /** 只改 content 一列。返回改动行数；-1 = 反射/执行失败（不静默，已打日志） */
    private fun updateContent(d: Any, id: Long, blob: ByteArray): Int {
        val m = updateMethod(d)
            ?: run { LogX.w("PREFIX no update on ${d.javaClass.name}"); return -1 }
        return runCatching {
            val cv = ContentValues().apply { put(COL_CONTENT, blob) }
            val r = m.invoke(d, TABLE, cv, "snsId=?", arrayOf(id.toString()))
            r as? Int ?: -1
        }.onFailure {
            LogX.w(
                "PREFIX update fail: ${(it.cause ?: it).javaClass.simpleName}: " +
                    "${(it.cause ?: it).message?.take(80)}"
            )
        }.getOrDefault(-1)
    }

    // ---------------------------------------------------------------- 备份

    private fun backupDir(): File? {
        val files = HostContext.app()?.filesDir ?: return null
        val dir = File(files, BACKUP_DIR_NAME)
        if (!dir.exists()) runCatching { dir.mkdirs() }
        return dir
    }

    private fun backupFile(id: Long): File? = backupDir()?.let { File(it, "$id.bin") }

    /** 写备份；已存在则不覆盖（保留最早的原始版本）。返回说明串 */
    private fun writeBackup(id: Long, blob: ByteArray): String {
        val dir = backupDir() ?: return "dir-n/a"
        val f = File(dir, "$id.bin")
        return if (f.exists()) {
            "kept-existing(${f.length()}B)"
        } else {
            runCatching {
                f.writeBytes(blob)
                "ok(${f.length()}B)"
            }.onFailure {
                LogX.w("PREFIX backup fail: ${it.javaClass.simpleName}")
            }.getOrDefault("fail")
        }
    }

    /** 日志里把换行/回车折成可见字符，保持单行 */
    private fun show(s: String): String =
        s.take(30).replace('\n', ' ').replace('\r', ' ')
}
