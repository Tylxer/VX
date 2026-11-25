# R86c route2 独立 Pro 复核报告（r86c-route2-review）

> 工作根：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`
> 性质：只读复核。唯一写文件 = 本报告。未跑 gradle/单测、未碰 adb/设备、未 commit/push。
> 判据纪律：只对用户肉眼 / 文件字节裁决；`hit`/`UploadHitCacheType` 只记录不裁决。
> 档位：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN 未闭合】。

## 状态追踪
- [x] Q1 TTL 裁决
- [ ] Q2 diff 全量核验
- [ ] 三件套交付

---

## Q1 —— TTL=2000ms 是否 BLOCKER

### Q1.1 六段间隔逐行亲核（不采信主会话算术，直接看原文）

| 例 | queued 行/时间 | m1e in 行/时间 | 我算间隔 | 主会话值 |
|---|---|---|---|---|
| V2-1 | `:901` 23:44:47.368 | `:921` 23:44:47.414 | **46ms** | 46ms ✓ |
| V2-2 | `:1039` 23:44:54.554 | `:1057` 23:44:54.599 | **45ms** | 45ms ✓ |
| s1b-1 | `:1317` 22:24:39.490 | `:1323` 22:24:39.522 | **32ms** | 32ms ✓ |
| s1b-2 | `:2540` 23:08:54.905 | `:2546` 23:08:54.946 | **41ms** | 41ms ✓ |
| s1b-长1 | `:2609` 23:09:05.875 | `:2652` 23:09:12.220 | **6345ms** | 6345ms ✓ |
| s1b-长2 | `:1375` 22:24:46.520 | `:1442` 22:24:56.591 | **10071ms** | 10071ms ✓ |

来源：V2 `:901/:921/:1039/:1057`；step1b `:1317/:1323/:2540/:2546/:2609/:2652/:1375/:1442`。
六值全部确认，无需更正。【真机实证·高】

> 注：`markTarget` 实登记点在 `FileSender.kt:163`（早于 `l1.a()` 与 `queued` 日志 `FileSender.kt:262`），故真实登记→消费间隔 **≥** 上表值（差约十几 ms），不影响结论。

### Q1.2 迟到帧归属裁决：**确属本次 FileSender queued 那次转发的上传帧**

**长1（d07f）**：`:2606 s0b ret path=d07fcca7...jpg` → `:2609 queued target=filehelper`（`imgLocalId=883190`）→ `:2649 j2R paths e=d07fcca7...jpg` → `:2652 m1e in field_fullpath=.../image2/d0/7f/d07fcca7eb1b1345647b65c623095d79.jpg_` → `:2655 c2c fexists=true fbytes=9554 fmd5pre=0ef205c1`。basename 三段（s0b / j2R / m1e fullpath）完全一致，且处于同一转发链。
**长2（19b9）**：`:1368 s0b ret=19b98c62...jpg` → `:1375 queued`（同 `ctx=19733550/44408694054@chatroom/`）→ `:1440 j2R e=19b98c62...jpg` → `:1442 m1e in .../19/b9/19b98c620134f3e2448be0bf573e47bf.jpg_` → `:1443 c2c fbytes=9554 fmd5pre=0ef205c1`。同样三段一致。

主会话"迟到前已有 x0cb 完成 ⇒ 可能是上次重试"的旁证经亲核**不成立**：
- `:2636 x0cb md5=0ef205c1` 下游 `:2642 f2C2 s0=aupimg_...1795532934429` + `:2631 j2R e=7055ba4d...jpg` ⇒ 属于 **23:08:54.905 那次 7055ba4d 转发**，与 d07f 不同图。
- `:1416 x0cb` 下游 `:1425 f2C2 s0=aupimg_...1795530279009` + j2R `e=1de2aa73...jpg` ⇒ 属于 1de2aa73 转发，与 19b9 不同图。

结论：迟到帧是本次转发的上传帧，**不是**上次转发的重试、也不是另一条消息的帧。【真机实证·高】

### Q1.2-补 独立发现：同一转发确有 host 级重试帧（主会话未提）

19b98c 有 **两次** m1.e：`:1442` 22:24:56.591（10.071s）与 `:1523` 22:25:07.449（20.93s），两次 `field_fullpath`（`:1443`/`:1525`）同为 19b98c basename，第二次 c2c（`:1526`）仍是 `fbytes=9554 fmd5pre=0ef205c1`，且其间**无新 FileSender queued**。对应 `:1506 x0cb` + `:1511 f2C2 s0=aupimg_...1795530286071`（新 client id / 新 fileId）。⇒ 同一次转发的 host 级重传，第二次会新建 task 并重新读到模糊字节。【真机实证·中】

### Q1.3 延迟致因（决定 TTL 是否在生产态复现）

- V2 日志 `R86C FS cand` 计数 **0**，间隔 45~46ms；step1b 计数 **244**，长延迟窗口被 `R86C FS cand` 行从 `:2601` 23:09:05.680 一直填满到 `:2621` 23:09:11.441（5.76s），或从 22:24:46.646 填到 22:24:55.780（9.13s）。
- 该日志来自 `probe/StockCdnProbe.kt:535-568` 的 P-FS 只读探针：`listFiles()` 两级递归 + 逐候选**整文件 MD5** + 每行 `LogX.i`，与 m1.e 同在 `[D]#worker`。V2（无枚举）无长延迟、step1b（有枚举）长延迟完全落在枚举窗口 ⇒ 强相关。【真机实证·中~高】
- **但**当前实现的 `ForwardCdnFieldHook.findTargetHevc`（`:161-180`，`admitTarget` `:183-186`，`md5PrefixOfFile` `:189-200`）在 m1.e before 内做**同款**两级 `listFiles` + 逐候选整文件 MD5（同步、同 `[D]#worker`）。故 probe-off 生产态并非天然免疫：当前转发自己的枚举可阻塞 worker，令随后到站的转发 m1.e 迟到 >2s。【源码实证·高 + 推断·中】

### Q1.4 四种修法逐条评估

**(a) 单纯扩 TTL 30s/60s**：能覆盖已观测最大 10.071s（30s 即可）。副作用可控性：同 talker 每目标 `pendingMap[wxid]=Pending(now)`（`:121`）是覆盖写、不累积；普通发图/未登记转发误命中被 9554B+45522B 双门禁 fail-closed（`:163-164,:183-186`），但会触发无谓全树枚举 → worker 卡顿【推断·中】；TTL 内二次转发在 `markTarget` 被调用时拿到新 ts，不存在"复用旧标记"。**推荐 15s**（>10.071s×1.5），不用 60s（无效残留窗口过长）。**关键：扩 TTL 单独不修复 Q1.2-补 的重试——首帧消费后重试无 pending，最后一次上传仍是 9554B。**

**(b) 改登记时机至更晚点（l1.a() 后 / j2.O 前）**：`j2.O before` 在 `[D]#worker`，与 m1.e 近乎同帧，理论上可把间隔压到 ms。但需**新增 j2.O hook**（违背裁决"不新增第二 hook"），且要能从 r0 现场读出目标 talker —— dex 是否支持未查 ⇒【UNKNOWN 未闭合】。裁决 §R2.1 已否决 l1.a()/j2.O after 作注入点。**不建议无 dex 取证即改。**

**(c) 改为"命中且字节准入通过才消费，否则保留"**：与"一次性防串味"直接冲突（未命中长期残留、被无关上传反复触发昂贵枚举），且**同样不解决重试**。不建议单独采用；若采用须加有界重试次数 + 成功写后才消费。

**(d) 先只读探针观测再定 TTL**：所需两路时间戳（queued、`SCD m1e in`）在现有日志已齐；真正缺的是 **probe-off + hevc-on 的当前构建一轮**。值得（一轮、只读、决定性），但不应作为延后修复的理由——见 Q1.5 转正条件③。

### Q1.5 Q4 裁决（TTL 维度）：**NO-GO**

不是"条件 go"：存在【真机实证·高】的零生效路径（迟到帧确属本次转发 + 2000ms 必超时 + pending 被清 + 不写 task）。分档：归属【真机实证·高】；延迟窗口枚举占用【真机实证·中】；生产 probe-off 态同款枚举致 >2s【推断·中】。按任务判据树（迟到帧确属本次 ⇒ 必超时 ⇒ 零生效）**升级 BLOCKER，不降级**。

转正条件（三条全满足才谈 go）：① TTL 改 ≥15000ms 且改用 `SystemClock.elapsedRealtime()`；② 重试帧有明确处置，保证**最后一次**上传也写 hevc；③ 一轮 probe-off/hevc-on 当前构建只读探针确认登记→消费间隔 < 新 TTL。改完必须再复核。

`[DONE Q1]`

---

## Q2 —— diff 全量独立核验

### Q2.1 hook 点（§3.1）
`ForwardCdnFieldHook.kt:76-112 install()` 仅对 `ok0.m1` 装一个 cb：`:103-104` 精确 `hookMethod(exact, cb)`，`:107` 签名漂移时 `hookAllMethods(m1cls,"e",cb)` 兜底（**同一 cb 对象，不是第二 hook**）。`:94-100 beforeHookedMethod`，`:95 if (!hevcEnabled) return` 为首行闸门；`:101` after 不写。源码全文件无第二 hook 安装。【源码实证·高】✓ 无偏差。
残余【UNKNOWN】：fallback 若命中多个 `e` overload，首个 before 消费 pending 后其余无命中；需 dex 确认 `ok0.m1` 的 `e` 重载数。

### Q2.2 key 与登记时机（§3.2/§3.1）
`:131-133 getObjectField(task,"field_talker")` 作 key；`FileSender.kt:163 markTarget(wxid)`（wxid = **目标会话**，`:190 q1.h(wxid)` 同源），`:164 arm(ctx.sourceChat,...)` 仅只读探针；`:163 < :164` ✓。M1 同性质坑（把 sourceChat 当 key）已绕开。`ForwardCdnFieldHookTest.kt:109-120` 有反向断言锁。off 时 `markTarget` `:119` 首行 return。【源码实证·高】✓

### Q2.3 字节准入（§3.4）
`:163-164` 模糊门禁 `exists + length()==9554L + md5PrefixOfFile()=="0ef205c1"`；`:44-45` 目标 `REF_MD5_PRE="cff4f5a5"`/`TARGET_LEN=45522L`；`:177 if (hits.size==1)`；`:145-147` fullpath 取自 `param.args[0].field_fullpath`；`:165-166` 正则 `/MicroMsg/[0-9a-fA-F]{32}/` 取根；`:169-172` 两级分桶有限枚举 `*.jpg_hevc`；`:183-186 admitTarget`。**无** `j2.R param.result`、无 r0 String 字段、无 xml `.msg.img.$md5`（`:30` 明确禁）、无 `hit`/`UploadHitCacheType` 参与判定。M1 真正绕开。【源码实证·高】✓

### Q2.4 单字段写与只读红线（§3.3/§3.4）
全文件 `grep set(Object|Int|Boolean|Long)Field` 仅 `:149` 一处 = `field_fullpath`。未见 fileId/aesKey/originPath/r0.e/r0.f/q1.j/P3AdmitGate/FLOOR/copyP3Temp/b1.u8/DB 写入；`arm()` `:226-237` 只调只读 `d3.O8()`/`a3`。【源码实证·高】✓
**注意【UNKNOWN】**：`git diff --stat` 显示 `ForceForward.kt`/`ForwardLogic.kt`/`ForwardPicker.kt`/`StockCdnProbe.kt` 等也在工作树改动中；无法从 HEAD 判定是否本 lane 引入，建议 lane 书面说明基线。

### Q2.5 并发/复位（§3.3/§3.6）
`:70 ConcurrentHashMap<String,Pending>`；`:135 ?: return` 未命中不写不删；`:136-141` TTL 超只条件 remove 自己条目；`:143 remove(field_talker,p)` 竞争失败直接 return；`:209-211 clear(wxid)` 精确清；`FileSender.kt:258`（queued=false）与 `:265`（onFailure）两条失败路径都 clear(wxid)。【源码实证·高】✓
残余【UNKNOWN 未闭合】：同 talker 并发多图 key 碰撞（impl note `:80` 已自认）；`markTarget` 覆盖写会让前一张图 pending 被后者顶掉，前一张 m1.e 若迟到消费到的是后者标记。

### Q2.6 旧 hook / 单测删除、接线、探针保留
- `J2R0eOverrideHook.kt` 与 `J2R0eOverrideHookTest.kt` 物理不存在（`ls` 确认）；`git ls-files | grep -i r0e` 无结果；源码仅单测反向断言文字提及（`ForwardCdnFieldHookTest.kt:174,179`）。
- `HookEntry.kt:142` 只 `ForwardCdnFieldHook.install(lpparam)`；`Keys.kt:200` 唯一新 Key `FORWARD_TASK_FULLPATH_HEVC`，无 `FORWARD_R0E_HEVC`；`EntryReceiver.kt:348-353` hevc 分支，`:290 r0=` 回显保留，`r86c`/`stockcdn` 分支保留。
【源码实证·高】✓
- **MINOR【源码实证·高】**：新 Key 在 `Keys.kt:200` 声明但**未注册进 PrefSpec 默认表**（`:520-540` 区间只有 `FORWARD_NATIVE`/`FORWARD_P3_BIGIMG`）。`Kv.kt:29-37` 对未注册 Key 由 `Keys.kt:688 defaultOf→""` 走 false ⇒ 冷启关符合设计，但设置 UI 无此项、`op=hevc sub=on` 运行时翻转不持久化，每次冷启需重播。

### Q2.7 单测反向断言与五项变异（§3.6）
`ForwardCdnFieldHookTest.kt` 13 例：`:75-88 noR0StringMicroMsgLocate` 直接反 M1（禁 `contains("/MicroMsg/")`、须 `Regex(...).find(fullpath)`、禁 `param.result`）；`:90-118` 字节判据；`:120-138` keyed 隔离/无 ThreadLocal；`:140-157` FileSender 接线（mark<arm、禁 sourceChat 当 key、两 clear）；`:172-266` M1~M5 真文本变异，每例 `occur(...)==1` 自变量唯一 + 共用判定函数翻红，无等价变异表象。`codeLines()` `:400+` 去注释，排除 KDoc 假绿。
`R86cQ4bProbeTest.kt` 文件 untracked（无法直接 diff 旧版）；现 `r0Block = slice(probeR86c→sigOf)`（`:380-381`，含 `probeR86c`/`r0Fields`/`idh`），`armBlock = slice(arm→turnProbe)`（`:383-384`，含 `if(probeEnabled) probeR86c(...)` 调用），16 锚点+4 硬锁断言内容仍在块内。【源码实证·高】未发现削弱；旧版差异【UNKNOWN】。
**关键缺口**：五项变异**没有 TTL 维度**，`TTL_MS=2000L` 不被任何测试判红 ⇒ 单测全绿不能证明 Q1 通过。

### Q2.8 独立第三条风险（主会话未提）

1. **【BLOCKER · 真机实证·高 + 推断·中】TTL=2000ms 零生效路径** —— 见 Q1，不重复。
2. **【MAJOR（潜在 BLOCKER）· 真机实证·中 + 推断·中】重试帧 × 一次性消费冲突** —— step1b 19b98c 两次 m1.e（`:1442`/`:1523`，无新 queued），第二次 `:1526 c2c fbytes=9554 fmd5pre=0ef205c1` 为新 task 的模糊字节。当前 `:143` 首帧命中即消费，重试帧无 pending ⇒ 最后一次上传仍模糊。单纯扩 TTL 只会让首帧消费、重试仍裸奔。必须明确"最后一次上传也注入"的处置并保证有界。
3. **【MAJOR · 源码实证·高 + 推断·中】`findTargetHevc` 同步全树 MD5 阻塞** —— `:161-180` 在 `[D]#worker` 的 m1.e before 内两级 listFiles + 逐候选整文件 MD5；真机同款枚举耗时 5.76s/9.13s（Q1.3）。可自致下一转发 TTL 超时 + 宿主线程卡顿。应改为优先按 `field_fullpath` 同目录/有限候选命中后即停。
4. **【MINOR · 推断·中】墙钟 TTL** —— `:121`/`:136` 用 `System.currentTimeMillis()`；NTP 校准或手改系统时间可使间隔为负（假通过）或暴增（假超时）。建议 `SystemClock.elapsedRealtime()`。
5. **【MINOR · 源码实证·高】Key 未注册** —— 见 Q2.6 末条。
6. **【UNKNOWN 未闭合】登记条件可能过窄** —— `FileSender.kt:158-169` 仅当 `ctx.imgLocalId>0 && ctx.msgSvrId>0` 才 markTarget；唯一 send 调用点 `ForceForward.kt:2741`。其它来源（ctx 缺失/值为 0）转发路径零生效，未见穷举。
7. **【UNKNOWN 未闭合】fallback 多 overload** —— 见 Q2.1 末条。

`[DONE Q2]`

---

## 三件套交付

### ① Q1+Q2 一句话结论 + 置信度
**Q1：TTL=2000ms 是 BLOCKER** —— 真机 6345ms/10071ms 迟到 m1.e 经逐行亲核确属本次转发的上传帧，必超时清零生效；当前实现自身还做同款全树 MD5 枚举可复现该延迟。**Q2：§3.1~§3.7 静态规格基本满足（hook 点/key/字节准入/单字段写/并发复位/旧 hook 删除/单测反 M1 均【源码实证·高】通过），但发现"重试帧 × 一次性消费"MAJOR 及墙钟 TTL、Key 未注册等风险。**
置信度：间隔数值与迟到帧归属【真机实证·高】；延迟窗口枚举占用【真机实证·中】；生产 probe-off 态复现 >2s【推断·中】；静态规格核对【源码实证·高】；同 talker 并发/重试最终字节态【UNKNOWN 未闭合】。

### ② 是否可装机：**NO-GO**
BLOCKER 清单：
- **B1** TTL=2000ms 在真机 6.3s/10.1s 迟到帧场景必超时 → pending 被清 → 零生效（Q1.5）。
- **B2**（潜在 BLOCKER，装机前必须处置）19b98c 实证同转发两次上传帧，第二次仍 9554B；一次性消费令最后一次上传无注入 → 肉眼仍可能模糊（Q2.8-2）。**扩 TTL 单独不能解 B2。**

MAJOR（不单独阻挡但需一并处理）：M1 `findTargetHevc` 同步全树 MD5 阻塞 worker（Q2.8-3）；M2 同 talker 并发多图【UNKNOWN 未闭合】（Q2.5）。
MINOR：墙钟 TTL、新 Key 未注册 PrefSpec（Q2.6/Q2.8-4/5）。
**只要 B1 存在即 NO-GO；B2 未处置即使修 TTL 也不得宣称"肉眼清晰"通过。**

### ③ 最小下一步
1. **实现 lane 改**：(a) `TTL_MS 2000 → 15000L` 且 `System.currentTimeMillis()` → `SystemClock.elapsedRealtime()`；(b) 重试场景明确处置，保证**最后一次**上传也写 hevc（消费时机后置到写入成功之后 + 有界重试，或按 `(talker, fullpath basename)` 重复命中重登记）；(c) `findTargetHevc` 由全树枚举改为命中同目录/有限候选即停，缩短 worker 占用。
2. **验证**：实现 lane 跑三闸（编译/定向/全量/构建）+ 单测补 TTL 与重试两项反向变异；再跑**一轮 probe-off + hevc-on 真机只读间隔观测**（只记录 queued→m1e in 与是否超时，不改判据）确认间隔 < 新 TTL。
3. **改完必须再交我复核**——本报告是证伪结论，非放行章。本次复核未改任何源码、未跑 gradle/单测、未碰设备。

`[DONE ALL]`
