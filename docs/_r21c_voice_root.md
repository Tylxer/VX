# R21c 取证 · 防撤回语音后播放失败 —— 根因判定

> 用户报障原话：**「防撤回语音后，播放失败」**
>
> lane：`voice-root`（取证 + 设计，只读源码 / 只写探针脚本，不碰 adb、不改代码）
> 关联代码：`RevokeRescue.kt` / `AntiRevoke.kt` / `CvCodec.kt` / `GroupDb.kt`
> 关联台账：`rescued` 表 `orig_type=34`（语音）共 6 条

---

## §1 三条根因判定表

| 根因 | 验什么 | 看哪个字段/文件 | 期望值 | 若成立说明什么 | 判定所需数据现在有没有 |
|------|--------|----------------|--------|----------------|------------------------|
| **A 预期行为**（恢复默认显示开着） | 6 条语音此刻在消息库里的 `type` + 开关 `antirevoke.restore_view` | `message.type`；`kv: antirevoke.restore_view` | 若 `type=268445456` 且 `restore_view=true` ⇒ A 成立 | 那行被改写成撤回形态，播不了是**预期**，不该改代码 | ✅ **已有，且已证伪**：`_rv.txt` 实测 `type=34`、`restore_view=false` |
| **B 字段污染** | 语音播放依赖的列（`imgPath`/`msgSvrId`/`status`/`flag`/`lvbuffer`）是否残留伪装值 | `message.imgPath/msgSvrId/status/flag` + 台账 `revoke_extra` 的完整字段清单 | 若 `imgPath` 为空/`status`≠已下载，且 `revoke_extra` 里带这些键 ⇒ B 成立 | 还原时 `origExtra` 只覆盖 isSend/lvbuffer/reserved，覆盖不回来 | ⚠️ **部分缺**：需要主会话补取「imgPath/status/flag」+「revoke_extra 全字段解码」 |
| **C silk 文件被删** | `voice2/` 下对应音频文件是否存在 | `message.imgPath`（若非空直接就是路径）；或 `voice2/<msgSvrId>` | 对照（正常语音）文件在、这 6 条文件不在 ⇒ C 成立 | 撤回流程先删文件再写库，文件删除走文件系统、不走 SQLite，拦不住 | ⚠️ **部分缺**：需要主会话跑 §3 的 `voice2` 存在性探针（`tools/_r21c_silk.py` 已备好） |

**一句话可证伪判据**：
- A：`select type from message where msgId=19289041` 若 `=34`（非 268445456）⇒ A 死。
- B：`select imgPath from message where msgId=19289041` 若非空且指向存在的 `.amr` ⇒ B 死（imgPath 没被污染）。
- C：`voice2/` 下那条语音的音频文件不存在、而一条**没被撤回**的正常语音文件存在 ⇒ C 成立。

### §1.1 需要主会话补取的数据 + 可直接执行的 adb 命令

> ⚠️ **本节的 voice2 枚举命令已被 §6.4 修正**：voice2 在 `${accountSd}`（≠ DB 哈希）下，别再按 `89476c7f…` 拼路径。**首选查 `imgPath`，见 §6.4。**

探针入口固定走 `op=label --es sub sql`（会遍历 16 个库，只有 `EnMicroMsg.db` 有 `message` 表，其余 `no such table` 是噪声）：

```bash
# 首选（权威）：查 imgPath —— 它直接就是语音文件的完整路径，绕开所有哈希猜测
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op label --es sub sql \
  --es sql "select msgId,imgPath,msgSvrId,status,flag from message where msgId in (19289041,19289057,19289088,19289117,19289824,19290094)"
# 拿到每个 imgPath 后逐个 ls：
adb shell ls -la '<imgPath>'
```

> 或直接跑现成脚本 `python tools/_r21c_imgpath.py`（只读，已备好，输出含判据结论）。

---

## §2 `origExtra` 三字段快照的充分性分析（B 的核心）

### 2.1 结论先行

**只快照 `isSend`/`lvbuffer`/`reserved` 三个字段，在「语音」这个场景下是够的。** 理由有三条，都来自源码或已有真机证据，不是猜：

1. **撤回 update 是整条被拦掉的，不是部分写入。**
   `AntiRevoke.guardMessage()` 在 `:497` 设 `param.result = FAKE_ROWS`，整个 `updateWithOnConflict` 的返回值被替换，**SQLite 根本没执行那次撤回写入**。所以撤回 ContentValues 里无论带什么字段（哪怕带了 `imgPath`），都不会落到库里。B 的「伪装写入污染」不可能由撤回 update 本身造成。

2. **我们自己写的伪装（`applyOne`/`applyView(on=true)`）只重放 `revokeExtra`，而 `revokeExtra` 的字段面已被真机实测钉死。**
   `AntiRevoke.kt:83` 与 `CvCodec.kt:10-12` 两处注释（真机实测）都写着：微信真撤回写的字段是 `type` + `content` + `lvbuffer`（两次都有）+ `isSend`/`reserved`（按撤回方二选一）。`revokeExtra = CvCodec.encodeFrom(values, skip=msgId)` 就是对**这份撤回 ContentValues** 的完整快照 ⇒ 字段面 = `{type, content, lvbuffer, isSend, reserved}`。
   而 `valuesFor(on=false)`（`:487-495`）写回的正是 `{isSend, lvbuffer, reserved}`（来自 origExtra）+ `{type, content}`（来自 orig_type/orig_content）⇒ **五个字段全部覆盖**。

3. **`imgPath`（语音文件路径）既不被我们写、也不被撤回 update 写。**
   `valuesFor` 全部分支只 `put` 上面五个键；`updateRow` 只写这个 ContentValues。`imgPath` 从未进入任何写入路径 ⇒ 不会被我们「写坏」。

### 2.2 逐字段核：语音被写过之后哪些列可能留在伪装值上

| 列 | 谁会写它 | origExtra 覆盖？ | 语音播放依赖？ | 残留风险 |
|----|----------|-----------------|----------------|----------|
| `type` | 撤回 update（拦）/ 我们的 mask | ✅ 用 `orig_type` | 是（34 才是语音） | 无（`_rv.txt` 实测 =34） |
| `content` | 同上 | ✅ 用 `orig_content` | 是（索引串） | 无（`_rv.txt` 实测是索引串） |
| `isSend` | 同上 | ✅ `origExtra.isSend` | 否（决定左右气泡） | 无 |
| `lvbuffer` | 同上 | ✅ `origExtra.lvbuffer` | 可能（语音元数据） | 无（快照了） |
| `reserved` | 同上 | ✅ `origExtra.reserved` | 否 | 无 |
| `imgPath` | **无人写** | ❌ 未快照 | **是（语音文件路径，见 2.3）** | ⚠️ 不覆盖，但也没人写坏它 |
| `msgSvrId` | 只 insert 时写 | ❌ 未快照 | 是（voice2 文件名候选） | 无（撤回不写它） |
| `status`/`flag` | 下载/状态更新 | ❌ 未快照 | 可能（已下载标记） | ⚠️ 需查当前值 |

### 2.3 ⚠️ 一个必须补证的疑点：`imgPath` 才是语音文件路径

`docs/_r16_g2_msgsnap.md:346` 说语音路径「极可能藏在 `field_lvbuffer` 或 `field_imgPath`」（当时是**推测**）。
本轮在 dex 字符串表里拿到**两条互相印证的铁证**：

1. classes4.dex 的真实 SQL：
```
'DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN ...'
```
`voiceinfo` 表（classes7.dex 建表串）是语音**续传/下载**表，主键 `filename`；这条 SQL 把 `voiceinfo.FileName` 与 `message.imgPath` 做等值 join。

2. classes10.dex `VoiceGCFileSystem.doMaintenance` 里的：
```
'SELECT imgPath from message WHERE type=34'
```
语音 GC 直接按 `imgPath` 清点 type=34 的语音文件。

⇒ **语音消息的本地文件路径就存在 `message.imgPath`**（与图片共用这一列），这是定案，不再靠猜。而 `origExtra` 没快照它。不过按 2.1 第 1 条，撤回 update 整条被拦、我们自己的 mask 也不写 imgPath，所以 imgPath **不会因我们的代码而变脏**。真正的风险是 2.4 那条旁路。

### 2.4 一条必须证伪的旁路（B 变体）

我们的 hook 只拦「长成撤回形状」的写入（`type` 低 16 位 = 10000/10002，`AntiRevoke.kt:365-371`）。如果微信的撤回流程里**另有一条「清 imgPath」的 update**（ContentValues 只带 `imgPath`、不带 `type`），它**不会被拦**，会把语音行的 `imgPath` 清空 ⇒ 行还在、路径没了 ⇒ 播放失败。这既不是 A（type 仍是 34）也不是纯 C（文件还在，只是引用断了）。

**证伪手段**：查 6 条语音当前 `imgPath` 是否非空。非空 ⇒ 这条旁路不成立。

### 2.6 追加假设核：content 第 1 段（talker）会不会参与拼路径 / 会不会被我们改坏

主会话追加一个 B 范畴假设：「微信播放时按 content 里的 talker 拼路径」。核完结论如下：

1. **content 第 1 段 = 发送者 wxid，是微信原生形态，不是我们改的。**
   `wxid_3dmyz2iqavq822:30478:0` 的第 1 段是**发送者** wxid（不是群 `20000134088@chatroom`）。群聊里收到的语音，content 第 1 段本来就填发送者 —— 这是微信自己写的，我们没动。
2. **播放不按 content 拼路径，按 `imgPath`。** §2.3 已定案：语音本地路径存在 `message.imgPath`（GC 代码直接 `SELECT imgPath ... WHERE type=34`）。content 的 talker 段不参与路径解析，改不改 content 都不影响「文件在哪」。
3. **我们的代码在 restore_view=false 时不会改写 content。**
   `applyOne()`（RevokeRescue.kt:264）第一行 `if (!Kv.getBool(Keys.ANTIREVOKE_RESTORE_VIEW)) return` —— 开关关着直接返回，不写任何字段。
   `applyView(on=false)` 的 `valuesFor` 确实 `put("content", r.origContent)`，但 `r.origContent` 是 record() 时快照的**原始索引串**，写回的是原样，不改坏。`_rv.txt` 实测 content 仍是 `wxid_xxx:…:…` 原始索引串，证明没被污染。
4. **唯一会改 content 的是 restore_view=on 的 mask**（写成「xx 撤回了一条消息」），但当前 restore_view=false，且那是**预期行为**（A 分支），与本次 bug 无关。

⇒ 这条假设不成立，content 不是根因。

### 2.5 结论

- `origExtra` 三字段快照**对「撤回 update 本身」是充分的**：撤回 update 被整条拦掉，不会污染任何字段。
- **不充分的地方在「语音文件路径 = `imgPath`」这一列**：它没被快照，但好在也没人写坏它。真正需要证伪的是 2.4 的旁路（微信有没有单独清 imgPath）。
- 若最终证实 `imgPath` 被清空（B 变体成立），最小修复是：`queryOriginal()` 的 `listOf(...)` 扩进 `imgPath`（及 `msgSvrId`/`status`/`flag` 兜底），见 §3。（⚠️ 该建议已随 §7.3 撤回，见下）

---

## §3 最小改动点建议（只写方案，不写代码）

### 3.1 若 A 成立 → **什么都不改**

（当前已证伪，此处为完整性保留）用户确认方式：设置页看「恢复默认显示」是不是开着；开着就是预期行为，关掉即还原。

### 3.2 若 B / B 变体成立 → 扩 `origExtra` 快照字段

- 改 `RevokeRescue.queryOriginal()`（`:142`）：`listOf("isSend","lvbuffer","reserved")` 扩为再加 `"imgPath"`（关键）、`"msgSvrId"`、`"status"`、`"flag"`（兜底）。
- 迁移：`rescued` 表已有 78 条老记录，其 `orig_extra` 只有 3 字段。**老记录无法补**（快照时机已过，库里那行若已被写坏则原文已丢）。处理策略：老记录保持现状，只有**新拦下的**才享受扩展快照；对已坏的老记录提供一次性「按 `msgSvrId` + `voice2` 文件名反推 imgPath」的修复探针（只读诊断，不自动写）。
- ⚠️ 注意：只有当 §2.4 旁路被证实（imgPath 真被清空）才需要动这里。**先取证，后改码**。
- ⛔ **§7.3 勘误：本建议已撤回**（`restore_view=false` ⇒ `applyOne():264` 早退，我们从未写过任何字段；imgPath 6/6 非空）。

### 3.3 若 C 成立 → 抢救文件（最有意思，也最可能成立）

事实：`content` 是索引串 `wxid_x:2860:0`，音频本体在 `voice2/`，**本地路径存在 `imgPath`（见 §2.3）**。微信撤回是「先删文件 → 再写库」，我们只 hook 了「写库」，**文件删除走文件系统（`com.tencent.mm.vfs`，见 classes6.dex `VoiceGCFileSystem`），不经过 `updateWithOnConflict`** ⇒ 拦不住。

可行性判断：
1. **hook 文件删除**：hook `com.tencent.mm.vfs` 的删除/rename 方法（`VoiceGCFileSystem` 相关），在删除前把 `voice2/<msgSvrId>` 复制一份到模块自己的目录。**可行**，但成本中等：要定位 vfs 层的删除入口（换版本易碎），且要在删除前拿到 msgId→msgSvrId 映射。
2. **事后重下**：行还在（msgId/msgSvrId/talker/createTime 都在），可以用这些去 CGI 重新下载语音（语音有下载 CGI）。**可行**，但要复刻下载链路（token/签名），成本高。
3. **结论**：C 成立时，**最便宜的止损是 hook vfs 删除（方案 1）**，但它是**增量修复**（只救以后被撤回的），救不回已丢的 6 条。已丢 6 条若要救，只能走方案 2 重下，或接受「语音防撤回只保气泡、不保音频本体」的已知局限。

### 3.4 多条同时成立时的优先级

- A 是预期行为，**永不修**。
- B 变体（imgPath 被清）与 C（文件被删）可能**同时存在**（微信可能既清引用又删文件）。**先修 C（救文件）**，因为 C 是「数据没了」；B 只是「引用断了」，文件还在时 B 可被 C 的修复顺带覆盖（救文件时把 imgPath 一起备份）。
- 优先级：**C（救文件）> B（扩快照）> A（不修）**。

---

## §4 已排除的死路

1. **「type 被改写成 268445456」**：`_rv.txt` 实测 6 条全是 `type=34`，且 `restore_view=false`。A 已死。
2. **「voice2 文件名 = content 里的那个小数字（30478/20785/…）」**：content 的 `wxid_xxx:30478:0` 中段（30478 等）与 `msgSvrId` 列（6139184963485905836 等）**是两个不同的数**（`_rv.txt` 实测）。voice2 文件到底用哪个命名，**不要靠猜** —— 直接查 `imgPath`（语音的本地路径就是它，见 §2.3）一锤定音。`tools/_r21c_silk.py` 只试了 `msgSvrId` 列 + msgId 分片，**没试 content 中段**，若 imgPath 为空需要枚举 voice2 时，两个候选都要试。
3. **「origExtra 三字段会写坏 imgPath/msgSvrId」**：`valuesFor` 全分支只 put `{isSend,lvbuffer,reserved,type,content}`，从不 put imgPath/msgSvrId ⇒ 不会写坏。B 的污染只能来自撤回 update（已被整条拦掉）或 2.4 旁路（单独清 imgPath 的 update）。
4. **「lvbuffer 丢失导致播不了」**：lvbuffer 是 `origExtra` 唯一 BLOB 字段，快照并写回，`_rv.txt` 里 `lvbuffer` 非 NULL（台账长度 391/319）。这条不成立。
5. **用 grep 在 dex 里找 `L<类名>;` 会假命中 type_ids**（每 dex 都有引用）——已改用 `_r13_findclass.py` 的 class_defs 定位 + 字符串表提取，绕开该坑。

---

## §5 结论（一句话）

> ⚠️ 见 §7.2 勘误：本判据已作废 —— `imgPath` 是 VFS 24-hex 虚拟路径，「文件不在 ⇒ C 成立」不成立；最终定案见 §7.1（根因 = `voiceinfo` 行被删，不是 C）。

**「问题 3（防撤回语音后播放失败）属于：真 bug。A（恢复默认显示）已证伪（type=34、restore_view=false）；B（字段污染）整条判死——restore_view=false ⇒ `applyOne()` 在 RevokeRescue.kt:264 直接 return，我们**从未写过**这些行的任何字段（且 imgPath 6 条全非空、长度 26 与对照一致，旁路也证伪），§3.2「扩 origExtra 快照」撤回；定案见 §7。**」

---

## §6 ⛔ 追加取证：`voice2` 目录在 `${accountSd}` 下，**不是** `${accountSalt}`（DB 目录）—— 现有 `_r21c_silk.py` 可能找错了地方

### 6.1 静态证据（classes10.dex，逐字）

模板字符串（`VoiceGCFileSystem.doMaintenance` 方法体，const-string 偏移 0x4176b8）：

| 变量 | 用在哪 | 结论 |
|------|--------|------|
| `${accountSalt}` | `MicroMsg/${accountSalt}/backup` `/bizmsg` `/sfs` `/record` `/` `/${dir}` | **DB + 加密数据目录**（`_rv.txt` 实测 = `89476c7fc82a9f977bc8a901d68b7d37`） |
| `${accountSd}` | `MicroMsg/${accountSd}/voice2` `/video` `/attachment` | **媒体目录**（语音走它） |

⇒ **语音文件在 `MicroMsg/<accountSd>/voice2/`，不是 `MicroMsg/89476c7f…/voice2/`**。
`tools/_r21c_silk.py` 的 4 个候选路径全都用了 `accountSalt` 哈希 `89476c7fc82a9f977bc8a901d68b7d37` ⇒ **全都找错了目录**。

### 6.2 `${accountSd}` 的取值规则

- `accountSalt` / `accountSd` / `accountAllOther` / `account` 是同一条 VFS 配置链上的**四个不同变量**（方法体 const-string 相邻出现：0x4159ec `account`、0x415ba6 `accountSalt`、0x415bf6 `accountSd`、0x416190 `accountAllOther`）。
- 方法里还硬编码了一个 32 位哈希 `ee1da3ae2100e09165c2e52382cfe79f`（0x416178、0x41eb8a，两处），出现在「`VFS.VFSStrategy` / `Account pair` / `Detected accountSalt in data`」的迁移代码里 —— 它**不是**本机 accountSalt（本机 = `89476c7f…`），是 VFS 迁移的某个锚点哈希。
- **静态层面无法直接推出本机 `${accountSd}` 的具体值**（它由 VFS 策略在运行时按账号算出，不是常量）。但 `docs/_r15_sns_cache_plan.md:460` 已真机实证：内部 `MicroMsg` 哈希 `89476c7f…`、外部 `cache` 哈希 `bfa6fa81…`/`22139c91…` —— **微信确实同时存在多个不同的 32 位哈希**，`accountSd` 极可能是又一个独立值。

### 6.3 语音文件命名规则（新增证据）

同方法体（VoiceGCFileSystem.doMaintenance）const-string 相邻出现：
- `'msg_'`（0x41763a）+ `'.amr'`（0x417636）⇒ 语音文件名形如 **`msg_<X>.amr`**
- `'SELECT imgPath from message WHERE type=34'`（0x417704）⇒ **语音本地路径 = `message.imgPath`**（第二条铁证，与 §2.3 的 DELETE SQL 互相印证）

⇒ 文件名是 `msg_<msgId 或 msgSvrId>.amr`（前缀 `msg_` + `.amr` 后缀），可能带分片目录 `<id/100>/`。**但不要猜 X 到底是 msgId 还是 msgSvrId —— `imgPath` 就是全路径，直接查它一锤定音。** ⚠️ 但 `imgPath` 是 VFS 虚拟路径（见 §7.2），`msg_<X>.amr` 是 VFS 内部落盘名，对外不可见。

### 6.4 修正后的验证命令（主会话直接跑，不依赖任何哈希猜测）

> ⚠️ 见 §7.2 勘误：本判据已作废 —— `imgPath` 是 VFS 24-hex 虚拟路径，下面的 `ls` 对这类串恒回 "No such file"，**无论文件在不在**，零判别力。

```bash
# 首选（权威）：查 imgPath —— 它直接就是语音文件的完整路径
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm \
  --es op label --es sub sql \
  --es sql "select msgId,imgPath from message where msgId in (19289041,19289057,19289088,19289117,19289824,19290094)"
# 拿到每个 imgPath 后逐个 ls：
adb shell ls -la '<imgPath>'

# 兜底（不猜哈希，枚举出 voice2 真身在哪个目录）：
adb shell find /data/user/0/com.tencent.mm/MicroMsg -maxdepth 2 -type d -name voice2 2>/dev/null
adb shell find /sdcard/Android/data/com.tencent.mm/MicroMsg -maxdepth 2 -type d -name voice2 2>/dev/null
# ⚠️ 用 find，别用探针的 scanDir —— scanDir 会滤掉子目录（files=0 有三种含义，见主会话 §3 提醒）
```

> ~~判定仍不变：`imgPath` 非空 + 文件不在 + 一条正常语音（type=34）文件在 ⇒ **C 成立**；`imgPath` 全空 ⇒ B 变体旁路成立。~~ ⚠️ 见 §7.2 勘误：本判据已作废。

---

## §7 定案与勘误

> `[DONE]` 已迁移至文末。

### 7.1 定案：根因 = `voiceinfo` 行被删（不是 C）

微信撤回语音是**两步**：
1. `DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN (...))` ← **删下载记录**
2. `UPDATE message SET type=..., content=...` ← 改撤回形态（**只有这步被 `AntiRevoke` 拦了**）

真机两种查法交叉验证（排除「换键」替代解释）：

| 组 | 按 `FileName = imgPath` | 按 `MsgLocalId = msgId` |
|---|---|---|
| 6 条被救下 | **0/6** | **0/6** |
| 2 条正常对照 | 2/2 ✅ | 2/2 ✅ |

对照行实例：`MsgLocalId=19289165 | FileName=05143110252654b6378e583103 | Status=99 | TotalLen=40973 | FileNowSize=40973`（`Status=99`=下载完成，`TotalLen==FileNowSize`=文件完整）。

⇒ 因果链：气泡还在、`type=34`、`content` 完整 ⇒ 看起来「防撤回成功」；但播放器按 `imgPath` 查 `voiceinfo` 查不到 `Status`/`TotalLen`/`NetOffset` ⇒ **点了没反应**。

⇒ **「文件在不在」不是本次的判据** —— 文件大概率一直在（C 未证、也不需证）。修复方向：hook `voiceinfo` 的 DELETE，由另一条 lane 实现。

### 7.2 勘误 1：`imgPath` 是 VFS 虚拟路径，`ls` 零判别力

- `imgPath` 是 **24-hex / 26 字符 VFS 虚拟路径**（如 `46142710252654b6378ddb4103`、`05143110252654b6378e583103`），无 `/`、无目录、无扩展名；微信 8.0.48 已切 `com.tencent.mm.vfs`，`java.io.File` 摸不到它。
- ⇒ `adb shell ls` 对这类串**恒回 "No such file or directory"**，**无论文件在不在**，「ls 失败 ⇒ 文件被删」是**零判别力**判据（100% 假阳性 C）。
- ⇒ §6.4 的 `ls` 命令、§5 的「文件不在 ⇒ C 成立」、§1.1 的 voice2 枚举命令、§6.3 的 `msg_<X>.amr` 落盘名 **全部作废**（已在 §6.4 / §5 各插勘误行）。

### 7.3 勘误 2：§3.2「扩 origExtra 快照」撤回

- `restore_view=false` ⇒ `RevokeRescue.applyOne()` 在 **`RevokeRescue.kt:264` 第一行就 `return`** ⇒ `valuesFor()` / `updateRow()` **从未执行** ⇒ 我们**一个字段都没写过**。这比「写了但写对了」更硬。
- 6 条 imgPath **全部非空**、长度 26 与正常对照一致 ⇒ 微信没发过「只清 imgPath」的 update，B 变体（§2.4 旁路）不成立。
- ⇒ §3.2「扩 `origExtra` 快照字段」**撤回**，不再需要。

### 7.4 保留有效的部分

- §6.1 的 `${accountSalt}` vs `${accountSd}` 变量区分（DB 目录 ≠ 媒体目录）。
- §2.3 / §6.3 的「`imgPath` 就是语音本地路径」两条铁证。
- §4 的死路清单（尤其 §4 第 5 条「grep 假命中 type_ids」的坑）。

---

## §8 备查：VFS API 静态结论（未用于本轮修复）

> 本轮根因已由 §7.1 定案为 `voiceinfo` 行被删，**不依赖文件系统判存在**。以下 VFS API 静态结论仅作备查，用 `tools/_r13_findclass.py`（`_proto()` uleb128 bug 已于 2026-10-24 修复）实证，**未上真机、未用于修复**。

### 8.1 判存在：`com.tencent.mm.vfs.o6`（全 public static 工具类）

- `o6` 定义于 classes10.dex，`super=Object`，access=0x0401（abstract），46 个方法**全部 public static（0x0009）**，无需实例（类比 `java.nio.file.Files`）。
- 判存在最稳（签名均实证）：
  - `o6.e(String)Z` — exists（反汇编：`q7.a(String)` 工厂 → `j6.<init>` → `j6.m()` → `j6.k()` → boolean）
  - `o6.l(String)J` — length（✅ 已实证：`docs/_r16_g2_msgsnap.md:479` 原文「`o6.l()` 取文件大小」）
  - `o6.E(String)InputStream` — 打开读流（比 exists 更强的「能否打开」判据）
  - 其余变体：`o6.f(String)Z`、`o6.g(String,Z)Z`、`o6.h(String)Z`、`o6.k(String)Z`

### 8.2 真实路径 / toUri

- **真实路径**：`o6.o(String)String`、`o6.i(String,Z)String`（均 public static）。
  - 反汇编跟踪：`o6.o` → `com.tencent.mm.vfs.v7.k(String,ZZ)String`；`o6.i` → `com.tencent.mm.vfs.v7.q(q7,Z)String`。
  - ⚠️ **勘误上一轮**：真实路径解析器是 **`v7`**（`v7.k` / `v7.q`），**不是** `q7.k`。上一轮误写成 `q7.k(String,ZZ)String` —— `q7` 只有 9 个方法（`<clinit>` / `<init>`×2 / `a` / `compareTo` / `equals` / `h` / `hashCode` / `toString`），**根本没有 `k` 方法**。
- **toUri**：`q7.h()Landroid/net/Uri;`（public final，路径对象 → Uri）。
- **是否抛异常**：DEX 签名不携带 checked exception；这些方法在文件不存在 / 解析失败时**可能抛运行时异常或返回 null / -1**，反射调用必须用 try/catch 包裹。`o6.e`（exists）最安全（返回 boolean，一般不抛）；`o6.l` / `o6.o` 在缺失时可能抛或返负/返空。

### 8.3 路径对象工厂

- `q7.a(String)Lcom/tencent/mm/vfs/q7;` — public static，把 String 解析成 VFS 路径对象。
- `q7` 构造：`<init>(Landroid/net/Uri;)V` 与 `<init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V`（均 public）。

### 8.4 可直接粘进 probe 的 Kotlin 片段（XposedHelpers 反射版，签名实证）

```kotlin
// 跑在微信进程内（probe 已持有 lpparam.classLoader）
val o6 = XposedHelpers.findClass("com.tencent.mm.vfs.o6", classLoader)
val q7 = XposedHelpers.findClass("com.tencent.mm.vfs.q7", classLoader)

fun vfsExists(imgPath: String): Boolean = runCatching {
    XposedHelpers.callStaticMethod(o6, "e", imgPath) as? Boolean ?: false
}.getOrDefault(false)

fun vfsLength(imgPath: String): Long = runCatching {
    XposedHelpers.callStaticMethod(o6, "l", imgPath) as? Long ?: -1L
}.getOrDefault(-1L)

// mtime 候选：o6.m(String)J（与 l=length 配对，强烈暗示 lastModified，但**未真机实证语义**；
// 若与 l 同值则说明 m 也是 size，需改试 v7.i(String)J / v7.r(String)J 或跳过 mtime）
fun vfsMtime(imgPath: String): Long = runCatching {
    XposedHelpers.callStaticMethod(o6, "m", imgPath) as? Long ?: -1L
}.getOrDefault(-1L)

fun vfsRealPath(imgPath: String): String? = runCatching {
    // 先裸 hex 试，拿不到再补 wcf://voice2/ 前缀
    (XposedHelpers.callStaticMethod(o6, "o", imgPath) as? String)
        ?: (XposedHelpers.callStaticMethod(o6, "o", "wcf://voice2/$imgPath") as? String)
}.getOrNull()

fun vfsToUri(imgPath: String): String? = runCatching {
    val p = XposedHelpers.callStaticMethod(q7, "a", imgPath) ?: return@runCatching null
    (XposedHelpers.callMethod(p, "h") as? android.net.Uri)?.toString()
}.getOrNull()

// doLs 风格：读一个 hex 串 → 判存在 + 打印 size/mtime/realPath
fun doLsHex(hex: String) {
    val ok = vfsExists(hex)
    val len = vfsLength(hex)
    val mt = vfsMtime(hex)
    val rp = vfsRealPath(hex) ?: vfsToUri(hex)
    log("vfs doLs hex=$hex exists=$ok size=$len mtime=$mt real=$rp")
}
```

> ⚠️ `wcf://voice2/` scheme 前缀是上一轮的推断（DB 里 imgPath 是相对路径），若 `o6.o(hex)` 返回 null/原样就补前缀再试；本轮未上真机验证。

---

[DONE]
