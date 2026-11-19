# R72b-L2 · MFP 只读探针真机前独立交叉复核

> 复核角色：R72b-L2 fresh-eyes 对抗性复核子代理（`r72b-l2-review`）
> 日期：2026-11-19　工作目录：`C:\Users\lx\WorkBuddy\2026-09-29-20-23-21`
> 纪律：只读 —— 不改源码、不碰 adb/设备/gradle、不 commit。
> 复核对象（当前 git diff）：
> - `wxgroup/app/src/main/java/com/lx/wxgroup/probe/MicroxFwdProbe.kt`（新建，278 行）
> - `wxgroup/app/src/main/java/com/lx/wxgroup/HookEntry.kt`（+8，探测区加 install）
> - `wxgroup/app/src/main/java/com/lx/wxgroup/entry/EntryReceiver.kt`（+13，`mfwd` 分支）
>
> 判据出处：`docs/_r72_microx_forward_parse.md` §6（H1~H6 + 四条判据）、
> `docs/_r71_g1_native_forward_verdict.md` §3（FNP 签名/地址）、`docs/_r36_forward.md` §1.1/§6（读取时点、G1 链）。
>
> **总判定：需修后装机**（详见 §8；完成 F1 代码修复 + F2/F3/F4 判据说明后可装机上真机）

---

## 0. 复核范围与方法

- 方法：对 3 个文件逐行读 `git diff` + 全文；对照 R72 §6 判据清单、R71 §3 FNP 签名、R36 §1.1/§6 读取时点；用 `grep` 精确核「写宿主痕迹 / 主动调用 / enabled 守卫 / 接线位置 / 工作区 diff」。
- 反汇编地址（来自 R71 §3，本次不重新反汇编，按已核实 ① 级采用）：`hl0/q0.<init>(S,S,I,I,J)`@classes7 `0x711560`、`q0.getType()I`=const/16 #522 @`0x710f5c`、`hl0/l1.a()Z`@`0x710360`。
- 证据纪律：下面每个结论都给 `文件:行号` 或文档出处；仅作逻辑推演，不臆断 dex 内容。

## 1. 只读纪律（PASS / FAIL）

**判定：PASS。** 未发现任何会写宿主对象或替宿主发消息的调用。

| 检查点 | 结果 | 证据 |
|---|---|---|
| `param.args[i] =` / `param.result =` | 无 | 全文件 grep 仅 0 命中；6 个回调只读取 `param.args` / `param.thisObject` / `param.result` 的值 |
| 主动调用宿主业务方法 | 无（仅允许项） | 全文件 `callMethod` **仅 1 处**：`MicroxFwdProbe.kt:86` 的 `getType()`，即 R71 FNP 已用纯 getter，无副作用；未出现 `q1.b()` / `l1.a()` / 发送方法的主动 `callMethod`/`invoke` |
| 反射写字段 | 无 | 无 `setIntField/setObjectField/setLongField` 等；所有反射为只读（`getLongField`/`getIntField`/`getObjectField`/`f.get*`） |
| 「真调」入口 | 无 | 无 `sub=test`/`sub=send` 分支；EntryReceiver 仅 `on/off/status`（`EntryReceiver.kt:235-243`） |
| 回调是否纯打印 | 是 | H3 before/after `:64-93`、H4 `:102-107`、H1 `:116-122`、H2 `:134-140`、H5 `:150-162` 体内只 `LogX.i/w` + 只读反射 |

唯一需点出但**不构成违规**的点：H3 after 的 `readLongField`（`:84`、实现 `:224-241`）会 `f.isAccessible = true` 遍历宿主对象字段。这是反射读的必要动作，不写值；`setAccessible` 作用于宿主自身类字段（非 framework hidden API），Android 上可用。**结论：只读纪律达标。**

## 2. hook 签名正确性（PASS（H3/H1/H5）/ 带保留（H4/H2））

| Hook | 判定 | 证据与推演 |
|---|---|---|
| **H3** `q0.<init>(S,S,I,I,J)` | **PASS** | `:63` 用 `XposedBridge.hookAllConstructors` 而非 `findAndHookMethod` 精确匹配，正好规避 R71 踩过的 NoSuchMethodError 坑（`:58-60` 注释亦说明）。5 参类型过滤在 `:67-69`（`size==5` + 两个 String + 两个 Int + 一个 Long），严格。fallback 类名 `:62` 为 `listOf("hl0/q0","com.tencent.mm.storage.j9")`，`:208` 注释解释必须带 `com.tencent.mm.` 全前缀否则解析成 `storage$j9` —— **但 `com.tencent.mm.storage.j9` 这个 fallback 在仓库现有材料中无 dex 可独立核验**（WeChat dex 不在本仓库）；好在 primary `hl0/q0` 是 R71 在同机型实测命中，fallback 不阻塞装机。 |
| **H4** `l1.a()Z` | **PASS（低保留）** | `:101` `findAndHookMethod(l1,"a",cb)` 未传参数类型。XposedHelpers 无参类型的重载会走 `findMethodExact(clazz,"a")`（等价于 `getDeclaredMethod("a")`），**只匹配无参 `a()`**，不会触发「同名多参重载冲突」；R71 §3 已证 `hl0/l1.a()Z`@`0x710360` 且声明在 l1 自身。保留点：若 `a()` 实际继承自父类，findMethodExact 会抛 NoSuchMethodError → 被 `guard` 捕获记 FAIL（`:199-206`），不会崩，但判据①的半条（`l1.a queued`）会缺失。概率低（R71 反汇编钉死在 l1），可接受。 |
| **H1** `q1.f(hl0.m)` | **PASS** | `:115` `findAndHookMethod(q1,"f","hl0.m",cb)`：参数类型用字符串类名是 Xposed 标准用法，由 `q1` 的 classLoader 解析；`"hl0.m"` 点号形式 `XposedHelpers.findClass` 兼容。签名与 R36 §1.1「setter 是 `q1.f(m)`」一致。 |
| **H2** `m.<init>` 选参数最多重载 | **保留（中等）** | `:129-136` 取 `declaredConstructors` 中参数最多者，再 `param.args.size != maxParams` 跳过其余。**这是启发式，可能漏掉真正被微X使用的构造器**：若 `m` 既有 6 参构造又有被实际使用的无参构造（我方 G1 就是 `new m()` 后逐字段写，见 R36 §1.1 默认值表 + §55 序列），6 参 maxParams=6 ⇒ 无参构造被 `:136` 跳过 ⇒ **H2 零日志**。后果：判据②若依赖 H2 会得到「微X没构造 m」的假阴性。**缓解**：`mFields()`（`:243-249`）反射按字段名读 a~f，H1 在 `q1.f` 处同样打印六字段，故判据②应以 H1 为准，H2 仅佐证（详见 §3）。 |
| **H5** `q1.b()` before 读私有字段 `h` | **PASS（读取时点正确）** | `:149` `findAndHookMethod(q1,"b",cb)` 匹配无参 `b()`（R36 §6 列 `q1.b()` 兜底入队，存在）。读取时点选在 `beforeHookedMethod`（`:150-162`）**是对的选择**：R36 §1.1 序列 `q1.f(m)` → 直写 `h=4/13` → `q1.b()`，故 `b()` 调用前 h 已被直写；反例见 R71 §3 `FNP q1.f subtype=0`（在 `q1.f` 的 after 读，太早读到 0）。`getIntField("h")` 先读、失败回落 `getObjectField("h")?.toString()`（`:153-159`）合理兼容 int/装箱。**但覆盖路径有缺口**：只 hook 了 `b()`，未 hook 主路径 `q1.a()`（R36 §6 明确 `q1.a()→l1.a()` 为主、`b()` 只在 a() 拿不到 l1 时用）——见 §3/§6。 |

## 3. 判据可证伪性（PASS / FAIL，重点）

**总体判定：带条件 PASS —— 代码能产出判据所需日志，但「无日志」的反例解释缺少前置校验，存在误判风险。**

### 3.1 `newMsgId` 不能当硬判据（明确定性：仅参考）

- 实现：`readLongField(obj,"msgId","field_msgId")`（`:84`）→ 先试 `msgId`/`field_msgId` 且要求非 0（`:225-227`），失败则向上扫 6 层父类、**返回第一个非零 `long` 字段**（`:229-240`）。
- 风险坐实：`q0` 是 NetScene 请求对象，并非落库后的 MsgInfo。其 `long` 字段可能含 `createTime`/`field_createTime`/`timestamp`/`svrId` 等；fallback 扫描**不校验字段名语义**，按声明顺序取第一个非零值 ⇒ 完全可能取到时间戳而非消息 id。R71 §3 真机日志 `newMsgId=19724386` 仅说明该次恰好取到像 msgId 的数，**不能证明算法总是取到 msgId**。
- **定性结论**：
  - `newMsgId=Y` **不可**作为「微X落库了新消息」的硬判据，只能当逐帧参考值。
  - 判据①的**硬证据必须是同行的 `type=522`（来自 `getType()`，R71 §3 已钉死 = const/16 #522）+ `l1.a queued=true`**（`q0 ctor` 行 `:88-91`、`l1.a` 行 `:105`）。
  - 主会话若看到 `newMsgId=Y≠0` 就宣布「微X落库成功」属于**误判**；必须在判据说明里写明「newMsgId 仅参考，type=522 + queued=true 才是判据①」。

### 3.2 每条判据的「日志=结论」与反例覆盖

| 判据 | 日志→结论（探针侧） | 反例覆盖情况 | 判定 |
|---|---|---|---|
| ① 走原生链 | `MFP q0 ctor ... type=522` + `MFP l1.a queued=true` ⇒ 与 G1 同链 | 文档有「无 FNP/MFP 日志 ⇒ 微X走自管线程」（R72 §6.2），但**探针默认关 + 需手动 on**，`status()` 回显是唯一前置校验，判据正文未写明「先看 `op=mfwd` 回显 enabled=true/挂上没挂上再解释空日志」 | **保留**：空日志既可能是「微X没走原生链」也可能是「探针没开/H3H4没挂上」，二者不可分 |
| ② m.a(imgId) | `MFP q1.f m[a=... b=... f=...]` ⇒ a≠-1 有 CDN imgId 路径 | a=-1 ⇒ 同 G1 无标记根因（R72 §6.2 已覆盖）。但 H2 可能零日志（§2）；且若微X根本不调 `q1.f`（没挂 ForwardInfo），同样无 H1 日志 ⇒ 不能反推 a=-1，只能说「没挂 ForwardInfo」 | **保留**：判据②应只读 H1 的 m 六字段，且「无 q1.f 日志」须单列一种结论 |
| ③ 子类型 | `MFP q1.b subtype=4/13` ⇒ 原生转发语义 | **H5 只 hook `q1.b()`**。微X若走主路径 `q1.a()`（R36 §6：a() 为主、b() 兜底），`q1.b()` 根本不调 ⇒ H5 零日志 ⇒ 读不到子类型。文档仅写「读到别值⇒自定义流程」，**未覆盖「无 H5 日志」** | **保留（中）**：判据③有路径性盲区，见 §6 |
| ④ 菜单锚点归因 | H6 未实现 ⇒ 该判据在探针侧**不存在** | 见 §6 | 降级，非探针缺陷 |

**新增的关键反例风险（跨判据）**：探针默认静默，若主会话在 `enabled=false` 或 `H3/H4 FAIL` 状态下点微X转发，`MFP` 日志全无，极易被误读成「微X没走原生链」这一**假阴性**。修复见 §8 指令 F4。

## 4. 默认关 / 冷启静默（PASS）

| 检查点 | 结果 | 证据 |
|---|---|---|
| `enabled` 默认 false | ✓ | `MicroxFwdProbe.kt:40-41`（`@Volatile var enabled = false`） |
| 回调第一行 return，零反射零拼接 | ✓ | 6 个回调首行均为 `if (!enabled) return`：`65,78,103,117,135,151`；反射/字符串拼接均在其后 |
| `turn(on/off)` 不写 Kv | ✓ | `:177-180` 只 `enabled = on` + `LogX.i`，无 `Kv.put*`；冷启即恢复 false（`enabled` 是内存变量） |
| install 接线在探测区、不动生产顺序 | ✓ | `HookEntry.kt:342-347`，紧随 HCR 之后、RedPacket 之前；`HookEntry.kt:72` 有 `HOST_PACKAGE` gate。DexKit 禁用块已在 `HookEntry.kt:391-393` 注释掉，本探针 install 在 `:346`，不在其中 |
| 开机是否静默 | ✓（仅 1 行状态） | `install()` 末尾 `:172` 每次开机打一行 `MFP installed ok=... enabled=false`；这是状态行而非探测数据，可接受。`installed` 幂等位 `:53-54` 防重复安装 |

注：默认关仅保证「日志静默」，6 个 hook 仍常驻安装（设计如此，与 HCR 探针同构）。开关回调体内无 IO，仅有 `LogX.i`。

## 5. 崩溃风险（PASS（低保留））

| 检查点 | 结果 | 证据 |
|---|---|---|
| 回调全 `runCatching` 包裹 | ✓ | `:72-74, 80-92, 104-106, 119-121, 137-139, 152-161`；`pendingForwardMsgId.set`(`:71`)、`getOrNull`(`:118`) 为无异常操作，未包可接受 |
| 无 UI 操作 | ✓ | 回调仅调 `LogX`（`util/LogX.kt:68-94`：`synchronized(lock)` 落盘 + `XposedBridge.log`，无 View/Toast/Handler-to-main） |
| 单钩失败只 WARN 不整体禁用 | ✓ | `guard()` `:199-206`：`runCatching(block)` 失败→`hookStatus[name]=false`+`LogX.w`，继续后续 hook；H3/H2 的 `set.isEmpty()→error` 也被 guard 吞掉 |
| ThreadLocal 配对 | ✓（低保留） | before 存 `:71` / after 取并 remove `:81-82`，同一构造器调用 before/after 在同线程，配对成立。低保留：若原构造器本身抛异常，Xposed 的 afterHookedMethod 不会执行 ⇒ ThreadLocal 残留到该线程下次 5 参构造；后果仅是一次 `forwardMsgId` 取值错乱（日志层），不崩宿主，概率极低 |
| 反射遍历父类字段的异常 | ✓ | `readLongField`/`fieldVal` 全程 `runCatching`（`:226,235,260-273`），取不到给 `?`；`f.isAccessible=true`(`:234,261`) 对宿主自身类字段可用 |

**结论**：无具体可指认的崩溃行。回调跑在 `wx_features_base_thread` 也不会因探针崩溃或污染宿主。

## 6. 归因可靠性（PASS（带条件），非致命）

- **H6 降级事实**：`:166-169` 明确不强挂菜单锚点，`:192` 的 `status()` 行写 `H6-menu OPTIONAL-未实现(...)`。因此 **R72 §6.2 判据④（菜单锚点归因）在探针侧完全缺失**——主会话不能指望「捕获微X模块菜单点击」来圈定时间窗。
- **降级决策本身正确**：盲 hook `Menu.add` 等通用点会命中所有菜单、刷屏且无法归因（`:166-168`），风险大于收益。支持该决策。
- **剩余归因路径只有一条**：每条日志带 `th=<线程名>`（`th()` `:277`，H1~H5 均拼 `th=`），配合「探针默认关 → 用户明确点微X转发时才 on → 随即 off」的时间窗。
- **若微X部分逻辑跑 main 线程，`th=` 是否失效**：是，`th=` 单条会失效甚至误导。但 R71 §6.1 实证微X转发按钮的 view 在 `wx_features_base_thread` 创建、R72 §5.2.3 推断其转发流程整体跑自管线程 ⇒ 微X动作日志大概率落在 `wx_features_base_thread*`；且**时间窗本身是主归因、`th=` 是辅证**。
- **判定：可接受，不构成致命缺口**，但有条件：
  1. 主会话必须按严格协议操作：`op=mfwd` 确认 on → **只点一次微X转发** → 立刻 off → 只看这段时间窗内的 `MFP` 日志；
  2. 窗口内不得有其它消息发送 —— 否则 `q0 ctor`/`l1.a` 会照常触发并产生**同形日志**，无法与微X动作区分（这是比 `th=` 更现实的风险）；
  3. 判据说明须写清「判据④未实现，归因靠时间窗 + th=，非菜单锚点」。

## 7. 工作区边界（PASS）

`git status --short` 实测：

```
 M docs/当前状态.md
 M docs/派发审计台账.md
 M wxgroup/.../HookEntry.kt
 M wxgroup/.../entry/EntryReceiver.kt
?? docs/_r72_microx_forward_parse.md
?? wxgroup/.../probe/MicroxFwdProbe.kt
```

- 探针引入的**源码**改动恰好 3 个文件，与任务给定范围一致；另有 2 个 modified docs + 1 个 untracked doc，按任务说明为既有/流程台账（`派发审计台账.md` 已预置本轮 R72b-L1/L2 行），不计入探针源码边界。
- 无其它源码、脚本、缓存文件被引入或改动。**PASS。**

## 8. 总判定与修复指令

### 总判定：**需修后装机**

理由：代码层面**只读纪律、线程安全、崩溃风险、默认关、接线、工作区边界全部达标**，可以装机而不污染宿主/不崩微信；但**判据产出存在两处实质缺口**，直接装机有较高概率拿到误导性或缺失的证据，因此不建议按现状直接上真机：

1. **判据③很可能拿不到数据（最严重）**：H5 只 hook `q1.b()`（兜底路径），而 R36 §6 明确 `q1.a()→l1.a()` 才是主路径、G1 真机（`_r71_g1_native_forward_verdict.md:39` `via=l1.a()`）走的就是 a()。微X若走原生链，极可能同样走 `a()` ⇒ `q1.b()` 根本不调 ⇒ H5 零日志 ⇒ 子类型 4/13 判据落空。
2. **空日志误判风险（次严重）**：探针默认关、需手动 on，而 R72 §6.2 判据正文未要求先核对 `op=mfwd` 回显。若主会话在 off 或 hook FAIL 状态下点微X转发，「无日志」会被误读成「微X没走原生链」的假阴性。

### 修复指令（按严重程度排序，交主会话执行）

| # | 严重度 | 文件:行号 | 改成什么 |
|---|---|---|---|
| **F1** | 高 | `MicroxFwdProbe.kt:147-164`（H5 guard 块） | 增加对主路径 `q1.a()` 的对称 hook：在 H5 块内再加一个 `findAndHookMethod(q1,"a",...)` 的 `beforeHookedMethod`，用**完全相同**的 `h` 读取逻辑（`:153-159`），日志加 `via=a` / 原 `q1.b` 日志加 `via=b` 以区分路径。使微X走 a() 或 b() 都能读子类型。改动小、只读、无副作用。 |
| **F2** | 中 | 判据说明（`docs/_r72_microx_forward_parse.md` §6.2 / 主会话 SOP） | 写明硬前置：解释任何「无 MFP 日志」前**必须**先发 `op=mfwd`（无 sub）回显，确认 `enabled=true` 且 `H3-q0.ctor`/`H4-l1.a` 为 `OK`；否则空日志无效、不得据此判「微X没走原生链」。 |
| **F3** | 中 | 判据说明（同上） | 明确定性：`newMsgId=Y` **仅参考、非硬判据**（`readLongField` 回退扫描可能取到 createTime/timestamp）；判据①唯一硬证据 = 同行 `type=522` + `l1.a queued=true`。判据②只认 H1 `q1.f` 打印的 m 六字段；**H2 零日志不得解读为「微X没构造 m」**（其 maxParams 启发式会漏无参构造，见 §2）。 |
| **F4** | 中 | 主会话 SOP（归因协议） | 写明判据④未实现、归因靠时间窗 + `th=`：严格「确认 on → 只点一次微X转发 → 立刻 off → 只看窗口内日志」；窗口内不触发任何其它消息发送，避免同形 `q0 ctor`/`l1.a` 日志混淆。 |
| **F5** | 低（可选） | `MicroxFwdProbe.kt:62` / `:101` | 若追求极致稳健：H4 改为 `findAndHookMethod(l1,"a","boolean",...)`?（本例无参，保持现状即可）；H3 fallback `com.tencent.mm.storage.j9` 无法在仓库内核验，但 primary `hl0/q0` 为 R71 实测命中，不影响装机。无需改动。 |

**装机结论**：完成 **F1**（代码，必改）+ **F2/F3/F4**（判据说明与 SOP，随行）后**可装机上真机**；若仅完成 F2~F4 而不改 F1，则判据③需接受「可能无数据」的预期。当前状态不建议直接装机。

[DONE] R72b-L2 独立交叉复核完成。总判定：**需修后装机**。只读纪律/崩溃/默认关/接线/边界均 PASS；两处实质问题：①H5 漏主路径 q1.a() 致判据③可能无数据（F1 必改）；②默认关下空日志误判 + newMsgId 非硬判据，需随附判据说明（F2/F3/F4）。无污染宿主、无崩溃、无越界改动。
