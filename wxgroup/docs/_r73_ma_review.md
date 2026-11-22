# R73 `m.a=imgLocalId` 修复 — 只读交叉复核（装机前第二视角）

- 复核 lane：r73-ma-review（只读，不改源码/测试/探针，不碰 adb，不跑 gradle，不 commit）
- 档位：Pro（hy4-flash-free 临时规则）
- 被审改动文件（5 个）：
  - `wxgroup/app/src/main/java/com/lx/wxgroup/core/FileSender.kt`（send 第4参 + m.a 赋值）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`（imgLocalIdOf / snapshotOf / attachForwardInfo / resendFile）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForwardLogic.kt`（Snapshot.imgLocalId）
  - `wxgroup/app/src/main/java/com/lx/wxgroup/probe/ImageNetSceneProbe.kt`（INP 补打 m.a/q1.B）
  - `wxgroup/app/src/test/java/com/lx/wxgroup/feature/R73ImgLocalIdFixTest.kt`
- 证据优先级：dex 反汇编字节 > 源码行 > 真机日志 > Pro lane 结论文档转述

## 风险点 1：`callMethod(storage, "Q2"/"a3", talker, arg:Long)` 宿主签名匹配

**判定：PASS**

独立反汇编验证（用仓库自带 `tools/_r27_disasm.py` 的 `Dex` 类读 `_cache/dex/*.dex` 的 `method_id/proto_str`，未改任何文件）：

| 宿主方法 | 反汇编签名（classes7.dex） | code_off | 我方调用 |
|---|---|---|---|
| `d3.O8` | `()Lcom/tencent/mm/modelimage/b1;` | `0x2a8eec` | `callStaticMethod(core,"O8")` ✔ 返回 b1 |
| `b1.Q2` | `(Ljava/lang/String;J)Lcom/tencent/mm/modelimage/r0;` | `0x2a35c4` | `callMethod(storage,"Q2",talker,arg:Long)` ✔ |
| `b1.a3` | `(Ljava/lang/String;J)Lcom/tencent/mm/modelimage/r0;` | `0x2a36b0` | `callMethod(storage,"a3",talker,arg:Long)` ✔ |

- 形参 `(String,long)`、返回 `r0` 对象：逐字节吻合（`J` = long）。`imgLocalIdOf` 实参 `talker:String`、`arg:Long`（`ForceForward.kt:1278`），类型匹配。
- **Q2 传 msgId / a3 传 msgSvrId**：`MsgRetransmitUI.B6`（code=`0x62c98c`）逐字节：
  - `0x0042 invoke h9->C0()` → `0x0046 invoke h9->getMsgId()` → `0x004a invoke b1->Q2` ⇒ Q2 的第 2 实参是 **getMsgId()**；
  - `0x006f invoke h9->C0()` → `0x0073 invoke h9->w0()` → `0x0077 invoke b1->a3` ⇒ a3 的第 2 实参是 **w0()=msgSvrId**。
  - 我方 `ForceForward.kt:1273-1276` 同构：`Triple("Q2","Q2",ref.msgId)` / `Triple("a3","a3",svrId)`；`ref.msgId` 源于 `h9.getMsgId()`（`:1510`），`svrId` 源于 `h9.w0()`（`:1271`）。**实参映射与 B6 完全一致**。
- SQL 列佐证（`Q2`/`a3` 各自 `0x000a`）：`Q2` 的 WHERE=`"msglocalid=?"`、`a3` 的 WHERE=`"msgSvrId=?"` ⇒ 均是查 ImgInfo2 行、返回 `r0`。
- 证据出处：主文档 `wxgroup/docs/_r73_wxnative_image_source.md` Q1-R.3 / 行 51、253、263、297；本次独立 dex 反汇编（classes7.dex）。

## 风险点 2：`r0.a` 字段语义（是否真 = imgLocalId）

**判定：PASS**

独立反汇编锚点：

1. **字段类型**：`Lcom/tencent/mm/modelimage/r0;`（classes7.dex）实例字段 44 个，其中 `a J`（field idx 5173）、`b J`（5174）。`getLongField(r0,"a")` 类型合法（J=long），不是 int/String。
2. **赋值来源**：`b1.Q2`/`a3` 在 `0x2d` 调 `r0.b(Cursor)` 回填。`r0.b(android.database.Cursor)`（code=`0x2b3ca0`）逐字节：
   - `0x0000 const/4 v0,#0` → `0x0001 invoke-interface Cursor->getInt` → `0x0006 iput-wide v32,v2, r0.a:J`
   - ⇒ 字段 `a` = Cursor **第 0 列**的 int（符号扩展成 long）。该列来自 `Q2/a3` 查询 `ImgInfo2` 的投影，即 ImgInfo2 的 `localid`（imgLocalId），不是 msgSvrId/time。
3. **闭环功能证明（排除"a=msgSvrId/time"）**：
   - `b1.o2(Ljava/lang/Long;)`（code=`0x2a52a0`）SQL `0x000e "id=?"` ⇒ 按 ImgInfo2 主键 `id`（=localid）查记录；
   - 原生链：`B6 0x0052 iget-wide r0.a` → k6 第 6 参 → `k6 [218] iput-wide m.a` → `j2.<init>(0x2aef10) 0x0126 iget-wide m.a` → `0x012c b1.o2(m.a)` 取回源消息 ImgInfo2。
   - 真机确定原生转发经此链拿到清晰大图（主文档 Q1-R.1/.3）；若 `r0.a` 是 msgSvrId 或 time，`o2(id=?)` 必查不中、`j2` 拿不到 ImgInfo2 ⇒ 原生转发不可能清晰。故 `r0.a` **必须**是 ImgInfo2 的 localid。
   - 旁证：主文档 `_r73_image_path_forensics.md` §2 已证 `ImgInfo2.id ≠ message.msgId`、`msglocalid` 真机恒 NULL；若 a=msgId，`o2(id=?)` 同样查不中。
4. 我方取值 `ForceForward.kt:1280 getLongField(r0,"a")` + `:1281 if (id > 0L) return id to src` ⇒ 取到的正是 imgLocalId 且只收正值。

证据出处：主文档 Q1-R.3 行 263/264、Q4.1 行 154；本次独立 dex 反汇编（r0 字段表、`r0.b`、`b1.o2`）。

## 风险点 3：门控与降级的单调性（返回 -1 是否严格等于修复前；有无写入错误正数）

**判定：PASS**

**(a) 取不到值 ⇒ 严格等于修复前行为**
- 修复前：`FileSender.kt` 只 `newInstance(mCls)` 后 `callMethod(q1,"f",m)`，从不写 `m.a`；`attachForwardInfo` 写 `setLongField(fw,"a", fi.fromImgId)`，而 `fi.fromImgId=NO_IMG_ID=-1L`（`ForwardNativeLogic.kt:88/103/127`）。
- 修复后：
  - `FileSender.kt`：`val forwardFrom = if (imgLocalId > 0L) imgLocalId else -1L` → 取不到时写 `-1`；`setLongField` 自身再包 `runCatching`，失败仅 WARN（`FileSender.kt:170-177`）。
  - `ForceForward.kt:662`：`val fromImgId = if (snap.imgLocalId > 0L) snap.imgLocalId else fi.fromImgId`，取不到时**回退 `fi.fromImgId` 即 -1**（不是新造值）。
  - `imgLocalIdOf` 任何一步失败（无 ref / 找不到 d3 / O8 失败 / C0/w0 失败 / Q2,a3 都抛或返回 null / r0.a 非正）都 `return -1L to "NA"`（`ForceForward.kt:1256-1283` 全 runCatching）；`snapshotOf` 再包一层 `getOrDefault(-1L to "NA")`（`:1327-1329`）。
  - ⇒ 不可用时的最终写入值与旧版**逐字节相同**（-1）；j2 侧 `m.a<=0` 一律走 `if-lez` 跳过（`j2.<init> 0x0120`）⇒ 与旧版同分支。**单调性成立，不会更差。**
- `hl0.m` 默认 `a`：`m.<init>(0x710750)` 里 `iput-wide a` 前无正数量构造（无 `const-wide` 正数），即默认 ≤0；即使 `setLongField` 失败，j2 的 `if-lez` 仍跳过 ⇒ 仍等同旧行为。

**(b) 有无"写入错误正数"路径（比 -1 更糟）**
- h9 直接字段捷径 `field_imgLocalId/field_localId/imgLocalId/localId`（`:1258-1261`）：独立 dex dump 证实 `Lcom/tencent/mm/storage/h9;`（classes4.dex）实例字段仅 `Q1,H1,I1,J1,K1,L1,M1,N1,O1,P1`，**无一命中**；父类 `te0/y0` 亦无 local/img/msgid/svr 命名字段。且每项都要求 `v > 0L` 才返回 ⇒ 该版本上此捷径恒失败、不产生错误正数。
- Q2 链（优先）：`WHERE msglocalid=?`，实参 `ref.msgId=h9.getMsgId()` ⇒ 查源消息的 ImgInfo2，`r0.a` 为其 localid（见风险点 2 闭环证明），正值即正确 imgLocalId。
- a3 链（兜底）：`WHERE msgSvrId=?`，实参 `svrId=h9.w0()` ⇒ 同源查询，正值同样为源图 localid。
- talker 取 `h9.C0()`（B6 同款），失败才退 `MsgRef.talker`；即便退化也只影响查不中返回 -1，不会造正数。
- ⇒ **不存在把 msgId/svrId/time 误当 imgLocalId 写入 m.a 的路径**；所有正值出口都经 `r0.a`(ImgInfo2 localid) + `>0` 校验。
- 多目标：`m.a` 是源消息属性，逐目标各建一份 `m/q1/j2`（`resendFile` 每目标独立 `FileSender.send(...,snap.imgLocalId)`），无跨目标污染。

证据出处：`FileSender.kt:166-177`、`ForceForward.kt:1255-1284/1325-1341/662-663`、`ForwardNativeLogic.kt:88/103/127`；独立 dex 反汇编（h9 字段表、`r0.b`、`b1.o2`、`j2.<init> 0x0120`）。

## 风险点 4：INP 探针补打（类型匹配 / q1.B 判别 / 只读性 / enabled 首行 return）

**判定：部分 PASS / 部分 FAIL（详见下；FAIL 仅诊断缺口，不影响修复正确性）**

**4.1 `m.a` 抓取 —— PASS**
- `findObjectOfType(args, thisObj, "hl0.m")`（`ImageNetSceneProbe.kt:186-207`）args 分支 `n == typeName || n.startsWith("$typeName$")`：`hl0.m` 是顶层类（dex 字段 decl 均为 `Lhl0/m;`，无外层 `$`），`n=="hl0.m"` 直接命中；`$` 前缀分支对内部类变体也覆盖。
- j2 主构造器（`0x2aef10`）proto 逐字：`V(I,String,String,String,I,t0,I,String,String,Z,I,Z,I,F,F,String,**Lhl0/m;**,String)` ⇒ **`Lhl0/m;` 是第 17 个直接入参**，构造器 `before` 时就在 `param.args` 里，必定可拿。
- `getLongField(m,"a")`：`hl0.m` 字段 `a J` 存在（独立 dex 字段表）⇒ 能读到真实 m.a（非 NA）。主日志锚点 `"INP j2init m.a="` 在 `:131`。

**4.2 `q1.B` 抓取 —— FAIL（诊断缺口）**
- `findObjectOfType(args, thisObj, "hl0.q1")`：
  - j2 的 6 个构造器 proto 全部列出（`0x2ae6b4/0x2aeab0/0x2aee68/0x2aeea4/0x2aef10/0x2af8a4`），**无一含 `Lhl0/q1;` 入参**；
  - j2 自身 hl0 类型字段只有 `N Lhl0/m;`（无 `q1`），父类链 `k1` 无 `hl0/q1` 字段；
  - 字段扫描分支 `:197` 只比 `f.type.name != typeName`（**无内类 `$` 分支**），且即便放宽也无处可寻。
  - ⇒ q1 在 j2 构造器 before 时**不可达**，`q1.B=` 实际恒打 `NA`（`:141`）。"补打 q1.B 坐实 IMAGE vs CDN_IMAGE"的验收判据失效。
- 但判别方法本身正确：`hl0/q1.B` 字段类型 `Lhl0/o1;`，`o1` 是枚举（access=`0x4011`, super=`java.lang.Enum`, 常量 d/e/f/g/h）⇒ 若 q1 可达，`(b as? Enum<*>)?.name` 能取 `e`/`g` 区分 IMAGE(o1.e)/CDN_IMAGE(o1.g)。缺口纯粹是"拿不到 q1 对象"，不是判别逻辑错。

**4.3 只读纪律 —— PASS**
- 两个 hook 的 `beforeHookedMethod` 首行都是 `if (!enabled) return`（H1 `:83`、H2 `:110`）；零反射零拼接（单测 `inpProbeLogsMaAndQ1B` 断言 count≥2）。
- 全程**只打日志**：`LogX.i` 之外无 `param.args[...]`/`param.result` 写入；`:198-201` 的 `f.isAccessible=true; f.get(thisObj)` 是只读字段读。
- 唯一主动调用 `XposedHelpers.callMethod(thisObject,"getType")`（H1 `:93`）是只读反射、单独 runCatching、拿不到跳过；`m.a`/`q1.B` 均为 `getLongField`/`getObjectField` 只读，与文件头纪律第 2 条一致。
- 安装用 `guard`/`runCatching` 包裹，单钩失败只 WARN，不崩宿主；不写 Kv/消息表、不入队、不下载。

证据出处：`ImageNetSceneProbe.kt:81-143/186-207`；独立 dex 反汇编（j2 全 6 构造器 proto、j2 字段表、`hl0/o1` 枚举 access flags、`hl0/q1.B` 字段类型）；单测 `R73ImgLocalIdFixTest.kt:149-160`。

## 总体裁决

| 风险点 | 判定 | 是否影响修复正确性 |
|---|---|---|
| 1. Q2/a3 宿主签名 | **PASS** | 否 |
| 2. r0.a 字段语义 | **PASS** | 否 |
| 3. 门控与降级单调性 | **PASS** | 否 |
| 4. INP 探针补打 | **部分 PASS / 局部 FAIL**（q1.B 抓取不可达，恒 NA） | **否**（仅诊断缺口，不碰生产链，不改 args/result） |

### 裁决：**可装机**

理由：风险点 1/2/3 全部 PASS，`m.a=imgLocalId` 的宿主签名、字段语义、降级单调性均有独立 dex 反汇编字节自证；风险点 4 的 FAIL 仅在探针观测层（q1.B 恒 NA），探针只读、不写宿主、enabled 首行 return，既不改变被修复的行为，也不引入崩溃/错发风险。按"FAIL 影响正确性才判不可装机"的口径，不构成阻断项。

### 装机后必须知道的约束（交 team-lead）
1. **验收主信号用 `m.a` 而非 `q1.B`**：`INP j2init m.a=<imgLocalId>` 必可读；`q1.B=` 预期恒为 `NA`（j2 构造器拿不到 q1），**不要把它当作判别依据**。IMAGE vs CDN_IMAGE 由 `q1.B` 判别这条路在本 hook 点不可用，但本修复不改 `q1.B`、`m.a>0` 已足以走 ImgInfo2/CDN 清晰图链（风险点 1/2 已证）。
2. **核心判据仍是真机人眼**：多目标收到 + 对端清晰大图（字节 ≫15KB）+ `onGYNetEnd 0/0` + `m.a=<imgLocalId>`；`ok=true` 不等于对端收到。
3. **补齐建议（非阻断，另开变更）**：若确需 q1.B 观测，应改 hook `hl0/q1.a`(`0x712168`) 或 `t81/g.k` 处读 q1.B，而非在 j2 构造器 before 找 q1；或退而用 `j2.B`/`j2.N` 等已可达字段佐证。

[DONE] R73 `m.a=imgLocalId` 只读交叉复核完成（4/4 风险点审毕）。总判定：可装机（唯一 FAIL 为探针 q1.B 不可达的诊断缺口，不影响正确性）。
