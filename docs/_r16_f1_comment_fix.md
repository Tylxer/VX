# R16 · F1 lane：4 处过时注释订正

> **声明：本次只改注释，零逻辑改动。**
> 未改任何函数签名、常量值、控制流；未新增/删除任何函数；
> 未碰 adb、未跑 gradle；未改测试文件。
> 改动范围仅 4 个 `.kt` 文件的 `//` 与 KDoc，外加本报告。

---

## ① `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt`

### ①-1 `imageCandidates()` KDoc —— 主体订正（本次核心项）

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:315`（KDoc 第 315-336 行，原 KDoc 起于 :306） |
| 原文 | `⚠️ **本轮的诚实边界**：微信把朋友圈原图缓存放在哪个目录、用什么分层，**尚未取证**。`<br>所以这里只产出「可能的形态」，由调用方 `File.exists()` 逐个实测 —— [pickExisting] 就是这么设计的（把存在性判断作为参数注入，本文件不做任何 IO）。<br>形态来自对微信其他缓存（头像/表情/语音）的通用做法，两种都试：<br>1. `root/<md5>` —— 单层扁平<br>2. `root/<md5[0..2]>/<md5>` —— 前两位分桶（微信多处缓存用这个） |
| 新文 | 首行改为 `原图缓存文件的**候选路径**（**旧 md5 路线遗留**，已被 R15 索引直扫取代）。`<br>随后新增「⚠️ **本组函数（[imageCandidates] / [pickExisting] / [md5sOf] / [md5sFrom]）只保留给旧探针 `op=moments sub=img` / `sub=imgtry` 对账**」<br>+ 2026-10-24 R15 取证事实四条：<br>· 目录 `/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`<br>· 文件名非 md5，是 `snsb_`/`snsu_`/`snst_` + mediaId（20 位左右）派生<br>· `MD5(mediaId)` 的 `[2]`/`[8]` 分桶实测命中率 **0.68%**，公式路线废弃<br>· 现行正解 = 索引直扫（`MomentsExport.buildSnsIndex()` → [snsNameCandidates] → [matchSnsFileName]）<br>+ `picked=NONE` 是**必然**结果的结论 |
| 依据 | R15 真机取证已确证目录与命名规则；`MomentsExport.buildSnsIndex()`(:564) / `copyOneFromCache()`(:665) 为现行链路，`MomentsLogic.matchSnsFileName()`(:485) 为查表函数。原文「尚未取证」与代码现状矛盾。 |

### ①-2 `mediaKeys()` KDoc

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:246` |
| 原文 | `图片候选文件的匹配线索（**待取证**用）。`<br>`微信磁盘上的朋友圈缓存文件命名规则**还没取证**（是 url 的 md5？还是 snsId+序号？不知道）。`<br>`这里把**所有可能用来对上的串**都吐出来，由 `MomentsExport.probe("img")` 拿去跟真实文件名对账，对上哪种就把那条规则写进导出逻辑。` |
| 新文 | 首行改 `图片候选文件的匹配线索（**旧 md5 路线遗留**，仅服务 `sub=img` / `sub=imgtry` 探针对账）。`<br>正文改：命名规则已于 2026-10-24 真机取证（R15），文件名是 `snsb_`/`snsu_`/`snst_` + mediaId 派生、**不是 md5**；本函数仍吐 snsId / URL 尾段 / URL 的 md5 只是为了让旧探针继续跑对账；现行正解是索引直扫，不再依赖这里的任何一串。 |
| 依据 | 同上。原「还没取证」已被 R15 推翻；函数本身保留（旧探针对账仍在用，见 `MomentsExport.logImgProbe()`:447、`tryCopyImages()`:813）。 |

### ①-3 `MD5_RE` KDoc 追加补正段

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:289`（追加在原 KDoc 末尾） |
| 原文 | `结论：**朋友圈的图根本没被下载到本机**，所以 md5 无从对上 —— …… 真正可行的线索是 content 里的**完整 CDN url**（见 [extractUrls]）。`（此句**保留未动**） |
| 新文 | 在原句后**追加**一段：`⚠️ **2026-10-24 R15 补正**：上面「根本没被下载到本机」只对**当时那台未浏览过该动态的设备**成立。R15 真机已确证缓存落在 …/cache/<hash32>/sns/<hex1>/<hex2>/，只是文件名不用 md5 而是 snsb_/snsu_/snst_ + mediaId 派生 ⇒ 「md5 对不上」是**命名规则**问题，仍是必然。现行正解是索引直扫，见 [matchSnsFileName] 与 [imageCandidates] 的 KDoc。` |
| 依据 | 原结论「根本没被下载」在 R15 取证后已不精确（缓存存在，只是键不是 md5）。原 KDoc 的警示意图（别再按 md5 找）仍然有效，故保留原文 + 追加补正，不重写。 |

### ①-4 `md5sOf()` KDoc

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:299` |
| 原文 | 仅 `从 [mediaKeys] 产出的候选串里挑出 md5。` + 「为什么要单独挑」+「只收小写」两段 |
| 新文 | 首行下**插入**：`⚠️ 属**旧 md5 路线遗留**，仅服务旧探针 `sub=img` / `sub=imgtry` 对账；现行导出链路走索引直扫（[snsNameCandidates] + [matchSnsFileName]），不经过这里。` |
| 依据 | `md5sOf` 在正式链路（`copyOneFromCache`）中零调用，只被 `logImgProbe`(:449) / `tryCopyImages`(:823) 使用。 |

### ①-5 `pickExisting()` KDoc

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:353` |
| 原文 | `从候选里挑第一个真实存在的。` + `@param exists` 说明 |
| 新文 | 插入：`⚠️ 属**旧 md5 路线遗留**（与 [imageCandidates] 同组），已被 R15 索引直扫取代，仅服务旧探针 `sub=img` / `sub=imgtry` 对账；`picked=NONE` 是必然结果，见上方 [imageCandidates]。`（`@param` 原样保留） |
| 依据 | `pickExisting` 调用点仅 `MomentsExport.kt:457` / `:828`，均为旧探针。 |

### ①-6 对象级 KDoc 目录结构图

| 项 | 内容 |
|---|---|
| 位置 | `MomentsLogic.kt:23` |
| 原文 | ` *      <原图>                       ← 图片（待取证，见 MomentsExport.COPY_IMAGES）` |
| 新文 | ` *      <原图>                       ← 图片（命名规则已取证 R15，见 MomentsExport.COPY_IMAGES）` |
| 依据 | 同 ①-1。 |

> ⚠️ R15 新加的正确注释（`fliterPre` / `matchSnsFileName` / `mediaIdsFromContent` / `snsNameCandidates` / `isSnsMediaName` / `SNS_PREFIX_*`）**一字未动**，已用 `git diff` 逐行核验。

---

## ② `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsExport.kt`

### ②-1 头注释「图片导出」整节

| 项 | 内容 |
|---|---|
| 位置 | `MomentsExport.kt:32`（原 :32，行号未变，仅内容替换 + 扩写） |
| 原文 | `## ⚠️ 图片（原图）导出：命名规则**未取证**，本版不做`<br>`微信磁盘上朋友圈缓存文件的命名规则还没取证（url 的 md5？snsId+序号？不知道）。`<br>`所以 [COPY_IMAGES] 恒为 false，这里**只把线索打出来**：每条导出的动态会顺带 log 一行 MOMENT img … keys=[…] urls=[…]（候选串由 [MomentsLogic.mediaKeys] 生成），拿到真实文件名后对账，对上哪种规则再把复制逻辑写进来 —— **严禁猜规则硬写**。`<br>`文本文件里已经带了媒体 URL 段（renderText 的「---- 媒体 ----」），内容不丢。` |
| 新文 | 标题改 `## ⚠️ 图片（原图）导出：命名规则已取证（R15），但**旧 md5 路线仍在**`<br>正文：目录与命名规则已定案（`/sdcard/Android/data/com.tencent.mm/cache/<hash32>/sns/<hex1>/<hex2>/`，`snsb_`/`snsu_`/`snst_` + mediaId，非 md5；`MD5(mediaId)` 分桶命中率 0.68% ⇒ 公式路线废弃）；<br>明确标注 `logImgProbe()` 与 `tryCopyImages()` 走 `mediaKeys → md5sOf → imageCandidates → pickExisting` 属**遗留对账探针**、`picked=NONE` 是必然；<br>指出新链路为 `buildSnsIndex()` / `copyImagesForRow()` / `probeSnsCache()`；<br>`[COPY_IMAGES] 恒为 false` 保留（理由改写为「真机复核未做」）；「严禁猜规则硬写」「文本文件已带媒体 URL」两句保留。 |
| 依据 | 常量 `const val COPY_IMAGES = false`(:67) 与原文「恒为 false」仍一致 ⇒ 半句保留；「未取证 / 本版不做」已被 R15 推翻 ⇒ 该部分替换。 |

### ②-2 `logImgProbe()` KDoc

| 项 | 内容 |
|---|---|
| 位置 | `MomentsExport.kt:439`（函数在 :447） |
| 原文 | `2026-10-18 起升级为**可判死**的形式：…… 这样下一轮真机上**不用改一行代码**就能判断缓存目录规则对不对（结论词：`picked=` 后面不是 `NONE` 就说明规则命中）。` |
| 新文 | 插入 `⚠️ **旧 md5 路线的遗留探针**（命名规则已于 R15 取证，md5 路线已废弃）；保留只为 `op=moments sub=img` 的历史对账可比性，正式链路见 [probeSnsCache]。`<br>把「`picked=` 不是 `NONE` 就说明规则命中」改为「`picked=NONE` 现在是**必然**结果（缓存文件名不是 md5），不再作为『规则没调对』的判据 —— 要判断新链路请用 `sub=snsprobe`」。 |
| 依据 | 该函数实现（:449-461）确实只走 md5 链路且只打日志；判据方向已被 R15 结论反转。 |

### ②-3 `imgRoots()` KDoc 追加补正段

| 项 | 内容 |
|---|---|
| 位置 | `MomentsExport.kt:493`（追加在原 KDoc 末尾） |
| 原文 | `⚠️⚠️ **实测结论：`picked=` 全是 `NONE`，且这是必然的**。…… ⇒ **朋友圈的图从未下载到本机**。所以不要再花时间调这里的目录规则。`（此段**保留未动**） |
| 新文 | 追加：`⚠️ **2026-10-24 补正（R15）**：上面「朋友圈的图从未下载到本机」是**当时那台设备**的结论。真机已确证缓存目录是 …/cache/<hash32>/sns/<hex1>/<hex2>/，只是文件名不含 md5 ⇒ **这条路仍然不通（因为键选错了，不是目录错了）**。本函数仅供 [logImgProbe] / [tryCopyImages] 两个旧探针使用；新链路见 [buildSnsIndex]。` |
| 依据 | 目录已确证，原「不要再调目录规则」的结论仍成立（原因从「目录不对」修正为「键不对」），故保留原文 + 补正。 |

### ②-4 `tryCopyImages()` KDoc

| 项 | 内容 |
|---|---|
| 位置 | `MomentsExport.kt:806`（函数在 :813） |
| 原文 | `试拷一条动态的原图（探针 `sub=imgtry` 专用，**不依赖 [COPY_IMAGES]**）。` + 「存在的理由」段 |
| 新文 | 插入：`⚠️ **旧 md5 路线的遗留探针**（`md5sOf(mediaKeys(...)) → imageCandidates → pickExisting`）。R15 已确证缓存文件名不含 md5 ⇒ 这里的 `copied=` 必然为 0，属预期，不是缺陷。`；末句补「新链路的对账请用 [probeSnsCache]（`sub=snsprobe`）。」 |
| 依据 | 函数体 :823-830 确实走 `md5sOf(mediaKeys(...))` + `imageCandidates` + `pickExisting`。 |

> ⚠️ 第二节里 `COPY_IMAGES` 常量的 KDoc（:59-67）中「R15 起代码链路已就位……保持 false」一段**是 R15 原有改动，非本次所写，未动**。

---

## ③ `wxgroup/app/src/main/java/com/lx/wxgroup/feature/MuteAtLogic.kt`

| 项 | 内容 |
|---|---|
| 位置 | `MuteAtLogic.kt:23`（类 KDoc 第 23-28 行） |
| 原文 | `⚠️ **群名匹配已实现但未接线（待取证）**：通知链路上目前只能拿到 talker（取证结论「通知静音」节：extras 里没有 chatroom id，contentIntent 反射取不到），拿不到群显示名，所以 [MuteAt] 现在传 groupName=null，群名元素暂时永远不命中。纯判据先按双格式写好测好；等拿到「talker → 群名」锚点（候选：通知 EXTRA_TITLE 是否即群名，需真机确认，可用 `op=quiet sub=at sub2=extras` dump 最近一条通知的 extras）后只需在 hook 侧一行接线。` |
| 新文 | `⚠️ **群名匹配已接线**：`MuteAt.kt:65` 已把 `GroupNameSource.nameOf(talker)` 传进 [groupInScope] 的 groupName 参数（此前「传 null、群名永不命中」的旧描述已作废）。`<br>``<br>`数据源 `GroupNameSource` 走**懒加载**：`nameOf()` 内部会 `warmUp()`，首次拿到主库句柄后才起后台 daemon 线程装载群名表（`msgDb` 在微信第一次写库前恒为 null，此时不起线程）。所以**冷启动首条通知**若装载尚未完成，`nameOf` 返回 null ⇒ 该条**退回纯 talker 匹配**，属预期降级，不是功能失效。真机探针 `GROUPNAME loads=0` 只说明**还没触发过装载**，不是失败。` |
| 依据 | `MuteAt.kt:63-68` 实际传参 `talker?.let { GroupNameSource.nameOf(it) }`；`GroupNameSource.kt:117-122` 的 `nameOf()` 内部调 `warmUp()`(:120)，`warmUp()`(:84-109) 起 daemon 线程懒装载，`msgDb` 为 null 时直接返回。原文「传 groupName=null」与代码事实矛盾。 |

---

## ④ `wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsCdnImage.kt`

| 项 | 内容 |
|---|---|
| 位置 | `SnsCdnImage.kt:81`（`Cfg` 声明上方，新增 4 行注释） |
| 原文 | `private data class Cfg(val enabled: Boolean, val rewritePath: Boolean)` —— 字段 `rewritePath` 无任何说明，且全文件零读取点 |
| 新文 | 声明上方加 4 行 `//` 注释：<br>`// ⚠️ 字段 `rewritePath`（`sns.cdn_rewrite_path`）**当前零消费点** —— 只被 refreshCfg() 写入、`<br>`// 全文件（乃至全仓）没有任何读取点。批量下载路线已于 2026-10-24 定案放弃`<br>`// （docs/取证结论.md ⑫.9：`picCachePath` 字段存在但恒空，hitPicE=7 / hitNoField=0；`<br>`// 批量请求 ok0.x0#n 从未触发，hitN=0）⇒ 这条路走不通。对应开关在 Keys.kt 里已标 hidden。`<br>**声明本身一字未改**（保持单行原样）。 |
| 依据 | `grep rewritePath SnsCdnImage.kt` 全文件仅 :81 声明与 :98 `refreshCfg()` 的构造写入，无读取 ✅；`grep SNS_CDN_REWRITE_PATH` 全仓仅 `Keys.kt:199`（定义）、`Keys.kt:318`（`hidden = true`）、`SnsCdnImage.kt:98`（写）✅。`docs/取证结论.md` ⑫.9 记录 `picCachePath` 恒空、`hitPicE=7/hitNoField=0`、`hitN=0`。 |

---

## 改动统计

### 本 lane 实际增量（按 hunk 归类，仅注释）

| 文件 | 新增 | 删除 |
|---|---|---|
| `MomentsLogic.kt` | +18 | -3 |
| `MomentsExport.kt` | +22 | -6 |
| `MuteAtLogic.kt` | +7 | -6 |
| `SnsCdnImage.kt` | +4 | 0 |
| **合计** | **+51** | **-15** |
| `docs/_r16_f1_comment_fix.md`（新建） | 本文件 | — |

### `git diff --stat` 全量（含 R15 遗留的未提交存量）

```
 wxgroup/.../feature/MomentsExport.kt | 443 ++++++++++++++++++++-
 wxgroup/.../feature/MomentsLogic.kt  | 261 +++++++++++-
 wxgroup/.../feature/MuteAtLogic.kt   |  13 +-
 wxgroup/.../feature/SnsCdnImage.kt   |   4 +
```

> ⚠️ `MomentsExport.kt` 443 行 / `MomentsLogic.kt` 261 行里绝大部分是 **R15 的索引直扫实现**
> （`buildSnsIndex` / `copyOneFromCache` / `matchSnsFileName` / `mediaIdsFromContent` 等），
> 在本 lane 开始前就已在工作区未提交，**不是本 lane 所写**。本 lane 只碰上面「实际增量」表列出的注释行。
>
> 逐行核验命令（本 lane 的 hunk 内无一行逻辑）：
> `git diff -U0 <file> | grep -E '^[+-]' | grep -vE '^[+-]\s*(\*|/\*|//)'`
> —— 被过滤掉后仍出现的代码行（`SNS_PREFIX_*` / `fliterPre` 等）全部落在 R15 存量 hunk 内。
>
> 另一条更直接的自证：`SnsCdnImage.kt` 与 `MuteAtLogic.kt` 除本 lane 外**没有**其它未提交改动，
> 这两个文件的 diff 通篇只有注释 —— 可作「本 lane 只改注释」的干净样本。

---

## 剩余已知失真（本 lane 未处理，供后续轮次参考）

1. `MomentsLogic.kt:23` 处目录结构图的 `<原图>` 已改为「已取证 R15」，但 `COPY_IMAGES` 仍为 false，
   即「目录里应有原图」与「实际不导出原图」并存 —— 这是**代码现状**，不是注释问题。
2. `MomentsExport.kt` 中 `COPY_IMAGES` 常量 KDoc 的「真机复核还没做」仍待 M1 验证后更新。
