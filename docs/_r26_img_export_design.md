# R26 · 朋友圈图片导出 —— 真机判据 + 回填 + 边界设计复核

> 目标微信 8.0.48 / versionCode 2588 · LSPosed 模块 `com.lx.wxgroup`
> 本文档只做**设计与复核**，不写业务代码、不跑 gradle、不碰 adb。
> 输入（只读）：
> - `MomentsExport.kt`（L155-205 常量区 / L560-715 导出主循环 / L860-905 buildSnsIndex /
>   L918-1065 copyOneFromCache·mediaIdsOf·copyImagesForRow·countImg / L1205-1277 probeSnsCache）
> - `MomentsLogic.kt`（mediaIdsFromContent / fliterPre / snsNameCandidates / matchSnsFileName /
>   imageFileName / isSnsMediaName）
> - `docs/_r15_sns_cache_plan.md`（Q1-Q6）、`docs/_r19_a4_probe.md`（全文）
> 真机事实采信 team-lead 提供（2026-10-26 18:30，PJD110 / Android 16）：
> 只读探针 `sub=snsprobe n=15` → 7 条全 hit=0（全是**已删**动态，图早已不在缓存）；
> 同机刚跑文本导出 `rows=7 written=0 skipped=7`（7 条 txt 全已存在，走 skip 分支）。

---

## 问题 1 · 只把 `COPY_IMAGES` 改成 true 够不够？

**结论：不够。判断成立。必须做「回填」。**

`copyImagesForRow` 只在「本次 txt 真写入成功」这一条路径里被调，结构上图片落盘被绑死在
「文本首次写入」这个唯一触发点上，已导出过 txt 的动态永远走不到它。这不是改个开关能解决的。

证据（行号）：

| 位置 | 代码 | 后果 |
|---|---|---|
| `MomentsExport.kt:655-659` | `if (txt.exists()) { exported.add(row.snsId); sk++; continue }` | 已导出动态直接 `continue`，不进 `ok` 分支 |
| `MomentsExport.kt:683-694` | `val ok = ...; if (ok) { ...; if (snsIdx != null) copyImagesForRow(snsIdx, row, dir) }` | 图片拷贝只在 `ok`（本次写 txt 成功）里调 |
| `MomentsExport.kt:634` | `if (!cur.first(row.snsId, exported)) { sk++; continue }` | `scanExportedIds` 扫出的已导出 id 在循环最前面就被去重跳过，**根本到不了 655 行** |

真机印证：`rows=7 written=0 skipped=7` → 7 条 txt 全已存在，全走 skip，`copyImagesForRow`
一次都不会被调。即便此刻把 `COPY_IMAGES` 改成 true，这 7 条仍是 0 图。

更深一层：不止「已导出 txt」补不了，连「**新**动态首轮 txt 写了、但当时缓存没滚出来导致图 miss」
的也补不了 —— 因为它的 txt 已经存在了，下一轮同样被 skip。所以回填是**必须**，不是可选。

<!-- DONE:1/6 -->

## 问题 2 · 回填策略（重复拷贝 + 日志爆炸两个坑）

两个坑都确认成立：

- **坑 1 重复拷贝**：`copyOneFromCache` 在 `MomentsExport.kt:955` 是
  `if (dst.exists() && !dst.delete())` —— 先删再写。回填若对已存在图重跑 = 每次全量重写全部图片。
- **坑 2 日志爆炸**：`copyImagesForRow` 在 `MomentsExport.kt:1034-1037` 对**每个 mediaId 都打一行**
  `img mid` 明细。导出每 3h 一轮、`MAX_ROWS_PER_RUN=500`，回填若把几千条「有 txt」的动态都拉进来
  打明细，5000 行环形日志会被 `img mid` 灌满，真判据行（`hit=<文件名>`）被挤掉。

### 策略（三个点）

**(1) `dst.exists()` 短路（在哪加）**

- 加在 `copyImagesForRow` 对每个 slot 构造 `dst` 之后、调 `copyOneFromCache` 之前，语义最清晰：
  `if (dst.exists() && dst.length() > 0) { imgAlready++; continue }`。
- **关键细节**：短路条件必须是 `length() > 0`，不能只看 `exists()`。`.tmp` 半成品、上次 rename
  失败的 0 字节残留，都是「存在但为 0 字节」—— 那是「上次失败」不是「已成功」，必须重拷。
- 新增第五态 `ALREADY`（或 `SKIP`），单独计数 `imgAlready`，**不打明细日志**。
  ⚠️ 注意别污染现有四态口径（`ROOT_MISS/FILE_MISS/READ_FAIL/COPIED` 互斥穷尽，
  方案 Q5 判据 #5「四态之和 = 已尝试 mediaId 总数」）。`ALREADY` 应独立于四态之外。

**(2) 回填触发点（关键结构决策）**

主循环的去重（`L634 cur.first` + `L655 txt.exists()`）把「已导出」动态挡在循环外，回填进不去。
两个选择：

- **A. 改去重语义**：让「txt 在但图不齐」的动态重新进循环 → 需要在**扫描阶段**就知道「图齐不齐」，
  而要判断缺哪张图就得解 `content` BLOB 拿 mediaId 列表，扫描阶段（`scanExportedIds` 只看文件名）
  做不了。**不推荐**。
- **B. 独立回填 pass（推荐）**：主循环完全不动。导出轮结束后（或独立定时 / 手动 `sub=backfill`），
  对导出目录里「有 txt」的动态，逐条查库拿 `content` → 解 mediaId → 对缺图补拷。限次、独立计数、
  聚合日志。

推荐 B 的理由：主循环「文本去重 = 幂等」（R15 Q6）语义不动，风险最小；回填与增量导出解耦，
各自限次、各自计数、各自日志，互不污染。

**(3) 限次 + 日志**

- **限次**：新增 `MAX_BACKFILL_PER_RUN`（建议 200 条动态，或 500 张图，二选一），回填轮跑满即停，
  下轮继续。防冷启后首轮疯狂补图把导出拖死。
- **日志**：回填轮**不打 `img mid` 明细**，只打一行聚合：
  `MOMENT img backfill rows= filled= miss= skip= already= err=`。
  明细只在主循环「首次新导出」的 `ok` 分支保留；并且建议给主循环的 `img mid` 也加个每轮上限
  （如 `IMG_LOG_MAX=200` 行，超出只计数不打行），保证即便首轮新导出也最多 200 行明细，
  真判据永远不被挤掉。
- **回填的期望管理**：回填只对「存活动态」有意义。查库时应**优先查当前 `SnsInfo`**（`SnsGuard`），
  查不到再查归档；归档动态的图基本 `fileMiss`，回填对归档的期望就是「快速 miss 完、不浪费」，
  不该为归档打任何明细。

<!-- DONE:2/6 -->

## 问题 3 · 判据设计（对着用户可见事实 + 反向对照）

判据必须对着**用户能看见的事实** = 导出目录里**真的出现了图片文件**，
而不是 `imgCopied>0` 这种内部计数器。

### 为什么不能用 `imgCopied>0` 当判据

- `imgCopied` 是跨轮累积的 `AtomicLong`（`MomentsExport.kt:265`），只在进程内存里，重载模块会清零；
  语义是「历史累计尝试成功数」，**不能证明「这一次、这个目录、真的落了盘」**。
- 真正的「用户能看见的事实」只有文件系统：目录里有没有 `img_*`、size 对不对、magic 对不对。

### 正面判据（真机操作 → 期望）

1. **前置**：滚朋友圈重建缓存，先确认 `names>0`（R19 判据，否则「缓存空」会误读成「链路错」）。
2. 跑一轮导出（或 `sub=export`）。
3. 检查 `Documents/WXGroup/moments/<昵称(wxid)>/<时间>/` 目录，`img_<snsId>_<slot>.jpg` 真的出现，且：
   - **配对**：img 文件名里的 `snsId` 与该目录 txt 文件名 `<snsId>.txt` 的 snsId **严格一致**。
   - **size>0**，且落在缓存源文件的量级区间（`snsb_` 1.1-1.5MB / `snsu_` 6-42KB / `snst_` 20-70KB，R15）。
   - **magic**：文件头 = JPEG（`ff d8 ff e0`），与缓存源一致。
   - **mtime**：落在本轮导出时间窗内。
4. 「通过」的表述 = **本次新出现的 img 文件数 N，且每个都能与同目录 txt 的 snsId 配对、
   size 量级正确、magic 是 JPEG**。

### 反向对照（证「文件不是别的来源留下的」）

| # | 对照手段 | 证什么 |
|---|---|---|
| 1 | **配对反解**：每个 img 的 snsId 必须 = 同目录 txt 的 snsId（`parseExportedId` 反解） | 任何对不上的 img 都是异常来源 |
| 2 | **破坏对照**：临时把 `cache/<hash32>/sns` 改名，再跑一轮 → 应 0 个新 img（R15 Q5 判据 #4） | 证明 img **只**来自 sns 缓存，不来自 url 下载或其他 |
| 3 | **清单 diff**：导出前 `ls` 记清单，导出后 diff，新增必须严格是 `img_<snsId>_<slot>.*` 且与 txt 配对 | 排除「目录里本来就有的旧文件被误当成新导出」 |
| 4 | **内容对照**：取一个 img 与对应缓存源（`snsb_/snsu_/snst_<mediaId>`）比 size 一致（或字节比对/哈希一致） | 证明是「拷过来的同一个文件」 |

判据 #2（破坏对照）是**最硬**的一条：它直接切断唯一来源，若断源后还有新 img 出现，
就说明图不是缓存链路产出的。

<!-- DONE:3/6 -->

## 问题 4 · 功能可用边界（`sns/` 冷启清空 + 已删动态取不回）

**真实价值边界（直说，不美化）：**

图片导出**不是**可靠的图片存档/备份，它是一个「机会主义缓存快照器」：
**只能抓到「用户本次微信运行期内滚屏加载过、且缓存尚未被冷启清空」的当前存活朋友圈动态的图。**

四条边界事实：

1. **已删动态的图基本取不回来**：归档表（`sns_archive`）里的动态，其缓存图早在删除/冷启时就被清了。
   R19 实测归档 7 条 `hit=0`；team-lead 2026-10-26 18:30 再证 7 条全 `hit=0`。
2. **冷启清空缓存**（R19 实测）：微信冷启动会清空 `sns/` 三级缓存。所以「今天导出的 txt，
   明天冷启后再补图」基本补不回来，除非用户**再次滚到**那些动态。
3. **大量 `imgFileMiss` 是常态，不是异常**：用户没点开大图就没 `snsb_`（原图只有 50 个 vs
   压缩 447 / 缩略 532，R15）；没滚到就没任何缓存。
4. **文本导出是可靠的**（content BLOB 在 archive 里存着）；图片是**尽力而为**。

### 是否需要在 stat 日志 / UI 明确告知：**需要**

- **stat 日志**：`stat()`（`MomentsExport.kt:1437-1446`）已有 `imgFileMiss`，但建议追加一个定性字段，
  如 `imgHint=cache-only`，让读日志的人一眼知道「miss 高是预期、不是 bug」。
- **UI**：开关标题/摘要写明「仅导出微信已缓存的图；历史/已删动态的图无法补取」。
  否则用户会以为「开了导出图片就能把朋友圈图全存下来」，制造「功能坏了」的投诉。

**结论一句话**：这个功能的价值 = 「顺手把刚看过的图存一份」，**不能当图片备份卖**。
落地时必须在 UI 与 stat 上把这条边界写清楚。

<!-- DONE:4/6 -->

## 问题 5 · `imageFileName` 恒 `.jpg`，要不要按真实扩展名落盘

现状：
- `MomentsExport.kt:1023` 写死 `imageFileName(row.snsId, slot, "jpg")`。
- `MomentsLogic.imageFileName`（`MomentsLogic.kt:291-294`）把 ext 强制收进 `IMAGE_EXTS`
  （jpg/jpeg/png/gif/webp/bmp/heic），非图片 ext 兜底 `jpg`。

事实：缓存文件本身**无扩展名**，且 R15 真机文件头 `ff d8 ff e0 00 10 "JFIF"` 已证**三者全是 JPEG**。
所以当前 `.jpg` 在 8.0.48 上**正确**。

### 结论：**当前不需要改（全 JPEG 已证），但值得留一个 magic sniff 作未来保险；现在不改也不会错。**

理由：
- 「真实扩展名」= 缓存文件的 **magic bytes** 决定的类型，与 `snsb_/snsu_/snst_` 前缀无关
  （前缀是「原图/压缩/缩略」档位，不是「格式」）。
- 8.0.48 实测三者都是 JPEG，`.jpg` 无错。
- 但微信新版本有向 HEIC/WebP 迁移的趋势，若未来缓存变成 webp，恒 `.jpg` 会把 webp 文件标成
  `.jpg`，Windows/部分看图器按扩展名处理会打不开。

### 最小改动（若要做，约 15 行，无结构重构）

在 `copyOneFromCache` 命中 `src` 后、写 `dst` 前，读 src 前 12 字节判 magic：

| magic | ext |
|---|---|
| `FF D8 FF` | jpg |
| `89 50 4E 47` | png |
| `52 49 46 46 .. 57 45 42 50`（RIFF..WEBP） | webp |
| `47 49 46 38`（GIF8） | gif |

把真实 ext 带回（`ImgResult` 加 `ext` 字段），`copyImagesForRow` 拿到结果后把 `dst` 从 `.jpg`
改成真实扩展名（或先判后写，直接写对名）。

**推荐：本次保持 `.jpg` 不动**（证据充分、加了 sniff 也无法真机验证 —— 当前没有非 JPEG 样本，
盲写逻辑是留了个没验过的分支），仅在 `imageFileName` 的 KDoc 里写一句
「若未来出现非 JPEG 缓存，需 sniff magic 落盘」。等真有样本再补 sniff。

<!-- DONE:5/6 -->

## 问题 6 · 门控 `const val COPY_IMAGES` 要不要改成配置键

现状：`const val COPY_IMAGES = false`（`MomentsExport.kt:165`）编译期常量。
引用点：`L301` install 日志、`L576` `buildSnsIndex` 条件、`L694` 判断、`L1439` stat。

参照：`Keys.kt` 的 `PrefSpec` + `Rows.kt` SWITCH 写法，加配置键
`Keys.MOMENTS_COPY_IMAGES = "moments.copy_images"`（默认 `"false"`）+ 一行
`PrefSpec(Keys.MOMENTS_COPY_IMAGES, PrefKind.SWITCH, "false", "朋友圈", "导出图片")`，
UI 自动渲染、备份自动带、`kvget/kvset` 探针可用。

### 结论：**值得改，一步到位换配置键，默认 false。**

理由：
1. **取证阶段要反复开关**：编译期常量每改一次要重装包 + restart（R19 已踩「没重载读旧日志」的坑）。
   配置键可 `kvset` 即时改、UI 一键切。
2. **语义本就该是用户开关**：「导出图片」与 `auto_export` 同级，不该是开发者常量。
3. **成本低**：Keys 加常量 + `Prefs.ALL` 加一行 + `refresh()` 读进 `config` + 4 处引用改成读配置，
   约 10 行。

### 关键取舍点

- **默认必须 false**：功能还没真机验过，不能默认开。`PrefSpec` 的 `def="false"` 正好满足。
- **不要搞「编译期 `COPY_IMAGES` + 运行期配置」双闸（AND）**：两个闸语义重叠，现场排查
  「到底哪个关了」会变难。推荐**单闸 = 配置键**。
- 唯一保留编译期常量的理由：担心「旧配置残留 true 导致意外开启」。但概率低，且默认 false 已兜底。
  折中若真要保险，可把 `COPY_IMAGES` 改成 `true` 当「开发 hard gate」+ KDoc 写「真机验完前不放」，
  实际生效 = 配置键 AND 常量。**但推荐不做双闸，直接单配置键 + 默认 false。**
- 落地注意：`refresh()` 目前是 `install()` 里最早调的（`MomentsExport.kt:297-302`），
  install 日志 `images=$COPY_IMAGES` 若要改成读配置，需确保读值时机在 `refresh()` 之后，
  否则日志显示的还是旧值（R19 已踩过「读到旧日志」）。

---

## 改动清单（可直接实施，按依赖排序）

1. **门控换配置键**（问题 6）：`Keys.MOMENTS_COPY_IMAGES` + `Prefs.ALL` 一行 + `refresh()` 读进
   `Config`（加 `copyImages: Boolean` 字段）+ 4 处引用改读 `config.copyImages`。默认 false。
2. **`dst.exists()` 短路 + 第五态**（问题 2 坑 1）：`copyImagesForRow` 对每个 slot 构造 dst 后、
   调 `copyOneFromCache` 前加 `if (dst.exists() && dst.length() > 0) { imgAlready++; continue }`。
   新增 `imgAlready` 计数（独立于四态），加进 `stat()`。
3. **独立回填 pass**（问题 2 核心）：导出主循环不动，新增 `backfillImages(...)`，限次
   `MAX_BACKFILL_PER_RUN`，优先查当前 `SnsInfo`，只打聚合日志 `img backfill rows= filled= miss= skip= already= err=`。
4. **`img mid` 明细限行**（问题 2 坑 2）：主循环 `copyImagesForRow` 的 `img mid` 加每轮上限
   `IMG_LOG_MAX`（如 200 行），超出只计数不打行。
5. **UI/stat 告知边界**（问题 4）：开关 title「导出图片」+ summary「仅导出微信已缓存的图；
   历史/已删动态的图无法补取」；`stat()` 追加 `imgHint=cache-only` 或等价定性字段。
6. **（可选，本次不做）magic sniff**（问题 5）：保持 `.jpg`，在 `imageFileName` KDoc 留注记，
   待出现非 JPEG 样本再补 sniff。

## 结论速览（给 team-lead 的 6 行摘要）

1. 只改 `COPY_IMAGES=true` **不够**：`copyImagesForRow` 只在 `if(ok)` 里调，已导出 txt 的动态
   在 `L634`/`L655` 就被 skip，**永远不会补图**。必须做回填。
2. 回填用**独立 pass**（不动主循环去重），`dst.exists()&&length()>0` 短路防重复拷贝，新增
   `imgAlready` 独立计数；回填只打聚合日志不打 `img mid` 明细，且 `img mid` 本身加每轮限行。
3. 判据 = 导出目录里 `img_<snsId>_<slot>.jpg` 真实出现且与同目录 txt 的 snsId 配对、size 量级对、
   magic 是 JPEG；反向对照 = 断 `cache/<hash32>/sns` 源后应 0 新 img。不用 `imgCopied>0` 当判据。
4. 真实价值 = 「顺手存刚看过的图」，**不是备份**：已删动态图取不回、冷启清缓存、`imgFileMiss`
   高是常态。必须 UI/stat 明示。
5. `.jpg` 当前正确（R15 已证全 JPEG），本次不改，KDoc 留「未来非 JPEG 需 sniff magic」注记。
6. 门控值得改配置键 `moments.copy_images`（默认 false），**单闸**，不做编译期+运行期双闸。

[DONE]

