# R21d F4 回退 + F1/F2 治本落码产物（Pro · r21d-f1f2-fix）

## 目标
1. 回退 F4（talker 闸门收紧，B2 读证裁决已作废），逐条对齐主会话确认的精确边界。
2. 保留 F3（关开关清残留）+ 增强 E 观测段 + bindHook 包装层 + pos/type/content/GroupDb/AntiRevoke，逐字不动。
3. 落码 F1：摘角标路径对齐 `apply()` 的 post 重试（解决 `ensure():664` 未 attach 静默失败）。
4. 落码 F2（条件）：dex 定位 itemView detach/recycled hook 点，可靠则按 MARK 清理；证据不足则写【UNKNOWN】并只交付 F1+F3。

## 输入（按顺序已读）
- `docs/_r21d_samegroup_probe_verdict.md`（B2 读证裁决：22:59 不是撞号、是 View 复用残留、F4 作废）
- `docs/_r21d_reuse_rootcause_verdict.md`（§修法建议 F1/F2/F3、§验证点 3 机制链）
- `docs/派发审计台账.md` 末尾「23:4x 判据 A 订正」+「2026-11-28 00:0x 派发回退 F4 + F1/F2 治本」
- 源码 `HintBadge.kt`(1314) / `HintBadgeLogic.kt`(203) / `HintBadgeLogicTest.kt`(385)
- git history：F4 之前的原文（`git diff` 可见 HEAD 原版，F3/F4 均为未提交工作区改动）

## 红线（分档）
- **进入**：仅可改 `HintBadge.kt` / `HintBadgeLogic.kt` / `HintBadgeLogicTest.kt`，唯一新建产物 = 本文。
- **保留不动**：F3 全部已落码部分、增强 E 段（`:1133+`）、bindHook 包装层（`:297-318`）、pos/type/content/GroupDb/AntiRevoke。
- **返回**：骨架 → 每子步 `[DONE]` → `[DONE ALL]`；产物含 ①F4 回退 diff 证据 ②F3/增强 E 保留逐字确认 ③F1 改动清单+行号 ④F2 hook 点 dex 裁决 ⑤三闸 ⑥变异验证 ⑦无误伤对照 ⑧诚实边界。
- **复位（绝对禁区）**：⛔ 禁碰 adb/装机/设备/开关/发图/git/装包；禁写模型路由字样。
- **分档纪律**：【源码实证】/【dex 实证】/【推断·高|中|低】/【UNKNOWN】，【推断】不得写成【已证】。

---

## ① F4 回退逐条确认（diff 证据）

**回退前状态**：F3/F4 均为未提交工作区改动（HEAD = 纯 F4-before 版本）。`git diff` 显示 F4 三处改动见 §输入。

| 边界条目 | 要求 | 实做 | 证据 |
|---|---|---|---|
| `HintBadgeLogic.kt` 删 `talkerOk` | :103-107 函数 + :91-102 F4 注释块 | 删函数 + 注释块；并把共用分节头 `// ---- R21d F4/F3 修法判据` 改为 `// ---- R21d F3 修法判据`（纯注释，去掉失效的 F4 字样） | 当前 `grep -n talkerOk HintBadgeLogic.kt` = 无；`git diff` 加行里 `talkerOk` 计数 = **0** |
| `HintBadgeLogic.kt` 保留 F3 | `cleanupTargetsOnDisable:119` 逐字不动 | 未动 | `grep` 命中当前 :101（行号因 F4 删除前移 18 行），函数体 `= badgedViews` 原样 |
| `HintBadge.kt:388-410` 回退旧闸门 | 两侧非空才核对、空放行；旧 lastMiss/限流日志 | 从 `git show HEAD` 逐字取回，**未自己发明** | `git show HEAD\|sed -n '/两侧任一为空就跳过校验/,/^            }$/p'` 与当前 `sed` 同标记抽取 `diff` = **IDENTICAL**（见下） |
| `HintBadgeLogicTest.kt` 删 3 条 `f4TalkerOk*` | :334/:344/:353 + F4 分节头 | 全删（含分节头） | 当前 `grep -n f4TalkerOk` = 无；`git diff` 加行里 `f4TalkerOk` 计数 = **0** |
| 3 条 `f3*` 单测保留 | :365/:378 | 未动 | 当前 :334/:347，函数体原样 |

**逐字比对命令与结果**（talker 闸门块，从标记 `⇒ 用 h9.getTalker()` 到块末 `}`）：
```
git show HEAD:.../HintBadge.kt | sed -n '/两侧任一为空就跳过校验/,/^            }$/p' > /tmp/head_gate.txt
sed -n '/两侧任一为空就跳过校验/,/^            }$/p' .../HintBadge.kt > /tmp/cur_gate.txt
diff /tmp/head_gate.txt /tmp/cur_gate.txt   # => IDENTICAL（无输出）
```
回退后闸门判定式为 `if (!curTalker.isNullOrBlank() && rescued.talker.isNotBlank() && curTalker != rescued.talker)`，`lastMiss="talker(${curTalker.take(10)}!=${rescued.talker.take(10)})"`，日志 `HINTBADGE talkerMismatch id=... cur=... led=... orig=...` —— 均为 HEAD 原文。
> 注：F1 随后只改该块末尾的 `runCatching { ensure(itemView,false,"") }` → helper 调用（见 §③），不改闸门判定式/计数/日志，属 F1 统一改动而非 F4 内容。

`[DONE] ① F4 回退逐条确认`

## ③ F1 改动清单 + 行号（工作区当前行号）

**新增 helper**（`HintBadge.kt:752`（KDoc :738-751），紧邻 `apply():729-735`）：
```kotlin
private fun unbadge(view: View, text: String = "", stillWanted: () -> Boolean = { true }) {
    if (!ensure(view, false, text)) {
        view.post { runCatching { if (stillWanted()) ensure(view, false, text) } }
    }
}
```
- 完全对称于 `apply()` 的 `if (!ensure(host,true,text)) host.post { ... ensure(host,true,text) }`。
- 【源码实证】`View.post()` 在 detach 态把 Runnable 排入 `HandlerActionQueue`，**下次 attach 才执行** ⇒ 正好覆盖「bind 当帧未 attach、稍后 attach」窗口；detach 后永不再 attach 的情形留给 F2（`stripBadges` 直接改 child 列表，不看 attach）。
- `stillWanted` 默认 `{ true }`；摘路径只调 `ensure(view,false,text)`，**绝不贴回角标**。

**统一后的调用点（全部 8 处）**：

| # | 当前行 | 原调用 | F1 后 | post 复查 guard |
|---|---|---|---|---|
| 1 | `:389` | `ensure(it,false,"")`（开关关早退） | `unbadge(it){ !on() }` | 开关又被打开则放弃旧摘，交新一轮 bind 重判 |
| 2 | `:405` | h9==null 闸门 | `unbadge(itemView){ !bound.containsKey(itemView) }` | 新一轮 bind 已重新登记则放弃 |
| 3 | `:413` | msgId<=0 闸门 | 同上 | 同上 |
| 4 | `:424` | rescued==null 闸门 | 同上 | 同上 |
| 5 | `:457` | talker 闸门（F4 回退后旧口径） | 同上 | 同上 |
| 6 | `:465` | type 闸门 | 同上 | 同上 |
| 7 | `:494` | content 对账闸门 | 同上 | 同上 |
| 8 | `:698` | sweep `ensure(v, show, text)` 的 `show=false` 分支（`:693-705`） | `unbadge(v,text){...}` | `if(!on()) true else (rescued()[id]==null && id!in forceSet)` —— 开关/台账恢复则放弃，交下一轮 sweep 重判 |

> sweep 的 detach 分支（`:630-633`）**只 `bound.remove`、不摘 View**，保持原样（该情形由 F2/F3 接管）；`fake()` 的 `ensure(v, vId==id||hit!=null, ...)`（验证入口，非纯摘路径）保持原样。

**与 F3 关开关清理并发的评估（不打架论证）**：
- F3 `refresh():281-289` 关开关时 `clearAllBadges()` → `stripBadges()` 递归 `removeViewAt`，**不检查 `isAttachedToWindow`、幂等**，对 detach 态也生效；与 F1 post 重试若同时发生，重复摘是 no-op。
- F1 摘路径 post 闭包调 `ensure(view,false,text)`（只摘不贴），不存在「开关关了还贴回去」；贴路径 `apply()` 的 post 闭包**原有** `if (on())` 复查（当前 `:733`，历史 `:664`）逐字保留 —— 这正是任务举的例子，已满足。
- F1 全部 post 闭包都带 `stillWanted` 状态复查：开关重开 / 新一轮 bind 重新登记 / 台账恢复时放弃旧摘，避免与 F3 清理或新绑定打架。
- 唯一残留竞态见 §⑧ 诚实边界（属 `apply()` 贴路径既有问题，本次不改，不属 F1 范围）。

`[DONE] ③ F1 改动清单`

## ④ F2 hook 点 dex 裁决：**找到（FOUND）**

### dex 实证链（全部只读解析 `_cache/dex/`，未臆造）
1. **目标类**：聊天 adapter 基类 `Lcom/tencent/mm/pluginsdk/ui/tools/q3;`（`ADAPTER_CLASS`，现有 bind hook 已证实活钩）。
   - `classes4.dex` 的 `class_data(q3)` 显示它**自己声明** `onViewDetachedFromWindow`（`code_off=4766436`）与 `onViewRecycled`（`code_off=4766488`），`flags=1`（public、非 bridge、非 native、有方法体），不是 `method_ids` 里的纯引用。
   - 反汇编（`tools/_r27_disasm.py`）：
     ```
     q3#onViewDetachedFromWindow:
       const-string v0,'holder'; Intrinsics.g(...)  // 1 个 holder 参数
       invoke-super w1->onViewDetachedFromWindow
       iget-object v16, q3->f : w1; invoke-virtual w1->onViewDetachedFromWindow
     q3#onViewRecycled: 同构（invoke-super w1->onViewRecycled + 委派字段 f）
     ```
2. **活钩性**：`q3 super = androidx.recyclerview.widget.w1`（重打包 RecyclerView.Adapter）；全 dex 扫描 `q3` 的子类**只有 `q0` 一个**，且 `q0` 只覆写 `getItem`，**不覆写**这两个回调 ⇒ 在 `q3` 上挂钩对聊天列表必然触发。
3. **不挂 `w1` 的理由**：`w1#onViewDetachedFromWindow` / `w1#onViewRecycled` 在 `classes12.dex` 都是空 `return-void`，且全 App 无数十处使用 ⇒ 挂 `w1` 会命中无关列表、无意义；挂 `q3` 天然限定聊天列表。
4. **holder → itemView**：回调拿到的 holder 与 `onBind` 是同一个 ViewHolder 类型 ⇒ 直接复用既有 `itemViewOf(holder)`（含排除 RecyclerView / 面积兜底）。
5. **列表形态**：`docs/_r16_f4_recycler_fix.md` + 增强 E `findChatList` 注释均实证 8.0.48 聊天列表是 `ChattingRecyclerView`（RecyclerView 形态）。ListView 形态边界见 §⑧。

### 落码（F2）
- `install()`（当前 `:193-198`，hook 在 `:197`）：`hookAdapterClass(it)` 后追加 `hookDetachCallbacks(it)`，**未改 bindHook 包装层**（现 `:328-349`）。
- 新增 `hookDetachCallbacks(c)`（当前 `:247-269`）：在 `q3` 上按名字挂 `onViewDetachedFromWindow` + `onViewRecycled`，过滤 static/bridge/参数个数，用 `hookedMethods` 的 `F2#` 前缀键去重，不重复挂。
- 新增 `detachHook`（当前 `:361-379`）：`afterHookedMethod` 里 `itemViewOf(holder)` → 先看 F3 独立登记 `badgedViews.containsKey(iv)`（没挂过角标直接跳过）→ 复用 F3 `stripBadges(iv)` 按 MARK 递归摘 → `bound.remove(iv)`；全程 `runCatching`，观测/清理失败绝不影响宿主。
- **幂等**：`badgedViews` 先筛 + `stripBadges` 递归摘（`removeViewAt` 后第二次调用自然摘不到），双重幂等；`bound.remove` 与 sweep 的 detach 分支同口径。
- **复用既有机制**：未改 F3 的 `markBadged/stripBadges/clearAllBadges` 一个字，F2 只是新调用方。

`[DONE] ④ F2 hook 点 dex 裁决 + 落码`

## ② F3 / 增强 E 保留逐字确认

| 保留项 | 要求 | 实证 |
|---|---|---|
| F3 `badgedViews:154` | 逐字不动 | `git diff` 该段为**工作区既有的 F3 新增**（非本代理改动）；本代理未触碰 |
| F3 `markBadged:770` / `stripBadges:780` / `clearAllBadges:801` | 逐字不动 | 本代理仅**新增调用方**（F1 helper / F2 detachHook / sweep），未改三者方法体 |
| F3 `refresh():281-287` 的 `clearAllBadges()` 接入 | 逐字不动 | diff 中该段是既有 F3 改动，本代理未动 |
| F3 `HintBadgeLogic.cleanupTargetsOnDisable:119` | 逐字不动 | 函数体 `= badgedViews` 原样（变异验证临时改过并 sha 还原） |
| 2 条 `f3*` 单测（原 :365/:378，现 :334/:347） | 逐字不动 | 未改 |
| 增强 E `:1139+`（observeBind / ring / rvRef / adapterRef / dump / badgeOnView / positionOf / obsLine） | 逐字不动 | diff 末尾整段是既有增强 E 新增，本代理未触碰 |
| bindHook 包装层（现 :328-349） | 逐字不动 | 未改；只在其**外部**新增 F2 `detachHook` 对象 |
| pos 换算 / type 闸门 / content 对账 / GroupDb / AntiRevoke | 逐字不动 | 均未出现在本次改动清单；content 对账仅 `:491` 摘调用换 unbadge（F1 统一改动） |

`[DONE] ② F3/增强 E 保留逐字确认`

## ⑤ 三闸结果（wxgroup/ · JDK21 · gradle-8.9 --offline）

| 闸 | 命令 | 结果 |
|---|---|---|
| 定向单测 | `testDebugUnitTest --tests com.lx.wxgroup.data.HintBadgeLogicTest` | **EXIT=0 / BUILD SUCCESSFUL in 20s** |
| 全量单测 | `python tools/gradle_run.py testDebugUnitTest` | **EXIT=0 / BUILD SUCCESSFUL in 13s** |
| 编译 | `python tools/gradle_run.py assembleDebug` | **EXIT=0 / BUILD SUCCESSFUL in 16s** |

> 未跑 `build`（按要求）；未装包、未碰设备/开关。

`[DONE] ⑤ 三闸`

## ⑥ 变异验证

**前置：确认变异有语义**（否则「变红」可能是噪音）：
- F3 纯判据可 JVM 测；F1/F2 依赖宿主 View（`isAttachedToWindow` / `View.post` / `removeViewAt`），本工程仅 JUnit4（无 Robolectric），**不能 JVM behavioral 测** —— 见 §⑧ 真机反向步骤。
- 语义链：`ensure():689` 对 `!isAttachedToWindow` 静默 return false；F1 post 重试下次 attach 执行；F2 `stripBadges` 不检查 attach、detach 态也生效。

**M0（JVM 真变异，证明 harness 有判别力）**：把 `cleanupTargetsOnDisable` 的 `= badgedViews` 改成旧缺陷 `= boundViews` ⇒
```
EXIT=1
RED: HintBadgeLogicTest > f3CleanupTargetsIgnoreEmptyBound FAILED
RED: HintBadgeLogicTest > f3CleanupTargetsKeepAllBadgedWhenBoundHasOtherViews FAILED
33 tests completed, 2 failed
源码已还原 sha256=76272dcc8e4d
```
正好 2 条 `f3*` 变红，其余 31 条不变 ⇒ 变异有语义、测试能抓住。

**M1/M2（F1/F2 机制模型 + 源码模式变异，内存态，不落盘）**：
- 语义前置：F1 模式 `view.post { runCatching { if (stillWanted()) ensure(view, false, text) } }` 命中 **1**；F2 模式 `val n = stripBadges(iv)` 命中 **1**（均须=1，否则无从变异）。
- 机制模型（参数取自源码实证）：`attached_at_call=false` 时 `ensure(false)` 失败；有 F1 且 `attaches_later=true` ⇒ 补摘成功；有 F2 ⇒ 不等 attach 直接摘；否则残留。
- 结果：
  - F1 场景「未 attach → 稍后 attach」：修复版残留=False / **删掉 post 重试后残留=True（变红）**。
  - F2 场景「detach 后永不再 attach（复用池）」：修复版残留=False / **删掉 `stripBadges` 调用后残留=True（变红）**。
- 源码反向变异（内存替换）：M1 去 post 重试后模式命中=0；M2 去 detach strip 后模式命中=0；磁盘源码 sha256 未变（`d3f4a145e54f`）。
- **诚实标注**：M1/M2 是「源码模式 + 机制模型」变异，不是真机行为断言；真机反向对照见 §⑧。

`[DONE] ⑥ 变异验证`

## ⑦ 无误伤对照

1. **正常被救消息角标仍在**：F4 回退后闸门恢复旧口径「两侧非空才核对、空放行」——正常被救消息（两侧非空且相等）照旧放行贴角标；`bound[itemView]=msgId` → `apply()` 成功路径**未改**，`apply()` 及其 `if (on())` post 复查逐字保留；F1 的 `unbadge` 只在闸门失败/开关关/sweep `show=false` 时调用，不进入成功路径。
2. **F3 关开关仍能清**：`refresh()` 的 `clearAllBadges()` 接入、`cleanupTargetsOnDisable`、2 条 `f3*` 单测全部未动；F2 只是新增 `stripBadges` 调用方，不削弱 F3（F3 的清集仍来自独立登记 `badgedViews`，不依赖 bound）。
3. **F4 回退后 `talkerMismatch` 旧口径恢复**：判定式、`lastMiss="talker(cur!=led)"`、日志 `HINTBADGE talkerMismatch id=... cur=... led=... orig=...` 与 HEAD 逐字相同（§① diff IDENTICAL）；`stat()` 的 `talkerMismatch` 计数保留；F4 新增的 `why` 分支与 `talkerOk` 判据已消失。
4. **F2 不误伤非聊天列表**：hook 挂 `q3`（聊天 adapter 基类），全 dex `q3` 仅 `q0` 一个子类且不覆写回调；`badgedViews.containsKey(iv)` 先筛，没挂过角标的 view 直接跳过，不动微信原生 View；`bound.remove` 与 sweep detach 分支同口径。

`[DONE] ⑦ 无误伤对照`

## ⑧ 诚实边界（UNKNOWN / 未证 / 残留）

- 【UNKNOWN】F1/F2 **真机行为未验**：本轮禁碰 adb/装机/设备/开关，所有验证都是 JVM / 静态 dex / 源码模型；行为结论须装机后交叉复核 lane 实测。
- 【推断·高】F2 hook 点的 dex 证据充分（`q3` 自己声明 + `q0` 不覆写 + `w1` 空实现），但「真机上每次滚屏 q3 的 detach 回调必然触发」属推断，须真机日志 `HINTBADGE F2 onViewDetachedFromWindow stripped=n` 确认。
- 【推断·高】22:59 根因 = View 复用残留，引自 B2/根因裁决；本轮未现场逐帧验证。
- **ListView 形态边界**：8.0.48 聊天列表是 RecyclerView（`ChattingRecyclerView`，`docs/_r16_f4_recycler_fix.md` 实证）；若某版本退 ListView（`getPositionForView` 形态），F2 的 `q3` hook 不覆盖，候选方向 = 另挂 `AdapterView` / itemView 的 `onDetachedFromWindow`。
- **残留竞态（既有、未改）**：`apply()` 贴路径 post 重试只复查 `on()`，不复查 `bound`；极端场景（贴重试排队期间同一 view 被 rebind 到非被救消息、且 gate 失败时 `ensure(false)` 当帧成功）可能把旧角标贴回，须等下一轮 sweep / 下一次 bind 摘掉。属 `apply()` 既有问题，不在 F1「摘路径对称」范围，未擅自扩大改动。
- F1 的 post 重试对「detach 后永不再 attach」无效，已由 F2 覆盖（两者互补）。
- **真机反向对照步骤（本代理不执行，交主会话/装机交叉复核 lane）**：
  - F1：进有被救消息的会话 → 角贴出 → 强制把同一 itemView rebind 到非被救消息（滚出滚回）→ 肉眼角标应消失；反向删掉 `unbadge` 的 post 重试重打同包 → 角标残留复现。观测 `stat` 的 `added/verifyFail` + `dump` 的 `viewBadge`。
  - F2：贴角标后把该 itemView 滚出屏 → 看日志 `HINTBADGE F2 onViewDetachedFromWindow/onViewRecycled stripped=n`；反向去掉 `detachHook` 里的 `stripBadges(iv)` 调用 → 残留复现。
  - F3：`kvset show_hint=false` → 3s 内角标消失（`clearAllBadges`）；反向去掉 `refresh()` 的 `clearAllBadges()` → 不消失（JVM 单测已锁）。
  - F4 回退：造 talker 两侧空的台账条目 + 当前会话同号消息 → 旧口径放行、`talkerMismatch` 不增；反向恢复 F4 → 抑制 + `talkerMismatch` 增。
- 本轮全程未碰 adb / 设备 / 开关 / 发图 / git / 装包；未写模型路由字样；唯一写入 = 本文 + 三处授权源码/单测。

`[DONE] ⑧ 诚实边界`

---

`[DONE ALL]`




