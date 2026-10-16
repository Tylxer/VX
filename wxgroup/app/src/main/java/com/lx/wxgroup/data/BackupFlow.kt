package com.lx.wxgroup.data

import android.content.Context
import com.lx.wxgroup.core.HostContext
import com.lx.wxgroup.util.LogX
import java.io.File

/**
 * 备份 / 恢复的**流程层**（glue）：读库现状 → 生成计划 → 照计划落库 → 对账。
 *
 * ## 为什么需要它
 *
 * 恢复的规则（[BackupMerge]）和落库的动作（[BackupIO]）之间原来没有中间层：
 * UI / 广播入口直接调 `BackupIO.restore`，而 `BackupMerge.plan` 这个"计划的唯一书面定义"
 * 没有任何调用方 —— 两套并行实现必然漂移（单测已抓出两处：文件内同名组会重复新建、
 * 文件内重复成员的 skipped 计数不一致）。
 *
 * 现在执行层**只照 plan 做机械动作**，判据全部收敛到 [BackupMerge]（有单测）：
 * ```
 * preview（dry-run，只读） ──┐
 *                            ├─→ BackupMerge.plan ──→ 执行 ──→ reconcile 对账
 * restore（写库） ───────────┘
 * ```
 *
 * ## 恢复是危险操作
 *
 * - **合并模式**不动任何现有数据，最坏是"什么都没加"；
 * - **覆盖模式**先删光再重建，中途失败会留下"半删"状态 —— 所以恢复前自动导出
 *   **安全快照**（[safetyNet]），失败时用户的退路是重新导入这份文件。
 */
object BackupFlow {

    /** 一次恢复的完整结果：统计 + 计划 + 对账结论 + 安全快照路径 */
    data class Outcome(
        val stats: RestoreStats,
        val plan: BackupMerge.Plan,
        /** 落库实际次数与计划一致才为 true；false = 出了意外，日志里有明细 */
        val reconcileOk: Boolean,
        /** 恢复前自动导出的安全快照；null = 没做成（只警告，不阻止恢复） */
        val safetyNet: File?,
    ) {
        /** 给 toast 用的一行中文摘要 */
        fun summary(): String = buildString {
            append("新增 ${stats.groupsAdded} 组 / ${stats.membersAdded} 人")
            if (stats.membersSkipped > 0) append("，${stats.membersSkipped} 人因已在其他分组跳过")
            if (stats.settingsApplied > 0) append("，${stats.settingsApplied} 项配置已覆盖")
        }
    }

    // ---------------------------------------------------------------- 读现状

    /**
     * 从库里读出 [BackupMerge.CurrentState]。
     *
     * `knownKeys` 取 `Prefs.ALL` 而不是 kv 全表：文件里没登记的 key 一律丢弃，
     * 可能是更高版本的新配置，写进来只会污染当前库（与 `Kv.importSome` 同一把尺）。
     */
    fun currentState(db: GroupDb): BackupMerge.CurrentState {
        val groups = db.listGroups()
        return BackupMerge.CurrentState(
            groupNames = groups.map { it.name }.toSet(),
            membersOfGroup = groups.associate { it.name to db.memberWxids(it.id) },
            owned = db.allMemberWxids(),
            knownKeys = Prefs.ALL.map { it.key }.toSet(),
        )
    }

    // ---------------------------------------------------------------- dry-run

    /**
     * 导入前预览：**只读，一个字节都不写库**。
     *
     * UI 的确认弹窗和 adb 探针（`op=plan`）都用它 —— 用户点"合并"之前
     * 有权知道会发生什么（新建几组、加几人、跳过几人、覆盖几项配置）。
     */
    fun preview(db: GroupDb, snap: Snapshot, replace: Boolean): BackupMerge.Plan =
        BackupMerge.plan(snap, currentState(db), replace)

    /** 计划的中文摘要（UI 弹窗 / 日志用） */
    fun previewText(plan: BackupMerge.Plan): String = buildString {
        append(if (plan.replace) "覆盖恢复：" else "合并恢复：")
        append("新建 ${plan.groupsCreated} 组、加入 ${plan.membersAdded} 人")
        if (plan.membersSkipped > 0) append("、${plan.membersSkipped} 人因已在其他分组跳过")
        if (plan.membersAlready > 0) append("、${plan.membersAlready} 人已存在")
        if (plan.settings.isNotEmpty()) append("、${plan.settings.size} 项配置将覆盖")
        if (plan.settingsDropped > 0) append("（${plan.settingsDropped} 项未登记配置已忽略）")
        if (plan.groupsCreated == 0 && plan.membersAdded == 0 && plan.settings.isEmpty()) {
            append("（没有可恢复的内容）")
        }
    }

    // ---------------------------------------------------------------- 安全快照

    /**
     * 恢复前的自动备份（覆盖模式的退路）。
     *
     * 走 [BackupIO.export] 的目录兜底路径，失败只警告不阻止恢复 ——
     * 备份功能本身失败时，不该连带把恢复也堵死。
     */
    fun safetyNet(ctx: Context, db: GroupDb): File? =
        runCatching { BackupIO.export(ctx, db)?.file }
            .onFailure { LogX.w("BACKUP safety net failed: ${it.javaClass.simpleName}") }
            .getOrNull()

    // ---------------------------------------------------------------- 执行

    /**
     * 执行恢复：先生成计划（此时库还是原状），再照计划落库，最后对账。
     *
     * @param replace false = 合并（默认，不动现有数据）；true = 覆盖（先删光再重建，
     *                必须由 UI 二次确认后才传）。
     * @param withSafetyNet 恢复前是否自动导出安全快照。链路自检（selftest）传 false：
     *                       它自己刚导出过一份，不需要再来一份。
     */
    fun restore(
        ctx: Context?,
        db: GroupDb,
        snap: Snapshot,
        replace: Boolean,
        withSafetyNet: Boolean = true,
    ): Outcome {
        // 计划必须在动库之前生成：replace 的现状假设就是"删光之后等于空库"
        val plan = BackupMerge.plan(snap, currentState(db), replace)

        val safetyFile = if (withSafetyNet) {
            val c = ctx ?: runCatching { HostContext.app() }.getOrNull()
            if (c != null) safetyNet(c, db) else null
        } else null

        // 覆盖模式：先删光（plan 已按空库生成，组名不会撞 UNIQUE）
        if (replace) {
            for (g in db.listGroups()) db.deleteGroup(g.id)
        }

        var created = 0
        var added = 0
        val byName = db.listGroups().associateBy { it.name }.toMutableMap()
        for (a in plan.groups) {
            var gid = byName[a.name]?.id ?: -1L
            if (gid <= 0) {
                // 正常走不到：plan 说合并的组必然在 byName 里。真发生了（并发写库）
                // 就按新建兜底，对账会把这次偏差数出来。
                gid = db.createGroup(a.name, a.color)
                if (gid <= 0) {
                    LogX.w("BACKUP create group failed: ${a.name}")
                    continue
                }
                created++
                // 置顶只在新建组时应用：合并语义下不该推翻用户现有的置顶状态
                if (a.applyPinned) db.setGroupPinned(gid, true)
                byName[a.name] = Group(gid, a.name, a.color, 0, System.currentTimeMillis())
            }
            if (a.add.isNotEmpty()) {
                // nickname 承载显示名：Friend.displayName 的取值顺序是 备注>昵称>别名>wxid
                added += db.addMembers(
                    gid,
                    a.add.map { Friend(wxid = it.wxid, nickname = it.displayName, type = it.wxidType) },
                )
            }
        }

        val settingsApplied = Kv.importSome(plan.settings)
        val ok = BackupMerge.reconcile(plan, created, added)
        val stats = RestoreStats(
            groupsAdded = created,
            membersAdded = added,
            groupsInFile = snap.groupCount,
            settingsApplied = settingsApplied,
            membersSkipped = plan.membersSkipped,
        )
        LogX.i(
            "BACKUP restore replace=$replace plan=${plan.summary()} " +
                "actual created=$created added=$added reconcile=$ok" +
                if (safetyFile != null) " safety=${safetyFile.absolutePath}" else ""
        )
        if (!ok) {
            LogX.w(
                "BACKUP reconcile mismatch: expected created=${plan.groupsCreated} " +
                    "added=${plan.membersAdded}, actual created=$created added=$added"
            )
        }
        return Outcome(stats, plan, ok, safetyFile)
    }
}
