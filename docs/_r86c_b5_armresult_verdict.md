# R86c B5 armfix 真机四发 · 独立 fresh-eyes 复核裁定

> 复核人：`r86c-b5-armresult`（独立 fresh-eyes Pro 复核，非续任何实现/复核 lane）
> 工作根 `C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`。
> 性质 = 四发真机结果裁定（事实核验 + 机制证据强度 + talker 升格 + go/no-go）⇒ **Pro 类**。
> 档位：【源码实证·高】/【真机实证·高|中】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】；每条带 文件:行号 锚点；【推断】不写成【已证】。
> 硬纪律：**只读复核**，唯一允许写 = 本文档；禁改源码/单测/任何其它文档，禁跑 gradle/`_junit.sh`，禁碰 adb/装机/设备开关，禁 git。
> 职责 = 证伪/找问题，**不为结论放宽判据、不虚构 BLOCKER、不盲从主会话**。
> 不可越红线：① 判据口径 = C2 第二次真实回包 `exist` false→true + `md5Len` 0→>0 + `logC2Shape` cls=Ltj/f 非空壳（review2 `docs/_r86c_b5_review2.md:104` / armverify `docs/_r86c_b5_armverify.md:187`）② 跨轮对照不得写成同会话对照 ③ **C2 命中 ≠ 清晰** ④ 探针肉眼不变 ⑤ 清晰须 B6 全量 + 用户肉眼 A→B→A 终审。
> 落盘策略：骨架 → 每问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。**先写文件再回报**。

状态进度：
- [x] 骨架 + 证据锚点
- [x] Q1 逐发核 6 条事实 → `[DONE Q1]`
- [x] Q2 B5 机制成立/证据强度裁决 → `[DONE Q2]`
- [x] Q3 talker 升格裁决 → `[DONE Q3]`
- [x] Q4 结论边界 / go-no-go / 三件套 → `[DONE Q4]`
- [x] 三件套 + 真 `[DONE ALL]`

---

## 证据锚点总表（先落盘，均本次亲核）

| 编号 | 事实 | 档位 | 锚点（本次亲核） |
|---|---|---|---|
| F1 | on1 arm 全链：`arm try a3 uLen=883 map=12`（keys 含 `.msg.img.$cdnbigimgurl`）→ `arm xml via=a3 idVia=map fidLen=194 fidPre=305f0201 md5Pre=ca47f83e uLen=883` → `arm sent … via=B8 fields=15` | 【真机实证·高】 | `_r86c_b5_armfix_on1.txt:675/:677/:686` |
| F2 | on2 arm 全链与 on1 逐字一致（uLen=883 / map=12 / via=a3 / fields=15） | 【真机实证·高】 | `_r86c_b5_armfix_on2.txt:570/:572/:581` |
| F3 | 四份日志**全无** `skip invalid talker/svrId`（首轮零生效唯一行彻底消失） | 【真机实证·高】 | grep 四文件 0 命中；首轮旧行 `_r86c_b5_first_try_raw.txt:283`（armverify `docs/_r86c_b5_armverify.md:27` V3） |
| F4 | on1 自建 C2 三回调 = 2 空壳(cls=null/exist=NA) + 1 真实(cls=tj.f 非空壳) `exist=true md5Pre=c4b700b7 md5Len=32` | 【真机实证·高】 | on1 `:688/:689`（ColdPool#15 空壳）、`:705/:706`（ColdPool#16 空壳）、`:708/:709`（HotPool#0 真实） |
| F5 | on2 自建 C2 同构三回调，真实回包 cls=tj.f `exist=true md5Pre=c4b700b7 md5Len=32` | 【真机实证·高】 | on2 `:583/:584`（ColdPool#14 空壳）、`:594/:595`（HotPool#2 空壳）、`:598/:599`（ColdPool#1 真实） |
| F6 | on1/on2 自建 taskFields 含 `field_fullpath=b6x_…jpg_hevc` 与 `field_talker=wxid_xlrbelf6hv0w22`（目标 wxid） | 【真机实证·高】 | on1 `:679`、on2 `:574` |
| F7 | off1 全链唯一 B6X 行 = `B6X STATUS enabled=false installed=true classes=true`，无 arm try/arm sent/自建 C2 行 | 【真机实证·高】 | off1 `:2`；setup `_r86c_b5_armfix_off_setup.txt:3`；grep B6X C2 shape = 0 |
| F8 | stock B6X 全链仅 STATUS（原生转发不触发自建 arm）；真实链 c2c `fbytes=45522 fmd5pre=cff4f5a5` → x0cb `md5=cff4f5a5 hit=1 exist=true` → f2C2 `field_exist_whencheck=true field_UploadHitCacheType=1` | 【真机实证·高】 | stock `:16`、`:199`、`:221`、`:224` |
| F9 | on2 真实链 P3 `origin-by-md5 miss reason=no-exist` → `resend-file p3=false` → 出口 c2c `fbytes=9554 fmd5pre=0ef205c1`、x0cb `md5=0ef205c1 hit=3`；自建 C2 命中与真实链清晰同发不成立 | 【真机实证·高】 | on2 `:566`、`:590`、`:604`、`:619` |
| F10 | off1 真实链 P3 `origin-by-md5 path=929c32b22214fd78eaac.jpg size=335644` → `p3=true` → c2c `fbytes=45522 fmd5pre=cff4f5a5` → x0cb `md5=cff4f5a5 hit=3` | 【真机实证·高】 | off1 `:160`、`:168`、`:175`、`:189` |
| F11 | on1 真实链同样 P3 miss→`p3=false`→出口 `md5=0ef205c1`（6 条事实未列，台账已记） | 【真机实证·高】 | on1 `:670`、`:695`、`:703`、`:727` |
| F12 | 历史 false 基线（无三字段，fields=12）：自建 C2 第 2 回调 `exist=false md5Pre= md5Len=0`，第 1 回调 `exist=NA` 空壳 | 【真机实证·高（历史轮次）】 | `docs/_r86b_b6step_state_final_raw.txt:212/:213`（另 `:209/:210` arm xml via=a3 idVia=regex:mid, fields=12） |
| F13 | 用户肉眼（stock 清晰 / off1 清晰 / on2 模糊 / on1 未报）——**不在日志内**，来自用户现场报告 | 【用户报告·非日志可核】 | 台账 `docs/派发审计台账.md:5952/:5953`（on1「肉眼未报」见 `:5953`）；任务书 6 条事实 |
| F14 | 本会话未观测 `msg.C0()` 原值，未测 `filehelper:`（不去冒号）是否也能命中 a3 | 【UNKNOWN】 | 四日志 grep `C0` = 0；`docs/_r86c_b5_armroot_verdict.md:93` 残余②自记【推断·中】 |

---

## Q1 —— 逐发核实主会话 6 条事实

### Q1.0 总判

主会话 6 条事实的**日志可核部分全部属实，无实质出入**。三处需补正/强调（非推翻）：① 判据字面「第二次真实回包」与本轮实际「3 回调 = 2 空壳 + 1 真实」的序号差异；② 用户肉眼 4 项均**不在日志内**，属用户报告，本复核无法亲核；③ on1 真实链同样是 P3 miss→`p3=false`→出口 `0ef205c1`（6 事实未列，台账已记），与 on2 同向，进一步佐证两链独立。

### Q1.1 事实 1（on1/on2 arm 全链 + `skip invalid` 消失）—— 属实【真机实证·高】

- on1 `:675` `B6X arm try a3 uLen=883 uPre=<msg><img … cdnbigimgurl="305f0201…"…`，同串 `map=12 mapErr=- keys=[… .msg.img.$cdnbigimgurl …]`（F1）；`:677` `arm xml via=a3 idVia=map fidLen=194 fidPre=305f0201 aesLen=32 md5Len=32 md5Pre=ca47f83e uLen=883`；`:686` `B6X arm sent … via=B8 fields=15`。
- on2 `:570`/`:572`/`:581` 与上逐字一致（F2）：可重复性坐实，非偶发。
- 四份日志 grep `skip invalid` = 0 命中（F3）；首轮零生效唯一行 `_r86c_b5_first_try_raw.txt:283`（armverify V3 `docs/_r86c_b5_armverify.md:27`）确已消失。
- 补注：`arm attrs` 取到 `cdnbigimgurl:194/cdnmidimgurl:194/cdnthumburl:194/md5:32`（on1 `:676`、on2 `:571`），非 `no_xml`/`u=blank`/`r0=FAIL` ⇒ armverify Q4.3 第 4 条「a3 失败或 no_xml 记 UNKNOWN」的逃生口**未触发**。

### Q1.2 事实 2（自建 C2 三回调 + taskFields 双 talker）—— 属实，附一处口径补正【真机实证·高】

- on1 三回调：`ColdPool#15` cls=null/strFields=NA（`:688`）+ exist=NA/md5Pre=NA/md5Len=-1（`:689`）；`ColdPool#16` 同（`:705`/`:706`）；`HotPool#0` `cls=tj.f strFields=a:30,…,field_fileId:194,field_filemd5:32,field_serverIP:22,field_toUser:19,…` 非空壳（`:708`）+ `exist=true md5Pre=c4b700b7 md5Len=32`（`:709`）（F4）。
- on2 三回调同构：`ColdPool#14`（`:583/:584`）、`HotPool#2`（`:594/:595`）、`ColdPool#1` 真实 cls=tj.f（`:598`）+ `exist=true md5Pre=c4b700b7 md5Len=32`（`:599`）（F5）。
- taskFields：on1 `:679`、on2 `:574` 均含 `field_fullpath=/data/user/0/com.tencent.mm/cache/b6x_…jpg_hevc` 与 `field_talker=wxid_xlrbelf6hv0w22`（目标 wxid，双 talker 落码对）（F6）。
- **口径补正（非出入）**：主会话「三次回调 = 2 空壳 + 1 真实」属实；但判据原文是「Proxy C2 **第二次真实回包**（第一次 `exist=NA` 空壳不参与）」（`docs/_r86c_b5_review2.md:104`、`docs/_r86c_b5_armverify.md:187`），其回调结构原型来自历史 b6step 的 **1 空壳 + 1 真实**（`docs/_r86b_b6step_state_final_raw.txt:212/:213`，F12）。本轮 on1/on2 真实回包是**第 3 个回调**，机械套「第二次」会在字面上错位。正确表述应为「**真实（非空壳）回包** cls=tj.f、exist=true、md5Len=32」——正向判据内容（exist 翻 true + md5Len>0 + 非空壳）成立，但「第二次」是序号措辞而非本轮事实。此项记 Q1 补正，不升级 BLOCKER。
- 另附只记录不裁决项：c2c `fmd5pre=c4b700b7`（on1 `:683`/on2 `:578`，`fbytes=3`）与 x0cb `md5=c4b700b7 hit=0`（on1 `:707`/on2 `:596`）按判据 `docs/_r86c_b5_review2.md:105` **只记录、不裁决身份**，本复核不据此推「原图身份命中」。

### Q1.3 事实 3（off1 自变量唯一）—— 属实【真机实证·高】

- off1 `:2` 唯一 B6X 行 `B6X STATUS enabled=false installed=true classes=true`；setup `_r86c_b5_armfix_off_setup.txt:3` 同。grep 四文件 `B6X C2 shape`：on1 3 行、on2 3 行、off1 **0**、stock **0**（F7）。
- ⇒ off 态 `enabled=false` 早退、自建 task 未下发、自建 C2 无回包，自变量（三字段干预）在 off 态确实不存在。此点本身属实；但它同时**造成 Q2 的同会话 false 数据缺口**，见 Q2.2。

### Q1.4 事实 4（stock 原生链 cff4 命中 + 肉眼清晰）—— 日志部分属实，肉眼属用户报告【真机实证·高 / 用户报告·非日志可核】

- stock `:16` `B6X STATUS enabled=true installed=true classes=true`，全文件无 arm try/arm sent/自建 C2（F8）——原生转发不触发自建 arm，符合预期。
- 真实链：`:199` c2c `fbytes=45522 fmd5pre=cff4f5a5 fidLen=194 md5Len=0` → `:221` x0cb `md5=cff4f5a5 md5Len=32 hit=1 exist=true` → `:224` f2C2 `field_exist_whencheck=true field_UploadHitCacheType=1`（F8）。与 B3 历史 `:5870` 同向，服务端此刻确有源图。
- `用户肉眼=清晰` **不在 stock 日志内**，来自用户现场报告（台账 `docs/派发审计台账.md:5953`，F13）。本复核如实标注为【用户报告·非日志可核】，不冒充日志实证。

### Q1.5 事实 5（on2 命中但肉眼模糊，真实链出口 0ef205c1）—— 日志部分属实，肉眼属用户报告【真机实证·高 / 用户报告·非日志可核】

- on2 `:566` `FORWARD P3 origin-by-md5 miss reason=no-exist` → `:590` `resend-file … p3=false ctx=19757536/filehelper:/ target=wxid_xlrbelf6hv0w22` → `:604` 出口 c2c `fbytes=9554 fmd5pre=0ef205c1 fidLen=0 md5Len=0` → `:619` x0cb `md5=0ef205c1 md5Len=32 hit=3 exist=true`（F9）。
- 同发自建 C2 真实回包命中（`:598/:599` `exist=true md5Pre=c4b700b7 md5Len=32`）**与**真实链出口 `0ef205c1` **同时成立** —— 两条链彼此独立，这是「C2 命中 ≠ 清晰」的真机实证锚点。
- `用户肉眼=模糊` 不在日志内，来自用户报告（台账 `docs/派发审计台账.md:5952`，F13）。日志能证的是「出口 md5 落到 0ef205c1 而非 cff4f5a5」，与「模糊」的用户报告方向一致，但「模糊」本身是用户观测、非日志字段。

### Q1.6 事实 6（off1 肉眼清晰，P3 瞬态字节随机性）—— 日志链属实，肉眼属用户报告，「瞬态随机性」为【推断·中-高】

- off1 `:160` `FORWARD P3 origin-by-md5 path=929c32b22214fd78eaac.jpg size=335644`（命中 path，非 miss）→ `:168` `resend-file … p3=true` → `:175` c2c `fbytes=45522 fmd5pre=cff4f5a5` → `:189` x0cb `md5=cff4f5a5 hit=3`（F10）。
- 对照 on1 `:670` miss/no-exist、on2 `:566` miss/no-exist，同图同 `srcMsgId=19757536`/`localId=885441`（on1 `:657`、on2 `:549` `prefix=filehelper:`）但 P3 `origin-by-md5` 结果在 off 那发命中、on 两发 miss ⇒ **P3 源 jpg 瞬态/状态依赖**有日志支撑，档位【推断·中-高】（同图不同结果【真机实证·高】，归因「瞬态源 jpg 在场」为【推断·中-高】）。
- 注意 hit 数值差异：off1 x0cb `hit=3`（`:189`）、stock `hit=1`（`:221`）。按判据 hit **只记录不裁决**（`docs/_r86c_b5_review2.md:105`），本复核不据此排序或裁决身份。
- `用户肉眼=清晰` 不在日志内，来自用户报告（台账 `docs/派发审计台账.md:5952/:5953`，F13）。

### Q1.7 Q1 补正清单（3 条，均非推翻、不升级 BLOCKER）

1. **判据措辞**：「第二次真实回包」→ 本轮应写「真实（非空壳）回包」，实际为第 3 回调（Q1.2）。
2. **肉眼证据来源**：4 项肉眼全部【用户报告·非日志可核】，不可写成「日志实证肉眼」；on1 肉眼未报（台账 `:5953`）。
3. **on1 真实链同向**：on1 也 `p3=false`→`0ef205c1`（F11，`:670/:695/:703/:727`），6 事实未列但佐证「两链独立」不只在 on2 单发。

`[DONE Q1]`

---

## Q2 —— B5 机制是否成立 / 证据强度裁决

### Q2.1 正向证据（已核，强且可重复）

- **自建 C2 翻转内容命中判据正向面**：on1 `:709`/on2 `:599` 真实（非空壳 cls=tj.f）回包 `exist=true md5Pre=c4b700b7 md5Len=32`，cls 与 String 字段非空壳（`:708`/`:598` `field_fileId:194,field_filemd5:32,field_toUser:19` 等）——命中 review2 Q4.2「exist 翻 true + md5Len 0→>0 + logC2Shape 非 Ltj/f 空壳」的**结果形态**（F4/F5）。
- **arm 自检全绿**：on1/on2 `arm try a3 uLen=883 map=12 keys 含 $cdnbigimgurl`（`:675/:570`）→ `arm xml via=a3 idVia=map`（`:677/:572`）→ `arm sent via=B8 fields=15`（`:686/:581`）；无 `skip invalid`/`no_xml`/`r0=FAIL`/`u=blank`（F1/F2/F3）。三字段确实随自建 task 下发：`field_fullpath`/`field_talker` 在 taskFields（on1 `:679`/on2 `:574`，F6）；c2c 端 `enableHit=true fakeSigEmpty=true fbytes=3`（on1 `:683`/on2 `:578`）显示 fake 大文件签名占位在场。
- **可重复性**：两次独立真机（21:34 on1 / 21:44 on2，双冷启）逐字一致，排除单次偶发（F1/F2/F4/F5）。
- **自变量唯一**：off1 全静默（F7），off/on 差异只由 B6X 开关驱动，`exist` 翻转与三字段在 on 态同现。

### Q2.2 反向证据 / 证据缺口（决定性，必须写进边界）

1. **false 基线是跨轮对照，不是同会话对照**【真机实证·高（历史轮）+ 口径限制】：唯一 `exist=false/md5Len=0` 锚点来自历史 b6step `docs/_r86b_b6step_state_final_raw.txt:213`（`exist=false md5Pre= md5Len=0`，第 1 回调 `:212` 空壳 `exist=NA`），该轮 fields=12（无三字段）、`arm xml via=a3 idVia=regex:mid`（`:209/:210`）、目标 filehelper、不同图/不同 msg（`:216 ctx=19733550/…`）。与本次 on 态（fields=15、idVia=map、同图 wxid 目标）**非同一会话、非同一控制变量**。判据明令「跨轮对照不得写成同会话对照」（任务书红线 + `docs/_r86c_b5_armverify.md:186` 要求同会话 off→on→off）。本复核如实登记：**这里只有「跨轮 false 基线 + 本轮 on 态 true」的横向拼接，没有同会话内的 false→true 纵向翻转**。
2. **同会话 off 态自建 C2 结构性缺失**：off1 `enabled=false` 早退、自建 task 未下发、自建 C2 0 回调（F7，off1 `:2`）。因此**同会话内拿不到「off 态自建 C2 exist=false」数据点**。判据 Q4.2 设计的证伪闸「若 off 态也 `exist=true` ⇒ 判 UNKNOWN 不写通过」（`docs/_r86c_b5_review2.md:103`）在本轮**无法被触发验证**：off 态不是「exist=false」也不是「exist=true」，而是「无自建 C2 可判读」。判据要求的 off→on→off 三次回包同会话闭环，实际退化为「off 无数据 / on 两次 true / stock 对照」，**A/B 的 false 侧缺位**。
3. **回调序号与判据原型不符**：判据「第二次真实回包（第一次空壳不参与）」原型 = 1 空壳 + 1 真实（F12 `:212/:213`）；本轮 on1/on2 = 2 空壳 + 1 真实，真实回包是第 3 回调（Q1.2）。结果形态命中，但「第二次」字面不适用。
4. **真实链 exist=true 的普遍性削弱排他性（仅作旁证，不改判据）**：off1 真实链 f2C2 `field_exist_whencheck=true`（`:197`）、stock 真实链 `field_exist_whencheck=true field_UploadHitCacheType=1`（`:224`）均在**无自建 C2**情形下出现 ⇒ 「exist=true」并非自建探针独有的因果指纹。按判据这些是真实链字段、**只记录不裁决**，不构成判据污染；但它提示自建 C2 的 `exist=true` 不宜被单独抬升为「三字段因果已证」。
5. **C2 命中与清晰度解耦（决定性边界）**：on2 自建 C2 命中同时肉眼模糊、真实链出口 `0ef205c1`（F9）；on1 同向（F11）。机制信号再强也**不驱动最终清晰度**。

### Q2.3 裁决（明确档位与「能否写机制通过」）

- **证据强度 = 【推断·高】，不是【已证】**。
  - 支撑升到「高」：on1/on2 两次独立真机、逐字可重复（F1/F2/F4/F5）；arm 自检全绿、三字段随 task 下发（F1/F6）；自建 C2 真实回包结果形态完整命中判据正向面（exist true + md5Len 32 + cls=tj.f 非空壳）；off 态自变量唯一（F7）。
  - 不能升到「已证」：唯一 false 锚为**跨轮** b6step（F12）；同会话 off 态自建 C2 **结构性缺失**（Q2.2-2），判据证伪闸未触发；回调序号与判据原型不符（Q2.2-3）；无「关三字段但保留自建 task」的同会话对照来隔离因果。
- **能否写「机制通过」= 否（带限定）**。按 review2 Q4.2 / armverify Q4.3 口径，「通过」隐含同会话 off→on→off 的 false→true 翻转闭环 + 证伪闸生效；本轮 off 侧无自建 C2 数据，闭环缺 false 角，**不构成判据意义上的完整 A/B**。
  - ✅ 允许表述：「**机制信号成立（推断·高）**——三字段能启动 checkExist 秒传探测的信号在 on1/on2 两次真机重复出现，自建 C2 真实回包 `exist=true/md5Len=32/cls=tj.f`；但 false 基线为跨轮 b6step（`docs/_r86b_b6step_state_final_raw.txt:213`），同会话 off 态无自建 C2 可判读（`_r86c_b5_armfix_off1.txt:2`），**未达『机制通过』**。」
  - ❌ 禁止表述：「机制通过 / 判据已满足 / B5 通过 / 三字段因果已证 / 清晰/问题解决」。
- **若 team 坚持写「通过」的前置**：必须在同一会话补一个「自建 task 不下发三字段（或 off 态可判读 C2）」的 false 对照，否则只能维持「机制信号·推断·高」。此结论与 armverify 装机前口径 `docs/_r86c_b5_armverify.md:190`（C2 变化只证三字段能启动探测、≠清晰）一致，不越级。

`[DONE Q2]`

---

## Q3 —— talker 升格裁决

### Q3.1 a3 真机命中的直接证据（拆成两个子命题，不可混为一谈）

主会话主张「talker【推断·高】→【真机实证·高】」，依据是 a3 真机命中 uLen=883。本复核把该升格拆为两个**不同的**命题分别裁：

- **命题 A（功能命中）**：新 talker `srcTalkerPrefix.trimEnd(':')`（本会话 `filehelper:`，on1 `:657` / on2 `:549`）能否作为 `a3(talker,msgSvrId)` 查询键在真机取到源 xml。
  - 实证：on1 `:675` `B6X arm try a3 uLen=883 uPre=<msg><img aeskey="60af…" cdnmidimgurl="305f0201…" cdnbigimgurl="305f0201…"…`、`:676` attrs 取到 `cdnbigimgurl:194/cdnmidimgurl:194/cdnthumburl:194/md5:32`、`:677` `arm xml via=a3 idVia=map fidLen=194 fidPre=305f0201 md5Pre=ca47f83e uLen=883`、`:686` `arm sent … via=B8 fields=15`；on2 `:570/:571/:572/:581` 逐字一致（F1/F2）。
  - `via=a3` 且 `idVia=map` 表明 id 由 map 命中、u 非空；无 `r0=FAIL`/`u=blank`/`no_xml`/`skip invalid`。⇒ **命题 A = 【真机实证·高】**：a3 用新 talker 真机命中并取到源 xml，这**确实**闭合了 armverify/armroot 反复登记的残余「`a3(talker,msgSvrId)` 本轮真机未直接命中」（`docs/_r86c_b5_armverify.md:57` `:180`、`docs/_r86c_b5_armroot_verdict.md:93` 残余①）。

- **命题 B（形态等价/语义正确）**：`trimEnd(':')` 后是否等价 stock `msg.C0()` 的原始形态、是否证明「源 talker 语义对」。
  - a3 命中的 u 内容含源图 `cdnbigimgurl` + `md5` + `aeskey`（on1 `:675/:676`、on2 `:570/:571`），可证明**取到的是源消息 xml**（功能语义有效）；但本会话**未观测 `msg.C0()` 原值**，也**未测试 `filehelper:`（不去冒号）是否同样能命中 a3**（F14，四日志 grep `C0` = 0）。
  - armroot 自记：`trimEnd(':')` 等价 stock `C0()` 属【推断·高】，残余「若 `h9.C0()` 与 `ref.talker` 形态不同，需以实际 `C0()` 为准」属【推断·中】（`docs/_r86c_b5_armroot_verdict.md:92/:93`）。a3 命中**没有直接观测 C0()**，因此**不能把「能取到 xml」自动升级为「与 C0() 形态等价已证」**。
  - ⇒ **命题 B = 【推断·高 → 真机实证·中】**：a3 命中提供强功能旁证（取到的 xml 字段指向源图记录），但未直接证 C0() 等价。

### Q3.2 裁决：主会话升格【真机实证·高】部分越级；只能整体升到【真机实证·中】

- a3 真机命中 uLen=883 **证明的是命题 A**（能取到源 xml、不再 skip），**不等价证明命题 B**（`trimEnd(':')` 源 talker 语义对/等价 C0()）。把 A 的真机实证·高直接套到 B，等于把「功能有效」写成「形态等价已证」，违反「【推断】不写成【已证】」。
- **裁决**：
  - 命题 A「a3 用新 talker 真机命中取到源 xml」= **【真机实证·高】**（on1 `:675-686` / on2 `:570-581`）。
  - 命题 B「`trimEnd(':')` 源 talker 语义对 / 等价 stock C0()」= **【真机实证·中】**（功能旁证为高、形态等价未直接证；armroot 残余②【推断·中】仍在 `docs/_r86c_b5_armroot_verdict.md:93`）。
  - 主会话「talker【推断·高 → 真机实证·高】」应改为 **「talker【推断·高 → 真机实证·中】」**（即任务书给出的第二种选项：「只证能取到 xml、未直接证 C0() 等价」）。
- **为何不是维持【推断·高】**：a3 确实真机命中（非仅旁证 Q2），功能面已从推断升为实证·高；故整体给「真机实证·中」而非原地不动，既反映实证增量又不越过 C0() 等价的直接证据缺口。
- **未越红线**：本复核不写「C0() 等价已证」、不写「talker 全链真机实证·高」；`trimEnd(':')` 的必要性/唯一性（不去冒号是否也行）仍 UNKNOWN（F14）。

`[DONE Q3]`

---

## Q4 —— 结论边界 / go-no-go / 三件套

### Q4.1 「零驱动」事实如何写才不误导

决定性锚点：**on2 自建 C2 命中（`:598/:599` exist=true/md5Len=32）与用户肉眼模糊（用户报告，台账 `docs/派发审计台账.md:5952`）同发成立**；真实链出口 `md5=0ef205c1`（on2 `:619`，`fbytes=9554` on2 `:604`）。on1 同向（`:670/:695/:703/:727`，F11）。反向 off1：无自建 C2（`:2`）而真实链 `md5=cff4f5a5`（`:189`）、用户肉眼清晰（用户报告）。stock：无自建 C2 而 `md5=cff4f5a5 hit=1`（`:221`）、用户肉眼清晰（用户报告）。

- ✅ 正确写法（三句缺一不可）：
  1. 「B5 三字段启动 checkExist 秒传探测的**机制信号成立（推断·高）**——on1/on2 自建 C2 真实回包 `exist=true/md5Len=32/cls=tj.f`（`:709/:599`），但 false 基线跨轮、同会话 off 无自建 C2，未写『机制通过』」（Q2.3）。
  2. 「**C2 命中 ≠ 清晰**：on2 自建 C2 命中与肉眼模糊同时成立，两链独立；决定清晰度的是**真实链 P3 瞬态源 jpg 是否在场【推断·中-高】**（on1/on2 `origin-by-md5 miss/no-exist` → `0ef205c1`；off1/stock `cff4f5a5`），自建 C2 对最终清晰度**零驱动**」。
  3. 「本探针**不发消息/不写宿主字段/肉眼不变**，清晰须后续 **B6 全量功能 + 用户肉眼 A→B→A 终审**（review2 Q4.2-4 `docs/_r86c_b5_review2.md:106`、armverify Q4.3 第 5 条 `docs/_r86c_b5_armverify.md:190`、fix note §6 `docs/_r86c_b5_armfix_note.md:157`）」。
- ❌ 禁止写法：「B5 已解决清晰度」「自建 C2 命中 = 原图命中/问题解决」「机制通过⇒可宣布成功」「肉眼清晰已被日志实证」（肉眼是用户报告 F13）、「跨轮 false 基线证明同会话翻转」。
- **诚实缺口必须随结论一起披露**：on1 肉眼未报（台账 `:5953`），「自建 C2 命中但肉眼模糊」的直接对照只有 on2 一发；off1/stock 清晰是用户报告非日志字段（F13）。不得写成「多轮肉眼 A/B 已实证」。

### Q4.2 go / no-go：是否进 B6 全量功能实现

**裁决 = NO-GO（暂不进 B6 全量功能实现）；先收尾归档 B5 + 补齐判据缺口，再重新评估。**【裁决·推断·中-高】

- **理由 1（因果靶点被真机反例证伪）**：B6 全量功能的目标假设是「自建 checkExist 若能命中秒传，则能提升模块转发清晰度」。但 on2 真机给出反例——自建 C2 已命中（`exist=true/md5Len=32`）而肉眼模糊、真实链出口 `0ef205c1`（F9）；on1 同向（F11）。**自建 C2 命中不是清晰度的充分条件**，继续投入 B6 全量缺乏因果支撑。这不是「机制没跑通」，而是「跑通了也不驱动目标」——比机制未证更不利 GO。
- **理由 2（机制准入未达）**：Q2.3 裁定只到「机制信号·推断·高」，未达「机制通过」；跨轮 false 基线 + 同会话 off 无自建 C2（Q2.2-1/2）。B5 作为干预前探针，其准入门槛未完整满足，不宜据此直接放大到 B6 全量实现。
- **理由 3（硬前提缺失）**：用户肉眼 A→B→A 终审（review2 Q4.2-4）未做；on1 肉眼未报；同会话「关三字段但保留自建 task」的 false 对照缺失。
- **为何不是虚构 NO-GO**：正向证据真实且可重复（F1/F2/F4/F5），B5 作为**只读探针实验本身**完成了它的取证目的——证伪了「C2 命中⇒清晰」、定位了真实链 P3 瞬态源 jpg 这个决定量。NO-GO 针对的是「进 B6 全量功能实现」这一下一步，不是否定 B5 的取证价值，也不虚构新 BLOCKER。
- **条件性再评估（非本轮 GO）**：若团队仍要推进 B6，前置须同时满足：① 同会话补 false 对照或明确放弃「机制通过」措辞、只保留「机制信号·推断·高」；② 完成用户肉眼 A→B→A；③ **B6 设计靶点改为真实链 P3 瞬态源 jpg 在场/字节来源**，而非自建 checkExist 探测（否则重蹈 on2 覆辙）。

### Q4.3 三件套

**① 四问一句话结论 + 档位**

- **Q1**：主会话 6 条事实日志可核部分**全部属实、无实质出入**；3 处补正——判据「第二次真实回包」应写「真实（非空壳）回包」（本轮为第 3 回调）；4 项用户肉眼均【用户报告·非日志可核】（on1 未报）；on1 真实链同 `p3=false→0ef205c1`（6 事实未列）（F1~F14，【真机实证·高】+【用户报告】）。
- **Q2**：B5 机制 = **【推断·高】·「机制信号成立」，不写「机制通过」**——on1/on2 自建 C2 真实回包 `exist=true/md5Len=32/cls=tj.f` 完整命中判据正向面且可重复（`:709/:599`、F1/F2/F4/F5），但唯一 false 基线为**跨轮** b6step（`docs/_r86b_b6step_state_final_raw.txt:213`）、同会话 off 态自建 C2 结构性缺失（`_r86c_b5_armfix_off1.txt:2`），判据证伪闸未触发（Q2.2/Q2.3）。
- **Q3**：talker **整体只能升到【真机实证·中】**——命题 A「a3 用新 talker 真机命中取到源 xml」=【真机实证·高】（on1 `:675-686`/on2 `:570-581`），但命题 B「`trimEnd(':')` 等价 stock C0() / 源 talker 语义对」未直接观测 C0()、未测不去冒号情形（F14），属【推断·高→真机实证·中】；主会话【真机实证·高】部分越级（Q3.2）。
- **Q4**：**C2 命中 ≠ 清晰**，on2 命中而肉眼模糊、真实链 `0ef205c1`（F9）为真机实证；清晰度由真实链 P3 瞬态源 jpg 在场决定【推断·中-高】，自建 C2 零驱动；探针肉眼不变；清晰须 B6 全量 + 用户 A→B→A 终审。**go/no-go = NO-GO 进 B6 全量，先收尾归档 + 补缺口**（Q4.1/Q4.2）。

**② go/no-go + BLOCKER 清单**

- **进 B6 全量功能实现 = NO-GO**；B5 取证目的已达成，按「机制信号·推断·高 / C2 命中≠清晰 / talker 真机实证·中」收尾归档。
- **BLOCKER 清单 = 空**（本复核职责是证伪/找问题；四发日志未发现新的零生效路径、判据污染或虚构问题；NO-GO 是「下一步投入」的裁决，不是新增 BLOCKER）。
- 必须随归档披露的非 BLOCKER 缺口：跨轮 false 基线（Q2.2-1）、同会话 off 自建 C2 缺失（Q2.2-2）、回调序号措辞（Q1.2）、肉眼为【用户报告】（F13）、on1 肉眼未报（台账 `:5953`）、C0() 等价 UNKNOWN（F14）。

**③ 最小下一步**

1. **team-lead（文档收口，纯文档不改码/不改锁/不装机）**：按 Q4.1 三句口径改写 B5 结论——「机制信号·推断·高（非机制通过）/ C2 命中≠清晰·两链独立·零驱动 / 清晰须 B6 全量+A→B→A 终审」；把「talker 升格」改为【真机实证·中】；标注 4 项肉眼为用户报告、on1 未报。锚点用本文档 F1~F14。
2. **暂停 B6 全量功能实现立项**：在「用户肉眼 A→B→A 终审」与「同会话 false 对照（或明确放弃机制通过措辞）」补齐前不启动；若启动，设计靶点改为真实链 P3 瞬态源 jpg 在场/字节来源，而非自建 checkExist 探测（Q4.2 条件性再评估）。
3. **若需加固机制证据（可选，非本轮 GO 前置）**：仅在同一会话内补一个「自建 task 不下发三字段」的 false 对照（保持其余变量一致），才能把「机制信号·推断·高」升为「机制通过」；跨轮 b6step 基线不得冒充该对照。
4. **口径红线随档下发**：md5Pre（c4b700b7/cff4f5a5/ca47f83e/0ef205c1）、hit（0/1/3）、`UploadHitCacheType`、`idVia` 一律只记录不裁决身份（review2 Q4.2-3 `docs/_r86c_b5_review2.md:105`）。

`[DONE Q4]`

---

真 `[DONE ALL]`

> 交付声明：产物 `docs/_r86c_b5_armresult_verdict.md`（本文本），证据锚点总表 F1~F14 + Q1~Q4 + 三件套全部落盘、真 `[DONE ALL]`。全程只读：唯一写文件 = 本文档；未改源码/单测/任何其它文档，未跑 gradle/`_junit.sh`，未碰 adb/装机/设备开关，未 git。所有【推断】未写成【已证】；跨轮 false 基线未写成同会话；C2 命中未写成清晰/问题解决；talker 整体只升到【真机实证·中】；用户肉眼如实标注【用户报告·非日志可核】。职责 = 证伪/找问题：逐发核实 6 事实全部属实（附 3 处补正）、裁机制为【推断·高·机制信号非机制通过】、talker 升格部分越级、go/no-go = NO-GO 进 B6 全量收尾归档；不为结论放宽判据、不虚构 BLOCKER、不盲从主会话。

