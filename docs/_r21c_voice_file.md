# R21c 语音撤回「播放失败」根因定案：第三道破坏是删磁盘文件

> 2026-10-25 真机实锤。**推翻前三轮的「只有两道破坏」结论。**
> 本文件只记录**已验证的事实**，未验证的推断单独标注。

---

## §1 结论速览

撤回一条**语音**消息，微信做三件事：

| # | 动作 | 拦到了吗 | 拦的人 |
|---|---|---|---|
| 1 | `UPDATE message SET type=... WHERE msgId=?` | ✅ | `AntiRevoke` |
| 2 | `DELETE FROM voiceinfo WHERE FileName= ?` | ✅ | `VoiceGuard` |
| 3 | **`File.delete(voice2/<桶>/msg_<imgPath>.amr)`** | ❌ 前三轮全漏 | （本轮新增 `VoiceFileGuard`） |

**只拦前两步时真机现象**：`voiceinfo` 行完好（`Status=99`、`TotalLen==FileNowSize`）、
`message` 行仍是 `type=34`、气泡还在、日志有完整的成对拦截记录 —— **但点播放毫无反应**。

⇒ 「表里有记录」不等于「能播」。播放要求「表行 + 磁盘文件」齐备。

---

## §2 实证过程（可复现）

### 2.1 第一次真机测试：三条链路全绿，播放仍失败

群「测试群聊」好友 verity 发的语音，撤回后日志：

```
23:04:55.542  AR blocked revoke msgId=19301779 self=false
23:04:55.571  VG blocked voiceinfo delete where=FileName= ? args=4323041025263e6e1a32793103
```

两条相隔 **29ms**，顺序是「先 UPDATE、后 DELETE」，与反汇编结论一致。
库状态核验：

| 检查项 | 值 | 判定 |
|---|---|---|
| `voiceinfo.FileName` | `4323041025263e6e1a32793103` | ✅ 行仍在 |
| `Status` | 99 | ✅ |
| `TotalLen` / `FileNowSize` | 4867 / 4867 | ✅ 完整 |
| `message.type` | 34（非撤回形态） | ✅ 未被改写 |
| `message.imgPath` | 与日志 `args=` 逐字一致 | ✅ 链路对上 |

**用户实测：播放失败。** ⇒ 「两道都拦住」不等于「修好了」。

### 2.2 全树递归扫描：对照组证明探针有效

新增探针 `op=label sub=find`（递归找文件名含关键字的文件）。**同一遍扫描内做对照**：

```
find '50095010242638d35faef1f106': hits=1 visited=40967 dirs=20827
  HIT .../voice2/01/65/msg_50095010242638d35faef1f106.amr size=20477B
find '4323041025263e6e1a32793103': hits=0 visited=40967 dirs=20827
```

| 组 | 对象 | 结果 |
|---|---|---|
| 对照 | 未撤回的语音 `50095010242638d35faef1f106` | `hits=1` 找到 .amr |
| 实验 | 被撤回的 `4323041025263e6e1a32793103` | `hits=0` **文件不存在** |

⛔ **关键是两组 `visited` / `dirs` 完全相同（40967 / 20827）** —— 证明两次扫描覆盖范围一致、
探针本身没漏扫。「找不到」是真的找不到，不是扫描失败。

### 2.3 桶目录 mtime 的时间戳旁证

被撤那条的桶目录 `voice2/01/70/`：**存在但为空**，`mtime=1792940695567`（23:04:55.567），
而 `VG blocked` 日志时间是 `23:04:55.571` —— 差 **4ms**。

⚠️ 这条只是旁证。空目录在 `voice2/` 里是常态（`01/d3` 也是空的、mtime 很早），
所以**不能**只凭「目录空」定案。定案靠的是 §2.2 的对照组命中差异。

---

## §3 音频文件路径规则（本轮破解）

```
voice2/<md5(imgPath)[0:2]>/<md5(imgPath)[2:4]>/msg_<imgPath>.amr
```

- 两级桶，各取 md5 小写 hex 的连续 2 个字符
- 文件名 `msg_` + imgPath（26 位 hex）+ `.amr`
- 微信给 silk 套了 `.amr` 外壳

### 验证样本（21 条真机路径，20 命中）

| imgPath | 实际桶 | md5[:4] | 命中 |
|---|---|---|---|
| `50095010242638d35faef1f106` | `01/65` | `0165` | ✅ |
| `3619341025268a9e016c7d4104` | `96/ce` | `96ce` | ✅ |
| `05143110252654b6378e583103` | `b0/f7` | `b0f7` | ✅ |
| `59142610252654b6378236f102` | `9d/73` | `9d73` | ✅ |
| `4323041025263e6e1a32793103`（被撤） | `01/70` | `0170` | ✅ |

唯一未命中：`542132102426d122a6a55b101`（**27 位**，多一位）—— 属异常样本，被当非法输入。

### ⛔ 两个必须记住的坑

1. **分桶用 md5，不是 imgPath 自身字符切分。**
   反例：`0514311025...` 以 `05` 开头，实际落在 `b0/f7`。两者毫无关系。
2. **不能靠全树扫来定位文件。**
   实测 **1.5 秒 / 20827 个目录**。而备份窗口（撤回拦截 → `vj0.p` 真正删文件）
   只隔着一次 `executor.submit` 线程切换，几十毫秒。扫完文件早没了，且把微信卡住。
   ⇒ 必须 O(1) 算路径。

---

## §4 修复结构（本轮新增）

| 文件 | 职责 |
|---|---|
| `data/VoiceBackupLogic.kt` | 纯判据：手写 MD5、桶计算、路径反解、`shouldBackup`/`shouldRestore`。零 Android 依赖，有 JVM 单测 |
| `feature/VoiceFileGuard.kt` | hook `java.io.File.delete/deleteOnExit`，纯字符串判据拦住对语音包的删除 |
| `feature/VoiceBackup.kt` | 备份/还原执行器，目录 `MicroMsg/<hash>/voice_bak/`（微信自己目录下，用户选定） |

接入点：
- `RevokeRescue.record()` 成功后 ⇒ ① `VoiceFileGuard.protect(imgPath)` + ② `VoiceBackup.backup(...)` + ③ `VoiceBackup.scheduleRestore(imgPath)`
- `VoiceGuard.onDelete` 命中后 ⇒ `VoiceBackup.restore(imgPath)`（**降级为「免费早触发」**，见下）

### 4.1 ③ 为什么必须有（R21c-4 复核判 FAIL 的修复）

已定案顺序是 `voiceinfo` DELETE **在前**、`File.delete`(磁盘) **在后**。所以在
`VoiceGuard.onDelete` 命中那一刻，**文件还在** ⇒ `shouldRestore(backup=true, target=true)`
恒 false ⇒ 那次 restore 是空操作；随后文件被删，就再没人补了。

`scheduleRestore` 在 `record()` 里排一个 **+1s 的幂等检查**（`HandlerThread` 上跑）：
删除链跑完之后才检查，文件没了就补回，还在就 no-op。
⚠️ 本项目**没有播放路径 hook**，不存在「用户点播放前补一次」那种兜底。

探针：`op=voicebak sub=backup|restore|ls`（`--es sql <imgPath>`）、`op=label sub=file|find`

---

## §5 单测与变异验证

`VoiceBackupLogicTest`：MD5 官方向量 7 条 + 真机桶样本 21 条 + 路径反解误拦反例 13 条。

**变异验证**（证明用例不是假绿）：

| 注入变异 | 变红的用例 | 结论 |
|---|---|---|
| `contains("/voice2/")` → `contains("voice2")` | `voiceSegNeedsBothSlashes` + `imgPathRejectsNonVoiceFiles`（**恰好 2 条**） | ✅ 真护栏 |
| `md5Hex` 退化成返回原串 | 5 条 md5/桶相关用例（**恰好 5 条**） | ✅ 真护栏 |
| `endsWith(".amr")` → `contains(".amr")` | **0 条** | ⚠️ 等价变异（见下），不算验证 |
| 后缀判据整体删掉（`take(26)`） | `imgPathRejectsTmpSuffix` + `imgPathRejectsNonVoiceFiles`（**恰好 2 条**） | ✅ 真护栏（R21c-5 补） |

⚠️ 第一次做的变异**无效**：改了 `bucketOf` 内部切分，但入参本来就是 md5，等于没改。
教训：变异必须打在**真正决定结果的那一层**。

⚠️ 第二次「0 条变红」也不是假绿：`endsWith` 改成 `contains` 后，`.amr.tmp` 会切出 30 位
字符串，被 `isValidImgPath` 的长度校验挡住 —— 我用脚本对 26 条输入穷举比对，两版**行为
完全等价**（0 条差异）。所以这个变异**天然无语义**，必须换成「整体删掉后缀判据」才打得到。

---

## §6 尚未验证的假设（上真机才知道）

| # | 假设 | 若不成立会怎样 | 证伪判据 |
|---|---|---|---|
| 1 | 微信删语音文件走 **Java** `File.delete()`，不是 native `unlink()` | `VoiceFileGuard` 完全无效 | 撤回后日志**没有** `VFG blocked file delete` |
| 2 | 备份发生在异步删除**之前** | 备份到空文件/半个文件 | `VB backup ok` 的 `bytes` 与实际文件大小不符 |
| 3 | 删除链在 `record()` 之后 **1 秒内**跑完 | `scheduleRestore` 检查时文件还在 ⇒ no-op ⇒ 之后被删 ⇒ 仍然播不了 | 日志有 `VB restore ok` 才证明补到了；只有 `VB backup ok` 而无 `restore ok` = 这条假设不成立 |

第 1 条是最大风险点 —— 若微信走 native，本轮修复等于没做，必须换方案
（例如在撤回拦截时就把文件**移走**再放回，而不是等删的时候拦）。

---

## §7 真机验证结果（2026-10-26 00:05）

### 7.1 结论：**`File.delete` hook 有效，路径 A 闭环**

上一轮 §6 假设 1（「微信可能走 native `unlink`，hook 拦不到」）**被证伪** ——
真机上 hook 确实命中了。

被撤回的语音：`测试群聊` 群里 verity 发的 `msgId=19303250`，
`imgPath=2800051026263e6e1a3c2ee106`。

### 7.2 完整时间线（同一条撤回，日志实锤）

| 时刻 | 日志 | 含义 |
|---|---|---|
| 00:05:45.858 | `RRV record msgId=19303250 ok=true origType=34` | 撤回被拦、台账写下 |
| 00:05:45.860 | `VFG protect img=28000510… ttl=60000ms pool=1` | 登记保护（+2ms） |
| 00:05:45.862 | `VB backup ok bytes=4578 dst=f6/86/…` | 备份拿到文件（+4ms） |
| **00:05:45.894** | **`VFG blocked file delete img=28000510…`** | **删除被拦（+36ms）** |
| 00:06:39 | `chain: src(exists=true,4578B) bak(exists=true,4578B)` | 文件在原位完好 |
| 00:07:02 | `message: type=34 imgPath=28000510… cl=28` | 表里仍是语音 |

**关键量级**：`record()` → `File.delete` 只隔 **36 毫秒**。这印证了
§4.1 里「1s 窗口有约 250 倍余量」的估算（36ms × 28 ≈ 1000ms）。

### 7.3 ⛔ 一个必须记住的判据设计错误（我自己踩的）

我最初把两个判据写成「并列缺一不可」：

```
判据1 VFG blocked file delete   ← 命中
判据3 VB restore ok             ← 空
⇒ 报「链路没闭环」← 错误！
```

**这两条是互斥的两条路径，不是并列要求**：

| 路径 | 判据 1 | 判据 3 | 说明 |
|---|---|---|---|
| **A：hook 拦住了** | ✅ 命中 | 必然空 | 文件从没被删 ⇒ `shouldRestore(备份在&&原位不在)` 正确 no-op |
| **B：hook 没拦住** | 空 | 应命中 | 删除成功，靠备份补回 |

总判 = **`判据1 或 判据3 至少一个成立`**（= 文件最终在原位）。
把「A 路径下 restore 不动作」当成失败，等于**要求已经成功的修复再做一遍无用功**。

✅ 判据已固化进 `tools/_r21c_voice_chain.py`（含 `elif blocked:` 修复
「清日志后误报 hook 没装」的假警报）。

### 7.4 三条独立自证（不依赖撤回动作）

用 `op=voicebak sub=chain` 探针（**新增**，`--es sql <imgPath>`）验证路径规则与账号目录推导：

```
acc=/data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37
bucket=01/65 src(exists=true,20477B) restore(ok=false) md5=01653b43fbb1575f2572cba8af5dca8d
```

- 账号目录推导**命中**（没扫 MicroMsg/ 兜底）
- `md5` 前四位 `0165` ⇒ 桶 `01/65`，与全树扫描结果一致
- `20477B` 与上一轮扫描字节数**完全一致**
- `backup` + `stat` 验证落盘：`files=1 bytes=20477 recent[ 65/msg_50095010….amr=20477B ]`

⚠️ 保留了一份 `50095010242638d35faef1f106` 的备份在 `voice_bak/` 作天然对照组。

### 7.5 仍未验证

| 项 | 状态 |
|---|---|
| **点播放能不能出声** | ⏳ 等用户确认（唯一的人工判据） |
| 路径 B（hook 没拦住时 restore 真补回） | ⏳ 未触发过。本轮 hook 全命中，没有机会走 B |
| `voice_bak` 会不会被微信 GC 扫到 | ⏳ 长期观察 |

---

## §8 最终结论：**R21c 已闭环**（2026-10-26 00:08，用户人工确认）

> 用户在「测试群聊」群里点击被撤回的语音（verity 发，`msgId=19303250`，
> `imgPath=2800051026263e6e1a3c2ee106`）——**能正常播放**。

### 8.1 「播放失败」的完整根因（三道破坏，逐一定位）

| # | 微信的动作 | 前三轮的处置 | 结果 |
|---|---|---|---|
| 1 | `UPDATE message SET type=... WHERE msgId=?` | `AntiRevoke` 拦 update | ✅ 早就解决了 |
| 2 | `DELETE FROM voiceinfo WHERE FileName=?` | `VoiceGuard` 拦 delete | ✅ R21b 解决了 |
| 3 | **`File.delete(voice2/<md5桶>/msg_<imgPath>.amr)`** | **没人管（不过 SQLite）** | ❌ **这就是「播放失败」的真凶** |

只拦 1、2 时：`message` 行还是 `type=34`、`voiceinfo` 行完好、界面气泡也在，
**但点播放毫无反应** —— 因为播放器要求「表行 + 磁盘文件」齐备。
§2 的全树扫描（对照组 1 命中 / 实验组 0 命中，`visited=40967 dirs=20827` 两边一致）
把这一步钉死了。

### 8.2 最终修复结构（三层，各自独立生效）

```
撤回被拦 (AntiRevoke)
  └─ record() 同一次拦截内，此刻文件必定还在：
       ├─ ① VoiceFileGuard.protect(imgPath)   ← 主路径：hook File.delete 直接拦住
       ├─ ② VoiceBackup.backup(type,imgPath)  ← 留副本（路径 B 的弹药）
       └─ ③ VoiceBackup.scheduleRestore(imgPath) ← 1s/5s/15s 三次幂等补回
```

真机实测走的是 **①路径 A**：`record()` 后 **36ms** `File.delete` 命中被拦，
文件从未离开原位 ⇒ ③ 正确 no-op（`shouldRestore(备份在 && 原位不在)`）。

### 8.3 关键数值（以后别再重测）

| 量 | 实测值 |
|---|---|
| `record()` → `File.delete` | **36 ms** |
| 被撤语音文件大小 | 4578 B（对照组样本 20477 B） |
| 全树扫 `voice2/` | 1.5s / 20827 目录 / 40967 节点 ⇒ 必须 O(1) 算路径 |
| 路径规则 | `voice2/<md5(imgPath)[0:2]>/<md5(imgPath)[2:4]>/msg_<imgPath>.amr`（21 样本 20 命中） |
| 备份体积代价 | 每条语音 ≈ 源文件大小（KB 级） |

### 8.4 ⚠️ 未验证项（诚实记录，别当成已验证）

| 项 | 状态 | 风险 |
|---|---|---|
| **路径 B**（hook 没拦住时 restore 真补回） | **从未触发** | ①若某天微信改走 native unlink，会切到 B；B 只有静态推理，无实证 |
| `voice_bak` 会不会被微信 GC 扫到 | 长期观察 | 被删了 ⇒ B 失效（A 仍有效） |
| 15s 那次重试是否必要 | 未观测到 5s/15s 命中 | 无害（幂等 O(1)），但可能纯属多余 |
| 大写 hex imgPath | 未遇到 | `isValidImgPath` 只认小写；若某版本存大写 ⇒ 静默跳过 |

### 8.5 相关文档

- `docs/_r21c_voice_file_review.md` —— 第一轮复核（Q4 FAIL ⇒ 已修）
- `docs/_r21c_delay_review.md` —— 延时还原复核（§2 FAIL ⇒ 已改有界重试）
- `tools/_r21c_voice_chain.py` —— 判据脚本（含「1 与 3 互斥」的正确判法）
- `tools/_r21c_voice_file_exists.py` —— 带对照组的文件存在性探针

### 8.6 稳定性复核（撤回后 6 分钟，2026-10-26 00:12）

| 时刻 | `src` | `bak` |
|---|---|---|
| 00:06:39（撤回后 54s） | exists=true, 4578B | exists=true, 4578B |
| 00:12:08（撤回后 6分22s） | **exists=true, 4578B** | exists=true, 4578B |

⇒ 文件**没有被后续的 GC / 清理任务二次删除**。`VFG` 的 60s 保护窗到期后
（`ttl=60000ms`，到 00:06:45 失效）文件仍然在，说明微信**只删一次**、不重试
—— 这是「拦一次就够」的关键前提，本次实测成立。
