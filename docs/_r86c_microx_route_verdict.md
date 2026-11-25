# R86c 图片清晰度 · 第3轮根因裁决（微X基线重裁：z3(r0.e) 终局路径可行性 + Hook 链路 + go/no-go）

> 任务性质 = **以微X已真机证明可行的机制为基线**，重裁我方从未走过的 `z3(r0.e)` 终局路径可行性 + Hook 链路设计 + go/no-go。只做只读推理与写文档，**不落码、不跑 gradle、不碰真机 adb**。唯一允许写的文件 = 本文档。
> 被重裁对象：`docs/_r86c_stability_reverdict.md`（第1轮）与 `docs/_r86c_verdict2.md`（第2轮「收尾归档不修」）——本轮要指出其共同盲区：只在 P3/相册框架 + q1.j 覆盖推理，未纳入微X已证可行的**第四条路**（j2.R → r0.e → z3 终局）。
> 触发：用户追问「为什么微X能实现」，主会话坦白上一轮「收尾不修」有盲区（三路不行仅指 P3 框架内三路，不等于所有路不行）。台账 `派发审计台账.md:5746-5751`。
> 三档标注纪律：【真机实证·高|中】/【源码实证·高】/【dex 实证·高】/【推断·高|中|低】/【UNKNOWN 未闭合】。【推断】绝不写成【已证】。判据只对**用户肉眼 / 文件字节**，绝不用 `hit`（0↔1↔3 翻转）或 `UploadHitCacheType` 裁决。每条结论标来源（dbg 行号 / 源码行号 / 文档章节）。
> 落盘策略（防父 turn 打断）：骨架 → 每答完一问立即写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。

## 输入清单（按序读毕，全部只读）
1. `docs/派发审计台账.md`「微X基线重裁（23:33）」段（:5746-5751）+ 微X图片真机观察段（:5228-5253）。
2. `docs/_r74_microx_impl_recheck.md` §Q2/Q3/Q4（微X成功=字节来源+终局两层；z3(r0.e) 指向大图字节）；`docs/_r76_p3_bytes_landing_plan.md:85-158`（临时文件命名/准入三门槛/r0.e-f 精确时机/hook j2.R after/方案A vs B）；`docs/_r77_p3_verdict.md:174-186`（Q4 裁决转方案B：hook j2.R after 写 r0.e/f）；`docs/_r86_b2_forensics_plan.md` §3（微X vs B2 三层等价性，含 36B 占位清晰对「r0.e 决定量」的挑战）。
3. `docs/_r71_g1_native_forward_verdict.md`、`docs/_r72_microx_forward_parse.md`（加密 dex 静态不可解证据）。
4. `docs/_r86c_stability_reverdict.md`（第1轮）、`docs/_r86c_verdict2.md`（第2轮「收尾不修」，本轮要指出其盲区）。
5. 源码（只读）：`feature/ForceForward.kt`（P3AdmitGate :106-145 / p3SrcOf+p3OriginFromMd5+copyP3Temp :2484-2606 / cleanup j2 onGYNetEnd :2625-2675 / P3决策 :2718-2765）、`core/FileSender.kt:180-263`（当前 q1.j 覆盖实现）、`probe/StockCdnProbe.kt:72,279-301`（SCD-j2R 观察点）。

## 不得推翻的受保护真机事实（逐条保留，本轮建立其上）
- 微X转发清晰（23:11 用户肉眼）；`m.a=-1`、`r0.u=EMPTY`、`R5/j6/B8/C2` 下载链全零、`ori=cache/_ffb490d4-…`（UUID 临时大图）、发后删除。【真机实证·高】台账 :5229-5235
- 微X清晰度唯一决定量 = `z3(r0.e)` 指向的本地文件是否大图字节（与 m.a/r0.u/q1.*/ori 全无关）；j2 读图唯一链 = `R()=o2(j2.i)→r0 → z3(r0.e/f/g) → o6.l()`。台账 :5241-5249、_r74 §Q3(b)
- 微X字节来源藏加密 dex（rolling-XOR + native CallMethod，静态不可解），`_<uuid>` 命名生成在加密 dex。R72/R74
- 我方当前实现走 `FileSender.kt:224-229` 改 q1.j（bigImgPath 覆盖），**从未 hook j2.R() 写 r0.e**——与微X终局路径不同。台账 :5750、源码实证
- 用户可稳定制造 45522B/cff4f5a5 hevc 字节（`image2/09/ec/09ec74ee…dc8.jpg_hevc`），但造不出 P3 认得的 335644B/216b 源 jpg。forensics §4 / verdict2 :16-18
- 我方老图 216b 转发模糊 5/5 可稳定复现（22:24×3 + 23:19×2），字节判据 `origin-by-md5 miss no-exist` / `p3=false` / c2c 9554B/0ef205c1 / x0cb md5=0ef205c1。verdict2 :115
- `hit` 0↔1↔3 翻转只记录不裁决；`r=false`、`download fired` 根因 UNKNOWN。

---

[DONE 骨架 + 输入清单 + 受保护事实]

---

## Q1‴ 微X机制的可复制性分层

**唯一裁决**：微X的「成功」必须拆成两层判——**层1「字节来源」我方 dex 可见范围内不可复现（其私有实现），但存在可控替代字节；层2「终局 z3(r0.e)」我方 dex 可见范围内可复现**。45522B hevc **可以充当喂给 `z3(r0.e)` 的「大图字节」候选**，但置信度止于【推断·中-高】，不得写成【已证】；它是「替代字节」，不是对微X私有来源的复制。

### 1.1 两层逐层判定表

| 层 | 微X实现（受保护事实） | 我方 dex 可见范围内 | 判定 + 来源 |
|---|---|---|---|
| **层1·字节来源**（把清晰字节造出来） | 加密 dex 私有下载/解码（rolling-XOR + native `libwxcoreloader.so::CallMethod`），落 `cache/_<uuid>`，发后删；`_<uuid>` 生成在加密 dex。真机 `R5/j6/B8/C2` 全零、`m.a=-1`、`r0.u=EMPTY` | 微X的**来源实现**不可反射调用（宿主 dex 里不存在该方法）；但「大图字节」这一**终态产物**可用我方 dex 可见来源替代：①335644B/216b 源 jpg——22:01 出现过但来源 UNKNOWN、用户造不出（verdict2 :28/:38）；②45522B/cff4f5a5 hevc——用户可稳定制造、已成功上传（step1b dbg:4010/4038/4055） | **微X来源实现不可复现**【dex 实证·高：R72 §1.5/§4，8 密文容器+CallMethod 空体+rolling-XOR】；**替代字节可复现**【真机实证·高·可制造/可上传】。精确化 verdict2：它说的「字节来源不可复制」指微X私有实现，不等于「我方无字节可用」。 |
| **层2·终局 z3(r0.e)**（让 stock j2 读到字节） | `o2(j2.i)→r0 → z3(r0.e/f/g) → o6.l()`；微X让 `r0.e` 指向含大图字节的本地文件（`cache/_<uuid>`）。宿主通用链 | 全部 dex 可见可反射：`j2.R()` after 拿 r0，`XposedHelpers.setObjectField(r0,"e"/"f",path)` 直接写；dex 锚点 `b1.R5`@2a710c / `b1.z3 SERVERID→Y2`@2a6b30 / `r0.l`@2b3ea4(写j非e) / `r0.n`@2b3ee4(写f) / `j2.R`@2ad864 / `z3(r0.e)`@2adece / `o6.l`@2adf0c（_r74 §Q4(c)表、_r76 §3.3/§3.5、_r77 :174-177） | **可复现**【源码实证·高 + dex 实证·高】。这是微X成功里唯一我方 dex 可见的「可复制层」。 |

### 1.2 关键回答：45522B hevc 能否充当微X的「大图字节」？

拆成三个属性分别判，避免把一个结论笼统写成【已证】：

1. **字节本身是否「清晰大图字节」——能，【真机实证·高】**。45522B/cff4f5a5 hevc 是用户从相册勾「原图」发出、已成功上传（`step1b dbg:4010 pathTail=dc8.jpg_hevc fbytes=45522 fmd5pre=cff4f5a5`、:4038 `a45522=1 cff4=1 hit=1`、:4055 上传 `x0cb md5=cff4f5a5`），对端肉眼清晰。它本身就是一张清晰图的编码字节，满足「大图字节」的清晰度属性。来源：forensics §4 / verdict2 :16。
2. **能否被 `o6.l()` / `z3(r0.e)` 读到——大概率能，【源码实证·高 + 推断·中】**。`o6.l(path)` 只读文件长度（`_r76:83`、`_r77:181` 的 dex 字节链），`o6.N` 只读字节；链上**没有格式/JPEG magic 校验**（`P3AdmitGate.magicMatchesExt` 是我方 P3 准入逻辑 ForceForward:141-145，不在 `o6.l/z3` 读图链上）。只要 `r0.e` 被 `setObjectField` 成该 hevc 文件的绝对路径、`length=45522>0`，读图链即可取到字节。唯一未定点是 `z3(绝对路径)` 的解析是否会因 `.jpg_hevc` 后缀改写路径——【推断·中】大概率直接返回绝对路径（`z3` 是路径规范化/解析，非格式校验），但无真机直接观测标【UNKNOWN 未闭合】。
3. **语义与格式兼容——有限制，【推断·中】**。①语义：45522B hevc 是用户新发那张图（`09ec74ee…dc8`）的字节，不是老图 216b 本体；用它属「视觉相同内容替换」，非「老图记录变清晰」（verdict2 :59）。②格式：`.jpg_hevc` 实为 hevc 编码，step1b 已证可上传，但**对端 hevc 解码兼容性未验证**【UNKNOWN 未闭合】（verdict2 :60）。

**净回答**：45522B hevc **可以**在层2 作 `z3(r0.e)` 指向的字节候选（层1 有可控替代字节、层2 读图链不卡格式），综合置信度【推断·中-高】；但它**不是**对微X私有字节来源的复现（那层仍【UNKNOWN】且不可复制），且语义为视觉替换、hevc 对端解码未闭合。绝不写成「45522B hevc = 微X大图字节已证可复现」。

### 1.3 本轮对前两轮的认知纠正

第1轮 reverdict / 第2轮 verdict2 把「字节来源不可复制」直接等同于「P3 hevc 方案须改 64KB floor 且无路可走」，是把**层1 与层2 绑死在 `P3→q1.j` 一条路**上的推论。实际上「字节（层1）」与「让 r0.e 指向它（层2）」是**解耦**的：终局层可独立选择 `j2.R → r0.e` 路径（Q2 展开），从而绕开属 P3→q1.j 路径专有的 P3AdmitGate。这是本轮与前两轮的分水岭。

`[DONE Q1]`

---

## Q2‴ z3(r0.e) 终局路径的 Hook 可行性与 floor 绕过

**总裁决**：`j2.R()` 可 hook after 写 `r0.e/f` 指向 `cache/_<uuid>` 字节，**核心 hook 点已具备源码级可行性**；(a) **确证绕开 P3AdmitGate 64KB floor**；(b) 宿主读图链对 45522B/.jpg_hevc 无 64KB 类硬门槛，但后缀兼容留 UNKNOWN；(c) 与 q1.j 路径技术上可共存、语义上建议互斥；(d) 多目标隔离规则明确、ThreadLocal 线程亲和性未闭合。

### 2.0 j2.R() hook after 本身——可行【源码实证·高 + dex 实证·高】

- `StockCdnProbe.kt:281-301` 已挂 `hookRobust("SCD-j2R", j2cls, "R", sig("R", emptyList(), "com.tencent.mm.modelimage.r0"), afterHookedMethod{...})`；after 里取 `param.result`（即 r0 实例），读 `longFieldStr(r0,"a"/"b")` 与 `strFieldStr(r0,"e"/"f"/"g")`。**这证明**：①j2.R() 可被 Xposed 挂 after；②`param.result` 就是 r0；③`r0.e/f/g` 是 String 字段、可读 ⇒ `XposedHelpers.setObjectField(r0,"e"|"f",path)` 在回调里可写。【源码实证·高 · StockCdnProbe.kt:279-301】
- dex 侧一致：`j2.R()` @`2ad864` = `if(q==null) q=b1.o2(j2.i); return q;`，r0 在 `R()` 里才取到并缓存到 `j2.q`；`O()/doScene` 用同一 r0。落图唯一链 `z3(r0.e/f/g)`@`2adece/2adee2/2adf06` → `o6.l()`@`2adf0c`。【dex 实证·高 · 台账 :5241、_r74 §Q3(b)、_r76:121-123】
- **写字段纠正（关键，会写错代码的点）**：`r0.l(String)`@`2b3e94` 实际写 **`j`**（不是 e）、`r0.n(String)`@`2b3ed4` 写 **`f`**；`field_fullpath = z3(r0.e)` 取的是 **e**。⇒ 必须 `setObjectField(r0,"e",tempPath)` 直接写 e，`f` 可 `r0.n()` 或直接写，**建议 e+f 双写**。【源码实证·高 · _r76:107-116】
- **时机三步严格有序**（_r76:119-124，【源码实证·高】）：①在 `j2.R()` 读完 ImgInfo2 之后（构造期写会被随后 o2 覆盖）；②在 `j2.O()/doScene` 读字节之前；③精确落点 = `j2.R()` after。若真机证 `O()` 在 `R()` 之前被调，则前移到 O() before（概率低，`O(r0;I)` 须持 r0 入参）。

### 2.1 (a) 是否绕开 P3AdmitGate 64KB floor——是【源码实证·高】

- `P3AdmitGate.admit` 的全部调用点都在 **P3→q1.j 路径**内，共 4 处：`p3SrcOf`:2496、`p3OriginFromMd5`:2545、`copyP3Temp` 复制前 :2574 / 复制后复核 :2595、P3决策 `p3ReadyPath`:2731。`FLOOR=64*1024`（:108），`if(f.length()<=FLOOR) return "below-floor"`（:124）。45522B < 65536 ⇒ 这条路上必被 below-floor 拒。
- **`j2.R()` after hook 不经过上述任何一处**——它是独立 Xposed 回调，只在 hook 里写 r0.e，不经 `copyP3Temp`/`p3SrcOf`/`p3ReadyPath`。出口 `FileSender.kt:224-229` 的 `>15*1024` 同样属 q1.j 路径，j2.R 路径不走它。
- ⇒ **45522B hevc 在 `j2.R→r0.e` 路径上不会被 below-floor 拒**。这**直接推翻** verdict2 :56/:105「方案A 必须改 P3AdmitGate floor」的前提——那前提仅在 q1.j 路径成立，新路径从根上绕开了它。【源码实证·高】
- 残余准入均非 64KB：o6.l() 要 `length>0`（45522 满足）、z3(路径) 解析、实现时可选的「文件存在/长度复核」（45522>15KB 也满足）。无 floor 类硬门槛。

### 2.2 (b) o6.l / z3 对 45522B / .jpg_hevc 的额外准入

| 检查点 | 是否卡 45522B hevc | 定性 + 来源 |
|---|---|---|
| `o6.l(path)` 取长度 | 不卡：`length=45522>0`；长度≤0 才走 `2adf30` 错误出口 | 【源码实证·高 + 推断·高】台账 :5241、_r76:83 |
| `z3(r0.e)` 拼/解析路径 | 无大小/格式准入证据；`z3` 是路径规范化 + `SERVERID://→Y2`，非 magic 校验 | 【源码实证·高】_r74 §Q4(c)表；对 `.jpg_hevc` 后缀是否改写路径无真机直接观测 ⇒ 【UNKNOWN 未闭合】 |
| JPEG magic 校验 | **不在**读图链上：`magicMatchesExt` 是我方 P3 准入（ForceForward:141-145），宿主 `o6.l/o6.N` 只读长度/字节 | 【源码实证·高】 |
| 对端 hevc 解码兼容 | 对端能否显示 hevc 未验证（step1b 仅证我方上传成功，未证对端解码显示） | 【UNKNOWN 未闭合】verdict2 :60 |

⇒ (b) 净判：宿主读图链对 45522B/.jpg_hevc **无 64KB floor 类硬门槛**；唯一宿主硬条件是 `length>0`（满足）。后缀解析与对端解码为 UNKNOWN，不写成已证。

### 2.3 (c) 与当前 q1.j 路径共存 / 互斥

- 两路径作用**不同对象、不同层**：q1.j 是 `hl0.q1` 字段（FileSender.kt:187/218-220/224-229），r0.e 是 `com.tencent.mm.modelimage.r0` 字段（经 j2.R 拿实例）。【源码实证·高】
- 技术上**可共存**（两字段独立写入）。语义上**建议互斥或一致指向**【推断·中】：q1.j 经 `q1.a()→g6/l1.a` 影响发送路径/大小（FileSender:236-252），r0.e 经 `z3(r0.e)→field_fullpath` 喂上传 task（台账 :5242 `2ace9c`）；若两边指向不同字节（q1.j=9554B 缩略图、r0.e=45522B hevc），哪一环最终决定上传字节本轮未闭合 ⇒ 潜在冲突。
- 现状实际互斥：45522B 下 q1.j 路径自锁（P3AdmitGate 拒→`bigImgPath=null`→FileSender 整块跳过），故引入 j2.R 路径后**实际只有 r0.e 生效**，无双写冲突。**最小方案：j2.R→r0.e 为新路径，q1.j 路径保持现状不动**；将来若同时放开 P3 floor 又挂 j2.R，须保证两者指向同一份字节或明确优先级。

### 2.4 (d) 多目标 / 并发风险

- 隔离规则明确【源码实证·高 · _r76 §4 + ForceForward.kt:2629-2675 cleanup hook 代码】：每目标在 resendFile 内自生成 `_<uuid>` 临时文件，不共享；字节来源（宿主只读大图）可共享，可写临时文件不共享；只在 `onGYNetEnd 0/0` **且**对端收到后删（两条件缺一不可，避免 unlink 早于 o6.N 读完导致截断）。cleanup hook 已用 long 字段 i/h/j/Q + 短 TTL 关联（:2637-2658）。**注意**：R78 订正 j2 的 localId/msgId 在 long 字段 `a`/`b`（StockCdnProbe:280），cleanup hook 用的是 `i/h/j/Q`——落码时需核对关联字段口径是否仍有效【推断·中，待确认】。
- 传递难题：j2.R after 需知道「本目标的 tempPath」。_r76 §3.5 方案B 用 ThreadLocal（`FileSender.send` 在 q1.a() 前 set、finally remove），**成立前提 = `R()` 与 `q1.a()` 同线程，未经真机确认**【UNKNOWN 未闭合 · _r76 §8】；兜底 = 按 imgLocalId 为 key 的短 TTL Map + 只匹配一次即移除（仍不共享文件）。
- 串味风险：①线程不一致 → 读到别目标 tempPath；②先完成目标删文件 → 后完成目标读空。均以「每目标独立文件 + TTL Map 兜底 + 两条件清理」缓解。

`[DONE Q2]`

---

## Q3‴ 最小只读验证方案（不落码）

**总裁决**：只读探针 + 用户操作可闭合 Q2 的**前提链**（hook 点可挂、`r0.e` 确是喂给读图链的字段、45522B hevc 字节在场、当前模糊基线的 `r0.e` 指向）；但「**写 r0.e 指向我们的字节后是否清晰**」这一步**必须落码**——`setObjectField(r0,"e",path)` 本身就是写代码，纯只读无法完成正向闭环。本轮不执行下列方案，只设计。判据对**肉眼 + 文件字节**，`hit`/`UploadHitCacheType` 只记录不裁决。

### 3.1 可只读验证（不改码、不动开关；交主会话/实现 lane 执行）

| 步 | 只读动作 | 验证的 Q2 假设 | 判据 / 来源 |
|---|---|---|---|
| V1 | 装 `op=stockcdn` + `op=microimg`，看 `SCD j2R after paths=[e=… f=… g=…]` 是否回显 | j2.R() after 可挂、`param.result`=r0、`r0.e` 是 String 可读（→ 可写） | 回显非空即证；_r76:121-123 / StockCdnProbe:293-296。【源码实证·高 + 需真机只读】 |
| V2 | 同一次转发，对照 SCD-j2R 的 `e` 值与 `MIO z3 out=…`（台账 :5231 的形态），看 z3 out 是否由 `r0.e` 解析而来 | `r0.e → z3(r0.e) → field_fullpath` 这条链在**我方**转发中成立（不只是微X） | dex 已证 `2ace9c field_fullpath=z3(r0.e)`（台账 :5242）；若真机 z3 out 与 r0.e 对应 ⇒ 链闭合【dex 实证·高 + 推断·高】。若不对应 ⇒ 整条 z3(r0.e) 路径前提动摇（见 3.3） |
| V3 | 用户相册勾「原图」发文件传输助手，P-FS 只读 guard 看 `a45522=1 cff4=1` | 45522B/cff4f5a5 hevc 字节确在场、可作层1替代字节 | step1b dbg:4010/4038/4055；【真机实证·高】 |
| V4 | 用户转发老图 216b（基线），SCD-j2R 读 r0.e 指向、SCD c2c 读 `fbytes/fmd5pre`、x0cb 读 `md5`，用户肉眼 | 当前模糊基线：`r0.e` 指向**小字节**（非 hevc），c2c 出 9554B/0ef205c1 | 预期 r0.e=216b/th_/36B 一类、c2c fbytes=9554 fmd5pre=0ef205c1、x0cb md5=0ef205c1、肉眼模糊（verdict2 :115 的 5/5）【真机实证·高】 |
| V5 | 只读引用微X已证锚点（23:11 真机）：微X清晰时 `MIO z3 out` 为大图名、`ori=cache/_<uuid>`；若可在同机共存观察 SCD-j2R，看其 r0.e 是否指向该 cache 文件 | 「r0.e 指大图字节 → 对端清晰」的**真机已证闭环**为终局路径提供基线 | 台账 :5231-5249；【真机实证·高】，只读引用不重做 |

### 3.2 必须落码才能验证（纯只读不可）

- **写 r0.e 指向我们的 hevc**：`XposedHelpers.setObjectField(r0,"e"|"f",tempPath)` 是新 hook 行为（_r76 §3.5 方案B），用户再怎么操作也无法让宿主把 r0.e 指向我们指定的 hevc —— 这是「落码」的定义本身。
- **写后是否清晰**：必须落码 + 真机发送，才能取 V4 的同款肉眼/字节判据看是否从模糊翻转清晰。
- **ThreadLocal / TTL Map 传递、z3 对 `.jpg_hevc` 后缀的解析、对端 hevc 解码**：均需实际写入并发送后才可观测（Q2 的 UNKNOWN 项）。

### 3.3 肉眼判据 + 字节判据（终审 / 旁证分工）

- **肉眼判据（终审，唯一）**：用户转发老图 216b → 对端肉眼清晰（当前基线 5/5 模糊）。对端是否真收到「新收到的消息」需复核对端非缓存复用（台账 :5238 同类混淆已提示）。
- **字节判据（机制旁证，非唯一裁决）**：
  - x0cb 出口 `md5` 由 `0ef205c1`（模糊）变为 `cff4f5a5`（我们写入的 hevc md5）——若变为其它值需解释（可能 z3 改写路径后宿主重编码）。
  - SCD c2c `fbytes` 由 9554 变为 ≈45522（或对应大图字节量），`fmd5pre` 相应变化。
  - SCD-j2R 读 `r0.e` = 我们写入的 `_<uuid>.jpg_hevc` tempPath；MIO/SCD `z3 out` 显示指向该临时文件（非 `th_`）。
  - 对端长按保存字节 ≫15KB、分辨率≈原图（_r86_b2 §3.2 判据4）。
- `hit` 0↔1↔3 翻转、`UploadHitCacheType` **只记录不裁决**（纪律）。
- **成败分叉**：若 V2 证实「r0.e→z3 out」对应成立，则 Q2 前提闭合 ⇒ 满足 Q4「进入落码设计」前置；若 V2 不成立（z3 out 不由 r0.e 决定，或 r0.e 与 fullpath 无关），则整条 `z3(r0.e)` 终局路径在我方链上前提动摇，应退回重新裁决而非落码。

`[DONE Q3]`

---

## Q4‴ go/no-go 重判

**唯一建议：【进入落码设计（z3(r0.e) 新路径）】**——但为**受门槛约束的 go**：以 Q3 V2 真机只读证实「`r0.e→z3 out`」链成立为进入前置；落码设计本身不等于直接上线，转正须真机肉眼+字节闭环。置信度：对「hook 点可行 + 绕开 floor」判【源码实证·高】；对「写 hevc 后一定清晰」判【推断·中】（有替代字节与读图链证据，但存在竞争理论与多个 UNKNOWN）。

### 4.1 为什么不再「收尾不修」——决定性新证据

- 第2轮 verdict2 的「收尾不修」建立在两个前提：①45522B hevc 必须改 P3AdmitGate floor（:56/:105）；②纯只读无法完成正向闭环且不进入落码（:120/:137）。本轮证明 ①**被推翻**——`j2.R→r0.e` 终局路径从根上绕开 P3AdmitGate（Q2(a)【源码实证·高】），45522B 无需改 floor；②**被收窄**——正向闭环确实需落码，但这正是「进入落码设计」要做的事，不构成拒绝落码的理由。
- 微X第四路（j2.R→r0.e）是我方从未走过的路径，其 hook 点已证可挂、终局链 dex 可见、floor 可绕——这是一条**前两轮未纳入评估的可复制层**（Q1 层2）。用户已给「做」授权（台账 :5746），边际收益不再趋零。

### 4.2 与 verdict2「收尾不修」的关系：推翻结论 + 保留事实 + 收窄推论

- **推翻**：verdict2 Q4″「收尾归档不修」整体结论，及其「方案A 须改 P3AdmitGate floor」「无落码正向闭环故不进入落码」两条支撑前提。
- **保留**（不触碰受保护事实）：5/5 模糊复现、45522B/cff4f5a5 vs 335644B/216b 区分、微X字节来源 UNKNOWN/不可复制、老图无原图按钮、`r=false`、`hit` 不裁决、方案A 属视觉替换语义、对端 hevc 解码 UNKNOWN、22:01 源 jpg 来源 UNKNOWN。
- **收窄**：「字节来源不可复制」收窄为「**微X私有字节来源**不可复制」（层1 有 45522B hevc 可控替代）；「三路都不行」收窄为「**P3→q1.j 框架内**三路不行，不含 j2.R→r0.e 第四路」。

### 4.3 关键风险（诚实标注，不写成已证）

- **竞争理论挑战（最重要）**：`_r86_b2_forensics_plan.md` §3 引 R79「stock 用 36B 占位却清晰」，主张清晰度决定量是**服务端秒传**（消息 xml 带 `cdnbigimgurl` + `checkExist` 命中），而非 `z3(r0.e)` 的本地字节；并称 R79「推翻」R74 的「r0.e 大图字节决定量」。这与本轮基线（台账 :5243「清晰度唯一决定量 = z3(r0.e) 指向文件是否大图字节」）存在张力。【推断·中，UNKNOWN 未闭合】若 B2/R79 成立，则「把 hevc 写进 r0.e」可能不翻转清晰度，go 会在真机失败。**这不否定微X真机基线**（微X确实靠 r0.e 指大图字节清晰），但意味着我方模块链上本地字节未必是充分条件 ⇒ 列为落码转正的最大失败风险，须由 V2 + 落码后肉眼闭环证伪/证实。
- 其余 UNKNOWN：`.jpg_hevc` 后缀经 z3 解析是否改写、对端 hevc 解码、ThreadLocal 线程亲和、cleanup 关联字段口径（a/b vs i/h/j/Q）、微X字节来源本身。

### 4.4 落码若 go 的最小规格要点（仅要点，本轮不落码）

1. **hook 点**：`j2.R()` after（复用 StockCdnProbe:281 `hookRobust` 同款，新增独立 callback 不污染只读探针）；`setObjectField(r0,"e",tempPath)` + `r0.f` 双写（e 是 field_fullpath 输入，r0.l 写 j 非 e）；备选前移 `j2.O()` before。独立开关（默认关），off 时不写 r0.e、逐字节回退。
2. **字节来源**：用户可稳定制造的 45522B/cff4f5a5 hevc（`_<uuid>.jpg_hevc`），经每目标独立临时文件；**不经 P3AdmitGate / copyP3Temp、不改 FLOOR、不调 b1.u8（不写库）**。
3. **准入**：宿主侧仅需 `o6.l length>0`；实现时可选「文件存在 + length>15KB + 非 th_」工程复核（45522 通过）；不加 JPEG magic 校验（读图链无此环）。
4. **判据**：肉眼（对端清晰，终审）+ 字节旁证（x0cb `md5` 0ef205c1→cff4f5a5、c2c `fbytes`≈45522、z3 out 指临时文件非 th_、对端保存≫15KB）；`hit`/`UploadHitCacheType` 只记录不裁决。
5. **回滚与清理**：每目标一份临时文件；只在 `onGYNetEnd 0/0` **且**对端收到后删（核对关联字段口径）；异常只丢本次、不影响 `send` 外层 `runCatching`；开关 off 立即停写。

### 4.5 进入 / 重启条件

- **进入落码设计前置（门槛）**：Q3 V2 真机只读证实「`r0.e → z3(r0.e) → field_fullpath`」在我方转发链上对应成立。V2 不成立 ⇒ 前提动摇，退回重新裁决，不落码。
- **落码转正条件**：真机肉眼清晰翻转（5/5 模糊基线被打破）+ 字节判据随之变化（x0cb md5 / c2c fbytes / z3 out 指向）。任一不满足 ⇒ 回滚开关、归档。
- **重启条件（若 go 后仍失败）**：查明 B2/R79 服务端秒传链是否才是决定量、或微X字节来源是否可在 dex 侧复现——任一落成新真机实证再重判。

`[DONE Q4]`

---

## 三件套

**① 本轮一句话结论 + 置信度。**
微X成功拆两层——字节来源（私有加密 dex，不可复现，仍【UNKNOWN】）+ 终局 `z3(r0.e)`（宿主 dex 可见，**可复现**）；我方从未走过的 `j2.R → r0.e` 终局路径 hook 点已证可挂、且**从根上绕开 P3AdmitGate 64KB floor**，故 45522B hevc 无需改 floor 即可作替代字节候选 ⇒ 建议【进入落码设计（z3(r0.e) 新路径）】（受 V2 只读门槛约束）。置信度：hook 可行 + 绕开 floor【源码实证·高】；写 hevc 后清晰【推断·中】（竞争理论 + 多 UNKNOWN，未写成已证）。

**② 修复方向。**
走微X第四条路：`j2.R()` after 写 `r0.e/f` 指向每目标独立 `cache/_<uuid>.jpg_hevc`（45522B hevc 字节），不动 q1.j 路径、不改 P3AdmitGate、不写库。判据始终对用户肉眼 + 文件字节，不用 `hit`/`UploadHitCacheType`。推翻 verdict2「收尾不修」结论、保留其全部受保护事实、收窄其「字节来源不可复制/三路不行」为 P3 框架内表述。

**③ 最小下一步。**
本轮不落码。下一步交主会话/实现 lane：先执行 Q3 V1-V5 只读方案，尤其 V2 证实 `r0.e→z3 out` 对应；V2 通过即按 §4.4 最小规格进入落码设计（新独立开关默认关），落码后以肉眼 + 字节闭环转正，失败即回滚。现场探针保持 off，本轮未碰 adb/gradle/开关。

---

> **交付声明**：Q1‴~Q4‴ + 三件套全部落盘，`[DONE Q1]` / `[DONE Q2]` / `[DONE Q3]` / `[DONE Q4]` / `[DONE ALL]` 均为真（非骨架预置假标记）。全程只读推理，未落码、未跑 gradle、未碰真机 adb 开关，唯一写文件 = 本文档。未推翻任何受保护真机事实（微X清晰、`m.a=-1`、`r0.u=EMPTY`、下载链全零、`cache/_<uuid>`、我方 5/5 模糊、45522 vs 335644 区分、`hit` 不裁决）。微X字节来源仍如实标【UNKNOWN 未闭合】，45522B hevc 充当大图字节标【推断·中-高】、写后清晰标【推断·中】，均未写成【已证】。已指出前两轮「收尾不修」盲区（只在 P3/相册框架 + q1.j 推理，未纳入 j2.R→r0.e 第四路），并诚实标注 B2/R79 服务端秒传竞争理论对「r0.e 本地字节决定量」的挑战。判据只对用户肉眼/文件字节，未用 `hit`/`UploadHitCacheType` 裁决。

真 `[DONE ALL]`
