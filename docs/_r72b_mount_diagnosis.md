# R72b-L4 · MFP hook 冷启挂载失败根因诊断与修复

> 角色：R72b-L4 子代理（时序修复）
> 日期：2026-11-19
> 纪律：只读诊断 + 仅改 `MicroxFwdProbe.kt` 与本文档；不碰其它源码、不碰 adb/设备/gradle、不 commit。
> 证据纪律：每个结论给 `文件:行号` 或文档出处；推断单独标注「推断」，不臆断 dex 内容。
> `MicroxFwdProbe.kt` 的 `:行号` 若无特别说明均为**改动前**行号（本诊断针对的现象）；
> 改动后的行号见 §4 与源码。

## 0. 现象与已核实事实

- 冷启日志 `MFP installed ok=1 fail=5`；status 逐行：H3-q0.ctor **OK**，H4-l1.a / H1-q1.f / H2-m.ctor / H5-q1.b / H5a-q1.a **FAIL**，错误形态统一为
  `IllegalStateException: class not found: hl0/l1` / `hl0/q1` / `hl0/m`。
  该异常文案出自探针自己的兜底 `error("class not found: ...")`（`MicroxFwdProbe.kt:236`），
  即 `XposedHelpers.findClass` 抛错后 `runCatching{}.getOrNull()` 返回 null 的路径——**不是 hook 方法签名错**。
- 类在宿主 dex 中真实存在（主会话核验，`docs/派发审计台账.md:4879`）：
  `hl0/q0|q1|l1|r1` 在 `classes4.dex` + `classes6/7`，`hl0/m` 在 `classes.dex`/`classes10.dex`。
- 候选名差异：H3 用双候选 `listOf("hl0/q0", "com.tencent.mm.storage.j9")`（`:62`），
  其余 5 个 hook 均单候选 `"hl0/l1"` / `"hl0/q1"` / `"hl0/m"`（`:100`/`:114`/`:128`/`:148`/`:170`）。
- 全项目 `XposedHelpers.findClass` 只有本探针用**斜杠**类名 `hl0/xxx`；ForceForward、FileSender、TextSender、SendProbe
  一律用**点号** `hl0.r1` / `hl0.m` / `hl0.o1` / `hl0.q1`（`ForceForward.kt:879-880,944-945,1031-1033`、
  `FileSender.kt:146-147,169`、`TextSender.kt:71`、`SendProbe.kt:149,154`）。
- 接线：`HookEntry.kt:346` 在 `handleLoadPackage` 内调 `install(lpparam)`；广播 `EntryReceiver.kt:234-243`
  把 `op=mfwd sub=on|off` 转给 `turn(on/off)`，无 sub 转给 `status()`。

## 1. 根因定案：为什么冷启 install 时 findClass 找不到这些类

### 1.1 两种可能，取 (a)

| 可能 | 内容 | 判定 |
|---|---|---|
| **(a) 时机 / dex 可见性** | `handleLoadPackage` 早于微信把业务 dex 挂到 `lpparam.classLoader` 的 `DexPathList`；`findClass` 底层是 `Class.forName(name,false,cl)`，只能找 defining dex 已挂到 `cl` 上的类。目标类在从属 dex（`hl0/q1`/`l1` 见 classes7 反汇编地址，`hl0/m` 见 classes10），冷启尚未可见 ⇒ 失败 | **✅ 主因** |
| (b) 斜杠类名格式 | 此 Xposed/ART 版本需要点号 `hl0.q1`，斜杠 `hl0/q1` 解析失败 | ❌ 不是根因（但作为零成本兜底一并修，见 §3-C） |

**判定依据（自证锚点）：**

1. **同一个 classLoader 对象，冷启失败、运行时成功 ⇒ 唯一变量是时机。**
   ForceForward 在 `install()` 把 `lpparam.classLoader` 存进 `loader`（`ForceForward.kt:163-164`），
   运行时 `nativeProbe()` 用**同一个** `cl = loader`（`:873`）调 `findClass("hl0.r1"/"hl0.m")` 成功
   （`:878-882`），`nativeReadyReport()` 运行时探测 `hl0.r1|hl0.m|hl0.o1` 也都能找到（`:1256-1262`）。
   若错在 loader（不是目标 loader）或类名格式，运行时不可能突然变对。
2. **斜杠名在此版本可用，格式不是拦路虎。**
   Android ART 的 `Class.forName` 把名字里的 `.` 归一成 `/` 后交给 `ClassLinker::FindClass`，而
   `FindClass` 本来接收斜杠规范名 ⇒ `hl0/q1` 与 `hl0.q1` 等价；本项目 H1 的参数类型串
   `"hl0.m"`（点号，`:115`）也是 Xposed 标准用法、`_r72b_probe_review.md:44` 已核兼容。
   所以 (b) 不成立。（工程上仍改成点号优先，纯属对冲，见 §3。）
3. **反证“H3 挂上说明斜杠名可用”是循环论证**：H3 的成功更可能来自 fallback（见 §1.2），
   而那个 fallback 是**另一个类**，不能用来证明 `hl0/q0` 这个斜杠名成功过。
4. **同一 dex 内的类应同命运**：`hl0/q0`/`q1`/`l1` 的反汇编地址同在 classes7
   （`_r71_g1_native_forward_verdict.md:45`、`_r16_p1p2_salvage_raw.md:1183` 证 q1 定义于 classes7）。
   若 classes7 冷启已可见，q1/l1 不该失败；反之 q0 也该失败——只有「q0 走的是另一条（fallback）」才自洽。

**结论：根因 = 冷启 `handleLoadPackage` 时业务 dex 尚未对 classloader 可见；不是类名写错，也不是 loader 拿错。**

### 1.2 H3 为何 OK：命中的是 fallback，不是 q0（推断，带反证）

- `com.tencent.mm.storage.j9` 与 `hl0/q0` **是两个不同的类**：
  - `com.tencent.mm.storage.j9`：classes4.dex，super `k74/q0`，106 方法，**消息表 DAO**（`docs/_r16_g2_msgsnap.md:239`）。
  - `hl0/q0`：**NetSceneSendMsg**，super `com.tencent.mm.modelbase.k1`（`docs/conclusions/send_msg.md:21`），
    构造体 @classes7 `0x711560`（`docs/_r71_g1_native_forward_verdict.md:45`）。
- 推断链：`hl0/q0` 在从属 dex，冷启不可见 → 第一候选失败 → 第二候选 `com.tencent.mm.storage.j9`
  是启动期就要用的核心 DAO、dex 可见性早 → `hookAllConstructors` 成功（构造器存在）→ H3 记 OK。
  但 5 参 `(S,S,I,I,J)` 过滤（`:67-69`）几乎不可能命中 DAO ⇒ **H3 的 OK 很可能是假阳性**，
  现场没有 `MFP q0 ctor ... type=522` 日志，判据①实际不可用。
- 反证：若命中的是 `hl0/q0`，则 classes7 冷启可见，同 dex 的 q1/l1 不该全部 `class not found` ⇒ 与实测矛盾。
- 本次不改 H3 语义（仅给候选加点号优先）；把「H3 是否假阳性」列为 §5 的真机验证项。


## 2. 为什么 ForceForward 能在运行时找到同类

ForceForward 走的就是 `_r36_forward.md:119` 记载的成熟手法：**install 只存 classLoader，探测改运行时**。

- `install(lpparam)` 只把 `lpparam.classLoader` 存进 `loader`，随后安装的是 android 框架类 hook
  （`ForceForward.kt:163-169`），**没有在冷启对 `hl0/xxx` 做 findClass**。
- 真正的类查找发生在 `nativeState()` → `nativeProbe()`（`:849`、`:871`），由 `planFor`（`:720`）
  或广播 `nativerdy`（`:1192`）触发——都是用户动作/广播，**此时微信已运行、
  `classes4/6/7/10` 早已挂到 classloader**。`:944-945`、`:1031-1033` 的真发路径同样在运行时才 findClass。
- 容错正确：`nativeProbe()` 用 `runCatching { XposedHelpers.findClass(...) }`（`:878-882`），
  注释明确 `ClassNotFoundError` 是 `Error` 不是 `Exception`，**`runCatching` 抓 `Throwable` 才兜得住，不能写 `catch (Exception)`**（`:868-869`）。
- 这解释了「同一个类名、同一个 loader，冷启 FAIL / 运行时 OK」的全部差异：**调用时机**。

## 3. 修复方案选择与取舍

| 方案 | 取舍 | 结论 |
|---|---|---|
| **A 延迟挂载** | install 存 loader + 触发点；`turn(true)` 时（微信已运行）再 findClass + 挂 hook。优点：时机确定、复用现有 `mfwd` 广播、与 ForceForward 同构且真机已验证；缺点：需把「挂载」与「开关」解耦，`turn(true)` 里先 mount | ✅ **选定** |
| B hook `ClassLoader.loadClass` | 在目标类被加载时挂。classloader 子类多、`loadClass` 高频、易重复挂/死锁，复杂度远超收益 | ❌ 不选 |
| C 改点号类名 | 若只是格式问题则 `hl0/q1`→`hl0.q1`。已判定不是根因，但**零成本** | ⚠️ 一并做：候选改成「点号优先 + 斜杠兜底」，对冲 §1.1-(b) 万一成立，零风险（两者指向同一个类） |
| D 找冷启就加载的锚类（如 `hl0/r1`）再做二次挂载 | 链条更长；`hl0/r1` 本身也在从属 dex，冷启同样可能不可见；且需额外 hook r1 方法 | ❌ 不选 |

**定案：A + C（A 是根本修复，C 是零成本加固）。**

为什么不选 B/D、为什么 A 的缺点可接受：SOP 本来就要求「先 `sub=on` 再解释日志」
（`_r72b_probe_review.md:137` F2），因此「挂载发生在 on 时」与现有使用协议天然一致；
探针默认静默，on 之前没有 hook 不会丢任何证据。


## 4. 具体改动（仅 `MicroxFwdProbe.kt`）

1. **字段区**：新增 `savedLoader`（install 存的 classLoader）、`mounted`（幂等标志）。
2. **`install()`**：改为**只存 classLoader + 打一行状态日志**，不再执行任何 findClass/hook；
   原 6 个 guard 整体迁入新的 `mount()`。`installed` 幂等语义不变。
3. **新 `mount()`**：`@Synchronized` + `mounted` 标志保证幂等（重复 on 不重复挂）；
   `savedLoader == null` 则 WARN 返回；6 个 guard 体**逐字保留**（只读纪律零改动）；
   末尾日志改为 `MFP mounted ok=... fail=... enabled=...`。
4. **`turn(on)`**：`on` 时**先 `mount()` 再翻 `enabled`**，保证日志开时 hook 已就位；`off` 行为不变。
5. **`status()`**：header 增加 `mounted=` 字段；未挂载时补一行
   `MFP hook <not mounted> (发 op=mfwd sub=on 触发挂载)`。其余逐行 OK/FAIL 清单与 H6 行不变。
6. **`guard()` / `findClass()`**：逻辑不变（仍 `runCatching` 包住、单钩失败只 WARN），
   补注释确认 `runCatching` 抓 `Throwable`（覆盖 `ClassNotFoundError extends Error`）；
   6 个候选改为**点号优先 + 斜杠兜底**，H3 顺序为 `hl0.q0 → hl0/q0 → com.tencent.mm.storage.j9`。
7. **未改**：EntryReceiver / HookEntry / 其它源码；回调体一行未动；无 `param` 写入、无主动调业务方法、无持久化；default off 不变。

## 5. 预期真机 status 变化与验证

- **冷启后**（不再有 `ok=1 fail=5`）：
  `MFP installed classLoader saved, mount deferred to op=mfwd sub=on`；
  `op=mfwd` 回显 `MFP STATUS enabled=false installed=true mounted=false ok=0 fail=0` +
  `MFP hook <not mounted> ...`（这是预期中间态，不是失败）。
- **发 `op=mfwd sub=on` 后**：`MFP mounted ok=6 fail=0 enabled=true`；
  `op=mfwd` 回显 `MFP STATUS enabled=true installed=true mounted=true ok=6 fail=0`，
  6 行（H3/H4/H1/H2/H5/H5a）**全 OK**，H6 行维持 `OPTIONAL-未实现`。
- **最坏情况**：个别 hook 仍 FAIL（签名/版本漂移），但失败形态不再是整齐 5 条
  `class not found: hl0/xxx`；`guard` 保证单钩失败不崩、其余照挂。
- **判定修复成功的硬指标**：`mounted=true` 且 `ok≥5`；判据可用性还需 H3 真产出
  `MFP q0 ctor ... type=522`——若 H3 仍 OK 但无该日志，说明 hook 落在 DAO fallback 上（§1.2 假阳性），
  需后续单独把 fallback 拿掉或修正，不属于本次时序修复范围（已列为遗留项交给主会话）。
- **复核命令（主会话执行，本代理不碰设备）**：
  `am broadcast -a <模块action> --es op mfwd --es sub on` → 看 `MFP mounted ok=? fail=?`；
  `am broadcast -a <模块action> --es op mfwd` → 看逐行清单。

[DONE] 诊断与修复完成。
