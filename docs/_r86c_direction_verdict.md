# R86c 方向裁决（fresh-eyes 独立 Pro）：放弃「只写 field_fullpath」后的两方向可行性裁决

> 角色：`r86c-direction-design`，fresh-eyes 独立 Pro 方向裁决。
> 工作根：`C:/Users/lx/WorkBuddy/2026-09-29-20-23-21`（路径相对此根）。
> 性质与边界（硬）：产出物 = Hook 链路设计 + dex 取证判读 + 可行性裁决 + 竞争理论处置。**只读**——唯一允许写的文件 = 本文档；禁改源码、禁跑 gradle/单测、禁碰 adb/装机/设备开关、禁 git commit/push。
> 判据纪律：只对肉眼 / 文件字节裁决；`hit` / `UploadHitCacheType` 只记录不裁决。分档【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN】，【推断】绝不写成【已证】。每条带来源（文档锚点 / 日志行号 / dex 地址）。
> 落盘策略（防父 turn 打断）：先建骨架写盘 → 每答完一问写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。
> 职责边界：在**放弃「只写 task.field_fullpath」约束**的前提下，评估「复刻微X」与「换 hook 点（B6/g9.c + checkExist 秒传建新记录）」哪条最可能真机闭环；诚实给 no-go，不为凑 GO 放宽判据。

## 背景（自足）
R86c：微信模块「转发（模块）」图片清晰度。第4轮落码 `ForwardCdnFieldHook.kt` 试图在 m1.e before 把 `field_fullpath` 改指向「清晰 hevc」，两次真机实测（实验1 f0196502、实验2 68b471c3bc）**证伪**：清晰态 hevc 字节逐图变（`148929/484bc088`、`45522/cff4f5a5`，历史 216b 亦 `45522/cff4f5a5`），模糊态三值全不同（`1709/13935/9554`），固定字节判据对任意新图 0 命中；两轮全文无 `injected` 日志，现 hook **从未真机写入生效**。`_r86c_generalize_verdict.md` 判 **NO-GO(0.75)**：无唯一身份信号 / fail-closed 与安全冲突 / B2/R79 竞争未排除。用户据此拍板「换方向：复刻微X / 换 hook 点」。

## 必读材料亲核清单（逐条读毕，不盲信转述）
- 微X路线：`_r86c_microx_route_verdict.md`（微X双层机制 + z3(r0.e) 终局链 + go/no-go）、`_r86c_r0e_diff_review.md`（j2.R after 写 r0.e 落码 NO-GO：r0 七 String 字段无 `/MicroMsg/` 完整路径，M1 BLOCKER）、`_r86c_r0e_route2_verdict.md`（第4轮改 m1.e before 理由 + ThreadLocal 被线程核验否定 + keyed TTL Map）。
- B6/checkExist 路线：`_r86b_b6_reimpl_design.md`（GO 但仅限最小步验证 checkExist 命中原图）、`_r86b_b6step_verdict.md`（最小步 no-go：缺 fullpath/enableHit/fake_sig、fileId=305f 是 mid URL、C2 两次 `exist=false md5Len=0`）、`_r86c_q4b_final_verdict.md`（决定量=回包 filemd5 字节身份；A=45522/cff4f5a5 有 fileId、B=9554/0ef205c1 无 fileId；`fmd5pre`=回调md5=出口filemd5前8）、`_r86c_q4_fullpath_verdict.md`（选型 Y：小占位 + 保留 305f fileId）、`_r86c_d3_dex_forensics.md`（m1.m 分派只看 `task.e`，field_fullpath 不参与分派；x0.u setFilePath 唯一字节入口；request 局部变量不可从 x0.u after 拿）。
- 竞争理论/dex 铁证：`_r79_stock_byte_source.md`（stock 清晰决定量可能是 xml `cdnbigimgurl/aeskey/md5` + 服务端秒传 `"support second !!"`，本地 36B 占位也清晰）、`_r10_cdn_api.md`（`CdnLogic.startC2CUpload/startSSUpload` 是 native acc=0x109，不可 hook）。
- 泛化裁决：`_r86c_generalize_verdict.md`（本轮前置，NO-GO 0.75；HDIMG basename 强信号两例【推断·中】）。

---

[骨架已落盘]

---

## Q1 —— 微X为什么能清晰？机制能否复制？

### Q1-a 微X清晰的决定量：靠「本地塞字节」还是「源图 cdnbigimgurl 秒传」？

**一句话裁决：微X靠「本地塞字节」——它把一份私有生成的大图字节落到 `cache/_<uuid>`，让终局读图链 `z3(r0.e)` 指向该文件；它不走 stock 的「源图 cdnbigimgurl + 服务端秒传」链。这是与 stock 微信两条不同的清晰机制，不可混为一谈。【真机实证·高 + dex 实证·高】**

逐条亲核：

| 证据 | 内容 | 来源 | 档位 |
|---|---|---|---|
| 微X真机四特征 | 转发清晰（23:11 肉眼）+ `m.a=-1` + `r0.u=EMPTY` + `R5/j6/B8/C2` 下载链全零 + `ori=cache/_ffb490d4-…`（UUID 临时大图）发后删除 | `_r86c_microx_route_verdict.md:17` / 台账 :5229-5235 | 【真机实证·高】 |
| 决定量 | 微X清晰度唯一决定量 = `z3(r0.e)` 指向的本地文件是否大图字节（与 `m.a`/`r0.u`/`q1.*`/`ori` 全无关）；j2 读图唯一链 `R()=o2(j2.i)→r0 → z3(r0.e/f/g) → o6.l()` | `_r86c_microx_route_verdict.md:18` / 台账 :5241-5249 | 【真机实证·高（现象）+ 推断·高（决定量归纳）】 |
| 字节来源 | 私有加密 dex 下载/解码（rolling-XOR + native `libwxcoreloader.so::CallMethod`），`_<uuid>` 命名生成在加密 dex，静态不可解 | `_r86c_microx_route_verdict.md:19` / R72/R74 | 【dex 实证·高】 |
| 与 stock 对照 | stock 清晰靠 `m1e task fileId=305f… md5=（空）` + `"support second !!"` 秒传 + 36B 占位也清晰；微X则 `m.a=-1`、`r0.u=EMPTY`、下载链全零 | `_r79_stock_byte_source.md:10/24/44/46` vs microx :17 | 【真机实证·高】 |

**关键辨析（防踩坑）**：`_r79_stock_byte_source.md:26` 曾「推翻 R77/R78 的 r0.e 大图字节决定量」，但那**针对的是 stock 微信的转发链**；`_r86c_microx_route_verdict.md` 建立的是**微X私有链**——两者是不同主体、不同机制。微X确实靠 `r0.e` 指本地大图字节（层2），stock 微信靠服务端秒传。这正是 R79 与 microx 基线长期张力的根源，也解释了为什么第4轮把 stock 的 `cff4f5a5` 固定值套到模块转发会失败（见 Q1-c）。

**`ori=cache/_ffb490d4-…` 发后删除说明什么**【真机实证·高 + 推断·高】：说明微X在转发时**临时生成/下载了一份 UUID 命名的大图字节到 cache、转发完成后删除**——字节是「本次转发的临时产物」，不是源图 ImgInfo2 记录本身、也不是持久缓存。这佐证了字节来源是微X私有下载/解码链（加密 dex 生成 `_<uuid>`），而非微信宿主已有的文件【来源：microx :17/:19；"私有生成"为推断·高，未直接 hook 微X下载器】。对我方的含义：目标终局不是「让源图记录变清晰」，而是「在转发帧提供一份临时大图字节供 `z3(r0.e)` 读」——这是**视觉相同内容的字节替换**语义【推断·中，microx :48】。

### Q1-b 「字节来源不可复制但终局可复制」——终局具体是哪个可观测状态？我方能否不解密微X dex 复现？

逐条给终局候选与档位：

| 终局可观测状态 | 是否可观测 | 我方能否不解密微X dex 复现 | 档位 / 来源 |
|---|---|---|---|
| `r0.e` 字段值（相对文件名，如 `de291b56…jpg`） | 可观测（SCD-j2R after 读 `param.result` 的 String 字段） | 可反射写：`setObjectField(r0,"e",path)`；但 r0 七 String 字段**无完整路径**，从 r0 自定位字节不可行 | 写字段【源码实证·高 + dex 实证·高】（microx :66-68、`_r86c_r0e_diff_review.md:32-39`）；自定位失败【dex 实证·高 + 真机实证·高】（_r0e_diff_review :37-40） |
| `field_fullpath`（m1e task 绝对路径，`= z3(r0.e)`） | **可观测且唯一绝对路径点**（SCD-m1e 探针 `StockCdnProbe.kt:434-494`） | 可反射写（m1.e before `setObjectField(task,"field_fullpath",…)`）；这是第4轮实际写点 | 【源码实证·高 + 真机实证·高】（`_r86c_q4b_final_verdict.md:98/179`、`_r86c_r0e_route2_verdict.md:123`） |
| 出口消息 `<img md5>` / `field_filemd5`（源图身份 `cff4f5a5`/`ca47f83e`） | 可观测（x0cb 回调、出口字段） | 不可直接写（由服务端回包 filemd5 决定，`x0.u` 不读 `field_filemd5`）；只能经字节/fileId 间接影响 | 【真机实证·高 + dex 实证·高】（q4b :42-58/:116、D3 :92） |
| 服务端秒传 key（fileId `305f…` / xml `cdnbigimgurl`） | 可观测（x0.u forwardFileid / g9.c 解析） | 可反射构造 checkExist task（B6 方向），但坏图 xml 无 `cdnbigimgurl`、只有 mid | 【dex 实证·高 + 真机实证·高】（D3 :165/:220-222、b6step :21） |

**净判**：终局的可复现部分 = **「让读图/上传链指向一份本地大图字节」这一状态**（r0.e / field_fullpath 可反射写，dex 可见）【源码实证·高 + dex 实证·高】。**不可复现部分 = 微X私有字节来源（加密 dex）**【dex 实证·高】；我方只能找替代字节，而替代字节在放弃固定值后**无唯一泛化判据**——两次真机已证（generalize :24/87）。所以「终局可复制」成立，但「终局可复制」≠「复制终局就能清晰」，后者被字节判据失效与竞争理论阻断【推断·中-高】。

### Q1-c V2 实证链为何第3/4轮落码走不通？换方向后是彻底放弃还是可复用？

V2 只读实证：`SCD j2R after e=de291b56…jpg` 与 `MIO z3 in s0=` 逐字一致、`z3 out=/image2/de/29/…jpg`——证 `r0.e → z3(r0.e) → field_fullpath` 映射在我方链上成立（至少发原图帧）【dex 实证·高 + 推断·高，来源：`_r86c_microx_route_verdict.md:114` V2 项、microx 受保护事实 :18】。但落码两次失败，分因：

| 失败点 | 机制 | 来源 / 档位 |
|---|---|---|
| r0 自定位字节（第3轮 j2.R after） | `resolveOriginLocal()` 用 `contains("/MicroMsg/")` 取根，但 r0 七 String 字段（e/f/g/j/l/m/u）全是 DB cursor 相对名/URI，无一含完整 `/MicroMsg/` 路径；更宽松谓词在更严格谓词两次 `skip reason=no localPath` 失败的同一字段集上不可能成功 ⇒ `e/f` 恒不写 | `_r86c_r0e_diff_review.md:15-22/32-40`【dex 实证·高 + 真机实证·高 + 推断·高·必然】 |
| j2.R 重建 + 多线程 | 跨帧 e 重建（`bd64a9fd→de291b56`）；16 次 j2.R after 跨 `[D]#worker`/`ColdPool#N`/`Thread-38x`/`wxg-forward` ⇒ 单帧身份/ThreadLocal 传递不可靠 | `_r86c_r0e_diff_review.md:78-81`、`_r86c_r0e_route2_verdict.md:20-23`（线程核验）【真机实证·高】 |
| ThreadLocal 跨线程（第4轮） | send/arm 侧全 `wxg-forward`，m1.e/c2c 侧全 `[D]#worker`，ThreadLocal 跨不过线程边界 ⇒ 恒 null | `_r86c_r0e_route2_verdict.md:18-24` R1【真机实证·高·否定】 |
| key 隔离未闭合 | 改 keyed TTL Map（`field_talker`）后，同 talker 并发多图无法区分【UNKNOWN】 | `_r86c_r0e_route2_verdict.md:74-76` 【UNKNOWN 未闭合】 |
| **固定字节判据失效（第4轮真正死因）** | `REF_MD5_PRE=cff4f5a5`/`TARGET_LEN=45522` 是 216b 单图指纹；实验1 清晰态 `148929/484bc088`、实验2 `45522/cff4f5a5`、模糊态三值各异 ⇒ 固定值对新图必 0 命中 | `_r86c_generalize_verdict.md:24/35-48/87`、`_r86c_clearness_reverdict.md:116/184`【真机实证·高】 |
| **hook 从未写入生效** | 两轮全文无 `injected` 日志 ⇒ 现 hook 两次都未写入 field_fullpath | `_r86c_generalize_verdict.md:203-204`【真机实证·高】 |

**换方向后处置**：
- **彻底放弃**：① 从 r0 字段自定位数据根（`resolveOriginLocal` 的 `contains("/MicroMsg/")`）——M1 结构性错误，局部打补丁会触发帧污染 fail-open（`_r0e_diff_review.md:80-81/258`）；② ThreadLocal 目标标记（线程核验否定）；③ 固定字节/MD5 判据（真机证伪）。
- **可复用**：① **终局传导链的认识**——`j2.O → field_fullpath=z3(r0.e) → x0.u setFilePath(field_fullpath)` 是宿主客观链，且 `setFilePath` 是唯一字节入口（D3 :98-99 / q4b :109）——微X方向与B6方向最终都要让正确字节/身份进入这条链，这个 dex 事实可复用；② `field_talker` keyed TTL Map 隔离机制（若仍需逐帧隔离）【`_r0e_route2_verdict.md:72-76`】；③ **「发原图时生成的 hevc 伴生文件就是清晰态字节」这一真机事实**（generalize :45-48）——可作字节来源候选，但只能作相对信号（basename/同根），不能作固定值。

`[DONE Q1]`

---

## Q2 —— 换 hook 点（B6/g9.c + checkExist 秒传建新记录）重评

### Q2-a b6step no-go 三缺口是否已被后续证据补上？

b6step 的三缺口逐条核对（不复述、亲核后给结论）：

| 缺口（b6step 原文） | 后续证据是否补上 | 结论 / 档位 / 来源 |
|---|---|---|
| ①自建 checkExist task 缺 `field_fullpath`/`field_enable_hitcheck`/`field_fake_bigfile_signature`，C2 `exist=false md5Len=0` | **认识已补上，实测未推翻**：`_r86c_q4b_final_verdict.md:17-20/42/98` 给出 A 态字段（有 fullpath 45522B/cff4f5a5、enableHit=true、fakeSigEmpty=true、fileId 有）；`_r86c_q4_fullpath_verdict.md:140-146` 选型 Y 明确要补这三字段；D3 :31/44-47 证 `field_fullpath` **不参与 m1.m 分派**（只用于日志 + 规整为空串），分派由 `task.e`（恒 true）+ `Ltj/k` 类型决定 ⇒ "补 fullpath 让 task 走对路"是伪命题（本就进 x0.u） | 缺字段致未命中的因果【dex 实证·高 + 真机实证·高】（D3 :31/49-50、q4b :42）；"补字段后能否命中"仍【UNKNOWN】（无干预实验） |
| ②fileId=305f 是 mid URL 非 big URL | **已被反证降级**：D3 :220-227 证坏图 attrs 无 `cdnbigimgurl`（`state_final:208`），B6 用 `r0.h` 二选一取到 mid=305f0201；但**同一串 305f0201 在 stock 清晰态命中原图**（`lock_state1_raw.txt:388 fidPre=305f0201 hit=1 md5=ca47f83e`），R82 四样本 fileId 逐字相同仍一清晰一模糊 ⇒ "mid 语义"本身不是未命中根因 | 根因②降为【推断·低】，fileId 零区分力【真机反证·高】（D3 :226-232、q4b :99） |
| ③C2 两次 `exist=false md5Len=0` | **未被推翻，仍是事实**：b6step :89/:213 实测零命中如实记；D3 Q1 补上因果（缺 fullpath → `x0.u setFilePath` 无本地字节算 md5 → hitCheck 不成立 → `exist=false`），但这是【推断·高】未直接观测；b6step :97-101 明判 M1（路径失败）坐实、M2（B2 秒传命题）**未证伪**（态① stock hit=1 说明秒传机制本身成立） | 缺口③仍【UNKNOWN（补字段后能否命中）】；因果机制【推断·高】（D3 :229-232、b6step :97-101） |

**净判**：缺口①的**根因认识**（缺三字段 + fullpath 是字节入口非分派条件）与缺口②的**降级**（mid 非根因）已被 D3/q4b/q4fullpath 补上【dex 实证·高 + 真机反证·高】；缺口③（C2 命中）**没有**被任何后续真机实验推翻或坐实——它仍是 B6 方向的核心未验证假设，需干预实验回答。

### Q2-b 关键：Q4b「决定量=回包 filemd5 字节身份」与 R79「决定量=服务端秒传身份」是否同一条链的两端？

**裁决：是「同一终局的两个观测面」，但不是「同一条上游链」——二者汇聚到同一出口状态（消息 `<img md5>` = 源图身份），上游入口分叉。正确落点应对准「让上传/秒传链认出源图身份」，而非第4轮的「改本地字节指向 hevc」。【推断·中-高（终局汇聚）；推断·中（落点对症性）】**

论证（逐条带来源）：

1. **终局汇聚（同）**：Q4b 的决定量 = 服务端回包 `C2CUploadResult.filemd5` 写进消息 `<img md5>` 的字节身份是否为源图 `cff4f5a5`（q4b :42/:250）；R79 的决定量 = 服务端秒传命中使重建消息 `<img cdnbigimgurl=...>`（`:10/:37`）。二者的**出口可观测状态都是「对端凭源图身份 cff4f5a5/ca47f83e 拉原图 → 清晰」**，且 `fmd5pre = 回调 md5 = 出口 filemd5 前8` 三态同源（q4b :55-58）证明字节身份一路传到对端。⇒ 同一条链（终局）的两端/两面【推断·中-高，真机实证·高支撑出口同源】。
2. **上游分叉（异）**：
   - 本地字节路径：`field_fullpath` 指向源图字节 → mars 从字节算 md5 → 回包 filemd5=源图身份（q4b :47-52，A 态 45522B/cff4f5a5 实证）。
   - 服务端秒传路径：fileId(305f) + 消息 xml `cdnbigimgurl/aeskey/md5` → checkExist 秒传命中 → 回包 filemd5=源图身份，本地 36B 占位字节无关（R79 :24/:62、q4fullpath :57-71）。
   - D3 :31/:44-47 证 `field_fullpath` 不参与 m1.m 分派、只是 x0.u 的 `setFilePath` 字节入口 ⇒ 两条路径最终都汇聚到 x0.u / 回包 filemd5 这个决定量，只是「喂什么让它认出源图」不同。
3. **竞争未排除（诚实）**：generalize :164 明标 B2/R79 服务端秒传与本地字节是**两条未做干预实验区分的因果链**；本轮仍无「强制改字段看出口 filemd5 翻转」的因果证据。⇒ 「同一终局」是【推断·中-高】，「两条上游链谁主导我方模块转发」仍【UNKNOWN】。
4. **落点对症性**：
   - 第4轮方向（改 field_fullpath 指向 hevc 字节）= 押注本地字节路径；已被两次真机证伪（generalize :24/87）。
   - B6 方向（让 checkExist 命中原图 / 让链认出源图身份）= 对准服务端秒传路径，与 R79 揭示的 stock 清晰机制**方向一致**，且能解释"本地 36B 占位也清晰"【推断·中】。
   - ⇒ 在两条方向间，**B6 的落点更对症**；但"更对症"是机制推断，不是已证——能否命中仍待干预实验。

### Q2-c B6 方向能否绕开 native 死路（startC2CUpload 挂不上）？

**裁决：能——B6 主链全程 Java 可达，不需要 hook native 上传入口。【dex 实证·高】**

逐点（均带 dex 锚点）：

| 环节 | 调用/落点 | dex 地址 | Java 可达性 |
|---|---|---|---|
| checkExist 下发 | `mq/k.B8(tj/k)Z`，3 指令纯 delegate 到 `ok0/m1.e` | classes7 co=`0x78f618`（D3 :22） | ✅ Java，非 native |
| 任务入队 | `ok0/m1.e(tj/k)Z`（`i=0x002b` 置 `e=true`） | classes7 co=`0x7b52b4`（D3 :34-37） | ✅ Java |
| 分派 | `ok0/m1.m(Z)V`（`i=0x00a0` 只看 `task.e`；`i=0x0129` 调 x0.u） | classes7 co=`0x7b6700`（D3 :39-50） | ✅ Java |
| 字节入口 / 字段读 | `ok0/x0.u(tj/k)I`：field_fullpath→setFilePath(i=0x000b)、fileId→forwardFileid(i=0x001d)、enable_hitcheck(i=0x0085) | classes13 co=`0x3a98cc`（D3 :79-99 / q4b :105-114） | ✅ Java（before 读 task，D3 :75-90） |
| 回调捕获 | 自建 `Ltj/i` Proxy listener，`C2(String,int,Ltj/e,Ltj/f,Z)I` | 接口 `Ltj/i`（`_r86b_b6_reimpl_design.md:32`） | ✅ Java 接口可自建，不依赖 Activity |
| 原图参数读取 | `b1.a3(talker,msgSvrId)→r0.u` → `g9.c(r0.u,"msg","")` 三参 | B6 co=`0x62c98c`（R79 :32-33 / D3 :177-185） | ✅ Java 静态/实例方法 |

**native 死路的真实边界**：`CdnLogic.startC2CUpload/startSSUpload` 是 `ACC_NATIVE` acc=0x109（R10 :79/:114/:133、`_r10_cdn_api.md:129-131`），Xposed 不可直接 hook；D3 主会话复核附录 B 已把「hook native 读 C2CUploadRequest 的探针 B」判 **no-go**【dex 实证·高】。但这只影响「想读 request 内部字段」的观测探针，**不影响 B6 主链**——B6 只需在 Java 层（m1.e / x0.u before）读写 task 字段、在自建 Proxy C2 里接回调，native 上传由宿主自己调用。⇒ B6 方向能绕开 native 死路；观测边界是「request 对象拿不到，只能读 Java 层 task 六字段」【dex 实证·高，来源 D3 :99/:285】。

`[DONE Q2]`

---

## Q3 —— 两方向对比 + 最小步设计

### Q3-a 两方向对比表

| 维度 | 微X复刻方向 | B6/checkExist 换 hook 点方向 |
|---|---|---|
| 机制可读性 | **中**：字节来源藏加密 dex（rolling-XOR + native CallMethod），静态不可解【dex 实证·高，microx :19/R72】；终局 z3(r0.e) 链 dex 可见 | **高**：checkExist 秒传建新记录全链 dex 可见（`b1.a3→g9.c→tj/k→mq/k.B8→j0.C2→b1.j6→k6→j2.O→f2.C2`）【dex 实证·高，R79 :32-37/:200】 |
| 字节来源可否复现 | **否（核心死结）**：私有来源不可复现【dex 实证·高】；替代字节（发原图 hevc 伴生文件）逐图变、放弃固定值后无唯一映射信号【真机实证·高，generalize :24/87/99】 | **不需本地字节来源**：靠服务端已有原图 + fileId(305f)/秒传命中；但坏图 xml 无 `cdnbigimgurl` 只有 mid（state_final:208），mid 能否正向命中未证【UNKNOWN，D3 :220-227】 |
| 与 B2/R79 竞争关系 | **对抗/未消解**：微X走本地字节终局，与 B2/R79 服务端秒传是两条链；我方模块链上本地字节未必充分【推断·中，generalize :164】 | **方向一致（对症）**：B6 正是对准 B2/R79 服务端秒传入口，若 B2/R79 成立则 B6 是对症方向【推断·中-高，R79 :10/187-211】 |
| hook 点是否 Java 可达 | 终局链 Java 可达，但 r0 七 String 字段无完整路径（M1 结构性失败）、需外部 tempPath 交接（改 FileSender，ThreadLocal 已否定）【dex+真机实证·高】 | **全 Java 可达**：mq/k.B8→m1.e→m1.m→x0.u + 自建 Ltj/i.C2 Proxy，不碰 native【dex 实证·高，Q2-c 表】 |
| 真机可验证性 | **低**：两次真机证伪（固定值 0 命中 + 无 injected 日志），写后清晰从未观测【UNKNOWN/推断·低，generalize :203-204】 | **中**：b6step 已跑过最小步（C2 exist=false md5Len=0），判据明确（C2 是否命中 + 出口 filemd5 + 肉眼）；关键干预实验未做【真机实证·高（旧路径失败）】 |
| 工作量 | **最大**：需复刻私有下载/解码，或找替代字节 + 唯一判据 + 逐帧隔离；规格扩展 AutoHdImg 捕获【推断】 | **中-高**：两段式异步 + Proxy listener + 多目标关联 + TTL + 开关互斥 + 防串味；D1(j6 九参)/D2(j0.C2 尾段) 未闭合【`_r86b_b6_reimpl_design.md:46-57/207`】 |
| 最大风险 | 字节来源不可复制 + 无唯一身份信号 + fail-closed 与安全冲突（generalize 三条硬伤）【推断·中-高】 | ①补字段后 checkExist 是否真命中（C2 exist=false 未推翻）②坏图 xml 无 big URL ③j0.C2 副作用未闭合 ④回调线程能否安全 send ⑤对端清晰仍是 R79 §4.6 未验证假设【推断·中 / UNKNOWN】 |

### Q3-b 微X方向「落码前只读取证最小步」

**目标**：证伪/坐实「HDIMG/ori 终局能否在我方转发帧观测到」+「HDIMG basename 强信号是否普适」。不改功能码、不写宿主字段、不装机改开关（沿用现有探针 off 态广播开），责任方 = 用户真机 + team-lead 汇总。

| 步 | 只读取证动作 | 验证假设 | 判据 / 门槛 / 来源 |
|---|---|---|---|
| M1 | 转发帧记录 ① `HDIMG fired img=<basename>` ② 清晰 hevc basename ③ 两者 mtime ④ 同根 hevc 数量；换 **≥3 张图**（含「本端发原图后转发」与「接收图转发」两类） | HDIMG img basename 普适等于清晰 hevc basename 且严格早于 m1.e | **通过门槛：≥3 图全部 basename 相等且 `HDIMG fired` 早于 m1.e**；任一不成立 → generalize 候选 A 直接否【真机实证·高（两例）；推断·中（机制），generalize :109/210】 |
| M2 | SCD-j2R after + MIO z3 探针读 `r0.e`/`z3 out`/`field_fullpath`，看终局 `r0.e→z3(r0.e)→field_fullpath` 在我方转发帧是否逐字对应 | 微X终局状态在我方可观测 | 对应成立才满足复用前提；不成立 → 整条 z3(r0.e) 终局在我方链前提动摇【dex 实证·高 + 推断·高，microx :114】 |
| M3 | 只读确认「发原图态 hevc 伴生文件」basename 与体积/MD5 逐图关系（不写死任何值） | 替代字节只能作相对信号，不能作固定判据 | 已由 generalize :24/87 真机证伪固定值；本步只复核相对信号普适性 |

**硬约束**：fail-closed 不写死、命中数==1 才写、0/>1 不写；`hit`/`UploadHitCacheType` 只记录不裁决；真机 A/B 终审必须用户肉眼（清晰 + A→B→A 翻转）。

### Q3-c B6方向「落码前只读取证最小步」

**目标**：证「源 xml 是否有 cdnbigimgurl」+「补齐字段后 checkExist 能否命中原图 big md5」。不改功能码、不写宿主字段、**不调 j6、不发消息**，责任方 = 用户真机 + team-lead 汇总。

| 步 | 只读取证动作 | 验证假设 | 判据 / 门槛 / 来源 |
|---|---|---|---|
| B1 | hook `ok0/x0.u before`（或复用已挂 SCD-c2c 于 m1.e before）读自建 checkExist task 六字段：fullpath 长度+尾缀、enable_hitcheck、fake_sig、fileId 长度+前缀、onlycheckexist、fileType；对照态① `lock_state1_raw.txt:375` 的 `enableHit=true fakeSigEmpty=true fullpathLen=124` | 缺口①字段差异坐实 / 补齐 | 现有双探针（SCD+B6X）已可零落码读全，D3 复核附录 C 证可零安装完成【dex 实证·高 + 真机实证·高，D3 :288-292】 |
| B2 | Proxy C2 加打 `args[3].javaClass.name` + Ltj/f 全部 String 字段名/长度 | 排 Q1④（C2 读错对象/空壳） | 第二次 `exist=false` 来自真实 Ltj/f 而非空壳；类名非 Ltj/f 或字段全空 → Q1④坐实【b6step :149/:159】 |
| B3 | 同时刻跑 stock 原生转发对照（目标 filehelper、logclear 分隔），看 x0cb 是否仍 `md5=ca47f83e hit=1 exist=true` + **对端肉眼清晰**，同次看我方模块转发是否仍模糊 | 排 Q1③（服务端过期/此刻无原图） | stock 此刻不命中/不清晰 → Q1③坐实、回退；仍命中 → 排除③、反证归①【真机实证·高，b6step :150/:158】 |
| B4 | **源 xml cdnbigimgurl 核对**：`b1.a3→r0.u→g9.c(u,"msg","")` 取四个 key，确认坏图 attrs 有无 `cdnbigimgurl` | 决定 fileId 取 big 还是 mid | 已有答案：**坏图 attrs 无 cdnbigimgurl，只有 cdnmidimgurl(305f)**【真机实证·高，b6step :21 / D3 :220-222】；但 stock 用同 mid 值清晰（D3 反证），故这不是死路，需 B5 补正向验证 |
| B5 | **补齐三字段后**（fullpath 对齐 A 态 + enable_hitcheck=true + fake_sig=""，保留 fileId 305f），自建 checkExist task 下发，Proxy C2 只读命中信号 | 核心假设：补齐后 checkExist 是否命中原图 big md5 | **通过门槛（三者同时）**：① C2 `field_exist_whencheck=true` 且 `field_filemd5` 非 0ef205（原图身份前缀，如 cff4f5a5/ca47f83e）；② 出口 filemd5 翻到源图身份；③ 用户肉眼 A→B→A 翻转。C2 仍 `exist=false/md5Len=0` 或出口不翻 → B6 方向 no-go【b6step :88-91/:157；q4b :226-235】 |

**硬约束**：最小步阶段不调 j6、不改 m.a、不发消息、不写任何宿主字段；`hit`/`UploadHitCacheType` 只记录不裁决；fail-closed（命中数==1、异常/TTL 超时不写、不误删他人条目）；真机 A/B 终审必须用户肉眼。B5 才是干预实验，须在 B1~B4 只读基线站住后、由实现 lane 另起任务执行。

`[DONE Q3]`

---

## Q4 —— go/no-go + 最小下一步

### Q4-a 方向优先级裁决（诚实，不为凑 GO 放宽判据）

| 方向 | 裁决 | confidence | 理由 |
|---|---|---|---|
| **B6/checkExist 换 hook 点** | **优先投入，但仅到「只读最小步 + 干预实验」层；不直接 GO 到落码** | **中（0.60）** | 机制 dex 全可见、Java 可达、不对抗 B2/R79 反而对症；no-go 根因（缺三字段）已被 D3/q4b 阐明、补字段路径明确；fileId mid 语义已降级；唯一未闭合的「补字段后能否命中」可通过一轮最小步证伪/坐实，成本可控。但核心假设未验、坏图 xml 无 big URL、D1/D2 副作用未闭合，故不判 GO 到全量落码 |
| **微X复刻** | **不优先投入（no-go）** | **中-高（0.70）** | 字节来源不可复制【dex 实证·高】、替代字节判据两次真机证伪【真机实证·高】、写后清晰从未观测【UNKNOWN/推断·低】——核心死结（字节来源 + 唯一身份信号）未解，投入产出比低 |
| **收尾归档** | 作为 B6 最小步失败后的默认出口，不作为本轮首选 | — | generalize 已判 NO-GO(0.75)，但 B6 方向尚未经「补齐字段后」真机实验检验（b6step no-go 针对的是旧 §5.3 字段构造路径，D3/q4b 已指出应补齐字段 + 对齐 A 态字段集），故不应直接收尾；B6 最小步失败则归档 |

**净裁决：优先投入 B6 方向，先做 §3-c 只读最小步 + 干预实验；微X方向暂缓。任一方向的最小步失败即按 generalize 默认收尾归档，现 hook 保持默认关。**

### Q4-b 若走 B6：最小规格（本轮不落码，仅规格）

1. **hook 点**：主链复用现有 `ok0/m1.e(tj/k)Z` before（`ForwardCdnFieldHook` 已挂同点，`param.args[0]`=task）读/对齐自建 checkExist task 字段；checkExist 下发走 `mq/k.B8`（Java delegate，不碰 native）；回调用自建 `Ltj/i` Proxy listener 接 `C2(String,int,Ltj/e,Ltj/f,Z)I`；进阶写点 `ok0/x0.u before` 在 `i=0x000b setFilePath` 前对齐 `field_fullpath`（x0.u 是新 hook 点，需新增 findClass/findMethod——此风险已由 q4b 主会话附录 :288 标出，须单独裁决）。dex 锚点：m1.e `classes7 co=0x7b52b4`、m1.m `classes7 co=0x7b6700`、x0.u `classes13 co=0x3a98cc`、B6 `classes7 co=0x62c98c`。
2. **字段**：自建 checkExist task 补齐 `field_fullpath`（对齐 A 态.content MD5=源图身份的小占位/原图字节路径——Y 小占位 vs X 原图字节仍有分歧，最小步先只读现状、B5 才对齐写）、`field_enable_hitcheck=true`、`field_fake_bigfile_signature=""`；**保留 `field_fileId=305f`（mid，勿清空）**、`field_filemd5=xmlMd5`、`field_onlycheckexist=false`。不写 native `C2CUploadRequest`（局部变量，x0.u after 拿不到；native 入口不可 hook，D3 :99/:285）。
3. **准入 / fail-closed**：C2 命中判定 = `field_exist_whencheck=true` 且 `field_filemd5` 非 `0ef205c1`（原图身份前缀）；命中数==1 才写，0/>1、反射异常、TTL 超时一律不写且不误删他人条目；`hit`/`UploadHitCacheType` 只记录不裁决。
4. **开关**：独立 Key（如 `FORWARD_B6_REIMPL`），默认关、`@Volatile`、冷启按 Key 默认复位、广播 on/off 不持久化；A/B 唯一自变量 = 该开关，反向对照期间冻结其余开关（`_r86b_b6_reimpl_design.md:84-88`）。
5. **单测要点**：断言自建 task 补齐三字段、fileId 305f 不清空；断言不 hook native `startC2CUpload/startSSUpload`（R10 死路）；断言 listener 用 `Proxy(Ltj/i)` 而非复用 `qf4/j0`（避 Activity 依赖）；断言最小步 C2 不调 j6、不发消息；断言命中数==1、TTL、fail-closed、`field_talker` keyed map（无 ThreadLocal）。变异真判红：删任一字段 / 清空 fileId / 改挂 native / 改回 ThreadLocal / 删命中==1 判断。
6. **仍需真机补证项**：补齐字段后自建 checkExist C2 是否 `exist=true` + 原图 md5（核心假设）；同一时刻 stock 原生转发是否仍 `hit=1` + 肉眼清晰（排服务端过期）；出口 filemd5 是否翻到源图身份；对端肉眼 A→B→A 翻转；D1（j6 九参映射）/ D2（j0.C2 尾段最小充分集）dex 补取证；C2 回调线程能否安全发起 send；坏图 xml 无 `cdnbigimgurl` 时 mid fileId 能否正向驱动秒传。

### Q4-c 「写后清晰」当前档位 + 最终坐实条件

**「写后清晰」当前档位 =【UNKNOWN / 推断·低】**。两轮真机全文无 `injected` 日志（generalize :203-204），现 hook 两次都未写入 field_fullpath；两轮观测到的清晰是微信原生链 / 本地 hevc 缓存状态依赖所致，**不是任何 hook 写入的结果**；微X方向「写 r0.e 后清晰」、B6 方向「补齐字段 + 秒传后清晰」均从未被真机直接观测。不得写成【已证】。

**任一方向最终坐实条件（唯一）**：用户肉眼对端「清晰」且 A→B→A（原生清晰 → 模块模糊 → 开闸清晰 → 回退模糊）翻转成立 + 字节旁证（出口 `filemd5` 从模糊身份翻到源图身份 `cff4f5a5`/`ca47f83e`、`fmd5pre`/回调 md5 相应变化、自建 C2 命中信号）。`hit`/`UploadHitCacheType` 只记录不裁决；机制旁证不能替代肉眼终审。

`[DONE Q4]`

---

## 三件套（交付）

**① Q1~Q3 一句话结论 + 档位。**
- **Q1**：微X清晰靠「本地塞字节」——私有加密 dex 造 `cache/_<uuid>` 大图字节、终局 `z3(r0.e)` 指向它（发后删说明是临时私有产物），不走 stock 的 xml cdnbigimgurl 秒传链【真机实证·高 + dex 实证·高】；字节来源不可复现【dex 实证·高】、终局 `z3(r0.e)/field_fullpath` 可反射复现【源码实证·高 + dex 实证·高】但「喂哪份字节」无唯一泛化判据【真机实证·高】；V2 传导链 `r0.e→z3→field_fullpath` 可复用，r0 自定位（M1）/ ThreadLocal / 固定字节判据彻底放弃，替代字节相对信号（HDIMG basename）可复用【推断·中】。
- **Q2**：b6step 三缺口中「缺三字段致未命中」的根因认识与「mid fileId 非根因」已被 D3/q4b 补上或降级【dex 实证·高 + 真机反证·高】，「C2 exist=false」仍未推翻、是核心未验证假设【UNKNOWN】；Q4b「回包 filemd5 字节身份」与 R79「服务端秒传身份」是同一终局的两个观测面、上游入口分叉【推断·中-高】，落点应对准「让链认出源图身份」（B6）而非第4轮「改本地字节」【推断·中】；B6 全链 Java 可达、可绕开 native 死路【dex 实证·高】。
- **Q3**：B6 在机制可读性、与 B2/R79 一致性、Java 可达性上占优，微X在字节来源可复现性上死结；两方向各一个只读取证最小步（微X=HDIMG basename 普适性 + 终局可观测性；B6=源 xml cdnbigimgurl + 补字段后 checkExist 命中原图 md5），均不改功能码、不写宿主字段、`hit`/`UploadHitCacheType` 只记录、真机 A/B 终审用户肉眼。

**② 方向 go/no-go + confidence + 优先级。**
- **B6/checkExist 换点：优先投入，仅到「只读最小步 + 干预实验」层，不直接 GO 到全量落码，confidence 中（0.60）**——机制/Java 可达/竞争对症均优，核心未知可一轮最小步证伪，但补字段后能否命中、坏图无 big URL、D1/D2 未闭合。
- **微X复刻：不优先投入（no-go），confidence 中-高（0.70）**——字节来源 + 唯一判据两死结未解。
- **收尾归档：作为 B6 最小步失败后的默认出口**，不作为本轮首选（B6 旧 no-go 针对旧字段构造路径，尚未经补字段后真机实验检验）。
- **「写后清晰」=【UNKNOWN / 推断·低】**，从未被任何 hook 写入的真机结果观测。

**③ 最小下一步与责任方。**
- **首选（B6）**：执行 §3-c 只读取证 B1~B4（现有双探针补字段只读 + Proxy C2 加打 Ltj/f 类名全字段 + 同时刻 stock 对照 + 源 xml cdnbigimgurl 核对，排服务端过期/读错对象），站住基线后由实现 lane 另起任务做 B5 干预实验（补齐三字段、保留 305f、自建 checkExist 下发、Proxy C2 只读命中）；门槛 = C2 命中 + 出口 filemd5 翻源图身份 + 用户肉眼 A→B→A 三者同时成立。**责任方：用户真机 + team-lead 汇总；实现 lane 执行 B5。**
- **备选（微X）**：执行 §3-b M1~M2（≥3 图验证 HDIMG basename == 清晰 hevc basename 且早于 m1.e + 终局 z3(r0.e) 可观测性）；不成立即否。**责任方：用户真机 + team-lead 汇总。**
- **默认**：任一步失败 → 收尾归档、现 hook 保持默认关。**责任方：team-lead。**
- **A/B 终审必须由用户肉眼做**；`hit`/`UploadHitCacheType` 只记录不裁决。

---

> **交付声明**：Q1~Q4 + 三件套全部落盘，真 `[DONE ALL]`（非骨架预置）。全程只读，唯一写文件 = `docs/_r86c_direction_verdict.md`；未改源码、未跑 gradle/单测、未碰 adb/设备/开关、未 git commit/push。未推翻任何受保护真机事实（微X清晰四特征、5/5 模糊、step1b/experiment 逐图 hevc 字节、fileId 逐字相同仍一清晰一模糊、`hit` 不裁决）。所有【推断】未写成【已证】；「写后清晰」如实标【UNKNOWN/推断·低】；B6「补字段后能否命中」如实标【UNKNOWN】、留待干预实验。判据只对用户肉眼 / 文件字节，`hit`/`UploadHitCacheType` 只记录不裁决。不为凑 GO 放宽 fail-closed。

真 `[DONE ALL]`
