# R18 · N7 评论域代码物理删除（2026-10-24）

> 用户拍板：**删除**。此前状态是「2026-10-20 功能停止，代码冻结保留」。
> 执行者：主会话（Flash 级：纯删除 + 清引用，无判据设计 ⇒ 不触 §四②）。

---

## 1. 删除范围

| 文件 | 大小 | 说明 |
|---|---|---|
| `feature/SnsCommentGuard.kt` | 59480B | 评论防删除（拦 delete SnsComment + attrBuf 差量还原） |
| `feature/SnsCommentTag.kt` | 87360B | 评论「[已删除]」标签渲染 |
| `feature/SnsCommentLogic.kt` | 50394B | 纯判据层（`diffDeleted` / `planTags` / `renderKey` 等） |
| `feature/SnsCommentLabelLogic.kt` | 9929B | 标签归属判定（`reconcile` / `shouldRemove`） |
| `test/feature/SnsCommentLogicTest.kt` | 51149B | **92 个 `@Test`** |
| `test/feature/SnsCommentLabelLogicTest.kt` | 14256B | **29 个 `@Test`** |
| **合计** | **272568B（约 266KB）** | **121 个测试用例** |

备份位置：`docs/_r18_snscomment_backup/`（6 个原始文件，284K）。

---

## 2. 清理的三个引用点

| 位置 | 原内容 | 处理 |
|---|---|---|
| `HookEntry.kt` import | `import com.lx.wxgroup.feature.SnsCommentGuard` / `SnsCommentTag` | **删除**（指向已删类，留着必编译失败） |
| `HookEntry.kt:269-284` | 两段被注释掉的 `install()` 调用 + 「实现完整保留……（冻结，不删）」的说明 | **重写**为「已物理删除」+ 留档技术原因（attrBuf 载体 / UI proto 无 commentSvrID / 渲染键模糊匹配 / rows=0·del=0 未定案） |
| `EntryReceiver.kt:388-403` | `"snsc"` 分支调 `SnsCommentGuard.probe` + `SnsCommentTag.probe` | **改成显式提示分支**（见下） |

### `"snsc"` 分支为什么不是直接删掉

```kotlin
"snsc" -> LogX.w(
    "OP snsc 已移除：朋友圈评论防删除 / [已删除]标签功能于 2026-10-20 停止，" +
        "代码于 2026-10-24 物理删除。若仍需取证评论表，用 op=sns --es sub cmts"
)
```

**理由**：整段删掉后 `op=snsc` 会静默落空，而老脚本与老文档里还写着这个 op
⇒ 排查的人会误判成「模块没生效」而不是「功能已被删除」。
留一句明确日志把排查方向掰正，成本一行。

---

## 3. ⛔ 边界：哪些「SnsComment」**不能**删（已逐条核过）

这是本次最容易误删的地方 —— 有 5 处 `SnsComment` 字样，但它们指的是**数据库表名**，不是类名：

| 位置 | 内容 | 为什么保留 |
|---|---|---|
| `SnsGuard.kt:30` | KDoc 里的 `execSQL "delete from SnsComment ..."` 示例 | 描述**微信真实执行**的 SQL，属取证结论 |
| `SnsGuard.kt:506` | `select * from SnsComment order by rowid desc limit $limit` | `SnsGuard.comments()` 的**评论表取证探针**，被 `op=sns --es sub cmts` 调用 |
| `SnsSql.kt:9` | KDoc 里的 `execSQL("delete from SnsComment ...")` | 同 30 行，SQL 解析器的输入样例 |
| `SnsSqlTest.kt:70` | `assertNull(SnsSql.matchDeleteCall("SnsComment", ...))` | 断言「**表名不是 SnsInfo**」⇒ 动态防撤回**不该**拦它 |
| `SnsSqlTest.kt:99` | 同上，删评论的 SQL 字面量 | 同 70 行 |

**判据**：`SnsGuard` / `SnsSql` 是**朋友圈动态防撤回**与**SQL 解析**的主体，
评论只是它们处理的一种表。删评论域 ≠ 删对评论表的认知。
`SnsGuard.comments()` 是独立探针，**不在删除范围**（本轮不动，是否另外收敛另行判断）。

---

## 4. 验收

| 判据 | 结果 |
|---|---|
| 四个类名在 `wxgroup/app/src` 下零活代码引用 | ✅ 仅剩 3 处**注释**（记录删除原因）+ 0 处活代码 |
| 数据库表名 `SnsComment` 保留 | ✅ 5 处全在（`SnsGuard` ×2 / `SnsSql` ×1 / `SnsSqlTest` ×2） |
| 构建产物清理 | ✅ `:app:clean` EXIT=0（1m8s），`build/` 下 `*SnsComment*` 零残留（否则增量 dex 会带上已删类） |
| `testDebugUnitTest` 用例数 | 待全量构建验证，**预期 606 → 485**（606-121） |
| `assembleDebug` | 待全量构建验证 |

> ⚠️ 构建**延后**执行：F4b lane 正在并行改 `MomentsExport.kt`，两条 lane 同跑 gradle
> 会撞锁（R16 实测 `FileAnalysisException: size in bytes: -1`）。等 F4b 交付后主会话统一跑一次全量。

---

## 5. 为什么要备份（而不是直接删干净）

用户拍板「删」，但保留一份原始文件在 `docs/` 下（**不在 `src/` 下，不参与编译**）有三个理由：

1. **121 个单测是纯粹的领域知识沉淀**（`diffDeleted` 三态、`planTags` 幂等、
   `renderKey` = `createTime:talker:type` 的规范化规则）。这些结论本身是对的，
   只是功能不做了。删掉等于把「评论 proto 不带 commentSvrID 该怎么办」的解法一起扔掉。
2. `docs/取证结论.md` ⑩ 段大量引用这些实现里的判据名，直接删会让文档指向空处。
3. 若将来重启该需求，从 `docs/_r18_snscomment_backup/` 拿回比重新取证便宜得多。

**成本**：284KB 磁盘 + 零构建开销（不在 `src/` 下）。**收益**：可逆。
