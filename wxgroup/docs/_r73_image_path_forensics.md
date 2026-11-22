# R73 图片转发第二层缺口：URI → 真实图片路径 只读取证

> lane：r73-image-path-forensics｜**只读取证**：未改 `app/src/main` 任何源码、未跑 adb/装机、未跑 gradle、未 git commit。
> 目标：判定「我方转发图片时如何把微信 `THUMBNAIL_DIRPATH://th_<32位md5>` 解析成磁盘上真实存在的图片文件」，给出可落地的唯一推荐方案。
> 证据等级：【源码确定】= 本项目源码/dex 字面；【真机确定】= 已有真机日志/SQL 实录；【强推测】/【假设】= 未取证，必须给装机验证方法。
> 所有「返回值/调用成功」都不作证据，只认物理文件与可复核日志，遵循宿主零崩溃纪律（全程 runCatching）。

## 目录

- [0 结论摘要（唯一推荐方案 / 最小改动 / 装机验收）](#0-结论摘要)
- [1 聊天图片真实路径规则与分桶](#1-聊天图片真实路径规则与分桶)
- [2 能否直接查 ImgInfo2 拿真实路径（SQL 方案评估）](#2-能否直接查-imginfo2-拿真实路径sql-方案评估)
- [3 退化取法与代价评估（目录扫描 / 分桶 O(1)）](#3-退化取法与代价评估)
- [4 关键陷阱：thumb 是缩略图，不能当转发源](#4-关键陷阱thumb-是缩略图)
- [5 落地接口：snapshotOf IMAGE 分支如何回填 localPath/fileExists](#5-落地接口)
- [6 单测策略：纯逻辑可锁 vs 必须真机验](#6-单测策略)
- [DONE 标记在文末](#done)

## 进度

- [x] 建文件 + 目录
- [x] Q1 路径规则与分桶
- [x] Q2 SQL 方案评估
- [x] Q3 退路评估
- [x] Q4 thumb/big 陷阱
- [x] Q5 落地接口
- [x] Q6 单测策略
- [x] 唯一推荐方案 / 最小改动清单 / 装机验收判据 + [DONE]
- [x] §7 追加取证（SQL 样例 vs 实录冲突裁决 / msgId 喂参 / 主库只读探针命令）

---

## 1 聊天图片真实路径规则与分桶

### 1.1 真机确定：绝对根 + 分桶（chat image2，8.0.48）

【真机确定】聊天 type=3 图片的真实文件落在**内部存储**（不是 /sdcard）：

```
/data/user/0/com.tencent.mm/MicroMsg/<32位hash>/image2/<md5[0:2]>/<md5[2:4]>/<文件名>
```

证据（真机日志/SQL 实录，逐条）：

| 证据 | 锚点 | 内容 |
|---|---|---|
| 缩略图完整路径 | `docs/_r52_device_log_snapshot.md:17,34` | `VFG blocked MEDIA delete ... path=/data/data/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/5a/9a/th_5a9a0b372dbc9b4465e776185700f1a7` |
| 同桶另一组 | `_cache/dbg_live.txt:233` | `.../image2/1a/7b/th_1a7b9f5d918f9d80ce5c8065d3dcb5e7`（md5 前缀 `1a7b` → 桶 `1a/7b`） |
| 第三组 | `docs/_r52_device_log_snapshot.md:155` | `.../image2/3f/ad/th_3fadbde02a26c4ab4034c3ab3a604e51`（前缀 `3fad` → `3f/ad`） |
| 第四组（大图目录） | `.workbuddy/memory/2026-10-28.md:545-546` | `.../image2/ec/d4/` 下 `ecd484...4a.jpg`(194288B)、`th_...`(3293B)、`th_...hd`(16667B)（前缀 `ecd4` → `ec/d4`） |
| 同目录内容快照 | `docs/_r52_device_log_snapshot.md:35` | `VFG dir n=10 .../image2/5a/9a :: th_5a9a11b4...(4182), 5a9a2ad02a2dabd863480bf5e948679d.png(40843), th_5a9a2ad0...(5888), 5a9ab42f30239bb60e786cc996775a92.png(140942) …` |
| 内部存储而非 sdcard | `.workbuddy/memory/2026-10-28.md:550` | 「图片路径在 `/data/data` 内部存储，不是 `/sdcard`：`/sdcard/Android/data/com.tencent.mm/MicroMsg` 下没有 image2」 |

⇒ **分桶规则 = md5 前 2 位 / 第 3~4 位**（`md5[0:2]/md5[2:4]`）。四个独立 md5 样本（5a9a / 1a7b / 3fad / ecd4）与目录前缀逐字吻合，【真机确定】，不是套 SNS 公式。

> ⚠️ 与朋友圈 SNS 分桶**不是一回事**：SNS 在 `cache/<hash32>/sns/<h1>/<h2>/snst_<mediaId>`，其桶按 mediaId 相关量算，此前实测 `md5[0:2]/md5[2:4]` 只中 0.68%（team-lead 背景输入）。**聊天 `image2` 的桶是本项独立真机实证**，两者不可互推。本项只用于聊天图片。

【真机确定】`<hash>` 是账号目录名（32 位 hex，真机 `89476c7fc82a9f977bc8a901d68b7d37`）。模块在微信进程内可直接由 `HostContext.app()?.dataDir`（= `/data/user/0/com.tencent.mm`，见 `probe/LabelHuntProbe.kt:898-906` 已有同型实现）拼 `MicroMsg/` 子目录；多账号时遍历 `MicroMsg/*/image2/<桶>/` 找命中即可，不需预判 hash。

### 1.2 文件名形态（真机实录，按优先级）

【真机确定】同一桶内可同时存在下列形态（`docs/_r52_device_log_snapshot.md:35`、`.workbuddy/memory/2026-10-28.md:545-547`）：

| 形态 | 实例 / 大小 | 语义 | 转发可用性 |
|---|---|---|---|
| `<md5>.jpg` / `<md5>.png` | `ecd484...4a.jpg` 194288B（memory:546）；`5a9a2ad0...png` 40843B、`5a9ab42f...png` 140942B（snapshot:35） | **大图/压缩图**（点开大图所见） | ✅ **首选**：清晰、真机确证存在 |
| `th_<md5>hd` / `th_<md5>hd_tmp.jpg` | `th_...hd` 16667B；`th_1a7b...hd_tmp.jpg`（`_cache/dbg_plan.txt:206`） | HD 缩略图 / 其下载临时文件 | ⚠️ 次选：比缩略图清晰但非原图 |
| `th_<md5>` | `th_5a9a0b37...` 4230B（多例 3~8KB） | 缩略图（到达即下） | ❌ 不能作为转发源（见 §4） |
| `<md5>hd` | 未见真机实例 | R34 文档描述的「原图」 | ❌ 【假设】，且真机原图常从未落盘（memory:548-549），不可依赖 |

⚠️ R34 文档（`docs/_r35_revoke_img_anchor.md:11`）写的 `<md5>` / `<md5>hd` 三档是**内部命名口径**；真机落盘文件名带图片扩展名（`.jpg`/`.png`），这是本次取证最关键的订正：**拼路径不能写死「裸 md5」，而应在桶目录里按文件名包含 md5 实际挑文件**。

### 1.3 URI 形态

【源码确定 + 真机确定】`message.imgPath` 对 type=3 恒为 `THUMBNAIL_DIRPATH://th_<32位md5>`：
- dex 校验正则 `^THUMBNAIL_DIRPATH://th_[0-9a-f]{32}$`（`docs/_r35_revoke_img_anchor.md:90-100` ① dex 级）；
- 真机大量实录（`docs/_r52_device_log_snapshot.md:13`、`wxgroup/_cache/dbg.txt:487` 等）。
- 复用现成纯函数 `MediaGuardLogic.hexToken()`（`app/src/main/java/com/lx/wxgroup/data/MediaGuardLogic.kt:80-100`）即可拿到 md5；【真机确定】它「优先取第一个恰好 32 位 hex 段」的策略在这类串上必然取对（前缀单字母 hex 段长度=1，不会顶掉 32 位段）。

---

## 2 能否直接查 ImgInfo2 拿真实路径（SQL 方案评估）

### 2.1 先给结论：**不是首选，且不能作为路径来源**

【真机确定】8.0.48 真机上 `ImgInfo2.bigImgPath` / `thumbImgPath` **都不是磁盘绝对路径，而是虚拟 URI**，直接拿去 `File()` 必然 `exists()=false`：

```
select id,msglocalid,msgSvrId,bigImgPath,thumbImgPath,origImgMD5 from ImgInfo2 order by id desc limit 3
  -> id=875536 | msglocalid=- | msgSvrId=277239618635419627
     | bigImgPath=SERVERID://277239618635419627
     | thumbImgPath=THUMBNAIL_DIRPATH://th_e42cbbcce83635996bfeb1b92584e6f7 | origImgMD5=-
```
（`docs/_r52_device_log_snapshot.md:90-98`，`-` = NULL；同形态另见 `:94-96`。）

⇒ 推翻 `_r35_revoke_img_anchor.md:325,342` 里的「预期日志 `bigImgPath=/…/image2/xx/yy/..`」——那是**预期样例，不是实录**（该文件 `:320` 自己写明「预期日志」）。真实形态是 `SERVERID://` 与 `THUMBNAIL_DIRPATH://`。
⇒ 即便查到 `bigImgPath`，仍要把 URI 解析成路径；而它连 md5 都没有（`SERVERID://<19位>`），比 `message.imgPath`（直接含 md5）信息更少。**SQL 不能回答「文件在哪」。**

### 2.2 关联式也必须订正：用 msgSvrId，不要用 msglocalid

【真机确定】`docs/_r35_revoke_img_anchor.md:78-79` 的 dex SQL 写 `ImgInfo2.msglocalid = message.msgId`，但真机 `ImgInfo2.msglocalid` **恒 NULL**、`msgSvrId` 是 19 位长 ID；`message.msgId` 是 8 位短 ID ⇒ 按 msglocalid 关联必然 0 行（`docs/_r52_device_log_snapshot.md:100-106` 已把 `RRV imgAudit row=0` 由此证伪为「关联口径错」而非「行被删」）。
【源码确定】`ForwardLogic.Snapshot.msgSvrId`（`app/src/main/java/com/lx/wxgroup/feature/ForwardLogic.kt:302-305`）来自 `h9.field_msgSvrId`（`docs/_r29_g2_msg_object.md:§4`），正是可用的 19 位真值。

若仍要查（仅用于**旁证原图是否已下载**，例如读 `status/iscomplete`，不用于取路径），唯一正确 SQL：

```sql
-- 参数 = snap.msgSvrId.toString()（19 位），不是 snap.msgId
select bigImgPath, thumbImgPath, status, iscomplete
from ImgInfo2
where msgSvrId = ?
limit 1
```
（dex 级证据：`msgServerImgInfoIndex ON ImgInfo2(msgSvrId)` 索引存在，`docs/_r35_revoke_img_anchor.md:58-65`；此查询便宜。）

### 2.3 模块内执行这条 SQL 的现成工具与反射要点（若实施旁证时复用）

【源码确定】复用 `AntiRevoke.msgQueryHandle()`（`AntiRevoke.kt:851-855`，返回 `Pair<db, rawQueryMethod>`），不要另起炉灶：

1. `db` 只能在 `updateWithOnConflict` 写库 hook 里捕获（`msgDb`，`AntiRevoke.kt:828,833-838`）；拿不到就返回 null，**不要退回 `lastDb`**（`AntiRevoke.kt:820-826` 已有踩坑记录）。
2. WCDB 同时有 `rawQuery(String,String[])` 与 `rawQuery(String,Object[])` 两个重载：绑定参数数组必须与方法第二形参类型一致，否则 `invoke` 抛 `IllegalArgumentException` 表现成「查不到」（`AntiRevoke.kt:889-896`；现成构造器 `RevokeRescue.kt:314-322` 的 `argsFor2` 可直接照抄）。
3. cursor 是 WCDB 自己的接口，不是 `android.database.Cursor`：全程 `XposedHelpers.callMethod(cur, "moveToFirst"/"getString"/"close")`（`RevokeRescue.kt:278-289`）。
4. 模块自己的读要加 `AntiRevokeHint.BYPASS` 前缀绕过 SQL 改写（`AntiRevoke.kt:897-901`）。
5. 整段包 `runCatching`，失败只打一行 warn 并返回 null，绝不把异常抛回微信线程。

⚠️ 反证：`ImgInfo2` 行在撤回时会被真删（`docs/_r52_device_log_snapshot.md:156-174`，`MTG delete table=ImgInfo2 where=id=?`），所以「行还在」本身也只是**弱假设**；更不能把 SQL 当路径唯一来源。

**本方案评估结论：SQL 只能做旁证、不是路径来源，因此不是首选方案。** 首选见 §5（URI→md5→桶目录→目录内挑真实大图）。

---

## 3 退化取法与代价评估

### 3.1 分桶 O(1) 定位（**实际应作为主路径**，不是退路）

【真机确定】桶规则与文件名见 §1。落地形态：

```
root  = HostContext.app().dataDir/MicroMsg/<每个 hash>/       // 真机唯一含 image2 的 hash
dir   = root/image2/<md5[0:2]>/<md5[2:4]>/
files = dir.listFiles()                                       // 真机该桶 n=9~13（snapshot:35 / dbg_live.txt:234）
pick  = 文件名 contains(md5) 且扩展名 ∈ {.jpg,.png,...}、非 th_ 前缀的最大文件
```

- 代价：一次 `listFiles()`（十几项）+ 文件名比较，**亚毫秒~毫秒级**；`File.exists/length()` 也只读。
- 可靠性：桶规则经 4 组独立 md5 真机验证；文件名按「含 md5 + 非 th_ 前缀」挑，不赌扩展名、不赌裸 md5。
- 【真机确定】目录里同时存在缩略图与大图（§1.2），必须先筛掉 `th_` 开头，否则挑到 4KB 缩略图（§4）。
- 多账号兜底：遍历 `MicroMsg/*/image2/<桶>/`，取第一个命中文件；全程 `runCatching`，任何 IO 异常只返回 null。

### 3.2 全树扫描 image2（**仅作最后兜底，不建议放进 snapshotOf 主线程**）

【强推测】代价：team-lead 背景输入「记忆里全树扫 1.5s」；本 lane 未实测，可信度中等（`ImgInfo2` 真机 842300 行，`docs/_r52_device_log_snapshot.md:90`，image2 文件数量级相近）。

- 可靠性：高——按文件名 `contains(md5)` 找，**不依赖分桶算法**，能覆盖「桶规则未来变动 / 历史版本文件在别处」。
- 代价：1.5s 量级。若同步放在点击转发的主线程 ⇒ 明显卡顿，不可接受；若作为兜底在**用户点击后、异步**跑，1.5s 可被进度提示吸收。
- 建议：**不做成默认路径**；即使做，也必须 (a) 单独后台线程、(b) `listFiles` 递归限深/限总数、(c) 找到第一个命中即返回、(d) `runCatching` 全程包住、(e) 加日志节流。与「保留我方多目标转发」目标的性价比低于 §3.1。

### 3.3 SQL / VFS 虚拟路径解析（不采纳）

- `wcf://image2/...` 只在 dex 字符串里出现（`docs/_r35_revoke_img_anchor.md:114`），真机 `bigImgPath` 实为 `SERVERID://`（§2.1）；把虚拟 URI 映射回 `/data/data` 需要微信 VFS 解析器，模块内无现成入口，【假设】且无收益。
- 结论：§3.1 已用最小代价拿到物理文件，无需退到 3.2/3.3。

---

## 4 关键陷阱：thumb 是缩略图，不能当转发源

### 4.1 三档并存，大小差一个数量级

【真机确定】

| 档 | 文件名 | 真机大小 | 对端收到什么 |
|---|---|---|---|
| 缩略图 | `th_<md5>` | 3293B / 4182B / 4230B / 5888B / 6~8KB（`docs/_r52_device_log_snapshot.md:35`、`_cache/dbg_live.txt:234`、memory:546） | ❌ **模糊小图**。这正是 `message.imgPath` 指向的东西 |
| 大图/压缩图 | `<md5>.jpg` / `<md5>.png` | 40843B / 140942B / 194288B（同上） | ✅ 正常清晰图，**转发应用这个** |
| HD 缩略图 | `th_<md5>hd` / `th_<md5>hd_tmp.jpg` | 16667B（memory:546-547） | ⚠️ 优于缩略图但仍非原图；可作兜底 |
| 原图 | `<md5>hd` | **未见真机实例** | ❌【假设】；memory:548-549 实证「194KB 对原图偏小 ⇒ 原图从未落盘过」 |

### 4.2 当前代码为什么正好踩在缩略图上

【源码确定】`ForceForward.snapshotOf` 现在把 `ref.imgPath` 原样当 `localPath`（`ForceForward.kt:1184-1189`）；而 `ref.imgPath` 对图片恒为 `THUMBNAIL_DIRPATH://th_<md5>`（§1.3）。两条发送路径都要求 `localPath` 非空且 `fileExists`：

- 原生 `nativeForwardImage`:1880-1886；
- 降级 `resendFile`:2027-2034。

URI 字符串 `File(URI).exists()=false` ⇒ `planFor` 判「无 text 无 file」→ `SKIP`（`ForwardLogic.kt:341-355`）。这就是第二层缺口的直接成因。
⚠️ 即便把 URI 简单替换成 `th_<md5>` 磁盘路径，**对端收到的也是模糊缩略图**——所以「解析成真实文件」的正确出口不是「把 th_ 文件发给对端」，而是**在同一个桶里挑出 `<md5>.jpg/.png` 大图**。

### 4.3 选型口径（写进实现）

【真机确定 + 强推测】桶目录内按优先级挑第一个存在且非空的文件：
1. `contains(md5)` 且**不以 `th_` 开头**且扩展名是图片（`.jpg`/`.png`/`.jpeg`/`.webp`，【强推测】扩展名集合来自真机观察到的 jpg/png，需装机补验其它格式）；
2. 多个候选时取**字节数最大**者（真机大图 40~194KB ≫ 缩略图 3~8KB，【真机确定】）；
3. 无 1 时再退 `th_<md5>hd` / `th_<md5>hd_tmp.jpg`（【真机确定】存在）；
4. 最后才退 `th_<md5>`（能发出图但模糊，须打 warn 留痕）；
5. 都没有 ⇒ 解析失败，安全 SKIP（§5）。

**装机验证方法（针对【强推测】部分）**：真机转发一张已点开过（大图已下载）的图片到文件传输助手，对端长按「保存图片」后比对分辨率/字节数是否等于源 `<md5>.jpg/.png`、明显大于 `th_<md5>`；同时看模块日志打的 `pick=` 文件名。若对端拿到 4KB 图 ⇒ 挑错到缩略图，回本节修候选排序。

---

## 5 落地接口：snapshotOf IMAGE 分支如何回填 localPath/fileExists

### 5.1 建议：**纯函数 + 宿主薄壳**拆两层，不要在 snapshotOf 内联

【源码确定】`ForwardLogic.Snapshot` 已有 `localPath`/`fileExists`（`ForwardLogic.kt:285-291`），**无需改数据结构**；只需在 `snapshotOf`（`ForceForward.kt:1184-1189`）替换 IMAGE 分支的取值来源。

**A. 纯逻辑层（放 `ForwardLogic`，零 Android，JVM 可单测）**

```kotlin
// md5 → 桶片段；真机四组样本已证（§1.1）
fun imageBucket(md5: String): String? =
    if (md5.length == MediaGuardLogic.MD5_LEN) md5.substring(0, 2) + "/" + md5.substring(2, 4) else null

// 纯选择：输入 (文件名, 字节数)，输出相对该桶目录的文件名。
// 宿主只负责 listFiles 并把 (name,length) 喂进来；选择口径全在这里，可被单测锁住。
fun pickImageFile(files: List<Pair<String, Long>>, md5: String): String? {
    // 1) 含 md5、非 th_ 前缀、图片扩展名 → 取字节最大者
    // 2) 退 th_<md5>hd / th_<md5>hd_tmp.jpg
    // 3) 最后退 th_<md5>（打 warn 由宿主做）
}
```

**B. 宿主薄壳 `ForceForward.resolveImagePath(imgPath: String?): String?`（只做 IO/反射）**

```
runCatching 全包，任何异常只 warn + null，绝不抛回微信线程：
1. imgPath 已是绝对路径且 exists → 直接返回（兼容「imgPath 本来就是真路径」的未来形态）；
2. md5 = MediaGuardLogic.hexToken(imgPath); 非 32 位 → warn(raw) + null；
3. bucket = ForwardLogic.imageBucket(md5);
4. roots = HostContext.app()?.dataDir + "/MicroMsg/" 下各子目录（已有同型取 dataDir 的写法：
   probe/LabelHuntProbe.kt:898-906；多账号逐个试）；
5. 对每个 root：dir = root/image2/bucket；dir.listFiles() → (name,length) 列表
   → ForwardLogic.pickImageFile(...) → 拼绝对路径 → exists() 且 length()>0 → 返回；
6. 任一失败/全 miss → warn(一行带 md5/bucket/roots数/candidates数) + null。
```
⛔ 只读：**不写库、不下载、不改任何微信状态**；不触发 `DownloadImgService`（R35 已证 `svc.c()=true` ≠ 下载成功，且非本缺口职责）。

**C. `snapshotOf` 的衔接（唯一改动点，约 6 行）**

```kotlin
val rawPath = ref?.imgPath.orEmpty()
val path = if (kind == ForwardLogic.Kind.IMAGE && rawPath.isNotBlank())
    resolveImagePath(rawPath).takeIf { !it.isNullOrBlank() } ?: rawPath   // 解析失败保留原 URI 供日志/排障
else rawPath
// 下面两行保持原样：
localPath = path,
fileExists = path.isNotBlank() && runCatching { File(path).exists() }.getOrDefault(false),
```
- 解析成功：`localPath`=真实大图路径、`fileExists=true` ⇒ `planFor` 走 `RESEND_FILE`（开关关）/ `NATIVE`（开关开），两条发送路的 `MATERIAL` 命门（`ForceForward.kt:1880,2027`）同时解开。
- 解析失败：`localPath` 仍是 URI ⇒ `fileExists=false` ⇒ `planFor` 安全 `SKIP`，**不崩微信、不发空壳**（与现状等价，只多一行 warn）。
- 非 IMAGE 分支**完全不调用** resolver（零额外开销、零行为变化）。

【强推测】多账号 root 遍历时若 `image2` 下有大量无关目录，`listFiles()` 只读单桶（十几项）不会放大；【假设】若真机出现 `MicroMsg/` 下有多个含 `image2` 的 hash 目录且目标图不在第一个，按「第一个命中即返回」可能挑错账号——**装机验证方法**：日志按 `roots=n hit=<第几个 root>` 打出，多账号用户登录后转发一张图，确认 `hit` 落在当前账号；若错，改为「按 `EnMicroMsg.db` 所在 hash 优先」（`ContactSqlSource`/`GroupDb` 已有捕获到的主库路径可复用）。

### 5.2 全程 runCatching 纪律（必须体现在实现里）

- `resolveImagePath` 最外层 `runCatching { ... }.getOrElse { warn; null }`；
- 内部每一步 IO（`dataDir`、`MicroMsg.listFiles`、`dir.listFiles`、`File.exists/length`）各自 `runCatching`，单个 root/文件失败不影响其它候选；
- `snapshotOf` 调用也用 `runCatching`（防御 resolver 未来改动），失败退回 `rawPath`；
- 日志只打路径尾段/md5 前 10 位，不打完整 wxid/文件名（节省 LogX 环形缓冲，沿用项目惯例）。

---

## 6 单测策略

### 6.1 零 Android 依赖的纯逻辑单测（可离线锁住，必须补）

沿用现有 `app/src/test/java/com/lx/wxgroup/...` 的 JVM 单测风格（参考 `MediaGuardLogicTest.kt` / `ForwardLogicTest.kt`），新增：

| 测试对象 | 判据（含边界） | 来源 |
|---|---|---|
| `MediaGuardLogic.hexToken` | `THUMBNAIL_DIRPATH://th_f315db38a5d17f6ec88a5c2e64eb2007` → 精确 32 位 md5；大写变体 → 小写归一；串中混 40 位 sha1 时仍取 32 位段；空/非 hex → null | `MediaGuardLogic.kt:80-100`；真机 URI（`wxgroup/_cache/dbg.txt:487`） |
| `imageBucket(md5)` | 四组真机样本：`5a9a0b37…→5a/9a`、`1a7b9f5d…→1a/7b`、`3fadbd…→3f/ad`、`ecd484…→ec/d4`；长度≠32、空串 → null | §1.1 真机确定 |
| `pickImageFile` | ① 桶内同时有 `th_<md5>`(4KB) + `<md5>.png`(40KB) + `<md5>.jpg`(140KB) → 选 `.jpg`（最大且非 th_）；② 只有 `th_<md5>hd` → 选 hd；③ 只有 `th_<md5>` → 选缩略图；④ 空列表/文件名不含 md5 → null；⑤ 其它消息的同桶文件（md5 前两位相同但后 30 位不同）不得误选 | §1.2/§4 真机确定 |
| `planFor(IMAGE, fileExists=true)` | 原生不可用 ⇒ `RESEND_FILE`；`fileExists=false` ⇒ `SKIP`（锁住「解析失败不空发」） | `ForwardLogic.kt:332-355` |
| 「不倒退」护栏 | 不给 `resolveImagePath` 传非 IMAGE kind 的函数契约（由 §5.1 的分支条件静态锁定；审阅即可，无运行时） | §5.1 |

⛔ 单测**不碰** File IO / Android / Xposed：resolver 只接受 `(name,length)` 列表做选择；真实路径存在性留给宿主薄壳。

### 6.2 必须真机验（纯逻辑锁不住的部分）

| 项 | 装机验证方法（唯一判据） | 对应等级 |
|---|---|---|
| `<hash>` 目录发现 / 多账号 | 收到图后看 `FORWARD resolveImage` 日志的 `roots=/hit=`；转发一张图，确认命中的是当前账号目录 | §5.1【假设】 |
| 桶目录真实存在 + 大图文件名形态 | 真机点开图使大图下载完成，看 resolver 打出的 `bucket=/pick=<真实文件名>`；须是 `<md5>.jpg/.png` 形态，不是 `th_` | §1/§4【强推测：扩展名集合】 |
| 两条发送路径都打通 | 默认关原生 → `FORWARD resend-file kind=IMAGE ok=true`；开 `chat.forward_native` → `FORWARD native image queued`（`ForceForward.kt:1952`）；对端**收到清晰图**而不是空白/模糊图 | 端到端 |
| 对端清晰度（§4 陷阱） | 转发后对端长按「保存图片」，比对字节数/分辨率 ≈ 源 `<md5>.jpg/.png`，≈4KB 即判失败 | §4 |
| 宿主零崩溃 / 无性能问题 | logcat 无 `FATAL EXCEPTION` / `ANR`，点击转发后 UI 不卡（单桶 listFiles 应为毫秒级）；解析失败只有一行 warn，无 `SKIP` 之外的空消息 | §5.2 |
| 反向对照 | 不点开、让大图未下载 ⇒ 桶内只有 `th_` ⇒ 若选择逻辑退到缩略图，对端应收到模糊图（证实 §4 取舍）；完全没有文件 ⇒ `SKIP` + warn，不空发 | §4/§5 |

---

## 0 结论摘要

### 唯一推荐方案（一句话）

**在 `snapshotOf` 的 IMAGE 分支新增单一 `resolveImagePath()`：`MediaGuardLogic.hexToken(uri)` 取 md5 → 主路径走真机实证的 `image2/<md5[:2]>/<md5[2:4]>/` 单桶定位，桶内按「含 md5、非 `th_` 前缀、图片扩展名、字节最大」挑出真实大图 `<md5>.jpg/.png` 回填 `localPath`+`fileExists=true`；仅当分桶未命中时才用 `AntiRevoke.wxDb()`（EnMicroMsg.db）按 `msgSvrId=?`（并列 `msglocalid=?` 兼容）查 `ImgInfo2.bigImgPath`，**只有返回绝对路径且 `File.exists()` 才采用**，虚拟 URI（`SERVERID://`/`THUMBNAIL_DIRPATH://`/`wcf://`）一律退回分桶结果；失败保留 URI 让 `planFor` 安全 SKIP，全程 runCatching。**（不发 `th_` 缩略图——对端收到模糊图；SQL 的正确喂参与证据冲突裁决见 §7。）

### 最小改动清单（均为建议，本 lane 未动一行源码）

| # | 文件 | 改动 | 行数级 |
|---|---|---|---|
| 1 | `feature/ForwardLogic.kt` | 新增两个纯函数 `imageBucket(md5)` / `pickImageFile(files, md5)`；不改 `Snapshot` 结构、`planFor`/`canResendFile` 不动 | ~20 |
| 2 | `feature/ForceForward.kt` | 新增 `resolveImagePath(imgPath)` 宿主薄壳（runCatching 全包、多 root 遍历、日志一行）；`snapshotOf` IMAGE 分支替换 `path` 取值（§5.1 C） | ~40 + 6 |
| 3 | `app/src/test/.../ForwardLogicTest.kt`（或新增 `ImagePathLogicTest.kt`） | §6.1 五组纯逻辑用例 | ~40 |
| 4 | 文档 | 本文件；不改 `docs/取证结论.md` 等既有结论 | 已落盘 |

⛔ 不改：`ForwardNativeLogic`、`FileSender`、`AntiRevoke`/`RevokeRescue`/`VoiceFileGuard`、任何 Key 默认值；不开 `chat.forward_native`；不做全树扫描默认化；不引下载。

### 装机验收判据（对着用户可见事实）

1. **主判据**：默认开关下长按一张**已点开过（大图已下载）**的图片 → 我方「转发（模块）」选一个目标 → `FORWARD resend-file kind=IMAGE ok=true` **且对端收到清晰图片**（保存后字节数/分辨率 ≈ 源 `<md5>.jpg/.png`，明显大于 `th_<md5>` 的 3~8KB）。
2. 开 `chat.forward_native` 后再转一张：`FORWARD native image queued ... sub=13` 且对端同样收到清晰图（证明两条路径共用同一份真实路径）。
3. 日志侧：`FORWARD resolveImage kind=IMAGE md5=<前10> bucket=<xx/yy> roots=n hit=<第几个> pick=<文件名> exists=true` 恰好一行；无 `FORWARD native image stage=MATERIAL` / `FORWARD resend-file no local file` 失败行。
4. **安全 SKIP**：长按一张未下载大图的图片（桶内仅 `th_`）→ 按 §4 口径应有明确选择/警告；完全没有文件 → `planFor` SKIP + 一行 warn，**无空消息、无 FATAL EXCEPTION、无 ANR**。
5. 反向对照：把 `ref.imgPath` 临时改回原 URI（不应用本改造）复现同一张图 ⇒ 仍出现 `FORWARD plan ... SKIP ... kind=IMAGE`（`team-lead` 给定现状日志），证明 1 的 PASS 确由本改造带来。

### 证据等级汇总

| 结论 | 等级 |
|---|---|
| 根=`/data/user/0/com.tencent.mm/MicroMsg/<hash>/image2/`；桶=`md5[0:2]/md5[2:4]` | 【真机确定】（4 组样本） |
| 大图=`<md5>.jpg/.png`；缩略图=`th_<md5>`；HD 缩略图=`th_<md5>hd` | 【真机确定】 |
| `bigImgPath=SERVERID://…` / `thumbImgPath=THUMBNAIL_DIRPATH://th_<md5>` 均为虚拟 URI | 【真机确定】 |
| `ImgInfo2.msglocalid` 恒 NULL，关联应走 `msgSvrId` | 【真机确定】 |
| URI→md5 复用 `hexToken` | 【源码确定 + 真机确定】 |
| 扩展名集合 / 多账号 root 选择 | 【强推测/假设】→ 装机验证方法见 §4.3 / §5.1 |
| 全树扫 1.5s | 【强推测】（team-lead 背景输入，未实测） |

---

## 7 追加取证：回应 team-lead 的三条补充

### 7.1 证据冲突裁决：`rawBig=/…/image2/0c/94/…` 是**样例**，不是真机实录

【源码确定】team-lead 引的 `_r35_revoke_img_anchor.md:191,342` 出自两处**格式样例**：
- `:186` 原文「落地输出一行（撤回被拦下时打…）」+ `:188-192` 示例 block；
- `:320` 小节标题「### 预期日志」，`:338` 原文「**④ 审计**（PASS 形态）」+ `:339-343` 示例 block。
- 更硬的反证：`docs/_r49_revokeimg_forensics.md:162` 明写「⛔ `:320` 小节标题是『预期日志』，`:331-347` 那些 `AR imgAnchor …` / `VFG protectMedia …` / `RRV imgAudit …` 是**预期输出样例，不是真机实录**，别当实证引用」。

⇒ `:191` 的 `rawBig=wcf://image2/...` 与 `:342` 的 `rawBig=/…/image2/0c/94/...` 是同一示例的**两个分支写法**（对应 `:210-211` 的 `big=NA` 与 `big=OK` 判读表），不能当「真机 bigImgPath 是绝对路径」的证据。

【真机确定】真机 SQL 实录恰恰相反，且来自**正确的库**：`docs/_r52_device_log_snapshot.md:88-98` 用 `op=label sub=on + sub=sql`（主库 `EnMicroMsg.db`），得到 `bigImgPath=SERVERID://277239618635419627`、`thumbImgPath=THUMBNAIL_DIRPATH://th_e42cbbc…`、`msglocalid=NULL`。同文件 `:100-106` 证明 `RRV imgAudit row=0` 是关联口径错、不是行被删；`docs/_r52_revokeimg_rootcause.md:81` 证明 `big=NULL` 是哨兵初值，不是「文件不存在」。

【源码确定】team-lead 关于探针连错库的纠正成立：`tools/dev.py:287` 明确 `op=sql` 打开的是 `sns.db`，所以那次 `no such table: ImgInfo2` 不能否掉表；但**本文引用的证据本来就不是那条探针**，而是 `op=label sub=sql` 的 EnMicroMsg.db 结果。两边不矛盾：DDL/关联式【① dex 级】有效，示例路径无证据价值，`wcf://` 风险仍是【②推断】。

### 7.2 SQL 拿绝对路径 vs 分桶定位：条件性结论 + 唯一推荐

**问：SQL 更可靠吗？答：只有在它真的返回绝对路径时才更可靠；在 8.0.48 真机已观测形态下不更可靠。**

| `bigImgPath` 真机/dex 形态 | 能否 `File()` stat | 处置 |
|---|---|---|
| `/…/image2/xx/yy/<md5>`（r35 示例分支） | 能 | 若真出现 ⇒ 微信自己的记录，**最可信，直接采用** |
| `SERVERID://<19位>`（真机实测） | 不能，且不含 md5 | 丢弃该路径，走分桶；md5 仍由 h9 imgPath 提供 |
| `THUMBNAIL_DIRPATH://th_<md5>`（真机实测 thumb 列） | 不能，但含 md5 | 丢弃路径；md5 可用于分桶（与 h9 同源） |
| `wcf://image2/xx/yy/<md5>`（dex 串，【②推断】） | 不能，模块无 VFS 解析器 | 丢弃路径；不要为它引入未取证反射 |

⚠️ 「hexToken + **猜分桶**」这个说法需要更正：分桶是 4 组真机样本逐字验证的 `md5[0:2]/md5[2:4]`（§1.1），且实现**不猜文件名**——`dir.listFiles()` 后按「含 md5、非 `th_`、图片扩展名、字节最大」实际挑文件。真正的「猜」只剩扩展名集合，而它被目录枚举消掉了。

**唯一推荐（收敛为一条有序链，不是二选一）**：
1. h9 `imgPath` → `hexToken` → md5（永远执行，零成本）；
2. **主路径**：`MicroMsg/*/image2/<md5[:2]>/<md5[2:4]>/` 单桶枚举挑大图（真机确证，无主线程 DB 风险）；
3. **兜底**：仅当 2 未命中，用 `AntiRevoke.wxDb()` 查 `ImgInfo2`，**只接受以 `/` 开头且 `exists()` 的 `bigImgPath`**（次选 `thumbImgPath`，但模糊需 warn）；虚拟 URI 一律忽略，回到 2 的结果；
4. 都没有 ⇒ 保留 URI → `planFor` SKIP。

### 7.3 msgId（19737270 这种）怎么喂进 SQL —— 必须用 msgSvrId

【源码确定】h9 同时给出 `getMsgId()`（8 位，如 `19737270`，`docs/_r29_g2_msg_object.md:§4`）与 `field_msgSvrId`（19 位，`ForwardLogic.Snapshot.msgSvrId`，`ForwardLogic.kt:302-305`）。dex 关联式含**两条腿**：`(ImgInfo2.msglocalid = message.msgId OR ImgInfo2.msgSvrId = message.msgSvrId)`（`_r35_revoke_img_anchor.md:67-80`）。

【真机确定】但真机 `ImgInfo2.msglocalid` 恒 NULL、`msgSvrId` 是 19 位；把 8 位 `msgId` 绑到 `msglocalid=?` 必然 0 行——这正是 `RRV imgAudit` 踩过的坑（`_r52_device_log_snapshot.md:100-106`）。**所以不能只拿 `msgId` 当 localId 喂进去；`msgSvrId` 才是 8.0.48 上真正命中的那条腿。**

推荐 SQL（两条腿都绑，跨版本安全；真机上只有第二条腿命中）：

```sql
SELECT bigImgPath, thumbImgPath, status, iscomplete
FROM ImgInfo2
WHERE msglocalid = ? OR msgSvrId = ?
LIMIT 1
-- args = [ snap.msgId.toString(), snap.msgSvrId.toString() ]
```

- `snap.msgSvrId == 0`（没拿到）⇒ 只按 `msglocalid=?` 查，**预期 0 行**，打一行 `svr=0` warn 后直接走分桶；⛔ 绝不能把 8 位 msgId 塞进 `msgSvrId=?`（位数对不上）。
- `msgSvrId` 列有索引 `serverImgInfoIndex`（`_r35_revoke_img_anchor.md:58-65`），`LIMIT 1` 便宜。

**模块内执行（据 team-lead 新锚点收敛）**：`ForceForward` 与 `AntiRevoke` 同包 `com.lx.wxgroup.feature`，可直接调 `AntiRevoke.wxDb()`（`AntiRevoke.kt:828`）= **EnMicroMsg.db**（不是 `op=sql` 的 sns.db）。wcdb 的 `SQLiteDatabase` super 是 `SQLiteClosable`，**强转 AOSP 恒 null，全程反射**：照 `AntiRevoke.kt:940-970 dumpRecent` / `GroupConvStore.kt:414-425 query(db,sql)`；`rawQuery` 有 `(String,String[])`/`(String,Object[])` 双重载，绑定数组按 `m.parameterTypes[1]` 构造（复用 `RevokeRescue.kt:314-322 argsFor2`）；Cursor 是 wcdb 自有接口，用 `XposedHelpers.callMethod(cur,"moveToFirst"/"getString"/"getInt"/"close")`；诊断查询加 `AntiRevokeHint.BYPASS`；`wxDb()==null`（还没捕获到写库）⇒ 跳过 SQL 走分桶，**不退 `lastDb`**（`AntiRevoke.kt:820-826`）。整段 `runCatching`，失败只 warn。

### 7.4 给主会话的只读真机验证命令（确认路径形态，不改代码）

`dev.py probe sql` 只能查 sns.db，别用它判 `ImgInfo2`。用已验证能查主库的 `op=label sub=sql`（TAG=`LABEL`，`LabelHuntProbe.kt:66,246`；只读关卡 `:455`）。`dev.py probe` 不映射 extra `sql`，须手工广播：

```bash
# 1) 开 60 秒抓库钩子（钩子在 rawQuery 热路径上，取完务必 sub=off 关掉）
am broadcast -a com.lx.wxgroup.PROBE --es op label --es sub on --ei sec 60
# 2) 查主库（补齐 5 列真实值）
am broadcast -a com.lx.wxgroup.PROBE --es op label --es sub sql \
  --es sql "select id,msgSvrId,msglocalid,bigImgPath,thumbImgPath,status,iscomplete from ImgInfo2 order by id desc limit 5"
python tools/dev.py q 'LABEL' 40
am broadcast -a com.lx.wxgroup.PROBE --es op label --es sub off
```

判读：若 `bigImgPath` 出现以 `/` 开头且能 stat 的值 ⇒ §7.2 第 3 步可提升为主路径；若仍是 `SERVERID://`/`wcf://` ⇒ 维持分桶主路径。另可针对刚长按的图精确查（用 `snap.msgSvrId`）：

```sql
select id,msgSvrId,msglocalid,bigImgPath,thumbImgPath from ImgInfo2
where msglocalid=19737270 or msgSvrId=<该图的19位msgSvrId> limit 1
```

[DONE]

