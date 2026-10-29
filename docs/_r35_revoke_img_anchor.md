# R35-2 图片撤回保护：SQL 层锚点取证 + 判据 + 落地

> lane：general-purpose-1（独立取证与实现 lane，R35-2）
> 目标微信：8.0.48 / 2588 GP 版（dex 在设备上，本 lane **不碰 adb**）
> 状态：**进行中**（每完成一个子步即时写盘）

## 0. 任务与红线

- 任务：找到图片撤回保护的可用新锚点，给判据，落地代码（仅 `feature/AntiRevoke.kt` / `feature/RevokeRescue.kt`）。
- R34-1 已定性事实（见 `docs/_r34_hdimg_anchor.md`）：
  1. 微信图片三档：`th_<md5>`（缩略图，到达即下）/ `<md5>`（大图，点开才下）/ `<md5>hd`（原图，查看原图才下）。撤回时只删 `th_` ⇒ 后两者当时**根本不存在**。
  2. `AutoHdImg.kt` hook `t1.<init>` 是**结构性缺陷**：挂 `<init>` ≡ 只跟微信自己决定发起的下载，中间隔 4 道门（自动下载条件 / 电量充电态 / priority / chat rank+totallsp）。
  3. ⛔ `svc.c()` 返回 true = 任务受理进队列，**不代表下载成功** ⇒ 不得作 PASS 判据。
  4. R34 推荐替代方向：**SQL 层锚点**（`feature/AntiRevoke.kt:302` 附近）。
- ⛔ dex 里有 SQL 字符串 ≠ 该业务真的调用了它；必须找到 `invoke-*` 指令 + 调用者链。
- ⛔ 「A 推 B」的时序结论必须显式标注为**假设**；只认 `invoke` 地址单调递增或真机日志时间戳。
- ⛔ 本 lane 不碰 adb / 不构建 / 不装包。

## 1. 子步进度

- [x] 建文件（落盘）
- [x] 1.1 读完 R34 文档 + 相关源码
- [x] 1.2 SQL 层锚点取证（本地 dex，未碰 adb）
- [x] 1.3 判据设计
- [x] 1.4 落地代码（AntiRevoke.kt + RevokeRescue.kt）
- [x] 1.5 真机验证命令与预期日志（§5，需主会话执行）
- [x] 1.6 风险与回退（§6）

---

## 2. SQL 层取证（① dex 字符串级 / ② 推断 / ③ 假设，逐条标注）

取证源：本地 `_cache/dex/classes*.dex`（15 个 dex 已在仓库内，**本 lane 未碰 adb**）。
手法：`_r4_dexref.py`（const-string → 归属类#方法）+ 自写字符串池 dump（DDL / SQL 全文）。

### 2.1 图片信息表在 8.0.48 叫 `ImgInfo2`，不是 `ImgInfo`（① DDL 级）

`classes7.dex` 字符串池里的建表语句（`CREATE TABLE IF NOT EXISTS ImgInfo2`）：

```
CREATE TABLE IF NOT EXISTS ImgInfo2 (
  id INTEGER PRIMARY KEY, msgSvrId LONG, offset INT, totalLen INT,
  bigImgPath TEXT, thumbImgPath TEXT, createtime INT, msglocalid INT,
  status INT, nettimes INT, reserved1 int, reserved2 int,
  reserved3 text, reserved4 text,
  hashdthumb int DEFAULT 0, iscomplete INT DEFAULT 1, origImgMD5 TEXT,
  compressType INT DEFAULT 0, midImgPath TEXT, forwardType INT DEFAULT 0,
  hevcPath TEXT, sendImgType INT DEFAULT 0, originSourceMd5 TEXT )
```

同池里另有 `CREATE TABLE IF NOT EXISTS ImgInfo ( … bigImgPath TEXT, thumbImgPath TEXT )`（只有 6 列）
和迁移语句 `insert into imginfo2 (id,msgSvrId, offset, totalLen, bigImgPath, thumbImgPath)
select id, msgSvrId, offset, totallen, bigimgpath, thumbimgpath from imginfo;`
⇒ **`ImgInfo` 是迁移源表，8.0.48 线上用的是 `ImgInfo2`**。

⚠️ 这条直接推翻任何「查 `ImgInfo` 表」的写法。**本项目此前从未取过这张表**（全仓 grep 无 `ImgInfo2`）。

索引（① 同池）：
```
CREATE INDEX IF NOT EXISTS msgLocalIdIndex      ON ImgInfo2 ( msglocalid )     ★ 按 localId 查有索引
CREATE INDEX IF NOT EXISTS serverImgInfoIndex   ON ImgInfo2 ( msgSvrId )
CREATE INDEX IF NOT EXISTS serverImgInfoHdIndex ON ImgInfo2 ( reserved1 )
CREATE INDEX IF NOT EXISTS iscomplete_index     ON ImgInfo2 ( iscomplete )
CREATE INDEX IF NOT EXISTS origImgMD5_index     ON ImgInfo2 ( origImgMD5 )
```

### 2.2 `message ↔ ImgInfo2` 的关联式（① const-string 级，含归属方法）

`tools/_r4_dexref.py classes10.dex "SELECT bigImgPath"` → 命中 1 条 string_id，归属：

```
Lcom/tencent/mm/vfs/ImageGCFileSystem;#c
  'SELECT bigImgPath,thumbImgPath FROM ImgInfo2, message
   WHERE message.type=3 AND (ImgInfo2.msglocalid=message.msgId OR ImgInfo2.msgSvrId=message.msgSvrId)'
```

⇒ 微信自己的 SQL 坐实两件事：
- `ImgInfo2.msglocalid = message.msgId`（**`message.msgId` 就是那个 localId**）
- `ImgInfo2.msgSvrId = message.msgSvrId`
- 图片消息 `message.type = 3`

⚠️ 证据等级说明（按 lane 红线如实标注）：
- **①**：字符串存在于 `ImageGCFileSystem#c` 的方法体里（const-string 命中即该方法引用了它）。
- ❌ **不是**「撤回链路调用了它」——`ImageGCFileSystem` 是**存储 GC**（文件名即语义），
  这条 SQL 是 GC 扫描用的。**「撤回时也走这条 SQL」无任何证据，属 ③ 假设。**
- 但它证明了**表名、列名、关联式**这三件事（数据契约层），这已经够我们写查询了。

### 2.3 `message.imgPath` 对 type=3 的形态（① dex 正则级，比 R31g 真机更硬）

`classes10.dex` 字符串池里有一段**校验正则**：

```
'^THUMBNAIL_DIRPATH://th_[0-9a-f]{32}$'
```
另有 `THUMBNAIL_DIRPATH://`（classes10）、`THUMBNAIL_DIRPATH://th_`（classes12）。

⇒ 与 R31g 真机观测 `imgPath = THUMBNAIL_DIRPATH://th_0c947a1dd158f52fcca431bb84954d5b`
**完全吻合**，且这次是**微信自己写的校验规则**，不是我们的观测。
⇒ **`MediaGuardLogic.hexToken()` 取「第一个恰好 32 位的 hex 段」在这种串上必然取到 md5**
（前缀里的单字母 hex 段长度都 = 1，不会顶掉 32 位段）。

其它类型（① 同池，`SELECT imgPath FROM message WHERE type=…`）：
```
type=34  → imgPath（语音，26 位，见 VoiceBackupLogic）
type=43  → imgPath（视频，形态未取证）
type=3   → THUMBNAIL_DIRPATH://th_<md5>
```

### 2.4 三档文件的 dex 侧命名（① 名字 / ② 与文件名前缀的对应）

`classes7.dex` 字符串池：
```
'image2_thumb'        'image2_not_thumb'        'image2_thumb_hd'
'image2/'             'wcf://image2/'
```
⇒ 三档在微信内部**确实存在三个名字**（缩略图 / 非缩略图=大图 / hd 缩略图=原图），
与 R34 的「`th_<md5>` / `<md5>` / `<md5>hd`」三档描述一致。
⚠️ **② 推断**：`image2_thumb`↔`th_<md5>`、`image2_not_thumb`↔`<md5>`、`image2_thumb_hd`↔`<md5>hd`
这个**一一对应是我读名字推的**，dex 里没有「名字→文件名」的映射表。别当定论。

### 2.5 撤回到底删了哪些表 —— **无证据，待真机**

全 dex 扫 `delete from` / `DELETE FROM` 后与消息/图片相关的只有这些（① 字符串级）：

```
classes4:  DELETE FROM ImgInfo2 WHERE msglocalid IN (SELECT msgId FROM message WHERE talker IN …)
classes4:  DELETE FROM ImgInfo2 WHERE id IN (SELECT ImgInfo2.reserved1 FROM message JOIN ImgInfo2 ON message.msgId = ImgInfo2.msglocalid WHERE talker IN …)
classes4:  DELETE FROM voiceinfo WHERE FileName IN (SELECT imgPath FROM message WHERE talker IN …)
classes4:  DELETE FROM videoinfo2 WHERE filename IN (SELECT imgPath FROM message WHERE talker IN …)
classes4:  DELETE FROM AppMessage WHERE msgId IN (SELECT msgId FROM message WHERE talker IN …)
classes4:  DELETE FROM message WHERE talker IN …
classes10: delete from %s where %s=%s         ← 泛型单条删除构造器
classes7:  DELETE FROM %s WHERE id = ? AND msgsvrid = ?
classes7:  DELETE FROM %s WHERE msgid = ?
```

**结论**：
- ⛔ **没有**找到 `DELETE FROM ImgInfo2 WHERE msglocalid = ?`（单条）这类字面量。
  单条删除走的是**泛型构造器** `delete from %s where %s=%s`（classes10），
  所以「撤回删不删 ImgInfo2」**光靠字符串取证答不了** —— 必须有 invoke 调用者链或真机日志。
- 现有全部 `ImgInfo2` 删除语句都是 `WHERE talker IN (…)` = **删会话/空间清理**，不是撤回。
- ⇒ **「撤回时 `ImgInfo2` 行还在」目前是 ② 推断**（依据：R21c 语音侧的教训是「行还在、文件没了」，
  图片同构）。**未取证，待真机**。

已知的撤回侧事实（沿用，不重证）：
- `UPDATE message SET type=… content=… lvbuffer/isSend/reserved WHERE msgId=?` → 由 [AntiRevoke] 拦（① 真机）。
- 语音侧另有 `DELETE FROM voiceinfo WHERE FileName=?` → 由 [VoiceGuard] 拦（① 真机，R21c）。
- 磁盘文件删除 `File.delete(image2/…)` → 由 [VoiceFileGuard] 拦（① 真机，R30）。

### 2.6 ⇒ 锚点选定

**选 `insertWithOnConflict` 上「message 表 type=3/43 的插入」当图片到达锚点。**

理由（对比 R34 的候选）：
| 候选 | 判定 |
|---|---|
| A. `insertWithOnConflict`（本选） | ✅ 项目内**已装**（`AntiRevoke.kt:302`），零新增 hook；表名/列名未混淆；**不依赖微信下载意愿** |
| B. `modelimage/b1`（ImgInfoStorage）入库方法 | ❌ 混淆名 + 8 个返回 `J` 的方法里哪个是 insert **未取证**（R34 子步 4.2 明写「别直接赌」） |
| C. `c2c/img/e`（C2CMsgAutoDownloadRes 队列表） | ❌ 7 个方法里没有明显 insert，插入点未取证（R34 子步 4.3） |
| D. `t1.<init>`（现状） | ❌ 结构性缺陷（R34 已定案：中间 4 道门） |

**关键增益（这是本锚点相对 `t1` 的真正优势，不只是「换个地方观察」）**：
ContentValues 里同时有 **`msgId`（= localId）与 `msgSvrId`**，两个都是真值。
而 `t1.<init>` 那条路真机出现过 `localId=0`（R34 子步 3.4：上游传的 0 ⇒ 下载无效且污染 `m.e` 去重集合）。
⇒ SQL 层拿到的 id 是**可直接喂给 `DownloadImgService.c(localId, msgSvrId, …)` 的干净入参**。
⚠️ 本 lane **不发起下载**（那是 `AutoHdImg` 的职责，该文件禁止本 lane 修改）；只把 id + md5 落地到锚点缓存，
为后续「到达即预下载」留出已取证的数据入口。

---

## 3. 判据设计（「撤回图片能点开」怎么自证）

### 3.1 ⛔ 先划掉假判据

| 判据 | 为什么不算 |
|---|---|
| `svc.c() == true` | 只 = 任务受理进队列，不代表数据落盘（R34 子步 5，① dex 级） |
| `HDIMG fired=+1` / `ok=+1` | 同上，且 `t1` 那条路本身就漏「没点开的图」（R34 结构性缺陷） |
| `message` 行还在 / `imgPath` 非空 | 行在 ≠ 文件在（R21c 语音侧的老教训：行和索引全在、文件没了 ⇒ 播放无反应） |
| `VFG protectMedia` 打了一行 | 只 = 把 token 放进保护池，**不证明那个文件存在**（R34 P2①：token 长度不对时照样入池照样打日志） |

### 3.2 ✅ 采用的判据：**读 `ImgInfo2` + 直接 `File.exists()/length()`**

一句话：**判据必须是「磁盘上那个字节文件存在且非空」这个物理事实，不能是任何一次调用的返回值。**

落地输出一行（撤回被拦下时打，每条被撤的图/视频一行）：

```
RRV imgAudit msgId=19384736 md5=0c947a1dd158f52fcca431bb84954d5b(32)
    big=OK(456789) thumb=OK(12345) hd=MISS
    row=1 status=0 iscomplete=1 hdThumb=0 rawBig=wcf://image2/0c/94/0c947a1d…
```

字段语义：
| 字段 | 取值 | 读法 |
|---|---|---|
| `md5` / `(32)` | `MediaGuardLogic.hexToken(message.imgPath)` | 长度必须 32；≠32 ⇒ imgPath 形态变了，整条功能失效且**必须看得见** |
| `row` | `ImgInfo2` 查到几行（`msglocalid=?` 优先，`msgSvrId=?` 兜底） | 0 ⇒ 表名/列名赌错，或被撤前就删了 |
| `status` / `iscomplete` / `hdThumb` | `ImgInfo2.status` / `.iscomplete` / `.hashdthumb` | 微信自己口径，`iscomplete=1`=已下完 |
| `big` / `thumb` / `hd` | 对 `bigImgPath` / `thumbImgPath` / `bigImgPath+"hd"` 做 `File.exists()+length()` | `OK(n)` / `MISS` / `NA`（路径不是绝对路径，如 `wcf://`，无法 stat） |

### 3.3 判读表（一眼定位到哪一层坏了）

| 观察 | 判读 | 下一步 |
|---|---|---|
| `md5=…(32)` 且 `big=OK(n>0)` | ✅ **PASS**：大图在盘上，点开必然能看 | 人工点开确认一次即收口 |
| `md5=…(32)` 且 `thumb=OK` 但 `big=MISS` | ⚠️ **R34 定性的根因复现**：大图当时根本没下过 | 要靠「到达即预下载」；本 lane 只负责把它查出来 |
| `md5=…(32)` 但 `thumb=MISS` | ❌ 缩略图被删了 ⇒ `VoiceFileGuard.protectMedia` 没拦住（时序晚 / native unlink） | 看有没有 `VFG blocked MEDIA delete` |
| `md5=`（空）或 `(n≠32)` | ❌ 锚点/取 token 坏了（imgPath 为空或形态变了） | 拿 raw 值去对 `RRV record … imgPath=` |
| `row=0` | ❌ `ImgInfo2` 表名/列名赌错，或撤回把行删了 | 真机 `op=sql` 直接 `select count(*) from ImgInfo2` 验表 |
| `big=NA` | ⚠️ `bigImgPath` 是 `wcf://` 虚拟路径（② 推断）⇒ 不能直接 stat | 看 `rawBig=` 打出真实形态，据此改解析 |

### 3.4 端到端（人眼）判据 —— 唯一最终判据

1. 对方发一张图 → **不要点开**；
2. 对方撤回；
3. 你去点那张图 → 能出大图 = PASS；转圈/黑屏/「图片已过期」 = FAIL。

⚠️ 必须与 3.2 的日志行**同时看**：只看现象分不清「文件没了」和「文件在但渲染走了别的路」。

### 3.5 反向对照（必须有，否则整轮不可信）

关掉 `Keys.ANTIREVOKE_MSG`（`kvset`）再复现一遍同一场景：
- 应看到 `AR blocked revoke` **消失**、`RRV imgAudit` **消失**、`VFG blocked MEDIA delete` **消失**；
- 且撤回提示正常出现（说明不是我们没拦住，是开关真的关了）。

---

## 4. 代码改动（行号 = 改动**后**）

只动了授权的两个文件。`git diff --stat`：
```
feature/AntiRevoke.kt   | 164 ++++++++++++++++++++-   （+158 / -6）
feature/RevokeRescue.kt | 115 ++++++++++++++++++++++   （纯新增，0 删除）
```

### 4.1 `feature/AntiRevoke.kt`

| 行 | 改动 | 说明 |
|---|---|---|
| 11 | `import com.lx.wxgroup.data.MediaGuardLogic` | 复用已单测覆盖的 token 提取（**未改 `data/**`**） |
| 97 / 104 | 新增 `TYPE_IMAGE = 3` / `TYPE_VIDEO = 43` | ① dex：`message.type=3`（ImageGCFileSystem#c 的 SQL）、`type=43` |
| 207 | 新增 `@Volatile imgAnchorOn` | 跟**防撤回开关**走（不是 DEBUG_LOG），理由见 4.3 |
| 216–221 | 新增 `data class ImgAnchor(svrId, imgPath, talker, at)` | |
| 229–235 | 新增 `imgAnchor`（CHM，256 上限）+ `anchorSeen` / `anchorBad` | 计数器就是真机判据 |
| 238–247 | 新增 `internal imgAnchorStat()` / `imgAnchorOf()` | 前者进 `ardump` 输出，后者给 `RevokeRescue` |
| 259 | `refresh()` 里 `imgAnchorOn = enabled` | |
| 411 / 416 | `probeInsert` 的门改成 `if (!imgAnchorOn && !probeOn) return` + `if (imgAnchorOn) anchorImage(...)` | **同一个已有钩子，没新增 hook** |
| 450–489 | 新增 `anchorImage(values, type)` | ⭐ 锚点本体 |
| 162 | `currentConfigLine()` 追加 `\| ${imgAnchorStat()}` | `op=ardump` 会打它 ⇒ 真机零新增探针就能体检 |
| 350 附近 | `install()` 里 `insertWithOnConflict` 的注释补② | 文档同步 |

`anchorImage` 做的事（**全部不查库、不写库**）：
1. `type & 0xFFFF` 不是 3 / 43 ⇒ return（最便宜的先判）
2. 取 `msgId` / `msgSvrId` / `imgPath` / `talker`
3. `VoiceFileGuard.protectMedia(imgPath)` —— **提前登记保护池**
4. `imgAnchor[msgId] = …`（有界，30 分钟 TTL 淘汰）
5. md5 不是 32 位 ⇒ `anchorBad++` + **打 warn 带 raw 值**（否则静默失效）
6. 打一行 `AR imgAnchor …`

### 4.2 `feature/RevokeRescue.kt`

| 行 | 改动 | 说明 |
|---|---|---|
| 8 / 13 | `import MediaGuardLogic`、`import java.io.File` | |
| 124–126 | `record()` 图片/视频分支里，`protectMedia` 之后调 `auditImage(msgId, orig.imgPath)` | 包在 `runCatching` 里，失败只留一行 warn |
| 245–300 | 新增 `auditImage(msgId, imgPath)` | ⭐ 判据本体 |
| 304–310 | 新增 `fileState(p)` | `OK(n)` / `MISS` / `NA` / `NULL` / `ERR` |
| 314–321 | 新增 `argsFor2(m, a, b?)` | 按反射方法的数组形参类型构造，不写死下标 |

### 4.3 三个「为什么这么写」（改动前想清楚的，别在复核时被改回去）

1. **`imgAnchorOn` 跟防撤回开关，不跟 `DEBUG_LOG`。**
   这个锚点是**功能**（它负责提前把 md5 塞进 `VoiceFileGuard` 保护池），不是诊断探针。
   挂到 DEBUG_LOG 上 ⇒ 用户没开调试时保护池是空的 ⇒ 撤回时文件照样被删，且**日志里什么都没有**。

2. **保护池提前到「到达」时入池，而不是等撤回。**
   现有链路是「撤回被拦下 → `RevokeRescue` → `protectMedia`」。
   而 `File.delete(image2/…)` 是异步跑的，它和「UPDATE 被我们拦下」的先后**没有取证**（R21c 只实证了语音侧「此刻文件还在」）。
   提前入池 = 把这道竞态消掉。入池幂等，撤回时再入一次无害（`RevokeRescue.kt:122` 原调用保留未删）。

3. **`auditImage` 只读。** 不写微信库、不改任何状态，只在撤回拦截的**首次判定**路径上跑一次
   （重试风暴走静默路径，不调 `record()` ⇒ 不会放大成上千次查询）。

4. **`anchorImage` 的日志按 `msgId + imgPath` 去重。**
   同一条消息可能被重复 insert（补拉 / 重试），不去重的话批量补历史消息会把 5000 行
   LogX 环形缓冲刷爆（本项目踩过：刷爆后**别的诊断日志全丢**）。
   ⚠️ 但 `imgPath == null` 时**不算重复** —— 那正是「形态异常」，必须照样走 `BAD` 告警。

---

## 5. 真机验证（**需要主会话执行**，本 lane 不碰 adb）

前置：装包 → 微信前台 → 清日志。

```bash
# ① 开开关（kvset 内部会立刻 AntiRevoke.refresh()，imgAnchorOn 当场翻转）
python tools/dev.py probe kvset --key antirevoke.msg --val true  --grep 'PROBE kvset'
python tools/dev.py probe kvset --key debug.log      --val true  --grep 'PROBE kvset'

# ② 顺手证「ImgInfo2 这张表在不在、列对不对」（判据：--grep 必须 ≥1 行，TAG 是 SQLX）
python tools/dev.py probe sql --q 'select count(*) from ImgInfo2' --grep SQLX
python tools/dev.py probe sql --q 'select id,msgSvrId,msglocalid,bigImgPath,thumbImgPath,status,iscomplete from ImgInfo2 order by id desc limit 5' --grep SQLX

# ③ 收一张图（**不要点开**）→ 看锚点接没接通
python tools/dev.py q 'AR imgAnchor' 20

# ④ 对方撤回 → 看审计
python tools/dev.py q 'RRV imgAudit' 20

# ⑤ 锚点体检（走现成的 ardump，不用新探针）
python tools/dev.py probe ardump --grep 'AR cfg'

# ⑥ 反向对照：关掉总开关再复现同一场景
python tools/dev.py probe kvset --key antirevoke.msg --val false --grep 'PROBE kvset'
```

⚠️ 闸门三（速查 §1.2b）：每次带 `--grep` 必须看到 **≥1 行匹配**，否则一律判「探针没执行」，不解读内容。

### 预期日志

**② 表存在**（否则说明 8.0.48 上表名不是 `ImgInfo2`，整条 §2 结论作废，回 §2.5 重新取证）：
```
SQLX  count(*) = 12345
SQLX  id=.. msgSvrId=.. msglocalid=.. bigImgPath=/…/image2/xx/yy/.. thumbImgPath=.. status=0 iscomplete=1
```
⚠️ `bigImgPath` 若以 `wcf://` 开头 ⇒ §3.3 的 `big=NA` 分支，`fileState` 需要按 raw 值改解析（这是**预期内的取证结果**，不是 bug）。

**③ 锚点接通**（收到图、**没点开**就该出现）：
```
AR imgAnchor msgId=19385072 svr=7412..  type=3 md5=0c947a1d..(32) talker=wxid_ab.. seen=1
VFG protectMedia img=0c947a1d..(32) ttl=600000ms pool=1 raw=THUMBNAIL_DIRPATH://th_0c947a…
```
- `seen>0` ⇒ ✅ 接通（证明 message 的 insert 真的过 `insertWithOnConflict`）。
- `md5=(32)` ⇒ ✅ token 正常；`BAD md5 len=…` ⇒ imgPath 形态变了。
- **一行都没有** ⇒ 插入不走这个 hook ⇒ 锚点要换（回 R34 候选 B/C）。

**④ 审计**（PASS 形态）：
```
RRV record msgId=19384736 … imgPath=THUMBNAIL_DIRPATH://th_0c947a…
RRV imgAudit msgId=19384736 md5=0c947a1d..(32) row=1 status=0 iscomplete=1 hdThumb=0
     big=OK(456789) thumb=OK(12345) hd=MISS rawBig=/…/image2/0c/94/0c947a…
```
✅ **`big=OK(n>0)` = 大图在盘上 = 点开必然能看**（这是本次唯一采纳的 PASS 判据）。
`big=MISS` ⇒ R34 定性的根因复现（大图当时就没下载过），要靠「到达即预下载」解决 —— **本 lane 不做**。

**⑥ 反向对照**：三条日志（`AR blocked revoke` / `RRV imgAudit` / `VFG blocked MEDIA delete`）全部消失，
且撤回提示正常出现。

---

## 6. 风险与回退

| 风险 | 等级 | 说明 / 处置 |
|---|---|---|
| 保护池起点提前 ⇒ 微信 10 分钟内删 `image2` 下同名文件被拦 | 低 | TTL 与现状同数量级（600s，`VoiceFileGuard.PROTECT_MEDIA_MS`），且**只在防撤回开关开着时**入池；撤回时原调用仍会再入一次 ⇒ 不比现状更激进 |
| `imgAnchor` 泄漏 | 低 | 256 上限 + 30 分钟 TTL 淘汰；只在开关开着时写 |
| `insertWithOnConflict` 热路径变慢 | 低 | 关着时 1 次 volatile 读即 return；开着时 = 1 次表名 String 比较 + 1 次 `getAsInteger("type")`，**零 IO** |
| `auditImage` 在微信 DB 线程上查 `ImgInfo2` | 低 | 只在撤回**首次判定**时跑一次（静默路径不调 `record`）；`msglocalid` 有索引（① dex） |
| `ImgInfo2` 表名赌错 | 中 | 只影响审计日志的准确性，**不影响任何拦截/保护**；真机 `op=sql` 一步可验 |
| `bigImgPath` 是 `wcf://` 虚拟路径 ⇒ `big=NA` | 中 | 预期内；`rawBig=` 会打出真实形态，据此改 `fileState` |
| 误伤其它功能 | 无 | 未动 `AutoHdImg` / `VoiceFileGuard` / `data/**` / `EntryReceiver`；`RevokeRescue` 原有分支一行未删 |

**回退**（两处独立，互不影响）：
- 关锚点：`AntiRevoke.kt:259` `imgAnchorOn = enabled` → `imgAnchorOn = false`。
- 关审计：删掉 `RevokeRescue.kt:124–126` 那 3 行 `runCatching { auditImage(...) }`。

**本节新增代码的覆盖面**：只覆盖「到达锚点 + 文件审计」两件事。
⚠️ **「让大图在撤回前就存在」（= 到达即预下载）本 lane 没做** —— 那需要改 `AutoHdImg.kt`（禁止），
且需要一个真机前提：`svc.c(localId, msgSvrId, 0, …)` 用**干净 localId** 到底能不能真的下下来
（R34 只证了「返回 true ≠ 成功」，没证过「干净 localId 就一定成功」）。
本 lane 交付的锚点已经把**干净的 `localId` + `msgSvrId` + `md5`**三者都备好了（① dex 关联式坐实），
下一棒接预下载时不用再取证入参。

[DONE]
