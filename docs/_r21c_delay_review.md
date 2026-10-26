# R21c 延时还原逻辑 —— 独立复核

> 复核对象：`VoiceBackup.scheduleRestore` / `handler()`（`VoiceBackup.kt`）+
> `RevokeRescue.record()` 新增第 ③ 步 + `VoiceGuard.onDelete` 改动后的注释。
> 复核立场：只读，不改任何 `.kt`、不跑 gradle、不碰 adb。
> 前置已采信事实：删除顺序 `voiceinfo DELETE` 在前、`File.delete` 在后（经 `executor.submit` 异步）。

---

## 结论表

| 编号 | 问题 | 判定 |
|------|------|------|
| 1 | +1000ms 是否足以覆盖 `File.delete` 实际执行 | **存疑（正常路径 PASS，超时窗口 FAIL）** |
| 2 | 只有一次检查够不够 | **FAIL（真实缺口）** |
| 3 | `handler()` 并发正确性 | **PASS** |
| 4 | 资源 / 泄漏 / 队列堆积 | **PASS** |
| 5 | 文件竞争（`copyTo overwrite=false`） | **PASS** |
| 6 | 参数来源（空串 / 带引号 imgPath） | **PASS** |
| 7 | `VoiceGuard` 注释「没有播放路径 hook」 | **PASS** |
| 8 | 其它问题 | **存疑（若干小瑕疵，见 §8）** |

---

## 1. 时序：+1000ms 是否足以覆盖 `File.delete`

### 1.1 正常路径：足够（PASS）

`record()` 在 `updateWithOnConflict` 的 `beforeHookedMethod` 内**同步**执行
（调用链：`AntiRevoke.revokeGuard.beforeHookedMethod` → `guard()` → `guardMessage()`
→ `RevokeRescue.record()`，见 `AntiRevoke.kt:308` / `:346` / `:466` / `:558`）。
`record()` 里 `backup()` 之后同步调 `scheduleRestore()`（`RevokeRescue.kt:124`），
它做的只是 `handler().postDelayed(..., 1000)`（`VoiceBackup.kt:204-207`），
这条调用本身在 `beforeHookedMethod` 返回前完成。

关键：`guardMessage` 在调 `record()` **之前**已经把 `param.result = FAKE_ROWS`
（`AntiRevoke.kt:526`）置位，跳过了本次 UPDATE 的方法体；但 `executor.submit(vj0.p)`
发生在 **`j9.va`（UPDATE 调用者）返回之后**，也就是我们的 hook 返回之后。删除链
`executor.submit → vj0.p → voiceinfo DELETE（被 VoiceGuard 拦）→ File.delete`
在同一进程内的线程切换，量级是毫秒。所以 `beforeHookedMethod 时刻 + 1000ms` 在
正常负载下**远超**删除完成时刻，覆盖充分。这与上一轮 `_r21c_voice_file_review.md`
§4.3 推荐的「+1s 一次性延时任务」一致。

### 1.2 阻塞/排队 >1s 的风险（FAIL 侧）

如果微信的 `executor`（那个负责跑 `vj0.p` 的池）此刻被其它任务阻塞或排队超过 1s，
则在 `t0+1000ms` 时：
- `restore()` 判 `shouldRestore(src.isFile=true, dst.isFile=true)` → **false** → no-op；
- 随后（比如 t0+2s）文件才被删，**没人再补** → 播放失败。

这不是纯理论：微信的删除可能落在某个**串行执行器**上，前面若有 IO 密集任务
（图片/视频清理、缓存写盘），排队长于 1s 并不罕见。

### 1.3 结论

「1s 后检查一次、检查不到就漏」在工程上**可接受作为主路径**，但它**不是**上一轮
结论里要求的「不依赖时序」的兜底——它只是把时序假设从「几十 ms」放宽到「1s」。
这个残余风险与第 2 条是同一个缺口，合并处理（见 §2）。

---

## 2. 只有一次检查够不够 —— FAIL（真实缺口）

**是真实缺口。** 判据链在 `VoiceBackup.kt:163`：

```
if (!VoiceBackupLogic.shouldRestore(src.isFile, dst.isFile)) return false
```

`shouldRestore(backupExists, targetExists) = backupExists && !targetExists`
（`VoiceBackupLogic.kt:138-139`）。若 1s 时刻目标文件**尚未被删**（`targetExists=true`），
这次 restore 就是 no-op；文件随后被删后，**没有任何后续动作**。

且第 7 条已确认：本项目**没有播放路径 hook**，所以「用户点播放前补一次」这条
替代兜底**不存在**。这意味着延时检查是**唯一**可靠还原点——单次检查一旦漏，
该条语音就永久播放失败。

### 建议（低成本、幂等、立即落地）

`restore` 本身幂等（文件在就 no-op、没了就补），重复/乱序调用安全
（`VoiceBackup.kt:191` 注释自己承认这点）。因此不必纠结「精确等多久」，改成
**有界重试**即可，例如在 `scheduleRestore` 里排 2~3 次：

```
postDelayed(restore, 1_000)   // 快路径，覆盖绝大多数
postDelayed(restore, 5_000)   // 二次兜底，覆盖 executor 排队 >1s 的情况
（可选）postDelayed(restore, 15_000)
```

每次成本只是一次 `src.isFile` + `dst.isFile`（O(1)，无目录扫、无查库），与
`VoiceFileGuard` 的 60s 保护窗、`AntiRevoke` 的 60s TTL 在同一数量级内，不会越界。
**这是本轮唯一必须改的项。**

---

## 3. `handler()` 并发正确性 —— PASS

`VoiceBackup.kt:211-223`：

```kotlin
private fun handler(): Handler? {
    delayHandler?.let { return it }
    return runCatching {
        synchronized(this) {
            delayHandler?.let { return it }
            val t = HandlerThread("VoiceBak").apply { start() }
            delayThread = t
            Handler(t.looper).also { delayHandler = it }
        }
    }.onFailure { ... }.getOrNull()
}
```

- **非局部返回是作者意图，且正确。** Kotlin 里 `synchronized`、`let`、`runCatching`
  全是 `inline` 函数，块内 `delayHandler?.let { return it }` 的 `return` 是**从
  `handler()` 函数返回**（不是从块返回），返回的是已缓存的非空 `Handler`。这正是
  双重检查的语义：外层快路径 + 内层（持锁）二次确认。
- **不会死锁。** `synchronized` 编译为 `monitorEnter` + `try/finally { monitorExit }`，
  即使块内发生非局部 `return`，`finally` 仍会释放监视器。
- **有正常 return-null 路径，且被调用方处理。** 若 `HandlerThread` 构造/`start()`
  抛异常，`runCatching.onFailure` 记日志、`getOrNull()` 返回 null；`scheduleRestore`
  对 null 打 warning 后安静放弃（`VoiceBackup.kt:200-203`）。不会崩宿主。
- **`@Volatile` + DCL 足够。** `delayHandler` / `delayThread` 均 `@Volatile`
  （`VoiceBackup.kt:78-82`）。`delayHandler = it` 是对**已完整构造**的 `Handler`
  的 volatile 写，读侧看到非 null 即拿到完整对象；内层持锁二次检查保证只有一个线程
  真正 `start()` 线程。没有部分构造可见性的问题。
- `Handler(t.looper)` 的 `t.looper` getter 会阻塞到 Looper 就绪，故随后 `postDelayed`
  安全。

结论：并发与空值路径均正确。

---

## 4. 资源 / 泄漏 / 队列堆积 —— PASS

- **线程常驻**：`HandlerThread("VoiceBak")` 启动后永不 `quit`，常驻一个线程
  （`VoiceBackup.kt:70-77` 注释已说明这是有意设计）。对长驻的微信进程无泄漏问题，
  只是多一个空闲线程。
- **队列不堆积**：每次撤回只 `postDelayed` 一条消息（`VoiceBackup.kt:204`），
  到点执行完即从 MessageQueue 移除。即使撤回风暴，也只在 1s 窗口内短暂堆积
  `N 条待执行消息`，N = 并发撤回数（极小），不会随时间累积。
- **不在主线程 / 高频路径**：`restore` 全在 VoiceBak 线程执行，操作仅为 md5、
  两次 `isFile`、必要时 `mkdirs` + 一次 `<20KB` 的 `copyTo`，无目录递归扫、无查库。
- **轻微点（非 bug）**：
  1. 首次 `handler()` 懒建时，`start()` + `t.looper` 阻塞发生在**微信 DB 线程**上
     （`record()` 所在线程），一次性数 ms，可接受。
  2. `scheduleRestore` 里的 `runCatching { restore(img) }.onFailure{...}`
     （`VoiceBackup.kt:204-206`）是**冗余**的：`restore()` 内部已把全部逻辑包进
     `runCatching`（`VoiceBackup.kt:153-173`），`restore()` 永不抛异常，外层这个
     `onFailure` 分支是死代码。

---

## 5. 文件竞争 —— PASS

`restore()` 用 `copyTo(dst, overwrite = false)`（`VoiceBackup.kt:168`），闸门是
`shouldRestore(src.isFile, dst.isFile)`。

- **我们不会写坏微信。** 若微信此刻正在写同一个 `dst`（重新下载 / silk 播放前缓存），
  检查时刻 `dst.isFile=true` → `shouldRestore=false` → 不动；即使检查与 `copyTo`
  之间存在 TOCTOU（检查时不存在、copyTo 前被微信重建），`overwrite=false` 会抛
  `FileAlreadyExistsException`，被外层 `runCatching` 吞掉 → restore 返回 false，
  **绝不会覆盖微信正在写/已写的文件**。
- **微信覆盖我们补的文件**（把补回去的文件再截断成 0 字节）：这是微信自身对同一
  路径的写行为，模块无法也无需阻止；后果等同原始「播放失败」，不会比现在更糟。
- 闸门对「目标存在就绝不覆盖」这一约束是**充分**的；唯一残余是上面那条 TOCTOU 只
  导致「漏还原一次」（不补、不重试），无数据损坏，可接受（若采纳 §2 的重试，会顺带
  覆盖这个窗口）。

---

## 6. 参数来源 —— PASS

`orig.imgPath` 来自 `queryOriginal()` 里 `getColumnIndex("imgPath")` + `getString`
（`RevokeRescue.kt:181-186`），随后 `backup` / `protect` / `scheduleRestore` 三者
都先过 `VoiceBackupLogic.isValidImgPath`（`RevokeRescue.kt:112/116/124` 各入口分别
在 `VoiceBackup.kt:104-105/151`、`VoiceFileGuard.kt:116`、`VoiceBackup.kt:197` 校验）。

`isValidImgPath`（`VoiceBackupLogic.kt:67-70`）= **长度恰 26 且全为 `0-9a-f` 小写**：

| 输入 | 结果 |
|------|------|
| 空串 `""` | `length!=26` → false ✅ |
| `"abc...26hex..."`（带引号） | 长度 28 或含 `"`（非 hex）→ false ✅ |
| 大写 hex | `all{...}` 只认小写 a-f → false ✅ |
| 首尾带空格/换行 | 长度 >26 → false ✅ |

均被挡下，不会拿脏数据拼路径去碰文件系统。

**唯一理论点（可接受）**：`queryOriginal` 未对 imgPath 做 `lowercase()`（对比
`VoiceGuard.imgPathsOf` 在 `VoiceGuard.kt:278` 做了 `p.lowercase()`）。若某版本微信
DB 里 imgPath 存的是大写 hex，则备份/还原会静默跳过。这与已定案事实
「真机样本全是 26 位小写 hex」一致，暂不构成风险，但若将来真机出现 `VB skip` 增多，
优先排查这一处。

---

## 7. `VoiceGuard` 注释「没有播放路径 hook」—— PASS

grep 全仓 `playVoice|OnPlayVoice|播放|VoicePlayer|onPlay|startPlay|play\(`，
命中全部是注释/文档（`HookEntry.kt`、`VoiceBackup.kt`、`VoiceGuard.kt`、
`NotifyMute.kt`、`GroupBar.kt`、`VoiceGuardLogic.kt` 等），**没有任何对播放相关
类/方法的 `XposedBridge.hook*` 调用**。`VoiceGuard.kt:196` 的论断
「本项目没有播放路径的 hook，所以不存在『用户点播放前补一次』这种兜底」**属实**。

（推论：正因为没有这条兜底，§2 的延时检查成了唯一还原点，进一步凸显单次检查的缺口。）

---

## 8. 其它发现的问题

1. **`delayThread` 字段只写不读（死字段）。** `VoiceBackup.kt:79` 声明、
   `:217` 赋值，全文再无读取点（已 grep 确认）。注释宣称「单线程、懒初始化」，但
   该引用对线程存活毫无作用——运行中的 `HandlerThread` 自身就是 GC root，持有引用
   并不能防止它退出、不持有也不会被回收。建议删除或改读它用于 `quit()`（当前没这
   需求），否则属误导性死代码。
2. **`scheduleRestore` 外层 `runCatching` 冗余**（见 §4，`restore()` 已内部吞全异常）。
3. **`backup()` 未校验源文件非空。** `backup()` 只判 `src.isFile`（`VoiceBackup.kt:114`），
   若撤回时文件是 0 字节占位（理论上不应发生，语音下载完成后非空），会把 0 字节文件
   备份，随后 `restore` 也会把它补回。`restore` 末尾有 `dst.length()>0` 校验
   （`VoiceBackup.kt:170`），但 `backup` 侧没有对应校验，不对称。属边缘防御，非 bug。
4. **`HandlerThread` 非 daemon。** 默认构造是非 daemon 线程。对长驻微信进程无实际
   影响（进程不会被这线程拖着），但若模块设计上希望「不阻碍宿主退出」，可显式 daemon。
   仅风格问题。
5. **`accountDir()` 非同步。** `restore`/`backup` 可能在不同线程（VoiceBak 线程、
   `VoiceGuard` 的 hook 线程、探针线程）并发首次触发 `accountDir()` 计算
   （`VoiceBackup.kt:251-273`）。结果幂等、字段 `@Volatile`，最坏是重复计算一次，
   无正确性问题。可忽略。
6. **日志噪声可控。** `restore ok` / `restore failed` / `scheduleRestore no handler`
   只在撤回或建线程失败时打，非风暴路径；重试风暴已被 `AntiRevoke.blocked` 静默路径
   挡住（不会重复调 `record()` → 不会重复 `scheduleRestore`），无刷屏风险。

---

## [VERDICT]

**FAIL。**

**必须改（一句话）：单次 +1s 检查在「删除被 executor 排队 >1s」时会 no-op 后漏掉、
且无播放路径 hook 可兜底，需把 `scheduleRestore` 改成有界重试（如 1s / 5s / 15s
各查一次），其余并发、竞争、参数校验均 PASS。**

---

## [修复回执] 2026-10-25 23:56

按本报告「必须改」项落地，逐条对齐：

| 复核项 | 处置 | 落地位置 |
|---|---|---|
| §2 FAIL 单次检查有缺口 | **改成有界重试** `RESTORE_DELAYS_MS = [1s, 5s, 15s]`，三个时刻各查一次；`scheduleRestore` 用 for 循环 `postDelayed` | `VoiceBackup.kt` `RESTORE_DELAYS_MS` + `scheduleRestore` |
| §8.1 `delayThread` 死字段 | **删掉**，不再保存 Thread 引用；KDoc 写明「运行中 HandlerThread 自身是 GC root，存引用无意义」 | `VoiceBackup.kt` 字段区 |
| §8.2 `scheduleRestore` 外层 `runCatching` 冗余 | **删掉**，直接 `h.postDelayed({ restore(img) }, delay)`，注释说明 `restore` 永不抛 | `VoiceBackup.kt` `scheduleRestore` |
| §8.3 `backup` 未校验源非空 | **补 `src.length() <= 0` 跳过**，日志 `VB skip: source empty`（避免「备了 0 字节、补回去还是播不了」的误导） | `VoiceBackup.kt` `backup` |
| §8.4 HandlerThread 非 daemon | 不改（判定为风格问题，对长驻微信进程无实际影响） | — |
| §8.5 `accountDir()` 非同步 | 不改（幂等 + `@Volatile`，最坏重复计算一次） | — |
| §1.2 阻塞 >1s 风险 | 由 §2 的三次重试覆盖 | — |

验证：`assembleDebug` BUILD SUCCESSFUL；全量单测 **648 tests / 0 failures / 0 errors**（改了实现但判据类 `VoiceBackupLogic` 未动，用例不受影响）。

⚠️ 仍未验证（只能真机）：三次重试是否真的有一次命中「文件已被删」。判据见
`tools/_r21c_voice_chain.py` 的**【判据 3】`VB restore ok`** —— 日志里出现它才算闭环。
