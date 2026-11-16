package com.lx.wxgroup.data

/**
 * 数据层伪会话（`wxgrp_` 行）的「差量计划 + SQL 生成」，纯 Kotlin、零 Android 依赖，可 JVM 单测。
 *
 * 定案见 `docs/_r54_gconv_design.md` §10：组名写 `rcontact.nickname` 就能让微信原生渲染，
 * 所以本层只回答「库里该有哪些行」和「怎么拼出安全的 SQL」，反射 execSQL / hook 都不在这里。
 *
 * 命名空间为什么用 `wxgrp_` 而不是 `wx__c_`：后者是微信自有内部命名空间（微X 借用了它），
 * 独立前缀才能保证按前缀隔离的 SQL 一次都不碰宿主行。
 */
object GroupConvPlan {

    const val PREFIX = "wxgrp_"

    /** 2^62：微信把 flag 的这一位当作「置顶」，置顶区内部再按 conversationTime 降序。 */
    const val PIN_BASE = 4611686018427387904L

    /**
     * 为什么用 `toULong` + 定长 16 位小写 hex：分组 id 是 Long，历史脏数据可能为负（甚至 -1），
     * 只有按无符号补零成 16 位才能双向无损映射；`toLong(16)` 直接解负数会被截断 / 溢出。
     */
    fun usernameOf(groupId: Long): String =
        PREFIX + groupId.toULong().toString(16).padStart(16, '0')

    /**
     * 为什么返回可空且用 `toULongOrNull(16)`：非法 hex、空、超过 2^64 的溢出值都反不出 id。
     * 点击 / 长按拿到 null 必须保守放行，绝不按 position 猜一个组出来（§4.5）。
     */
    fun groupIdOf(username: String?): Long? {
        if (username == null || !username.startsWith(PREFIX)) return null
        val hex = username.removePrefix(PREFIX)
        if (hex.isEmpty()) return null
        return hex.toULongOrNull(16)?.toLong()
    }

    /**
     * 与 [ownWhere] 口径刻意不同：ownWhere 是 SQL 层的宽口径（覆盖所有 `wxgrp_` 行，
     * 连 trial 探测行也要能 clean / repair）；这里要求「能反解出 id」才算一条可处理的本方分组行，
     * 这样视图层放行后紧接着用 [groupIdOf] 反查 id 必不为 null。
     */
    fun isOurs(username: String?): Boolean = groupIdOf(username) != null

    /**
     * 为什么 flag 必须能被重修而不是写一次了事：微信会清掉 2^62 这一位（真机 12 条掉出 3 条，
     * §5.1），掉出置顶区后 [plan] 靠 flag/time 判据发现漂移并给出 Update 走补偿写。
     */
    fun flagOf(conversationTimeMs: Long): Long = PIN_BASE + conversationTimeMs

    data class Desired(val groupId: Long, val name: String, val timeMs: Long, val sortOrder: Int)
    data class Existing(val username: String, val flag: Long, val conversationTime: Long, val nickname: String)

    sealed class Op {
        data class Insert(val d: Desired) : Op()
        data class Update(val d: Desired, val reason: String) : Op()
        data class Delete(val username: String) : Op()
    }

    /**
     * 输出确定性（Delete→Update→Insert，组内按 username 字典序）是为了让 Store 一次事务按序执行、
     * 也让测试能逐字节比对，不受输入列表顺序影响。
     *
     * flag/time 优先于 rename：flag 漂移是宿主清了置顶位，比组名变化更紧急；且一条 desired
     * 最多产一条 Update。Store 处理 Update 时 [repairSql] 与 [renameSql] 都跑一遍即可同时修
     * flag/time 和 nickname，不必为「两种原因」产两条 Op。
     */
    fun plan(desired: List<Desired>, existing: List<Existing>): List<Op> {
        val want = desired.associateBy { usernameOf(it.groupId) }
        val have = existing.associateBy { it.username }

        val deletes: List<Op> = (have.keys - want.keys).sorted().map { Op.Delete(it) }
        val updates: List<Op> = want.keys.intersect(have.keys).sorted().mapNotNull { u ->
            val d = want.getValue(u)
            val e = have.getValue(u)
            when {
                e.flag != flagOf(d.timeMs) || e.conversationTime != d.timeMs -> Op.Update(d, "flag/time")
                e.nickname != d.name -> Op.Update(d, "rename")
                else -> null
            }
        }
        val inserts: List<Op> = (want.keys - have.keys).sorted().map { Op.Insert(want.getValue(it)) }
        return deletes + updates + inserts
    }

    /**
     * 为什么是 6、为什么不用 LIKE：6 = [PREFIX] 长度；`_` 在 LIKE 里是单字符通配，
     * `like 'wxgrp_%'` 会命中 `wxgrpX...` 之类的行，substr 精确比对才不误伤。
     */
    fun ownWhere(): String = "substr(username,1,6)='${PREFIX}'"

    /** 为什么把 `'` 变 `''`：SQL 字面量里单引号必须成对转义，否则组名里的引号能闭合串造成注入。 */
    fun quote(s: String): String = "'" + s.replace("'", "''") + "'"

    /**
     * 为什么只写 7 列：`rconversation` 实测 30 列，新增列全有默认值，7 列 minimal insert
     * 真机成功（§10.3），别硬编码 20 列。`content`/`digest` 故意不写（留空串）才有竞品
     * 「只有组名 + 时间、没有灰色摘要」的视觉。普通 `insert` 而非 `or replace` —— Store 会先删后插。
     */
    fun insertConvSql(d: Desired): String {
        val u = quote(usernameOf(d.groupId))
        return "insert into rconversation (username,flag,conversationTime,msgType,status,chatmode,isSend) " +
            "values ($u,${flagOf(d.timeMs)},${d.timeMs},1,3,1,1)"
    }

    /**
     * 为什么 type=0 / showHead=87 + nickname：§10 实测不写 nickname 时 UI 回落成原始 username；
     * type=0 已足以排除通讯录泄露；`rcontact` 同名行让微信渲染拿得到联系人对象（§1.3）。
     */
    fun insertContactSql(d: Desired): String =
        "insert into rcontact (username,type,showHead,nickname) " +
            "values (${quote(usernameOf(d.groupId))},0,87,${quote(d.name)})"

    /** flag 与 conversationTime 一起重修：只补 bit 不回填时间会把行顶到 1970 年（§5.2）。 */
    fun repairSql(d: Desired): String =
        "update rconversation set flag=${flagOf(d.timeMs)},conversationTime=${d.timeMs} " +
            "where ${ownWhere()} and username=${quote(usernameOf(d.groupId))}"

    fun renameSql(d: Desired): String =
        "update rcontact set nickname=${quote(d.name)} " +
            "where ${ownWhere()} and username=${quote(usernameOf(d.groupId))}"

    /**
     * 删语句为什么带 [ownWhere] + 精确 `username=` 双保险：username 一旦拼错，单靠 `username=`
     * 可能删到宿主真实会话；ownWhere 把影响面锁死在 `wxgrp_` 命名空间内（真机 clean 用
     * 「非本方行数前后不变」自证）。
     */
    fun deleteConvSql(username: String): String =
        "delete from rconversation where ${ownWhere()} and username=${quote(username)}"

    fun deleteContactSql(username: String): String =
        "delete from rcontact where ${ownWhere()} and username=${quote(username)}"
}
