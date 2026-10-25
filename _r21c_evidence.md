# R21c 取证事实（问题 3：防撤回语音播放失败）

## 已闭环：A 排除（真机实测 2026-10-25 15:24:56）

`tools/_r21c_voice_state.py` 直接查 EnMicroMsg.db，6 条 `orig_type=34` 的行**此刻**的形态：

| msgId | type | isSend | msgSvrId | content |
|---|---|---|---|---|
| 19289041 | **34** | 0 | 6139184963485905836 | `wxid_3dmyz2iqavq822:30478:0` |
| 19289057 | **34** | 0 | 3941829116377846651 | `wxid_3dmyz2iqavq822:12424:0` |
| 19289088 | **34** | 0 | 4749556186668879079 | `wxid_3dmyz2iqavq822:19648:0` |
| 19289117 | **34** | 0 | 1083697330173103688 | `wxid_3dmyz2iqavq822:2684:0` |
| 19289824 | **34** | 0 | 2129905180187988684 | `wxid_3dmyz2iqavq822:20785:1:` |
| 19290094 | **34** | 0 | 6524856252354654119 | `wxid_xlrbelf6hv0w22:2860:1:` |

**判据结果**：`type` 全部 = 34（语音原形态），**没有一个是 `0x10000000|10000 = 268445456`**。

⇒ **根因 A（「恢复默认显示」开着把消息改写成撤回形态）已被排除。**
  这不是预期行为，是**真 bug**。

## 附带修正两条既有认知

1. **`content` 第 3 段不是固定的 `0`** —— `19289824` 和 `19290094` 是 `:1:`（**4 段**，尾段为空）。
   之前记录「`:20785:1:` 是变体」得到证实：**段数不固定**，解析时长只能取第 2 段。
2. **`msgSvrId` 是 19 位正数**（如 `6139184963485905836`），不是负数、不是 msgId 同值。
   ⇒ 语音文件若按 msgSvrId 命名，可以直接用这 6 个值去 `voice2/` 查。

## 下一步判据（正在跑 `tools/_r21c_silk.py`）

查 `voice2/` 下是否有对应 `msgSvrId` 文件 + **对照组**（正常未撤回语音的文件在不在）。
- 对照在 & 这 6 条缺失 ⇒ **根因 C 成立**：微信撤回流程**先删媒体文件再写库**，
  我们只拦了写库（hook SQLite `update`），文件删除是文件系统操作，拦不住。
- 对照也不在 ⇒ 文件根目录判断错了，重查路径。

---

# 第二轮取证：imgPath（2026-10-25 15:31:42）

依据：lane `voice-root` 在 `classes4.dex` 找到一条真实 SQL
`DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN ...`
⇒ 语音文件的本地路径存在 `message.imgPath`。

## 实测结果

| msgId | type | imgPath 长度 | imgPath | status/flag | 归属 |
|---|---|---|---|---|---|
| 19289041 | 34 | 26 | `46142710252654b6378ddb4103` | 3/0 | 被救下 |
| 19289057 | 34 | 26 | `09142810252654b6378349f100` | 3/0 | 被救下 |
| 19289088 | 34 | 26 | `16142910252654b63783b00103` | 3/0 | 被救下 |
| 19289117 | 34 | 26 | `55142910252654b6378d32f100` | 3/0 | 被救下 |
| 19289824 | 34 | 26 | `33145810252654b63780c01104` | 3/0 | 被救下 |
| 19290094 | 34 | 26 | `5615081025263e6e1a38aef106` | 3/0 | 被救下 |
| 19289165 | 34 | 26 | `05143110252654b6378e583103` | 3/0 | **对照** |
| 19289006 | 34 | 26 | `59142610252654b6378236f102` | 3/0 | **对照** |

## 结论 1：B 变体排除
`imgPath` 6 条全非空、`length` 与对照**完全一致（26）** ⇒ 微信**没有**单独发一条「只清 imgPath」的 update，
⇒ `voice-root` §2.4 的旁路假设**不成立**。字段污染这条也基本死掉（`type`/`content`/`imgPath`/`status`/`flag` 全部原样）。

## 结论 2（关键）：imgPath 是 **VFS 路径**，不是文件系统绝对路径
值形如 `46142710252654b6378ddb4103` —— **24 位十六进制**，无 `/`、无目录、无扩展名。

这解释了本轮前 5 次取证为什么全失败：
- `${sdTo}/MicroMsg/${accountSd}/voice2` 这些 DEX 模板是**老的直连文件系统**路径
- 微信 8.0.48 已切到 `com.tencent.mm.vfs`（`voice-root` 在 classes6.dex 找到 `VoiceGCFileSystem`）
- VFS 把真实路径映射成这种 hex 串 ⇒ **在真实文件系统里按名字找不到任何语音文件**
- 我用 `adb`/`CDN ls` 在 `/sdcard`、`/data/user/0` 下怎么翻都是空 ⇒ **不是「文件不存在」，是「我找错了坐标空间」**

## 结论 3：一条重要的推理链（待最终证实）
`restore_view=false`（voice-root 实测）⇒ `applyOne()` 在 `RevokeRescue.kt:264` **直接 return**
⇒ `valuesFor()` / `updateRow()` **从未执行** ⇒ **我们根本没写过这些行**。

那么「语音播不了」只剩两种可能：
- **C-1**：微信撤回流程**先删媒体文件、再写库**。我们拦住的那次 update 是「写库」这一步，
  文件删除走 VFS/文件系统，`updateWithOnConflict` hook 拦不到 ⇒ 气泡还在（行没改）、**音频本体已没了** ⇒ 点播放失败。
  这与 `type=34`、`content` 完整、`imgPath` 非空**完全自洽** —— imgPath 只是"引用"，微信删文件时不维护这列。
- **C-2**：文件还在，但 VFS 映射断了 / 我们的某个 hook 干扰了 VFS 打开。

**这两个必须靠「VFS 路径 → 文件是否可打开」来分。** 这正是下一步。

## 下一步（需 vfs 解析）
`imgPath` = 24 hex。要判文件在不在，必须走微信自己的 VFS API：
- `com.tencent.mm.vfs.VFSFile` / `FileSystem`（`voice-root` 已在 classes6.dex 见到 `VoiceGCFileSystem`）
- 或 `com.tencent.mm.vfs.VFSFileOp` 之类
⇒ 给探针加 `sub=vfs --es val <hex>`，用 `VFSFile(hex).exists()` + `.length()` 判存在；
再对**对照**跑一遍（对照存在 + 被救下缺失 ⇒ C-1 定案）。

---

# 第三轮取证：根因定案（2026-10-25 15:34:47）

## `voiceinfo` 表结构（EnMicroMsg.db，真机 pragma 实测）
主键 `FileName`(TEXT)，另有 `User` / `MsgId` / `NetOffset` / `FileNowSize` / `TotalLen` /
`Status` / `CreateTime` / `LastModifyTime` / `ClientId` / `VoiceLength` / `MsgLocalId` /
`Human` / `reserved1` / `reserved2` / `MsgSource` / `MsgFlag` / `MsgSeq` / `MasterBufId` / `checksum`

## 决定性对照实验（**两种查法交叉验证**）
| 组 | 按 `FileName = imgPath` 查 | 按 `MsgLocalId = msgId` 查 |
|---|---|---|
| 6 条被救下语音 | **0/6 无行** | **0/6 无行** |
| 2 条正常对照语音 | 2/2 有行 | 2/2 有行 |

对照行的实际内容：
```
MsgLocalId=19289165 | FileName=05143110252654b6378e583103 | Status=99 | TotalLen=40973 | FileNowSize=40973
MsgLocalId=19289006 | FileName=59142610252654b6378236f102 | Status=99 | TotalLen=35827 | FileNowSize=35827
```
`Status=99` = 下载完成；`TotalLen == FileNowSize` = 文件完整。

## 为什么必须用两种查法
`voiceinfo` 有 `FileName` 和 `MsgLocalId` 两个键。「按 FileName 查不到」本来有两种解释：
- D-1 整行被删
- D-2 行还在但 `FileName` 被改了

**两种查法都是 0/6 ⇒ 排除 D-2 ⇒ D-1 定案。**

## 根因（最终）
**「防撤回」只保住了 `message` 行，没保住 `voiceinfo` 行。**

微信撤回流程对**语音**做的是两步：
1. `DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE ...)`
   —— 删掉语音的**下载记录**（`voice-root` 在 `classes4.dex` 找到这条真实 SQL）
2. `UPDATE message SET type=..., content=... WHERE msgId=?`
   —— 把消息行改成撤回形态（**这一步被我们拦住了**）

我们的 `AntiRevoke` 只 hook 了 `message` 的 `update`，**第 1 步的 `DELETE` 没拦**
⇒ `message` 行还在（气泡显示正常，`type=34`、`content` 完整、`imgPath` 非空）
⇒ 但 `voiceinfo` 里那条下载记录没了
⇒ 播放器要按 `imgPath` 去 `voiceinfo` 拿 `Status`/`TotalLen`/`NetOffset` 才能播
⇒ **查不到 ⇒ 点了没反应** ⇒ 用户看到的就是「防撤回语音后播放失败」

## ⛔ 本轮已排除的错误方向（不要再走）
| 方向 | 为什么错 |
|---|---|
| 「恢复默认显示」开着导致被改写成撤回形态（A） | 6 条 `type` 全是 34，没有 268445456 |
| `origExtra` 三字段快照不充分（B） | `restore_view=false` ⇒ `applyOne()` 在 :264 直接 return ⇒ **我们从未写过这些行** |
| `imgPath` 被单独清空（B 变体） | `imgPath` 6 条全非空，长度与对照一致（26） |
| 文件本体被删（C） | 走错坐标空间：`imgPath` 是 **VFS 24-hex 虚拟路径**，不是文件系统路径 |
| 在 `/sdcard`、`/data/user/0` 下找 `voice2/` | 微信 8.0.48 已切 `com.tencent.mm.vfs`；DEX 里的 `${sdTo}/MicroMsg/${accountSd}/voice2` 是**老模板**。为此白写了 6 版脚本（`_r21c_silk.py` ~ `_r21c_silk6.py`），全废 |

## ✅ 附：hint 语音时长单位「毫秒」已端到端闭环（第四轮，2026-10-25 15:43）

`HintTextLogic.voiceSeconds` 取 `content` 第 2 段当毫秒。端口到端对账（`tools/_r21c_verify_hint6.py`）：

| msgId | content | voiceinfo.TotalLen | 第 2 段 | B/s(按毫秒) | B/s(按秒) |
|---|---|---|---|---|---|
| 19289165 | `wxid_3dmyz2iqavq822:23475:1:` | 40973 | 23475 | **1745** ✅ | 1.75 |
| 19289006 | `wxid_3dmyz2iqavq822:20665:1:` | 35827 | 20665 | **1734** ✅ | 1.73 |

两条码率 **1745 / 1734 B/s，相差 0.6%** —— 恒定码率 silk 语音（≈13.9 kbps）的教科书特征。
按秒算只有 1.7 B/s，比任何音频编码低两个数量级。**单位判据闭环。**

### ⛔ 附带挖出的工具坑：`label sub=sql` 不自己开库（本轮最贵，白跑 4 版脚本）

设备日志原文：
```
15:42:07.762 Thread-6  LABEL sql: no db instance (先 sub=on 开窗口)
```
`LabelHuntProbe.sub=sql` 查的是**已收集到的 db 实例**，而这个池子靠 `sub=on` 挂钩子
在「微信开库那一刻」抓。**微信每次冷启动（`package loaded`）进程重建 ⇒ 池子清零**
⇒ 之后所有查询静默返空（**只回 `no db instance` 一行，不报错**）。

- ⚠️ 这与「微信后台不投递广播」是**两个不同的坑**，别混。
- ⚠️ 症状极像「表没这个字段 / SQL 写错了」：`_r21c_verify_hint2.py` / `_hint3.py` 都因此误判成
  「探针不支持 `length(content) cl` 别名」「`in ()` 一次取多条不行」，**两个判断都是假的**。
  真相：`_r21c_diag_msg.py` 里 q2/q3/q4 能出结果，纯粹因为当时窗口碰巧还开着。
- ✅ **正确姿势**：`sub=on --ei sec 180` → `am start` → 等就绪 → 再 `sub=sql`。
  `tools/_r21c_verify_hint6.py` 已把这套封装成 `open_window()` + 遇 `no db instance` 自动重开。

## 修复方向（**待设计，尚未实现**）
hook `voiceinfo` 的 `DELETE`：撤回删下载记录时拦下来（与 `AntiRevoke` 拦 `message` update 同思路）。
- 入口候选：`com.tencent.wcdb.database.SQLiteDatabase.delete` / `deleteWithOnConflict`
- 判据：`table == "voiceinfo"`，且被删的 `FileName` 属于**我们已救下的** msgId（查 `rescued` 台账 / 反查 `message.imgPath`）
- ⚠️ 风险：`voiceinfo` 也用于**正常的语音清理**（微信自己删过期语音）。不能无条件拦，必须认人。
