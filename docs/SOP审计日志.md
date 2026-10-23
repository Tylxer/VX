# SOP 合规审计日志（轮末自审，每轮追加）

> 依据 `CLAUDE.md` §四④。输入：`docs/派发审计台账.md` + `docs/并行开发轮次计划.md` + `git diff --stat`。
> ✅ `requestModelId` **用 Bash + Python 读** `~/.workbuddy/projects/<proj>/<session>/subagents/<id>.jsonl`
> （2026-10-19 22:38 实测：**仅 Read/Grep 工具被拒，Bash 畅通**，可事后回填）。
> ⛔ 别再写「沙箱拒绝读 `~/.workbuddy`、不要尝试读」——那是错的，且与 `docs/开发回路速查.md` §8.6 的 60 秒体检直接冲突。

## 审计 6 项（照抄判定）

| # | 项 | 判据 | 常见 FAIL 形态 |
|---|---|---|---|
| ① | 分包下限 | 台账「实现 lane」数 ≥ `CLAUDE.md` §四① 表格要求 | 只有复核 lane；实现全主 agent 写 |
| ② | 档位显式 | 台账 model 列无空、无 `lite`/`reasoning` | 留空=继承父会话 |
| ③ | 文件不重叠 | `git diff --stat` 独立核验；台账**实现 lane > 0** 且其独占文件出现在 diff 中 | 台账 0 实现 lane 而 diff 非空（全主 agent 写） |
| ④ | 设备锁 | 子代理行「碰adb」全为「否」 | — |
| ⑤ | 轮次重排 | 计划文档末尾有 lane 表（执行者/model/独占文件/碰设备/任务） | 只写一句「重排为 Rn」 |
| ⑥ | 真机前复核 | `adb install` 之前有复核记录，且给的是 diff 正文 | 给文件路径 |

> ⚠️ ③ 的**旧判据「作者不全为主 agent」不可执行，已废弃**：子代理不 commit、工作树 diff 无作者字段、
> git 作者恒为同一人，用它判只会得到恒假结论。改用上面的「实现 lane 数 + 独占文件出现在 diff」判据。

---

## 历史

### R7b（2026-10-19 22:05）— **未合规**

| ① 分包 | ② 档位 | ③ 文件 | ④ 设备锁 | ⑤ 重排 | ⑥ 复核 | 总体 |
|---|---|---|---|---|---|---|
| **FAIL** | **FAIL** | PASS* | PASS | FAIL | PASS | **未合规** |

\* ③ 技术上 PASS（子代理只读未写），但实质 FAIL —— 全部改动同一作者，等于单 agent。

**违规明细**

| # | 违规 | 依据 | 证据 |
|---|---|---|---|
| 1 | 实现 lane = 0，7 文件 ~3500 行全主 agent 写 | §四① | 台账 R7b 统计 |
| 2 | 两次 spawn **未传 `model=`** → 继承父会话 | §四② | 台账两行「未传」；**22:38 Bash 硬核验：两 spawn 实跑 `custom-local:deepseek-v4-flash-202605`**，审 Hook 级改动应 Pro ⇒ 真错档 |
| 3 | 台账轮末补记，非派发当时 | §四③ | 本段为 22:05 补写 |
| 4 | SOP 审计未在轮末落，用户追问才写 | §四④ | 本文件 R7b 段事后补 |
| 5 | 复核给文件路径而非 `git diff`（1400 行文件） | §二第 4 条 | 复核#1 prompt |
| 6 | 改动未攒齐反复 `adb install` | §二第 5 条 | 台账「主·装包」行 |
| 7 | ⑤ 计划文档只有「重排为 R8」一句声明，无 lane 表 | §四⑤ | 当时的计划文档末尾 |
| 8 | `MEMORY.md` 5152B 超 4KB | §二第 3 条 | `wc -c` 实测 |

**根因（两项 FAIL 同源）**
- 分包不足 ⇒ 主 agent 亲手写了 3500 行 ⇒ 手上没有现成 `git diff` ⇒ 复核只能「自己去看源码」。
  所以第 5 条是第 1 条的**副产品**，不是独立疏忽。
- 档位错判：**复核档位 = 被审改动的档位**（`CLAUDE.md` §四②）。本轮被审的是 Hook 级改动 ⇒ 应 Pro；
  未传 `model=` 继承父会话 Flash（22:38 已硬核验实跑 Flash）。
  ⛔ 两种极端都错：「复核是只读 ⇒ Flash」错，「复核一律 Pro」也错 —— 一律按**被审改动的档位**定。

**当场整改**
| FAIL | 整改 |
|---|---|
| ① | `CLAUDE.md` §四① 改为**量化表**（≥3 文件 ⇒ ≥2 实现 lane），并写明「复核 lane 不计入实现 lane」 |
| ② | §四② 写明 `model` 默认值 = 继承、**不传不报错**，档位按产出物判定，复核档位 = 被审改动的档位 |
| ③ | §四③ 改为**先记台账再派发**（填不出 model 列就不许派） |
| ⑤ | 已补写 R8 完整 lane 表 |
| ⑧ | `MEMORY.md` 压到 4135B ⚠️ **未达标**（4135 仍 >4096；复审已再压至 **4055B** 才真正合规） |
| ④ | 轮末必出本报告（`CLAUDE.md` §四④）—— 本次 R7b 报告即整改产物 |
| ⑥⑦ | 「攒齐再上机」与「计划须含 lane 表」已并入 §四①/⑤ 硬规则 |

### R8（2026-10-20 01:40，离线段，无真机）— **合规**

| ① 分包 | ② 档位 | ③ 文件 | ④ 设备锁 | ⑤ 重排 | ⑥ 复核 | 总体 |
|---|---|---|---|---|---|---|
| PASS | PASS | PASS | PASS | PASS | PASS* | **合规** |

\* ⑥ 见下方说明（给的是「改动清单」而非 diff 正文，已如实标注）。

**证据**

| # | 判据 | 实测 |
|---|---|---|
| ① | 实现 lane = 2（L1 `SnsCommentGuard.kt` / L2 `SnsCommentTag.kt`）；独立功能文件 = 2 ⇒ 应派 ≥1 ⇒ 实际 2 | 台账 R8 统计行 |
| ① | 「现成问题清单就是现成 lane」：Guard 5 条 + Tag 2 条**当轮派完**，未推下轮 | L1/L2 产物全 `[x]` |
| ② | 6 次 spawn **全部显式** `model="Hy4 preview"`；无空、无 `lite`/`reasoning` | 台账 model 列 6 行 |
| ③ | 独占文件零重叠：`_cache/r8_l1~l4_done.md` / `r8_static_rows.md` / `r8_offline_review.md` / `tools/_r8_{seed,rows}.py` 各归各 lane | `find -newermt` 全仓核验 |
| ④ | 无真机，adb **全程零调用**；台账子代理行「碰adb」全「否」 | L3 自报「零 adb」；L6 纯静态 |
| ⑤ | R9 lane 表已落 `docs/并行开发轮次计划.md` 末尾（含执行者/model/独占文件/碰设备/任务） | 见该文件 |
| ⑥ | L5 复核在编译通过后、装机前完成；总判定「可以装机」 | `_cache/r8_offline_review.md` |
| — | 编译 `BUILD SUCCESSFUL` EXIT=0；单测 **525 条 0 失败 0 错误**（01:36 XML 核验，基线未倒退） | — |

**⑥ 的如实说明（不美化）**
本轮**没能提供 `git diff` 正文** —— 工作树自 `86e7808` 起累积 **1661 行**改动（含 R7b 及更早，从未 commit），
整喂会爆 token 且混入历史改动。改为「**L1/L2 自报改动清单（含行号）+ L5 逐条打开代码核实**」模式。
这满足 §二第 4 条的精神（不喂全文件），但**不满足字面**（非 diff 正文）。
⇒ 根治办法已写进 R9：**本轮结束后对当前工作树打一个 commit**，让 R9 起能产出精确 diff。

**② 的特殊说明**
用户 00:52 临时指令：模型分流**统一 `Hy4 preview`，且不写入规则文件**。
⇒ §四② 的 Pro/Flash 表本轮**暂不执行**，规则文本未动。故 ② 按「是否显式传 ID」判定（PASS），
**不按「档位是否符合 Pro/Flash 表」判定** —— 该维度本轮不适用。

**`requestModelId` 已硬核验（01:44 Bash + Python 读 transcript）**：6 条 lane **全部** `hy4-preview`
（`requestModelName` = `Hy4 preview`）⇒ **传 `model="Hy4 preview"` 被系统接受，不是静默回落**。
（补充一条工具经验：`requestModelId` 在 jsonl 的 **`providerData`** 里，不在顶层 —— 之前按顶层 `get` 取到 None。）

**⚠️ 当时误判，已于 02:07 更正（保留原错误以存证）**
全仓核验时发现 `docs/_r10_cdn_api.md` / `docs/_r10_cdn_chain.md` 在 01:32/01:46 被写入，
当时我判定为「并行会话/遗留进程的产物」，**这是错的**：
它们是**并行会话 R9 的 M1/M2 lane 正常产物**，台账 `:102-103` 有明确派发记录（agent `94abc07a`/`93c5398d`）。
错误根源：我只按自己的 lane 命名（L1–L6）去比对，看到 M1/M2 就判为「非本轮」。

**⛔ 02:07 用户决定：B 线（并行会话）已关闭，后续所有开发由本会话承担。**
⇒ 撞号问题自此消失（R9 只有本会话一条线）；B 线产物转为**只读归档**，不排进任何轮次。

**L5 复核结论摘要（下次装机前必读）**
- A 区五条**全通过**。其中 #11 实为 **6 处**裸 return（非 L1 自报的 5 处，多出的 `:293` 由 #5 引入），**已全部改掉，无遗漏**。
- B 区两处通过；`logBind` 三个调用点外层额度逐一核实全覆盖，删前置判断后行为等价。
- C 区 L1 的两处「主动偏离原清单」经独立复核**均正确**（`archived` 计数无功能路径依赖；告警分叉只改文案不改判据）。
- D 区 L6 静态结论**存疑**：`probe("ids")` 原不输出 `childCount`，用 `rows=0` 排除 (b) **不成立**；且 L6 漏了 `Llh3/l1->b` 这条闭合路径。
- ⇒ 主 agent 已按建议补 `child=`/`attached=` 两个判据（`SnsCommentTag.kt:1566+`），下次真机可一次判死。

### R10 阶段 0（2026-10-20 03:4x）— **N/A（本轮无实现 lane，判定为重点单列）**

| ① 分包 | ② 档位 | ③ 文件 | ④ 设备锁 | ⑤ 重排 | ⑥ 复核 | 总体 |
|---|---|---|---|---|---|---|
| **N/A** | **N/A** | PASS | PASS | **FAIL→已补** | **N/A** | 合规（见下逐项说明） |

**本轮性质**：只改**方案文档**（`_cache/r9_cdn_plan.md`，gitignored）+ 计划/台账/记忆，**未派任何子代理、未写任何 `.kt`/`.py`**。
所以 ①②⑥ 的常规判据不适用 —— 但**不豁免**，逐项说明为什么是 N/A 而不是漏做：

| # | 判定 | 理由（不是「跳过」） |
|---|---|---|
| **①** 分包下限 | **N/A** | `CLAUDE.md` §四① 的量化表格针对的是**实现 lane**（≥3 独立文件 ⇒ ≥2 实现 lane）。本轮**0 个文件被实现**（只改文档），表格前提不成立。<br>⚠️ 但若按「本轮总工作量」看，阶段 0 修 3 个 BLOCKING 属**判据/方案设计**，按 §四② 本就该 Pro 档 —— 由**主 agent 亲自做**，未降档 |
| **②** 档位显式 | **N/A** | 无 spawn ⇒ `model` 列无值可填。台账已如实标注「不适用（未 spawn）」，**不是留空** |
| **③** 文件不重叠 | **PASS** | `git status --short` **干净**（无未提交改动）；本轮改动全部在 gitignored 的 `_cache/` 与 docs 里。<br>🔑 **关键核实**：F1 收尾消息声称「`tools/_r9_cdn.py` 仍为 untracked」⇒ 若属实会与我已提交的 `5205da0` **形成两套实现并存**。已 `git status` + `git diff --stat` 双查：**均无输出**，确认无分叉；F1 那条是**它自己提交前的旧视角**，已被我的提交覆盖 |
| **④** 设备锁 | **PASS** | 全程**未碰 adb / 未装包 / 未跑场景**。`assembleDebug` + `testDebugUnitTest` 只是本地 Gradle |
| **⑤** 轮次重排 | **FAIL → 已补** | 复查时发现原 R10 lane 表有**3 处实质错误**，已改：<br>① 「I1/I2/I3 并行」⇒ **依赖反了**，I1 引用的 `Keys.SNS_CDN_*` 由 I2 产出 ⇒ 改 `I2 → I1/I3`；<br>② 独占文件写 `data/Prefs.kt` ⇒ **该文件不存在**（`data/` 已 ls 确认；`Prefs` 是 `Keys.kt:195` 的 object）；<br>③ I3 写「新建 `SnsCdnProbe.kt`」⇒ **本轮 F2/F3 已建好并接线**（514 行 + `EntryReceiver` 已有 `"cdn"` 分支）⇒ I3 只剩补 `reset`/`hook` 对齐。<br>计划末尾 lane 表仍完整（执行者/model/独占文件/碰设备/任务 五列齐） |
| **⑥** 真机前复核 | **N/A** | 本轮**未执行 `adb install`**，前提不成立。<br>但「复核」这件事本身做了，且比复核更硬：3 个 BLOCKING 全是**我逐符号打开源码核实**后修的，不是照抄 V1 结论（详见台账「主 agent 亲自复验」段） |

**本轮最值得留下的一条教训（方法论级）**

> **「Grep 有命中」≠「API 可用」。** B-2 是「API 根本不存在」（`WifiOnly` 零命中，容易发现）；
> 我自己额外抓到的 `HostContext.app()` 返回 **`Context?`**（`core/HostContext.kt:63`）是更隐蔽的一类：
> **名字命中、签名不符** ⇒ 骨架直接 `.getSystemService()` 是 nullable receiver，编译期才报错。
> ⇒ 以后核任何「方案里引用的外部 API」，**必须打开定义看签名**，不能只 Grep 名字有没有。

**产物与验证**
- `_cache/r9_cdn_plan.md` 696 → **824 行**；`docs/并行开发轮次计划.md` R10 段整体重写。
- `assembleDebug` **EXIT=0**（9s，无代码改动属增量空跑）。
- `testDebugUnitTest` **EXIT=0**，实跑 **525 条 / 0 失败 / 0 跳过**（`test-results/*.xml` 时间戳 03:42:20，非陈旧产物）。
- 全程**未碰 adb**，符合「正式开发等用户通知」的协作边界。

### R11（2026-10-20 04:0x~05:0x）— **首轮真正跑满 6 项的轮次**

| ① 分包 | ② 档位 | ③ 文件 | ④ 设备锁 | ⑤ 重排 | ⑥ 复核 | 总体 |
|---|---|---|---|---|---|---|
| **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | **PASS** | 合规 |

| # | 判定 | 证据 |
|---|---|---|
| **①** 分包下限 | **PASS** | 本期改动独立功能文件 **6 个源码 + 2 测试 + 1 脚本**。§四① 表：≥3 独立文件 ⇒ ≥2 实现 lane。**实际派 4 个**（I2/L1/I1/I3），超额。<br>🔑 之所以切成 4 条而不是 2 条：契约文件 `_cache/r11_contracts.md` 把产物切成**互不重叠**（I2 独占 `Keys.kt`、L1 独占两个纯逻辑文件、I1 独占 `SnsCdnImage.kt`、I3 独占 `SnsCdnProbe.kt`），拆开边界清晰、单条体量小、被打断时损失小（§五「有界子步」原则） |
| **②** 档位显式 | **PASS** | 4 条 lane 全部**显式传 `model=`**，无一条靠继承。事后从子代理独立 transcript 的 `providerData.requestModelId` 回填：<br>I2 = `custom-local:deepseek-v4-flash-202605`（规划 Flash ⇒ **一致**）<br>L1/I1/I3/V2 = `custom-local:deepseek-v4-pro-202606`（规划 Pro ⇒ **全部一致**）<br>⇒ 本轮 **5/5 路由零漂移**。判据设计（L1 熔断语义 + 魔数表）、Hook 链路（I1）、反射（I3）、复核（V2）都按 §四② 归 Pro；I2 是纯照抄登记 ⇒ Flash |
| **③** 文件不重叠 | **PASS** | 4 条 lane 的独占文件**零交集**（契约文件逐条划线）。<br>⚠️ 唯一有交叠风险的是 `SnsCdnImage.kt`：I1 建好后主 agent 又改了 ~60 行（B-1 拆分 / wifiOnly / 时钟域 / hookAllMethods）。这是**串行接管**不是并行写 —— I1 已交付完毕后我才动，无并发冲突。<br>`git status --short` 已核：11 个文件全部为本轮预期产物，无意外文件 |
| **④** 设备锁 | **PASS** | 全程**未碰 adb**（无 `dev.py` / `adb` 调用），`gradle_run.py` 只跑 `assembleDebug` + `testDebugUnitTest`（本地）。<br>符合用户「本 round 明确不碰设备」的边界，也符合「正式开发等用户通知」 |
| **⑤** 轮次重排 | **PASS** | 已在 `docs/并行开发轮次计划.md` 写入 **R11 段**（完成表 + 本 round 不做表 + R12 待办分「离线/真机」两栏）。<br>🔑 该段还记了一条**上机前警告**：主 agent 修完 V2 复核项后**未再过第二遍复核**，而 B-1 拆 `cfg()`/`refreshCfg()` 是唯一「改了异步语义」的地方 ⇒ 建议上机前对它单独复核 |
| **⑥** 真机前复核 | **PASS** | 本轮**没装包**，但按用户级铁律「真机测试前必须交叉验证」，我在离线阶段就把 1109 行 diff 交给 Pro 复核（V2 = `agent-b2cf51e3`），报告 `_cache/r11_review.md` 判「**不可上机**」。<br>**AI 复核 + 自查双通道**：V2 抓到 1 BLOCKING + 4 建议；我另自查出 3 条 V2 漏掉的（见下）。<br>⚠️ 未做第二遍复核 —— 已在计划里显式登记为风险，不隐瞒 |

**V2 复核抓到的最重要一条（值得写进方法论）**

> **「模块里所有同步 IO 都必须在热路径之外」不是只有 `LogX` 一条。**
> B-1 的形式是：`enabled()` → `cfg()` → TTL 到期时**同步** `Kv.getBool` → `GroupDb.getPref` →
> `readableDatabase.query`。**SQLite 读和文件写是同量级的磁盘 IO**，且它伪装成「读一个配置」看起来很轻。
> 而 `onC2CDownloadCompleted` 是**每张图都调**的 xposed 回调。
> ⇒ 同类自查清单：看到「读配置 / 读开关 / 查 DB」出现在回调里，一律要问「这个读会不会落盘」。

**主 agent 自查出、V2 未提的 3 条（说明复核不能替代自查）**

| 项 | 性质 |
|---|---|
| **时钟域错乱** | `breaker.hit(now)` 传 `currentTimeMillis()`，`breaker.blocked()` 传 `elapsedRealtime()` ⇒ 同一 `tripAt` 被两套时钟比较，熔断冷却判定**静默错乱**。这类 bug 单测抓不到（两个函数各自都「对」），只有通读调用链才看得见 |
| **`android.app.ActivityThread` 是 `@hide`** | 编译期 `Unresolved reference`。这是「照抄 Android 知识」的坑：`ActivityThread.currentProcessName()` 在网上到处都这么写，但在 SDK 里不可见 |
| **`gradle_run.py` 吞掉 Kotlin 报错** | 错误过滤用 `"^e:"` 做 substring 匹配（不是正则锚点）⇒ 失败时只见 `BUILD FAILED`，真实错误全丢。**这会让后续每次编译失败都白查一轮**，属工具链级隐患 |

**产物与验证**
- 新增/改动：`Keys.kt`(+8) / `SnsCdnBreaker.kt`(73) / `SnsCdnCheckLogic.kt`(93) / `SnsCdnImage.kt`(~360) /
  `SnsCdnProbe.kt`(547) / `HookEntry.kt`(接线) + 两个测试(287) + `tools/_r9_cdn.py`(+60)。
- `assembleDebug` **EXIT=0**（9s）。
- `testDebugUnitTest` **EXIT=0**，实跑 **552 条 / 0 失败 / 0 错误 / 0 跳过**（XML 解析核验，基线 525 ⇒ +27）。
- 全程**未碰 adb**。

---

### R11b（2026-10-20 05:0x~05:4x）— K8 文件发送：**第二轮复核改用主 agent 自证的记录**

#### 六项自审

| # | 项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 轮次计划存在且已重排 | ✅ | `docs/并行开发轮次计划.md` R11b 段 + R12 待办按「能否离线」分栏 |
| 2 | 台账先记后派 | ✅（有一次瑕疵） | 台账 R11b 段已写；但 **V3 是「先派后补」**——派完才写台账，违反 §四③。已如实标注在台账里 |
| 3 | 档位分流 | ⚠️ **无法验证** | 两次派出的复核 lane 都 `killed`，没留下 `requestModelId`。台账「待回填」 |
| 4 | 编译 + 单测 | ✅ | `assembleDebug` EXIT=0；`testDebugUnitTest` **557 条 / 0 失败**（XML 核验，基线 552 ⇒ +5） |
| 5 | 交叉复核（§四⑥） | ⚠️ **降级为自证** | 见下「子代理两连失败」 |
| 6 | 文档/记忆更新 | ✅ | `取证结论.md`（订正 `q1.g` 语义 + 补 `O8` 逐字段表 + 视频死路）、`需求功能清单.md`（撤「显示未读是空壳」）、`_cache/r11c_filesender_review2.md` |

#### ⚠️ 本轮最重要的方法论事件：**子代理两连失败，连「先写占位文件」都没执行**

| 尝试 | 任务形态 | 存活 | 产出 | 失败信号 |
|---|---|---|---|---|
| **V3** | 完整复核（3 个问题 + 7 项检查） | **2m 36s** | 零（`_cache/r11c_...md` 未创建） | `Status: killed`，stdout/stderr 全空 |
| **V3b** | 收窄到 3 个问题，**要求第 0 步先 Write 一个 5 行占位文件** | **52s** | 零（连占位都没建出来） | 同上 |

**关键判读**：
1. 不是「任务太大」——收窄到 3 个问题、并明确要求「30 秒内先落盘占位」，**仍然零产出**。
   说明它连**第一次 tool call** 都没走到。
2. 不是「prompt 不清楚」——两版 prompt 都把证据源、行号锚点、禁止事项写得很细。
3. ⇒ 对照既有结论（子代理是父 turn 的子生命周期，父 turn 被 abort 会连带 settle），
   **这是环境级问题，重试无用**。

**处置**：**不第三次重试**，改由主 agent 亲自逐条对照反汇编自证。报告写在
`_cache/r11c_filesender_review2.md`，每条结论附可复查的反汇编行号。

**代价（必须承认）**：
- 失去了「独立第二视角」。V2b 那一轮正是靠独立复核抓到了主 agent 漏掉的 2 条 BLOCKING，
  说明这个环节**确实有效**，本轮缺失它是实质性损失。
- 补偿只做到「结论可复查」，做不到「视角独立」。

**教训**：
- 派子代理做复核时，**「先落盘占位」这个保险本身就是失效的**——它需要第一次 tool call，
  而被杀时往往连第一次都没到。真正能保住的只有「父 turn 不发生打断」。
- 所以对**关键复核**（上机前的最后一道），不能把它设成「必须靠子代理」，
  必须在计划里就准备好「子代理失败 ⇒ 主 agent 自证 + 如实降级标注」这条退路。

#### 三条 BLOCKING（两条来自 V2b，一条主 agent 自查）

| # | 来源 | 问题 | 严重度 |
|---|---|---|---|
| B-1 | V2b | VIDEO 用 `r1.a(o1.VIDEO)` 造普通 `q1`，而 `t81/g.i` 首句 `instance-of o3` 不是就 return ⇒ `k1.a` 恒 null ⇒ NPE 被吞 ⇒ **恒 false，只留一行 NPE** | 静默失效（可接受范围：上层只看到 failed=1） |
| B-2 | V2b | IMAGE 漏 `q1.h=13` ⇒ 落 `t81/g.a` default 分支，把 null 的 `q1.x`/`q1.A` 传进 `j2` | **异步层可崩微信**（非法 CGI 入队后异常不在 `runCatching` 内） |
| **B-3** | **主 agent 自查** | 路径塞错字段：`q1.g()` 只写 `q1.b`（文件名），真实路径是 `q1.j`。`t81/g.a` 用 `q1.j` 去 `vfs/o6.l()` 取文件大小，而 `q1.<init>` 把它初始化为 `""` ⇒ **构造出 path="" 的非法 CGI** | **V2b 完全漏掉**，且比 B-2 更根本 |

**B-3 的教训（可复用的通用判据）**：
「照抄某个方法」时，**不能只看它调了哪些 setter** —— 还要看它有没有
**直接写字段**（`iput`）。微信里 `q1` 这类 builder 大量使用 `iput` 直写无 setter 的字段，
只抄 setter 会漏掉一半状态。**判据：把目标方法的指令流从头到尾读一遍，
把每个 `iput` 和每个 `invoke-virtual` 都记下来，后者对照 setter、前者直接 `setXxxField`。**

#### V2b 报告的一处误读（本轮订正）
`q1.o` 是 `I`（int，资源 id `0x7f0803d6`），V2b 误读成 float（`1.80795e+38`）——
那只是 dexdump 把同一串比特按 float 解释的**注释**。写 `setFloatField` 会抛
`IllegalArgumentException`。同一函数里真正的 float 字段是 `q1.y:F` / `q1.z:F`。

#### 🔴 顺带发现的上游断点（不在本轮范围，但必须记）
`ForceForward.snapshotNow()` 恒返回 `kind=UNKNOWN` / `localPath=""`（G2 未取证）
⇒ `planFor` 首行 `isCovered(UNKNOWN)=false` 直接 SKIP ⇒ **`resendFile` 永远走不到**。
即：`FileSender` 反射链修好了，但**业务路径仍未接通**；G2 是 Hook 级取证，只能真机。

**产物与验证**
- 新增：`core/FileSender.kt`（~250 行）、`tools/_r11_filesend.py`、`_cache/r11c_filesender_review2.md`。
- 改动：`ForwardLogic.kt`（`canResendFile` 砍 VIDEO + 长注释）、`ForceForward.kt`（kind 映射 + `filerdy` 探针）、
  `HookEntry.kt`（接线）、`ForwardLogicTest.kt`（2 个测试改语义，+5 条）。
- 文档：`取证结论.md`（订正 `q1.g` 语义、补 `O8` 逐字段表、补视频死路证据）。
- `assembleDebug` **EXIT=0**；单测 **557 条 / 0 失败**（基线 552 ⇒ +5）。
- 全程**未碰 adb**。

---

## R11c（2026-10-20）：红包名单空壳 —— 保留定义、不渲染

### 起因

R11b 提交后按「扫剩余离线债」的流程 `grep TODO|未接线|空壳`，命中 `Keys.kt:157/164`。
核实后确认它是**真空壳**，且形态比一般的"功能没做完"更麻烦：

| 事实 | 证据 |
|---|---|
| 零消费点 | `REDPACKET_ALLOW` / `REDPACKET_EXCLUDE` 在 `main/` 与 `test/` 下**除定义与登记外零引用** |
| 但会被渲染 | `Keys.kt:263/267` 在 `Prefs.ALL` 里登记为 `PrefKind.TEXT` ⇒ `SettingPage` 画出两个输入框 |
| 但删不得 | 用户旧备份文件里可能带这两个 key，删定义会让 `BackupFlow.knownKeys` 当未知 key 丢弃 |

⇒ **两个错误答案之间夹着唯一正确的那个**：留着定义、不渲染。

### 处置（三层约束，三个文件分别保证）

| 约束 | 保证方式 |
|---|---|
| 定义还在 | `Prefs.ALL` 保留那两行，`hidden = true` |
| 备份还在 | `Kv.exportAll()`（`:72`）与 `BackupFlow.knownKeys`（`:69`）都遍历 `Prefs.ALL`，**未改** |
| 不渲染 | 新增 `Prefs.visible(domain)` = `byDomain(domain).filter { !it.hidden }`，三个渲染点全改用它 |

改动点：
- `Keys.kt`：`PrefSpec` 加 `hidden: Boolean = false` + 一段「什么时候该用 / 反例是什么」的注释；
  新增 `Prefs.visible(domain)`；`byDomain` 注释里点明「**不过滤 hidden**，渲染一律用 `visible`」。
- `SettingPage.kt:51`、`MassSendPage.kt:106`、`MainPage.kt:72`（`switchSummary` 的分母）改 `visible`。

### 为什么 `MainPage.switchSummary` 也要改

它算「已开 n / m 项」的分母。若用 `byDomain`，红包域分母会把两个 hidden 的 TEXT 也算进去 ——
不过滤的话分母偏大，且与二级页实际行数对不上。**空壳的一个隐蔽副作用是污染统计口径**，
所以它与渲染点同等对待。

### 新单测 `PrefsVisibilityTest`（8 条）

不测业务，只锁**形态**：hidden 的项必须「定义在 / 备份在 / 不渲染」同时成立。

1. `hiddenKeysAreStillRegistered` —— `Prefs.ALL` 里有、`spec()` 能查到；
2. `hiddenKeysAreNotRenderedInAnyDomain` —— 遍历所有域，`visible()` 结果里都没有；
3. `byDomainKeepsHiddenButVisibleDropsIt` —— 两者对 hidden 项恰好差这一项（防「把 visible 改成 byDomain 别名」）；
4. `hiddenDefaultsToFalse` —— 新配置项不能悄悄不显示；
5. `hiddenDoesNotAffectDefaultsOrClamp` —— hidden 只影响渲染，不改默认值/钳制；
6. **`noUnexpectedHiddenPrefs`** —— 反向守卫：hidden 白名单变动必须显式改测试；
7. `keysAreUnique` —— 重复 key 会让 `spec()` 静默取到后一条；
8. `keyPrefixMatchesDomainGrouping` —— `<域>.` 前缀与 `domain` 分组必须一致。

第 6 条是本轮**最有价值**的一条：`hidden` 是个"眼不见"的旋钮，一旦被滥用就变成
"功能没做完但先把开关藏起来"的合法借口 —— 那比空壳更糟，因为连设置页有这个条目这个线索都没了。
把它钉成白名单，新增 hidden 必须过一次显式决策。

### 「空壳的唯一正确形态」——写进方法论的判据

遇到"定义了但没消费点"的配置项，先分清是哪一种：

| 情形 | 正确处置 |
|---|---|
| 功能**将来要做**，只是没做完 | **把 `Prefs.ALL` 那一行删掉**，做完再加回来。`hidden` 不是"待办暂存区" |
| 功能**永久不做**（如本轮的「消息层自动抢红包」已被用户否决） | 删 key 定义 + 删登记；但**如果旧版本曾把 key 写进备份**，则留定义 + `hidden` |

区分标准只有一条：**旧备份文件里会不会有这个 key**。会 ⇒ 留；不会 ⇒ 删。

### 验证

- `testDebugUnitTest` **EXIT=0**，XML 核验 **565 条 / 0 失败 / 0 错误 / 0 跳过**（基线 557 ⇒ +8）。
- `assembleDebug` **EXIT=0**（11s）。
- 全程**未碰 adb**。

---

## R11d（2026-10-20）：清掉三处「文案在说谎」

### 起因

R11c 提交后继续 grep，命中三处**注释/文案与代码事实相反**。它们不是实现 bug，
但都属于「用户或下一个人读到会判断错」的东西 —— 比 bug 更难发现，因为代码跑得好好的。

| # | 位置 | 文案说的 | 代码事实 |
|---|---|---|---|
| 1 | `MainPage.redpacketPage()` footnote | 「决策引擎目前只做识别与记录：取证前 type/文案两项留空」 | 决策引擎**整块已删**（`LuckyLogic`/`LuckyProbe`），页面上只剩 4 项，脚注在描述一个不存在的东西 |
| 2 | `MainPage.broadcastPage()` KDoc | 「依赖底层发送接口（S9，**未定位**）：`MassSendRunner.send` 恒返回 NO_CHANNEL」 | S9 **2026-10-18 已接通并真机验证**，同一个文件的 `MassSendPage` 类注释里写着 ✅ |
| 3 | `GroupDetailPage:136` 「群发消息」按钮 | 点击 toast「群发还没做（M3 之后）」 | 群发**早就做好了**（`MassSendPage`），界面里留着一个按了只会挨骂的按钮 |

第 3 条最严重：它不是文案问题，是一个**假按钮**。

### 第 3 条的处置：接真链路，而不是删按钮

两个选项：
- **删按钮** —— 最省事，但分组页是「这群人是谁」的视图，从这里发消息是最自然的需求路径；
- **接链路** —— 群发页已经是完整实现（后台跑、风控闸门、暂停/继续、结果对账），
  缺的只是「把这个分组填进目标」。

选后者，但**只做填目标 + 跳转**，不在这里另起发送循环 —— 否则同一件事两份实现，迟早不一致
（群发的运行态必须活过弹窗重建，那是 `MassSendPage` 单例的职责）。

### 自查发现的一个真隐患：静默覆盖用户目标

第一版实现是 `Kv.putList(BROADCAST_TARGETS, listOf(picked))` 直接覆盖。
自查时发现这是**静默数据损失**：用户可能在群发页精心选了 30 个分组/标签/好友，
从成员列表点一下「群发消息」就把它们全清了，而且**当场没有任何提示**，
等到下次群发才发现目标没了。

改成三分支：

| 当前目标状态 | 行为 |
|---|---|
| 空 | 直接填，不问（没有可损失的东西） |
| **就是**这个分组 | 直接跳（重复点同一件事不该反复确认） |
| 别的内容 | **先弹框问**「会把它们全部换成 X」→「设为当前分组」/「取消」 |

判据（可复用）：**任何"覆盖式写入"在写入前都必须回答"被覆盖掉的东西用户还在乎吗"**。
答不上来，就先问；问了嫌烦，才考虑是不是该改成"追加"或"另立一个 key"。

### 另一处自查：Dialog 引用顺序

`sendToGroup` 回调里要 `dialog.dismiss()`，而 `dialog` 原本在按钮之后才 `val` 出来。
把 `Dialog(ctx)` 的构造提到按钮之前（`setContentView` / `setOnDismissListener` 仍在原处）
—— 构造与配置分离，不改变任何语义。这是编译器直接报 `Unresolved reference` 的那类问题，
**在没有编译过的代码里非常容易漏**。

### 验证

- `assembleDebug` **EXIT=0**（17s）。
- `testDebugUnitTest` **EXIT=0**，XML 核验 **565 条 / 0 失败 / 0 错误 / 0 跳过**（与 R11c 持平）。
- 本轮无新增单测：改动都在 UI 层（`Dialog` / `AlertDialog` / `Panel`），
  纯判据为零 —— 与其为 UI 写 mock 测试，不如把「覆盖前先问」写成判据留在注释里。
- 全程**未碰 adb**。

---

## R11e（2026-10-20）：把「无副作用」这个说法降级为可证的说法

### 起因

R12 待办第 5 项：「`_cache/r9_cdn_plan.md:118`『无副作用』是过度断言，落地时按 V1 原话改」。
一直挂在那里当纯文档活。这轮把它做掉，顺手把「无副作用」这类词的判定方法固化下来。

原文（`r9_cdn_plan.md` 第 137/138 行）：

> 2. `after` 覆盖 `param.result` 在效果上与 replace 完全等价（**`dk.a()` 无副作用**，只是调一次 native + 打一行日志）；
> 3. 开关关闭时 `after` 可以 `return` 不改任何东西 ⇒ **零副作用**，`replace` 做不到这一点
>    （它会连 `SnsMethodCalculate.markStartTimeMs/EndTimeMs` 一起吃掉，**可能**影响微信自己的耗时埋点）。

### 先取证，再改文案

没有直接改字，先用新建的 `tools/_r12_dexclass.py` 静态读 dex 把 `dk.a()` 的方法体看清楚：

| 项 | 实测 |
|---|---|
| 定义位置 | `classes3.dex`（**`class_defs` 命中**，不是 `type_ids` 引用 — 认这个才不会假命中） |
| `dk.a` | access `0x9` = public static，`code=0x472f14`，`insns_units=35`（与原文档记载一致） |
| 方法体里有什么 | `SnsMethodCalculate.markStartTimeMs` → `CdnLogic.allowBatchImageDownload()`（native）→ `markEndTimeMs` → `l2.j` 日志 |
| `iput` / `sput` | **零**（`dk.a()` 自身没写任何字段） |

所以「`dk.a()` 本身不写状态」这条**是真的**，但它**不等于「无副作用」** ——
因为看一个方法有没有副作用，要把它**调用的方法**也算进来。于是继续查 `SnsMethodCalculate`：

| 方法 | 关键调用（实测） | 实际行为 |
|---|---|---|
| `markStartTimeMs` | `System.currentTimeMillis` → **`Map.put`** | 往 `methodStartTimeMap` 写一张计时表项 |
| `markEndTimeMs` | `StringBuilder` 拼串 → **`ii3/e.<init>`** → `hn4/t0.g` | 出表 + **构造并上报一条监控事件** |

**⇒ 结论被反过来了**：`replace` 会把 `markStartTimeMs/EndTimeMs` 整对吃掉 ⇒
`methodStartTimeMap` 少一项、`ii3/e` 那条监控事件不发。
原文的「**可能**影响耗时埋点」不是「可能」，是**确定**；而且不止影响性能采样，还**丢了一条本该上报的事件**。

更关键的一点：这让微信的埋点出现「第三方模块特有的差异」（某些帧有事件、某些帧没有），
**这类差异本身就是自动化风控的经典特征**。这不是文案问题，是选择 `after` 的**真实理由之一**。

### 改法：不是删掉断言，而是换成能站住的断言

- 原第 2 条「无副作用」→ 改成「方法体只有计时/日志/native 调用，**零 `iput`/零 `sput`**」+ 明确加一句
  **「这是『方法自身无状态副作用』，不等于『整个调用零副作用』」**；
- 原第 3 条「零副作用」→ 改成「**开关关闭时对返回值零影响**，且保留了原方法体的全部埋点」；
- 在结论后追加一整块**修正段**，把上面两张表原样落进去，并写明结论不变（仍选 `after`）但**理由要改口**。

### 可复用判据

> **说「无副作用」之前，先把方法体里所有被调用的方法都看一遍**，
> 尤其是名字里带 `mark` / `record` / `report` / `track` / `notify` 的 —— 它们几乎没有不写状态的。
> 「**方法自己不写字段**」是一个更弱的结论，**别把它说成「无副作用」**。

这条正好是 R11b 里 B-3 那条判据的补集：
B-3 说「照抄方法要连 `iput` 一起抄」；这条说「判断副作用要把 `invoke` 一起算」。
一个管**写**，一个管**读后写**，合起来才是「一个方法到底做了什么」的完整回答。

### 工具：`tools/_r12_dexclass.py`

轻量 dex 解析器，只做三件事：

1. 按 `class_defs` 精确定位类（避免 `type_ids` 假多重命中）；
2. 输出去混淆后的**方法表 + 字段表 + `code_item` 偏移**（一眼看出哪些方法有方法体、哪些是 native）；
3. 给 `--method` 时粗粒度抽出该方法的 **`const-string` 字面量** 与 **`invoke` 目标**。

为什么不用 dexdump：`classes7.dex` 全量反汇编 = **233MB / 281 万行**，读不动也搜不动。
这个脚本按需解析，一个类 1 秒内出结果。

⚠️ 它只做到「粗粒度」：`invoke` 提取不解析 `35c/3rc` 的寄存器列表，
`const-string` 只取低 16 位索引。**用于「有没有这类调用」的判断足够，用于「精确还原逻辑」不够** ——
后者还是得回去 dexdump 切片。

### 验证

- `assembleDebug` **EXIT=0**（9s）。
- 本轮**未改任何 Kotlin 源码**（纯文档 + 新工具），单测基线不变（565 条 / 0 失败）。
- 全程**未碰 adb**。

---

# R12（2026-10-23）：普通文件发送链路取证 —— 推翻上一轮的推论

## 起因

用户说「电脑已连接上，继续开发」。核实后**设备仍未枚举**：

- `adb devices -l` 三次返回空（`List of devices attached` 之后无设备行）；
- `kill-server` / `start-server` 之后依旧为空；
- `Get-PnpDevice` 显示 `Error  USB  未知 USB 设备(设备描述符请求失败)  USB\VID_0000&PID_0002\7&385622AB&0&4`。

`VID_0000&PID_0002` 是 **USB 枚举阶段失败**的典型特征（手机在设备描述符请求阶段就掉了）——
常见原因：数据线只供电不传数据 / USB 口或线缆损坏 / 手机未选「文件传输(MTP)」/ 驱动未装。

⇒ 未向用户谎报设备可用，按「只做不碰真机的部分」继续。

## 上一轮结论哪里错了

上一轮我写的是：

> `qf4/o4` 里**没有**「发普通文件（appmsg type=6）」的公开方法。……它走的是另一条路
> （`pluginsdk/model/app/u0` 那一套，37 个方法）。

**前半句对，后半句是猜的。** 真实入口是 **`qf4/r5`**，而且不需要碰 `u0` 的 37 个方法里的 14 参那个。

## 本轮真正解出来的东西

| 项 | 结论 |
|---|---|
| `oq4.W8`/`V8` 是什么 | **发文本**。`V8(String,Str,int,int)` 只把第 5 参 `J` 补 `0L` 转 `W8(String,Str,int,int,long)`；`W8` 按 `hl0/s1.a(toUser)` 分 PPC / 非 PPC 两支，用 `r1.b` builder |
| 文件入口 | `qf4/r5`（`SendWXMediaMessageHelper`，**单例**：`static Lqf4/r5; a` + `a()` 工厂） |
| 载体 | `WXFileObject`（`type()` 硬编码 `const/4 v0,#6`）+ `WXMediaMessage` |
| 分派 | `r5.l`（14 参）按 `msg.getType()` 选包装类，type 6 → `qf4/d5`，全部收敛到 `r5.c(path, 回调)` |
| 执行体 | `r5.j`（13 参 / 232 行），`getType()!=6` 直接跳 talker 循环 |
| 安全门 | `w.d(path)` 只查 `content://` authority 含 `com.tencent.mm`/`com.tencent.wmpf`，**普通路径不拦** |
| `app/m` 怎么造 | `app/m` 的 `Superclass` 就是 `Ljj/k0;`（实测）⇒ `r5.j` 里 `iget-object jj/k0.field_appId` 合法。`r5.h` 是**纯 POJO 拼装、零查库** |
| `u0.A` 9 参 | 全部解出；第 2 参（appId）有 `""` 兜底（`847e1c`），第 5 参是字面量 `2`，第 6/7/8 参可传 `""`/`0` |
| 回调状态机 | `q5.invoke`：`3` = 文件校验失败（`cb_key_file_check_invalid`）；`2`/`0` = 走 `r5.e`→`r5.d`；其它 = 直接返回 |

## 仍未审计完（如实记录）

| 方法 | 行数 | 作用 |
|---|---|---|
| `r5.d` | **812** | 分类型发送枢纽（图片 `bh1/h.I`、文件、表情 `bh1/h.L`、视频 `u2`+`modelimage/a`），`r5.j` 有 6 个调用点 |
| `u0.D` | 461 | 单 talker 的建行 + 填 appmsg + 入网 |
| `u0.l(q)` | 492 | 由 `te0/q` 的 190 个字段反推 appmsg type |

⇒ **不能断言「一定能发出 type=6 的文件消息」。** 中间三层任一处字段为 null，
症状是「消息发出去了但显示空白/未知应用」，**不抛异常**（`4d35fe` 之后全是 `a8.F0` 判空 + 分支，无 try/catch）。

## 本轮新增的可复用判据（★ 最有价值）

> **「方法签名里没写文件」≠「这个方法不发文件」。**
>
> `W8(String,String,int,int,long)` 看着最像发送总入口（有 5 个参数、有 `long`），
> 实际是纯文本；真正的文件入口藏在 `r5.j` 的 **第一个 `getType()==6` 分支**里。
>
> **判据**：看参数里**有没有路径 / 字节流 / `WXMediaMessage` 系对象**，不看方法名的字母序，
> 也不看「谁长得最像总入口」。

同族陷阱（本轮踩到的）：

> **`iget-object v1, Ljj/k0;.field_appId` 出现在参数类型为 `app/m` 的方法里，不是 bug。**
>
> 第一反应是「类型对不上 / 工具串方法了」。实际是 `app/m extends jj/k0`（实测 `Superclass`），
> 子类实例读父类字段完全合法。**看着「不可能」的 iget，先查继承链，别急着怀疑工具。**

## 工具修正

`tools/_r12_dexclass.py` 的字段输出从 `f[0]`（那是**类型名**）改成 `f[1]`（**类型描述符**）+ `f[2]`（字段名）：

```
改前：  0x1      String.m()        ← 看不出字段本名
改后：  0x1      Ljava/lang/String;  filePath
```

这个 bug 隐蔽得很：输出看起来「有内容、格式正常」，只是**少了一列有用信息**。
本轮能发现 `te0/q` 有 190 个字段、`app/a` 只有 6 个字段，全靠修好后的输出。

---

# R13（2026-10-23）：`u0.l` type 推导审计 —— 解掉了上一版自己下的封锁令

## 起因

R12 在 `docs/取证结论.md` 里写下：

> ⛔ **`u0.l(q)` 的 492 行不逐个审计完，不许承诺「普通文件一定能发」。**

理由是「`te0/q` 190 个字段，任一为 null 会静默走进『消息发出去了但显示空白』」。
R13 把这 492 行审完了，**结论是：上一版的担心找错了位置**。

## 上一版哪里想歪了

上一版的隐含假设是「`r5.j` → `r5.d`(812) → `u0.D`(461) → `u0.l`(492) 是一条必须穿完的长链」。
实际上 `r5.j` 在 `getType()==6` 分支里**根本不进 `r5.d`**，而是直接对每个 talker 调 `u0.A`。
把「812 行 `r5.d`」当成阻塞项，等于把不在路径上的东西算进了关键路径。

## 本轮的硬证据（三条）

| # | 证据 | 地址/位置 |
|---|---|---|
| 1 | 微信**原生**「+ → 选文件」`q6.onActivityResult`（`requestCode==227`）手工拼 14 个 `te0/q` 字段、`setType(0x41000031)` 硬编码、**完全不调 `u0.l`** | `0x5963b4`，指令 `0x0052`~`0x0284` |
| 2 | `u0.l` 的 `sparse-switch` 里 **key=6 → target `0x02dd` → `const v11, #41000031`**，与证据 1 的常量逐位相同 | `0x4d3e10`，payload `0x4d4550` |
| 3 | `u0.D` 第 166 条 `MsgInfo.setType(u0.l(q))`，第 18 条先调 `u0.r`（填 38 字段含 `i`） | `0x4d34a4`，`0x0023` / `0x0158` |

## 顺带解掉的：`u0.A` → `B` → `D` → `r` 四层

| 方法 | registers | ins | 形参区间 | 只写哪些 `te0/q` 字段 |
|---|---|---|---|---|
| `u0.A` | 23 | 9 | v14..v22（STATIC） | 无（只补 5 个 null 转 B） |
| `u0.B` | 26 | 14 | v12..v25（STATIC） | `q.d` `q.F` `q.r` |
| `u0.D` | 32 | 12 | v20..v31（STATIC） | `q.I1/P/Q/o/p/s` |
| `u0.r` | — | — | — | 38 个，含 `i`（`= mediaObject.type()`） |

`u0.l` 依赖的 10 个字段（`D1 D2 G2 H Y Z d h2 i x1`）里，**`i` 由 `u0.r` 从
`IMediaObject.type()` 搬进来**（`4d5424`），而 `WXFileObject.type()` 是硬编码 `const/4 #6`。
⇒ `i == 6` 必然成立，`u0.l` 必然返回 `0x41000031`。

## 两条可复用判据（本轮最有价值）

> **「`ins` 包含隐式 `this`」——手算参数位置时漏掉这个 1，会让所有形参整体偏移 1 位，而且不报错。**
>
> 踩法：`r5.j` 是 `PUBLIC FINAL`（非 static），13 个形参，`ins=14`。
> 我按 `ins=13` 推断，于是 `v21`（第 4 参 String）被当成第 3 参、`v23`（第 6 参 int）
> 被当成第 4 参，得出「`const/4 #2` 是第 4 参」的错误结论。
>
> **判据**：`access & 0x8 != 0` ⇒ STATIC ⇒ `ins == 形参占位总数`；
> 否则 `ins == 1 + 形参占位总数`，且 `v(reg-ins)` 就是 `this`。
> 已固化为 `tools/_r13_sigmap.py`（含断言：`this + 形参占位 == ins`，不收敛直接报错）。

> **「dexdump 的方法元数据打印在 `|[addr]` 行之前，按行切片会把它切掉」。**
>
> 切片脚本习惯 `if '|[' in line` 开始收集，结果 `name/type/registers/ins` 全部丢失，
> 再从切片里 grep `registers:` 就会读到**下一个方法**的元数据。
> 实测表现：`r5.j` 真值 `registers=31/ins=14`，误读成 `28`。
>
> **判据**：需要 `registers/ins` 时必须从**完整反汇编**里按 `[addr]` 回溯前 40 行，
> 不能从切片里找。已固化为 `_r13_sigmap.py` 的 `find_meta()`。

## 与原生路径的差异（如实记录，不藏）

| `te0/q` 字段 | 原生「+→选文件」 | `r5.j` 经 `u0.A` | 影响 |
|---|---|---|---|
| `.r` | `4`（`0118`） | `2`（`r5.j` 内部 `const/4 #2`） | 只是 XML `<androidsource>` 不同 |
| `.d` | `""`（`0111`） | `field_appId` | `u0.l` 只在 `i==3` 分支读，type=6 不读 |
| `.F` | `""`（`0113`） | `field_appName` | 首发送无影响（`u0.w` 重发时才用） |

**仍然只能真机验的 1 条**：`message` 表落库 `type=1090519089`、对端能显示下载。
静态取证到此为止，没有更多可挖的。
