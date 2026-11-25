# R86c Q4b 三只读探针真机事实（2026-11-24 22:01）

> 目的：把 NO-GO 裁决的 G1/G2/G3 三项 UNKNOWN 用真机只读取证变成事实。本文档**只记事实，不做根因定性/选型裁决**（那是 Pro 类，另派）。
> 原始日志：`_r86c_probe_forensics_raw.txt`（39 行，从 logcat 按 `R86C` 过滤、去 LSPosedFramework 双写）。
> 取证条件：新 APK 21:58 装机（lastUpdateTime 21:58:20）、双冷启、11 hook 全挂载（含 `SCD-r86cfs exact`@21:59:21.037）、`op=r86c sub=on`@21:59:45（stat 回读 fs=true r0=true 确认）、用户模块「转发（模块）」到文件传输助手@22:01、`sub=off`@22:02:08（stat 回读双 false 确认已关）。

## 肉眼判据（事后补录，2026-11-24 22:1x 用户澄清）
- 【真机实证·用户肉眼·高】用户事后明确确认：**22:01 那次模块「转发（模块）」到文件传输助手，对端收到的就是清晰大图**（在 AskUserQuestion 中三选一明确选「那次模块转发就清晰」，非记混、非别的那次）。
- ⇒ 这与历史 B 态「模块转发必模糊（9554B/0ef205c1）」完全相反，也与「当前旧修复码 `skip("no localPath")` 未写 fullpath ⇒ 应模糊」的代码预期相反。**本条为推翻既有裁决前提的关键新事实，交 Pro 重新定性**（主会话不自裁）。
- 那次确为模块转发的铁证：`probeR86c`@22:01:21 只在 `arm()` 内被调（`ForwardCdnFieldHook.kt:157`），`arm()` 只在 `forwardCtx!=null`（模块转发）时触发（`FileSender.kt:158`）。
- 出口 md5 未能取回：logcat 缓冲已刷（现仅剩 22:07 后），SCD 主开关当时未开、x0cb 常驻 hook 日志已随缓冲丢失；`_r86c_probe_forensics_raw.txt` 只含 R86C 前缀探针行，无出口 md5。
- ⚠️ 原「本次未采肉眼」表述作废，以本条为准。

## 三判据事实

### G1 · P-FS 字节可获得性 —— 【真机实证·高】命中且唯一
- `R86C FS verdict scanned=22 a45522=1 cff4=1 hit=1 ambiguous=false`（22:01:47.361）
- 唯一命中候选：`R86C FS cand tail=e64b3b2.jpg_hevc len=45522 md5pre=cff4f5a5`（22:01:26.016）
- 读法：模块转发同一时刻，MicroMsg 数据根 `image2/` 两级分桶下**存在且仅存在一份**内容 MD5=cff4f5a5、体积 45522B 的 `.jpg_hevc` 文件。
- ⇒ G1 前置「字节可获得」= **满足**（无歧义）。

### G2 · P-R0 z3 四路路径 —— 【真机实证·高】四路全不命中 cff4f5a5
当前待转发图 r0 的字段原值（`R0ID a3Fields`/`o2Fields` 逐字相同）：
- `e = 216b1f7ccd59b1268349fd0eb3c46801.jpg`
- `f = NA`
- `g = THUMBNAIL_DIRPATH://th_216b1f7ccd59b1268349fd0eb3c46801`
- `j = NA`
- `l = NA`

四路 z3 拼路径结果（第 4 参 false 与 true 两轮**完全相同**）：
| field | raw | 拼出 tail | len | md5pre |
|---|---|---|---|---|
| e | 216b1f7c….jpg | fd0eb3c46801.jpg | 335644 | `86ff18ad` |
| f | NA | NA | -1 | NA |
| g | THUMBNAIL_DIRPATH://th_216b1f7c… | 8349fd0eb3c46801 | 15906 | `f6ab3ac5` |
| j | NA | NA | -1 | NA |
- `R86C R0 verdict hitFields=none` + `retryTrue hitFields=none`（22:01:21.180/.187）
- 读法：`z3(r0.e/f/g/j,"","",bool)` 拼出的三条路径分别是 335644B 源图 jpg（86ff18ad）、15906B 缩略图（f6ab3ac5）、无路径——**没有一路指向 cff4f5a5/45522B 的 hevc 原图**。e 路命中判据 `len==45522 && md5pre==cff4f5a5` 为假。
- ⇒ G2 前置「z3 某路命中原图 hevc」= **不满足**（就本次 o2 的 r0 而言）。

### G3 · P-R0ID a3 vs o2 —— 【真机实证·高】不同对象、同字段同记录
- `R86C R0ID a3id=75915761 o2id=7688662 sameObj=false`（22:01:21.172）
- `a3Fields` 与 `o2Fields` **逐字完全相同**（e=216b1f7c…jpg / f=NA / g=THUMBNAIL_DIRPATH://th_216b1f7c… / j=NA / l=NA）。
- 读法：`a3(talker,msgSvrId)` 与 `o2(imgLocalId)` 经不同查询键（dex 已证 `msgSvrId=?`/w7 vs `id=?`/B7）返回**两个不同对象**（identityHashCode 不同、sameObj=false），但**字段内容指向同一条图片记录**。

## 事实层的表观矛盾（交 Pro 裁决，主会话不下结论）
1. **G1 与 G2 冲突**：本地确有 cff4f5a5/45522B 原图文件（G1 go），但 z3 从 o2 的 r0 字段拼不出它（G2 no-go）——即「字节在，但 Q2 选型的拼路径手段取不到它」。若修复仍走「z3 现场拼路径」，写入的 fullpath 会是 335644B 源图或 15906B 缩略图，而非原图 hevc。
2. **原图 hevc 文件名 vs r0 字段**：G1 命中的是 `e64b3b2.jpg_hevc`（45522B/cff4f5a5）；r0.e 是 `216b1f7c....jpg`（335644B/86ff18ad）、r0.g 是 `th_216b1f7c…` 缩略图。三者文件名 md5 前缀互不相同——z3 的输入字段里**没有**指向 hevc 原图文件名的候选。
3. **sameObj=false 但字段全同**：a3 与 o2 是不同对象但同一记录；Q3.2 抛回的「inject 该复用哪条 r0」在本次取证里两者字段一致，但对象不同——需 Pro 判断这对选型意味着什么。
4. **第 4 参 boolean 假/真两轮结果一致**：Q5 遗留的「z3 第4参是否影响路径」UNKNOWN，本次真机显示 false/true 拼出完全相同的三条路径 ⇒ 该 UNKNOWN 在本次样本上不成立（路径不受此参影响）。

## 交给 Pro 的裁决问题（主会话不自裁）
1. G1 go / G2 no-go 冲突下，NO-GO 是否维持？还是选型需从「z3 拼路径」转向「P-FS 已证文件系统可直接定位 cff4f5a5 唯一文件 → 写它的绝对路径进 field_fullpath」？
2. 若转向文件系统定位方案，落码规格怎么改（复用 P-FS 扫描逻辑写入 fullpath）？闸门/只读红线/判据（肉眼 A→B→A）如何保持？
3. 本次转发的肉眼清晰/模糊未知（未采集），是否需要补一轮肉眼判据 + A/B 对照，还是字节级证据已足以推进？

> 状态：探针已 off（22:02:08 确认双 false），冷启亦静默；未改源码、未提交、未推送。
