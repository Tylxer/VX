# R21c 语音文件防删修复 —— 独立复核

> 复核对象：`VoiceFileGuard.kt`（hook `File.delete`）+ `VoiceBackup.kt`（备份/还原）
> + `VoiceBackupLogic.kt`（纯判据）+ 三处接线（`RevokeRescue.record` 接入备份 /
> `VoiceGuard.onDelete` 接入还原 / `HookEntry` 安装 hook）。
> 复核立场：只判「这份修复能否在真机闭环」，不重写、不改源码。

---

## 结论速览

| 问题 | 判定 | 一句话 |
|------|------|--------|
| Q1 `File.delete` 能否命中 | **存疑（需真机）** | `null` classLoader 正确；但「微信走 Java `File.delete` 还是 native unlink」静态判不出，必须上机证伪 |
| Q2 是否误拦正常清理 | **PASS** | `protected` 名单（60s TTL + 26 位 hex）是硬闸门，反向样本单测已覆盖，无实质误拦 |
| Q3 备份时机是否太晚 | **PASS** | `record()` 在 `beforeHookedMethod` 内同步跑，严格早于 `executor.submit` 排程，无竞态 |
| Q4 `onDelete` 里调 `restore` 是否错 | **FAIL（已修，见 R21c-5）** | 按已定案顺序 `File.delete` 在 `voiceinfo` DELETE 之后，`restore` 触发太早、恒 no-op；兜底形同虚设。**已补 `VoiceBackup.scheduleRestore` 在 `record()` 里排 +1s 幂等检查，`onDelete` 那次降级为「免费早触发」** |

---

## Q1. `File.delete()` hook 到底能不能命中？

### 1.1 `XposedHelpers.findClass("java.io.File", null)` 传 null 是否正确 —— **正确（PASS）**

`VoiceFileGuard.install()`（`VoiceFileGuard.kt:129`）：

```kotlin
val cls = runCatching { XposedHelpers.findClass("java.io.File", null) }.getOrNull()
```

`java.io.File` 是 bootclasspath 类。`XposedHelpers.findClass(name, null)` 在 LSPosed/EdXposed
里等价于用 `BOOTCLASSLOADER` 去 `Class.forName`，对 framework 类是标准正确写法
（另一种等价写法是传 `lpparam.classLoader`，因为 app 的 classloader 最终也委托到 boot）。
这里 null 不会导致找不到或找错类。`hookAllMethods` 拿到的是**同一个** `File.class`
（boot 类在 ART 里进程内单例），所以 hook 对全进程所有调用者生效，这点注释写的是对的。

### 1.2 只 hook `File.delete` + `deleteOnExit` 是否足够 —— **不保证（存疑）**

`VoiceFileGuard.install()` 只挂了 `"delete"` 和 `"deleteOnExit"` 两个方法
（`VoiceFileGuard.kt:136`）。它们能拦住的是**所有走 `java.io.File.delete()/deleteOnExit()`**
的调用（无论微信用哪个 classloader 调，最终都落到同一个被 hook 的 `File` 方法上）。

但微信删这个文件**可能**走的路径有四种，前两种拦得住，后两种拦不住：

| 路径 | 是否被拦 | 说明 |
|------|---------|------|
| `new File(p).delete()` | ✅ | `hookAllMethods(File, "delete")` 命中 |
| `File.deleteOnExit()` | ✅ | 已挂；`beforeHookedMethod` 置 `result` 跳过注册 |
| `android.system.Os.remove/unlink()` | ❌ | `libcore` 另一类，没 hook，直达 native |
| C++ 层直接 `unlink()` / 微信自有 native 清理 | ❌ | Java hook 完全不可见 |

`deleteOnExit` 一个次要点：它返回 `void`，`beforeHookedMethod` 里 `param.result = true`
（`VoiceFileGuard.kt:163`）对 void 方法是无害的 no-op（真正起作用的是 `returnEarly` 跳过原方法
注册），不会崩，但代码里没注释说明「result 对 deleteOnExit 无效、靠跳过方法体生效」，属可读性瑕疵，非 bug。

### 1.3 如何在真机上低成本证伪（给可执行判据）

静态判不出「微信到底走 Java 还是 native」，只有一条路：**看撤回后有没有 `VFG blocked` 日志**。

```bash
# ① 清日志 + 确认 hook 已装（没装则整个功能静默失效，先排除这个）
adb shell am broadcast ... --es op logclear      # 清 LogX
# 撤回一条语音消息
python tools/dev.py q --grep 'VFG'               # 过滤本功能日志
```

三条判据，命中哪条就对号入座：

1. 出现 `VFG installed hooks=delete,deleteOnExit` ⇒ hook 装上了（否则先查 `HookEntry` 安装段）。
2. 撤回后出现 `VFG blocked file delete img=<26hex>` ⇒ **`File.delete` 就是删除路径，hook 命中，修复闭环**。
3. 撤回后**没有** `VFG blocked`，但文件仍消失（用 `op=voicebak sub=stat` 或 `label sub=find`
   递归扫 `voice2/` 看该 imgPath 命中数 = 0）⇒ **`File.delete` 不是删除路径（native unlink），
   `VoiceFileGuard` 完全无效**，只能靠 `VoiceBackup` 备份 + 还原兜底（而那个兜底见 Q4，触发点不牢靠）。

> 反向对照建议：关掉防撤回开关再撤一条，确认 `VFG` 日志消失（证明日志不是噪声）。

### 结论

`findClass` 正确；`File.delete` 是否命中**静态无法判定**，必须真机。代码自己也在
`VoiceBackup.kt:33-35` 承认了 native unlink 风险。所以本项判定为**存疑**，而非 PASS。

`[DONE-Q1]`

---

## Q2. `VoiceFileGuard.shouldBlock` 有没有误拦风险？

### 2.1 误拦的「总闸门」决定了风险上限很低

`shouldBlock`（`VoiceFileGuard.kt:110-118`）的完整判据链是：

```
imgPathOfVoiceFile(path) != null
  AND protected[img] != null        ← 硬闸门
  AND until >= now（60s TTL 内）
```

关键在 `protected` 这个 `ConcurrentHashMap<String,Long>`：**只有** `RevokeRescue.record()`
在「成功拦下一次撤回」后、且 `orig.imgPath != null` 时才 `protect(imgPath)`
（`RevokeRescue.kt:110-118`），TTL 60s（`VoiceFileGuard.kt:73`），过期即 `remove`（`shouldBlock:113-115`）。

所以能被拦的文件，**必须同时满足**：
1. 路径含 `/voice2/`；
2. 文件名是 `msg_<26位小写hex>.amr`；
3. 那个 hex **恰好等于最近 60s 内一条刚被撤回的语音的 imgPath**。

条件 3 把误拦面收到「近期被撤回语音的文件」这唯一目标上。`imgPath` 本身含时间戳+随机，
不同消息几乎不可能撞 hex（不是 hash，是消息唯一标识）。**因此「误拦微信正常清理」本质上不可能发生**——
只有「撤回后 60s 内，微信 GC 恰好要删这条刚被救的语音文件」这一种情况会被拦，而那正是本功能想保护的对象，
且 60s 后自动放行，不会永久堵住存储回收。

### 2.2 逐条反例审查（`imgPathOfVoiceFile`，`VoiceBackupLogic.kt:101-110`）

| 反例输入形态 | 结果 | 是误拦还是漏拦 | 现有单测是否覆盖 |
|------|------|------|------|
| `/.../Voice2/01/70/msg_<26hex>.amr`（大写目录段） | `contains("/voice2/")` 不命中 → null | 漏拦（保守，安全） | 未覆盖，但不会误拦 |
| `/.../voice2x/...`、`/.../myvoice2/...`、`/.../voice22/...` | 两侧斜杠不命中 → null | 无 | ✅ `imgPathRejectsNonVoiceFiles` |
| `msg_<26hex>.amr.tmp` | `endsWith(".amr")` 不命中 → null | 无（`.tmp` 不拦） | 未显式覆盖，但逻辑由 `endsWith` 保证 |
| `msg_<26hex>.silk` | `endsWith(".amr")` 不命中 → null | 无 | ✅ 有 `.silk` 用例 |
| `msg_<26hex大写>.amr`（文件名大写 hex） | `isValidImgPath` 只认小写 → null | 漏拦（真机全小写，无实际影响） | ✅ `imgPathValidation` 大写用例 |
| 25 位 / 27 位 hex | `isValidImgPath` 长度≠26 → null | 漏拦（27 位是已知异常样本，明确放弃） | ✅ 25/27 位用例 |
| `voice2/01/65/index.tmp`、`voice2/.../50095...amr`（无 `msg_` 前缀） | 前缀不命中 → null | 无 | ✅ |
| Windows 风格 `\` 分隔符 | Android `File` 内部恒用 `/`，实际不会出现；即使出现，`contains("/voice2/")` 不命中 → null | 漏拦（理论） | 未覆盖，但无实际意义 |
| 软链接 `msg_<26hex>.amr -> 别处` | `File.delete()` 删的是链接本身，被拦 = 链接保留 | 无实质危害 | 未覆盖 |

**结论**：所有「该放行」的形态要么被 `/voice2/` 双斜杠、要么被 `msg_` 前缀 + `.amr` 后缀 +
26 位小写 hex 三层过滤掉，漏拦（保守）远多于误拦，不存在「误拦微信正常清理」的可构造反例。

### 2.3 一个值得补的单测缺口（非 bug）

`imgPathRejectsNonVoiceFiles` 没显式覆盖 `msg_<26hex>.amr.tmp` 与「大写目录段 `/Voice2/`」，
但这两种情况分别是 `endsWith` 和 `contains` 的直接推论，属「覆盖面略窄」而非「判据有洞」。
建议补两条防回归，不强制。

### 结论

**PASS**。判据链末端有 `protected` 名单 + 60s TTL 兜底，误拦风险被结构性限制在「刚被撤回的语音文件」这一个
本就要保护的目标上，不存在误伤微信正常 GC 的反例。

`[DONE-Q2]`

---

## Q3. 备份时机会不会太晚？

### 3.1 `record()` 里的同步 `copyTo` 一定早于异步删除 —— **是，由调用顺序保证，非竞态**

关键事实链（全部已由源码 + 反汇编定案，非推测）：

1. `AntiRevoke` 的拦截钩子是 `beforeHookedMethod`（`AntiRevoke.kt:308`），挂在
   `updateWithOnConflict` 上（`AntiRevoke.kt:294`）。
2. `guard()`（`AntiRevoke.kt:346`）→ `guardMessage()`（`:466`）→ `RevokeRescue.record()`
   （`:558`）**全程同步**，在 `beforeHookedMethod` 返回前完成。
3. `record()` 内部：`queryOriginal`（SELECT）→ `GroupDb.recordRescued`（写台账）→
   `VoiceFileGuard.protect` → `VoiceBackup.backup`（`copyTo`），全同步（`RevokeRescue.kt:88-118`）。
4. 反汇编定案：`executor.submit(vj0.p)` 是 `j9.va`（UPDATE）**返回之后**才排程的
   （`[532] j9.va` 之后 `[576-579] new vj0.p + executor.submit`）。

⇒ 我们的 hook 在 UPDATE 方法体**执行之前**就跑完了 `backup()`；而 `executor.submit` 要等
`j9.va` 整个返回后才发生。**`backup()` 严格早于「删除任务被创建」**，更别说早于删除任务执行。
所以「executor 线程先跑完」在物理上不可能——任务压根还没被 `submit`。不存在时序竞争。

> 附带好处：`record()` 里同步做 SELECT + copyTo 会**阻塞**微信这条线程，等于把 `executor.submit`
> 往后再推了一点，只会让备份更从容，不会更赶。

### 3.2 万一有竞态的最坏后果（防御性推演，实际不会发生）

- 拷到半个文件：不可能。语音 `.amr` 在撤回时早已下载完成，撤回不会触发对该文件的写，`copyTo` 读到的是完整稳定文件。
- 备份了已被删的空文件：不可能。`backup()` 先 `if (!src.isFile) return false`（`VoiceBackup.kt:89`），
  文件没了就直接跳过，不会拿空文件糊弄。
- 最坏情况（文件已被删）=「没备份成」，退化成当前已确认的坏行为（播放失败），**不会更糟**。

### 3.3 `if (dst.isFile && dst.length() == src.length()) return false` 有无问题 —— **可接受的启发式，非 bug**

`VoiceBackup.kt:97`。这个「已备过就跳过」只在**同一条消息被重复撤回/重试**时触发，`dst` 是
上一次备份的同名文件。担心点：

- 「被截断但长度碰巧相同」：`copyTo` 是整文件复制，截断只会更短，不会恰好等于 `src.length()`；
  只有「内容被替换成同长度的别的内容」才会撞长度，而语音文件一旦下载就是不可变的，不存在这种替换。
- 未做内容校验（checksum）：对 <100KB 的不可变文件属过度设计，不做合理。

结论：跳过判据在语音文件不可变的前提下没有实际失效路径。

### 结论

**PASS**。备份严格早于异步删除（由调用顺序保证），且「已备过」判据无实际漏洞。

`[DONE-Q3]`

---

## Q4. `VoiceGuard.onDelete` 里调 `VoiceBackup.restore()` 是不是错的？

### 4.1 判定：**无害，但按已定案顺序几乎必然无效（存疑 → 需改进）**

先看已定案的删除顺序（任务背景 + `VoiceGuard.kt:20-23` 反汇编定案）：

```
UPDATE message（AntiRevoke 拦）  →  executor.submit(vj0.p)  →  vj0.p:
   [a] SQLiteDatabase.delete("voiceinfo","FileName=?")   ← VoiceGuard 拦，这里调 restore()
   [b] File.delete() 删 voice2/<桶>/msg_<imgPath>.amr      ← 在 [a] 之后
```

`onDelete` 的 `restore(whereArgs.firstOrNull())`（`VoiceGuard.kt:194`）在 **[a] 拦截点**执行，
而磁盘文件删除 **[b] 在它之后**。所以：

- 此时磁盘文件**还没被删** ⇒ `shouldRestore(backupExists=true, targetExists=true)` 返回 false
  （`VoiceBackupLogic.kt:138-139`）⇒ **no-op**。
- 随后 [b] 跑，文件被删。`VoiceFileGuard` 若拦得住（`File.delete` 是 Java 路径）则文件保住；
  若拦不住（native unlink）则文件没了，而 `restore` 刚才已经 no-op 过，**没人再补**。

⇒ 在「`File.delete` 走 native、需要靠 backup+restore 兜底」这一**它本应兜底**的场景里，
`onDelete` 的 restore 恰好**先于删除触发、恒 no-op**，兜底形同虚设。

反过来的另一种顺序（`File.delete` 在 `voiceinfo` DELETE **之前**）下，`onDelete` restore 才有效——
但那条顺序与已定案事实相反。所以这个调用点是「无害但几乎必然无用」。

### 4.2 有害吗？

**不有害**，具体查证：

1. `restore()` 全程 `runCatching` 吞异常（`VoiceBackup.kt:144-146`），最坏只记一行 warning，不会崩宿主。
2. `src.copyTo(dst, overwrite = false)`：若 `shouldRestore` 判定与 `copyTo` 之间目标被微信重建，
   `overwrite=false` 抛 `FileAlreadyExistsException`，被吞，不会写坏文件。
3. `restore` 以 `whereArgs.firstOrNull()` 为 imgPath；若撤回走的是 `MsgLocalId=?`（传十进制 msgId），
   `isValidImgPath`（26 位 hex）不通过 → 直接 false，不会拿 msgId 当路径瞎还原。
4. 唯一轻微代价：重试风暴时每次拦下的 DELETE 都会跑一次 `restore`（md5 + 两次 `isFile`），
   但 `shouldRestore` 在文件仍在时秒退，开销可忽略；首次 `accountDir()` 可能触发一次 `MicroMsg/`
   `listFiles()` 目录扫（`VoiceBackup.kt:190-195`），有缓存、一次性、被 runCatching 兜住，可接受。

### 4.3 最可靠的还原触发点建议

要求：**不能依赖时序假设、不能显著增加 hook 开销**。

- **首选（零时序假设，但需额外 hook，成本最高）**：hook 播放入口，在用户点播放、微信真正读
  `voice2/<桶>/msg_<imgPath>.amr` 之前 `restore(imgPath)`。语义上唯一「文件必须存在」的时刻就是播放，
  天生不依赖删除时序。代价是要定位深度混淆的播放链路（当前未做，属新增工作）。
- **次选（几乎零时序假设、成本极低、可立即落地，推荐）**：把还原从「拦截点」挪到
  「**删除链跑完之后**」。在 `RevokeRescue.record()` 的 `backup()` 成功后（此刻删除任务还没排程），
  排一个**一次性延时任务**，约 +1s（远大于 `vj0.p` 的 ms 级执行）后调 `restore(imgPath)`。
  `restore` 幂等（文件还在就 no-op，没了就补回），所以即使对「删除到底多快完成」判断有偏差也不出错，
  跑几次都安全。开销 = 每次撤回一条语音一个延时检查，可忽略。

> 结论：`onDelete` 里的 restore 可以**保留**（作为一次免费的早触发尝试，无害），
> 但**绝不能当作可靠兜底**。必须补一个「删除链结束后」的幂等 restore（次选即可），
> 否则 `VoiceBackup` 声称的「native unlink 兜底」在当前代码里是失效的。

### 结论

**存疑（无害但几乎必然无用）**。调用点本身不有害，但作为「兜底」它在关键场景下触发太早、恒 no-op，
需要按 4.3 补一个不依赖时序的还原触发点。

`[DONE-Q4]`

---

## [VERDICT] FAIL（条件性：真机确认 Q1 后部分通过）

**必须改什么（一句话）：补一个「删除链结束后」的幂等还原触发点，让 `VoiceBackup` 的兜底真正成立；否则
在 `File.delete` 走 native unlink 的场景下（Q1 未证伪），修复会静默失效。**

具体理由：

1. 主机制 `VoiceFileGuard` 本身**逻辑正确、误拦风险低、时序正确**（Q2/Q3 均 PASS），
   **只要真机确认微信删语音文件走 `java.io.File.delete()`**，这一层单独就能闭环，修复成立。
2. 但 `VoiceBackup` 被注释标榜为「万一 native unlink 的兜底」（`VoiceBackup.kt:12-18`、`RevokeRescue.kt:114-115`），
   而它唯一的自动还原触发点（`VoiceGuard.onDelete`）在 native unlink 场景下**先于文件删除触发、恒 no-op**（Q4），
   ⇒ 兜底不成立。代码的「声明能力」与「实际能力」不符。
3. 因此，**当前代码的正确性完全押在 Q1 的未证实假设上**。在花一轮真机之前，应先补上 4.3 的幂等延时还原
   （或播放前还原），把「native unlink」这条兜底路径补成真的，再上机一次验证两条路径。

若 Q1 真机确认 `File.delete` 就是删除路径（日志出现 `VFG blocked file delete`），则 `VoiceFileGuard`
单独闭环，本 FAIL 可降级为「可选加固」；在此之前，按 FAIL 处理，先补还原触发点。

---

## 前端无法验证、必须真机确认的假设

以下每条都是「静态看不出、只能上机试」，上机时必须逐条采集证据：

1. **微信删语音文件到底走 `java.io.File.delete()` 还是 native `unlink()`/`Os.remove()`。**
   判据：撤回后日志有无 `VFG blocked file delete img=<26hex>`（见 Q1.3 的 3 条判据）。
   这是整个修复闭环与否的**总闸门**。

2. **`VoiceFileGuard.install()` 在微信进程里是否真的装上了**（`VFG installed hooks=delete,deleteOnExit` 出现）。
   没装则功能静默失效，且日志里只有一行 `VFG: java.io.File not found` 或 `no hook installed` 的 warning。

3. **`message.imgPath` 在 8.0.48 真机上确实可读、且为 26 位小写 hex**（`RevokeRescue.queryOriginal` 新增的
   `getColumnIndex("imgPath")` 反射路径）。若列名在真机上是别的（或老库无此列 → idx<0 → null → 备份侧静默跳过），
   需确认 `VB skip: source missing` 之外的日志形态。

4. **`backup()` 的 `accountDir()` 推导是否正确命中当前账号目录**（`.../MicroMsg/<hash>`）。
   判据：撤回语音后 `op=voicebak sub=stat` 能数出对应 `msg_<imgPath>.amr` 且 `bytes>0`；
   只看到 `backup ok` 不够，必须 `stat` 里有实际文件（代码自己也在 `VoiceBackup.kt:152-154` 强调这点）。

5. **`voiceinfo` DELETE 与 `File.delete` 的真实先后顺序**（Q4 判定所依据的顺序虽已反汇编定案，
   但「[a] SQL delete → [b] 磁盘 delete」这一相对顺序建议用日志再核一次：
   同一次撤回里 `VG blocked voiceinfo delete` 与 `VFG blocked file delete` 两条日志的先后）。

6. **`File.delete` 在 `voice2` 之外还有没有第二处删除**（例如把 `voice_bak/` 也纳入清理范围）。
   `voice_bak` 放在微信自己的 `MicroMsg/<hash>/` 下（与 `voice2` 平级，不在 `voice2` 内），
   理论不被 voice2 递归清理连带删，但需确认微信的存储 GC 是否会扫到同级 `voice_bak` 目录。
