# R73 修复 · 聊天页「最新一条消息」长按不出「转发（模块）」入口

> 实现子代理：r73-latest-msg-fix（hy4flash 临时规则；Pro 类本应 deepseek-v4-pro，台账需注明）。
> 宿主：微信 8.0.48 / versionCode 2588，包 com.tencent.mm；模块 com.lx.wxgroup。
> 项目根：`wxgroup/`；文档根：本目录。纪律：不碰 adb / 设备 / gradle / commit，不跑单测（闸门由主会话统一执行）。
> 红线：不改 NativeMenuGate 四条件逻辑本身、不碰 R73-L6 快照入参链、不碰 add/menu 参数与文案、缓存带生命周期、全程 runCatching 不崩宿主。

真机铁证（主会话 2026-11-20 01:19）：

```
01:19:02.352 FF NATIVE gate closed enabled=true age=127 talker= ref=false   ← 最新一条（失败）
01:19:11.522 FF NATIVE added id=1464270849 size=10 group=1464270848        ← 历史一条（成功对照）
```

定性：`age=127` 排除时间窗；有 `gate closed` 说明 `q.p` 确实被调用、菜单走了原生构建链，排除「菜单没走 q.p」。
根因收敛为：最新一条 `msgRefOf(pressedView())` 返回 null ⇒ `hasRef=false` ⇒ 门禁挡死；历史消息正常。
据只读取证 `docs/_r73_entry_presence_forensics.md` §8，最可能失败点是 **失败点 B（positionOf 对末端消息返回 -1 / getItem 越界落 footer）**——最新消息是列表最底、唯一紧邻 footer/边界那条。

---

## 一、可行性取证（方案选择依据）

任务决策规则：若项目内 dex 反汇编产物能枚举出全部 `g0` 宿主类清单且签名统一 → 用 g0 缓存 h9 方案；否则退而修 `msgRefOf` 失败点 B。本节给出取证结果。

### 1.1 取证产物与方法

- 产物：`_cache/_s9dump/classes*.txt`（classes / classes2..15，共 15 份，`dexdump` 输出，约 1383 万行）。
- 方法：Python 逐文件解析 `Class descriptor` 与 `name : 'g0'` 后紧跟的 `type : '...'`，筛出参数含 `Lcom/tencent/mm/storage/h9;` 的方法；统计签名去重、类继承关系。脚本未写入项目（临时 `/tmp`）。

### 1.2 结果：可枚举、签名统一

| 指标 | 实测 |
|---|---|
| 全 dex 声明 `g0` 的方法数 | 551（含非消息类） |
| 其中第 4 参为 `Lcom/tencent/mm/storage/h9;` 的 `g0` | **134 个类、134 个方法** |
| 去重后的签名种类 | **1 种**（完全统一） |
| 统一签名 | `g0(Lva4/r3;Landroid/view/View;Landroid/view/ContextMenu$ContextMenuInfo;Lcom/tencent/mm/storage/h9;)Z` |
| 分布 | `com.tencent.mm.ui.chatting.viewitems.*`（133 个）+ `ChattingItemDyeingTemplate`，跨 classes3/4/5/7/14 |
| 继承关系 | 全部继承公共基类 `viewitems/s0`（s0 自身也声明同签名 g0）；子类各自 override ⇒ 不能只 hook 基类，需逐类 hook |

结论：**满足「可行」判据**（可全量枚举 + 签名唯一）⇒ 选 g0 缓存方案。

### 1.3 为何 g0 缓存点对本问题「时序完美」

构建链（`docs/_r73_native_menu_inject.md` §2.1）：
`View.performLongClick → wg4/a.onItemLongClick → r3.clear() → onCreateContextMenu → g0(r3,view,info,h9) → q.n→q.o→q.p`。

- **g0 在 q.p 之前执行**：故 g0 缓存的 h9 在门禁（`onShowGridMenu:338`，q.p before）时已就绪；
- **点击发生在菜单显示之后**：缓存的 h9 在点击快照（`snapshotOf:811`）时仍可用（只要生命周期未失效）；
- **关键事实**：最新消息出现 `gate closed ref=false` 本身就证明 q.p 被调用、菜单已构建 ⇒ 该消息的 g0 **一定带着正确的 h9 被调用过**（即使 g0 内部 early-return，入口参数仍可取）。这正是 View 链取不到 h9 时、g0 参数仍可靠的根本原因。

### 1.4 冷启时序风险与处置（不回避）

`docs/_r72b_mount_diagnosis.md` 已证：业务 dex 晚于 `handleLoadPackage` 挂载，冷启直接 `findClass` 这 134 个类会 `ClassNotFoundError`。
ForceForward 无独立 `turn(true)` 挂载钩子（`probe("on")` 仅翻 `enabled` 布尔）。因此采用**延迟挂载**：

1. **主触发**：在 `View.performLongClick` before 回调（trap）首次命中时（此时微信已运行、dex 全量可见，且早于同一次长按的 g0 调用）同步安装 g0 hook，`AtomicBoolean` 保证只挂一次。这样**第一次长按即可命中**；
2. **辅触发**：`probe("on")`（`op=forward sub=on`）也调一次挂载，对齐 team-lead「turn(true) 时 mount」的指导；
3. 单类 hook 失败只 WARN、不阻断其余类（134 个中挂上多少用多少，日志报 `ok=`）。

风险残留（诚实声明）：首次长按需一次性挂载 134 个方法，主线程有一次性开销（实测待真机确认，量级百毫秒）；若某类签名漂移，该类 hook 失败但其余照常，且 `msgRefOf` 原路径仍是兜底。

### 1.5 缓存生命周期设计

- 缓存体：`WeakReference<Any>` 指向 g0 第 4 参 h9（数据对象，宿主 adapter 持有；弱引用在消息被移出/删除时自然失效 ⇒ 安全降级为 null，不会误转旧消息）。
- 清空：新一次 burst 开始（`performLongClick` before 中 `now - burstStartAt > BURST_GAP_MS`）时清空 ⇒ 防跨消息串味。
- 时效校验：`cachedG0At >= burstStartAt` 才认，杜绝上一轮遗留缓存被本轮误用。
- 门禁与快照两处统一走 `cachedRef()`（把缓存 h9 复用 `msgRefFromH9` 反射出 MsgRef）。

### 1.6 方案对两处依赖的覆盖（team-lead synthesis 硬要求）

| 依赖点 | 原逻辑 | 改后（只改 ref 取源，门禁四条件不变） |
|---|---|---|
| 门禁 `onShowGridMenu:338` | `hasRef = v!=null && msgRefOf(v)!=null` | View 链 ref 为 null 时再取 `cachedRef()`；`NativeMenuGate.shouldInject` 四条件一字不动 |
| 点击快照 `snapshotOf:811` | `ref = msgRefOf(v)` | `ref = msgRefOf(v) ?: cachedRef()`；ref 决定 kind/text/imgPath/msgId/msgSvrId 全部链路原样复用 |

两者都因「ref 非空」而恢复，避免「按钮出来但点下去 SKIP」。

[DONE-RECON]

---

## 二、实现改动

**选定方案：g0 入口缓存 h9（方案一）**，未改 `msgRefOf` 本身（失败点 B 的其它兜底留作后续真机必要时再补）。

改动文件仅两个：`ForceForward.kt`（生产）、新增 `R73LatestMsgFixTest.kt`（纯 JVM 单测）。行号为改动后。

| 位置（改后行号） | 改动 | 说明 |
|---|---|---|
| `ForceForward.kt:32` | 新增 `import java.util.concurrent.atomic.AtomicBoolean` | 延迟挂载幂等标志 |
| `:153-215`（清单起于 `:165`） | 新增 `G0_HOST_CLASSES`（134 个类名，dex 全量枚举冻结）+ KDoc | 统一签名 `g0(r3,view,info,h9)Z` |
| `:248-256` | 新增 `cachedG0Ref: WeakReference<Any>` / `cachedG0At` / `g0RefHooksMounted` | 降级缓存状态 |
| `:309-337` | trap 内新 burst 清 `cachedG0Ref/cachedG0At` + `if (newBurst) installG0RefHooks()` | 生命周期清空 + 延迟挂载主触发 |
| `:339-383` | 新增 `installG0RefHooks()` | 逐类 findClass + hookAll "g0"，callback 缓存 `args[3]`（h9）；全程 runCatching、单类失败不阻断 |
| `:452-456` | 门禁 `hasRef = viewRef != null \|\| cachedRef()!=null` | **只改取源**，`NativeMenuGate.shouldInject` 四条件一字未动 |
| `:931-932` | 快照 `ref = msgRefOf(v) ?: cachedRef()` | 两处依赖统一降级 |
| `:1011/1018-1034` | `msgRefOf` 第 5 步抽成 `msgRefFromH9(h9)` |  View 链来源与 g0 缓存来源字段完全一致 |
| `:1037-1052` | 新增 `cachedRef()` | 弱引用解引用 + `cachedAt >= burstStartAt` 时效校验 |
| `:1668-1672` | `probe("on")` 调 `installG0RefHooks()` | 辅触发（对齐「turn(true) 时 mount」），幂等 |

**未碰（红线）**：`NativeMenuGate.shouldInject` 四条件、`snapshotNow()` 仍走 `staleViewForProbe()`、`staleViewForProbe()` 实现、`installItemClickBind/installNativeMenuInject`、`onShowGridMenu` 的 `menu.add`/参数、`LABEL_FORWARD="转发（模块）"`。

## 三、单测

新增 `app/src/test/java/com/lx/wxgroup/feature/R73LatestMsgFixTest.kt`，**12 个纯 JVM 用例**（零 Android / Xposed / 设备依赖）：

- 纯逻辑等价复刻（6）：最新一条 View 链失败走 g0 缓存（门禁放行 + 快照带真 msgId）、View 链优先不被串味、两侧都失败安全降级、新 burst 清缓存、早于 burst 的缓存拒绝。
- 源码字符串断言（6）：门禁/快照两处走 `cachedRef`、134 类清单齐全、trap 触发延迟挂载且逐类 hook、新 burst 清缓存 + 时效校验、两来源共用 `msgRefFromH9`、红线未破。

**变异有效性**：把降级取源改回原样（`hasRef` 去掉 `cachedRef()`、`snapshotOf` 去掉 `?: cachedRef()`）→ `gateUsesG0CacheFallback` / `snapshotUsesG0CacheFallback` 变红；去掉新 burst 清缓存 → `newBurstClearsG0Cache`（源码断言）变红；去掉 `installG0RefHooks()` 调用 → `trapTriggersDeferredG0Mount` 变红；改回旧文案 → `redLinesUntouched` 变红；去掉 P1 的 `ok == 0` 回滚分支 → `zeroHookedResetsIdempotentFlag` 变红。注：`staleCacheRejected` 是纯逻辑等价复刻的自证规约锁，不读生产源码，不随上述生产变异变红。本代理未跑 gradle，闸门由 team-lead 统一执行。

## 四、真机确认判据（供 team-lead）

1. **入口出现**：长按最新一条，抓 logcat 应见 `FF g0-ref mounted classes=134 hooked=...`（首次长按，一次性）后出现 `FF NATIVE added ...`；不应再是 `gate closed ... ref=false`。
2. **点击可用（不 SKIP）**：点「转发（模块）」后应见 `FF forward clicked kind=TEXT|IMAGE ...`（非 UNKNOWN），并进入选人器与发送；若 kind 仍 UNKNOWN 说明缓存未命中（检查 hooked= 是否为 0 或 g0 是否被该消息类型调用）。
3. **不串味**：长按一条历史消息后立即长按最新一条，确认转发的是各自消息（msgId 不同）——验证新 burst 清缓存生效。
4. **不崩宿主**：首次长按挂载 134 个 hook 无 ANR / crash；个别类签名漂移时 `hooked=` 小于 134 但其余正常。
5. 若首见 `FF g0-ref mount ok=0 will retry ...`：P1 回滚已生效，标志复位，下一次长按会自动重试挂载——**不要据此立即判失败**，先看后续长按是否转为 `hooked=>0` 且入口出现。仅当连续多次长按始终 `ok=0` / `hooked=0`（业务 dex 始终不可见或 findClass 全失败）才说明延迟挂载时机不足，需退到「修 msgRefOf 失败点 B」备选方案（文档 §1.2 已记录可行性）。

[DONE]
