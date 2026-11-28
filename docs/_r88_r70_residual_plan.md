# R88 · R70 判据剩余项执行方案（只出方案，不落码）

> 状态：**方案待主会话自审**。本轮未改 `app/src/main`、未改单测、未装机、未跑任何真机场景。
> 来源：`docs/当前状态.md` §二 B1（R70 判据剩余 4 项）与 `docs/_r70_microx_delete.md` §5 / §7。
> 档位：Pro（判据设计 + 宿主通知链推导 + 失效边界裁决）。Pro/Flash 双 402 已登记，取③主会话自审；用户明确「先出个方案，我去主会话自审」。

---

## [DONE] S1 现状裁决

### 已闭环（不要重做）
- **A′ 正向**：UI 删组后不冷启，727ms 内分组行消失，`gcs stat own` 减 1（`_r70_microx_delete.md` §5 第 6 条、`当前状态.md` §一 R70 行）。
- **关开关反向**（§7，2026-11-19 20:17）：只关 `chat.gconv_delete_notify`，库行照删但**无 `s4.b event=5` / 无 `p3.e` / 无 `p3.f`**，界面旧行冻结、冷启才消失 ⇒ A/B 因果闭环。
- **b2 建组不受本开关影响**：反向实验中建组仍秒出（`nativeInsert ... OK`、`event=2`）。

### 剩余 4 项（`当前状态.md` §二 B1）
| # | 项 | 性质 | 静态能否定案 |
|---|---|---|---|
| Q1~Q4 | 微X删组动态对照：`s4.b(5)` / `p3.f` / 真删 SQL / 肉眼 1~2s | 微X业务 dex 加密，静态受阻 | 否，必须真机侧观察 |
| P | payload 变异：具体 username → 空串，看是否触发全清分支 / 置顶重排 | 需制造一次 `event=5` 空串事件 | 否，且副作用风险最高 |
| M | 误伤反向：删组后真实会话行数/消息不变（`gcs stat other` 恒定） | 真机只读统计 + 人工点开真实会话 | 部分（源码可证只发事件不写库，但需真机坐实） |
| D | b2 建组 + A′ 删组双轨 A/B：`event=2` 进 / `event=5` 出互不干扰 | 真机 HCR 日志 | 否 |

### 源码边界（[源码实证]）
- `HostConvDelete.kt:14-21`：`k74/y0.b()` 只投递事件总线，**不写库、不删消息**；宿主 `s4.k/n/l` 才是真删 API，方案绝不调用。
- `HostConvDelete.kt:38`：事件码 5 = delete；`:19-21` 宿主删单行用**具体 username**，全清才用空串，空串会走 `p3.N=true + i3.r(null,1)` 全清分支。
- `HostConvDelete.kt:69-79`：`notifyDeleted(username: String)` 直接把传入字符串透传给 `s4.b(5,s4,username)` ⇒ **传空串无需改生产逻辑**，但需要一个探针入口。
- `GroupConvProbe.kt:138-168`：`op=gcs sub=stat` 已输出 `stat conv total/own/other`、`stat contact total/own/other`、`stat pinned ...`，误伤反向可直接用，零新增代码。
- `docs/_r70_microx_delete.md:195`：HCR 探针 `op=hcr sub=on/off` 已挂 13/13 hook，Q1~Q4 与双轨 A/B 可直接复用。

---

## [DONE] S2 D2 路线对照表

| 路线 | 核心动作 | 代码量 | 额外写库 | 竞态 / 副作用 | 失效场景 | 验证成本 |
|---|---|---|---|---|---|---|
| **R2 完整四项（推荐）** | 新增一个**只发事件探针**跑 payload 变异，其余三项用现成 `hcr` / `gcs` 探针 | ~2 文件（新探针 + EntryReceiver 接线）+ 单测，<120 行 | 无（探针只发 `s4.b(5)` 事件，不写库） | 空串事件可能触发全清分支/置顶重排 ⇒ 必须最后跑、冷启复位、设急停 | 微X未装则 Q1~Q4 转 UNKNOWN；空串实验若真触发全清需立刻停 | 一次装机 + 一轮真机（约 4 个动作） |
| R1 最小三项 | 跳过 payload 变异，只跑 Q1~Q4 + 误伤 + 双轨 | 0 行源码 | 无 | 无新增风险 | 「具体 username 更优」只停留在源码推断，未坐实 | 零装机，一轮真机（3 个动作） |
| R3 静态归档 | 把剩余 4 项标为「静态受阻 + 已定案不做」，不动设备 | 0 行 | 无 | 无 | 微X链与 payload 分支永远停在 [推断]，用户问题「参考微X」无实证 | 0 |

---

## [DONE] S3 推荐路线 R2：执行步骤（待自审后执行）

### 前置（一次做完，之后不再动开关）
1. 开关回读：`chat.gconv_enabled=true`、`chat.gconv_native_insert=true`、`chat.gconv_delete_notify=true`。
2. `op=hcr sub=on` → `op=logclear` → 记录 `op=gcs sub=stat` 基线（`conv other` / `contact other` / `pinned`）。
3. 微X侧测量时**唯一自变量** = `chat.gconv_enabled=false`（冷启生效）；测完恢复 true 并冷启。

### 动作 A：误伤反向 M（零新增代码，先做）
- UI 删一个测试分组（不冷启）。
- 判据：`gcs stat` 的 `conv other` / `contact other` **恒定**、`conv own` 减 1；人工点开 2~3 个真实会话，消息完好。
- 用户可见事实优先：真实会话没丢、消息还在；计数器仅辅助。

### 动作 B：双轨 A/B D（零新增代码）
- 建组：HCR 见 `s4.b event=2` → `p3.f cursor+1` → 肉眼 1~2s 秒出。
- 删组：HCR 见 `s4.b event=5` → `p3.e event=5` → `p3.f cursor-1` → 肉眼 1~2s 秒消。
- 互斥判据：`event=2` 与 `event=5` 必须一进一出；只看到其中之一不算双轨通过。

### 动作 C：微X动态对照 Q1~Q4（需微X在线）
- 关 `chat.gconv_enabled` + 冷启 → 微X建测试组 → 微X删组，HCR 全程开。
- Q1：删组瞬间有 `s4.b event=5 user=wx__c_<hex>` ⇒ 坐实微X走宿主通知链。
- Q2：`p3.f` 命中、`cursor`/`getCount` -1、`item` 少一行。
- Q3：HCR `sql delete` 是否出现 `wx__c_` 行 ⇒ 区分宿主 API 删 vs SQL 直写；两种都不影响我方方案（我方保留 execSQL 删行）。
- Q4：肉眼 1~2s 内消失。
- 四者全中才把 `_r70_microx_delete.md` §1.2 的 [推断·高] 升为 [真机实证]；缺任一保持 [推断]，不得写成已证。

### 动作 D：payload 变异 P（最后做，急停优先）
- 需新增探针入口（见 S4）：`op=gconvdel sub=empty` → 调 `HostConvDelete.notifyDeleted("")`。
- 对照顺序：先做一次正常删组（具体 username，基线）→ 再单独发空串事件。
- 观察 HCR：是否出现 `p3.N=true` + `i3.r(null,1)` 全清分支、置顶重排、滚动跳位；肉眼若真实会话行集体跳动/消失，**立即停**并冷启复位。
- 判据：具体 username 不触发全清、空串触发全清（或至少触发置顶重排）⇒ 坐实「具体 username 更优」。
- ⚠️ 这是本轮唯一有副作用风险的动作，必须放最后；不与其他动作并行。

### 复位（每个动作后）
- `op=hcr sub=off`；微X测量后恢复 `chat.gconv_enabled=true` + 冷启；空串实验后冷启 + `gcs stat` 复核 `other` 与 `pinned` 回到基线。

---

## [DONE] S4 若自审通过：落码规格（Flash 档，按现成判据接线）

新增只读发事件探针（不写库、不改返回值）：
- `wxgroup/app/src/main/java/com/lx/wxgroup/probe/GconvDeleteProbe.kt`：`run(sub)` 只认 `"empty"`/`"stat"`，`empty` 调 `HostConvDelete.notifyDeleted("")`；任何其它 sub 返回 unknown，零副作用。
- `entry/EntryReceiver.kt` 加 `op=gconvdel` 分支，转后台线程调用（与 `gcs` 同模型）。
- `app/src/test/.../GconvDeleteProbeTest.kt`：unknown sub 不触发事件；`empty` 只调用一次 `notifyDeleted("")`；开关关时仍可调（探针显式触发，不绕过生产 gate 的语义要在测试里写清）。
- 变异验证（先确认有语义）：
  1. `notifyDeleted("")` 改成具体 username ⇒ 空串对照用例必须红；
  2. 事件码 5 改 3 ⇒ HCR 判据对应用例必须红；
  3. 探针接线漏掉 `op=gconvdel` ⇒ unknown 用例必须红。
- 三闸：`dev.sh test` / `assembleDebug` / 残留检查；装机前交叉复核 diff（Hook/反射级改动 ⇒ 按 Pro 档复核，若 Pro 子代理 402 则三件套登记后主会话亲核）。

---

## [DONE] S5 三档红线

- **进入边界**：本轮只读；不改 `GroupConvPlan.delete*Sql`、不动 `SYNC_THROTTLE_MS`、不调宿主 delete API `s4.k/n/l`、不改 `NativeConvInsert` b2 行为。
- **返回边界**：只交付本方案文档；落码与真机均需用户自审后另行确认；Q1~Q4 未真机前不许写成已证。
- **复位边界**：所有开关恢复基线（`gconv=true` / `native_insert=true` / `delete_notify=true` / HCR off / logclear 后不残留）；空串实验后必须冷启复核 `gcs stat other` 与 `pinned`。

## [DONE] S6 诚实边界

- 微X业务 dex 加密未解（R72 定案），Q1~Q4 只能真机侧观察，不能静态补证；若微X未在线，保持 UNKNOWN。
- `gcs stat other` 是**进程内计数**，只能证明「没有被连带删行」，不能证明消息内容逐字节不变；消息完好必须人工点开确认。
- 空串 payload 的全清分支副作用来自 dex 推断 [推断·高]，真机若未复现也不能反推「空串安全」——判据按实际 HCR 日志写，不按愿望写。

[DONE ALL]
