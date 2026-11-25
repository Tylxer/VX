# R86c Q4b 三只读探针 · 落码规格收口（接入点 + dex 可达性 + 逐行规格）

> 任务性质 = Hook 链路设计 + 接入点选型 + dex 可达性取证 + 逐行落码规格。
> **本代理不改任何源码、不跑 gradle、不碰真机 adb**；唯一写文件 = 本文档。
> 交付对象 = Flash lane（机械照写）。判据来自 `docs/_r86c_q4b_refmd5_verdict.md` Q3.1（表格 :129-136）/ Q3.2 / 三件套②，**本规格不改判据**。
> 三档标注：【dex 实证】/【真机实证】/【推断·高|中|低】/【UNKNOWN 未闭合】。
> 落盘策略：先建骨架 → 每答完一问立即写盘留 `[DONE Qn]` → 三件套 → 真 `[DONE ALL]`。

## 输入基线（只读读毕）
- `docs/_r86c_q4b_refmd5_verdict.md` 全文（Q1/Q2/Q3.1/Q3.2/Q3.3/Q4/三件套）。
- 源码：`probe/StockCdnProbe.kt`、`core/ForwardCdnFieldHook.kt`、`core/FileSender.kt:130-263`、`entry/EntryReceiver.kt:258-298`、`probe/B6CheckExistProbe.kt`、`HookEntry.kt:137-141/365-373`。
- 本轮亲跑 dex（偏移自算，非照抄）：见 Q2 命令清单与原始输出。

---

## Q1 接入点唯一选型

**唯一推荐（一句话）**：**方案② —— P-FS 在 `ok0/m1.e(tj/k)Z` 上另挂一个独立只读 guard（`SCD-r86cfs`，与现码 `SCD-c2c` 同 method 不同 callback，复用其 `field_fullpath` 取 root 范式），P-R0+P-R0ID 挂 `ForwardCdnFieldHook.arm()`（由 `FileSender.send` 转发上下文调用），三探针共用一个独立 op `r86c`、默认关；不新建 probe 类、不改 HookEntry**。方案①（全塞 SCD-c2c）与方案③（全新建独立 probe 类）均否决。

### 1.1 三候选逐一评估（唯一推荐，禁「都可以」）

| 候选 | 评估 | 裁决 |
|---|---|---|
| ① 全塞 SCD-c2c hook | P-FS 可达（`task.field_fullpath` 已有，dex 实证）；但 P-R0/P-R0ID 必需的 `imgLocalId`/`msgSvrId` 在 `tj/k` 里**没有字段**（本轮 dex 亲跑全字段表见 Q2：`field_talker:String` 在，`msgSvrId`/`imgLocalId`/`toUser` 类字段零命中）⇒ 无法从 `param.args[0]` 取全两探针参数。若硬凑从 `W:J`/`t:J`/`field_mediaId` 猜，属 UNKNOWN 猜测，违反「不打完整 hash/不假设」纪律。 | **否决**：P-R0/P-R0ID 参数在 task 上 dex 不可达 |
| ② P-FS 独立 guard + P-R0/P-R0ID 挂 `arm()` | P-FS 在 `ok0/m1.e` 上新挂独立 guard code `SCD-r86cfs`（现码 `:421-422` 已证同 method 可挂多 callback），回调内只读 `task.field_fullpath` 取 root（范式 `StockCdnProbe.kt:436-438`）；**不碰** `SCD-c2c` 既有日志（独立 callback、独立 flag `r86cEnabled`）。P-R0/P-R0ID 所需 `talker/msgSvrId/imgLocalId/o8` 在 `arm()` 入口（`ForwardCdnFieldHook.kt:142-152`）与调用点 `FileSender.kt:158-160` 齐备，正是模块转发当场。两处都加**默认关的 gated 块**，普通发图零反射。改动面 3 文件、不动 HookEntry。 | **唯一推荐** ✅ |
| ③ 全新建独立 probe 类 | P-FS 仍要在 `ok0/m1.e` 再挂一个 hook（Xposed 允许同 method 多 callback，但需新 status/installed/幂等/日志前缀全套）；P-R0/P-R0ID 的参数只在 `arm()` 调用点，`arm()` 仍必须改一行调用探针 ⇒ **省不掉 production 改动**，反而多出「新类 + HookEntry 接线 + 双 hook 生命周期」三层表面积，隔离收益不抵复杂度。 | **否决**：多文件多钩零额外隔离，净增风险 |

### 1.2 三探针逐项来源交代（接入点 / 时点 / 参数来源）

**公共时点关系**：`FileSender.send` 先 `arm(talker,msgSvrId,imgLocalId)`（`:158-160`），随后构造 q1/j2 并经 `q1.a()`/`l1.a()`（`:236/252`）下发，最终触发 `ok0/m1.e(tj/k)`（SCD-c2c）。即 P-R0/P-R0ID 在**转发动作起点**采，P-FS 在同一次转发的**上传任务下发点**采，相隔一次本地构造（【推断·高】同一转发窗口，毫秒级；文件系统在该窗口不变是合理前提，P-FS 判据本身只看文件系统事实）。

| 探针 | 接入点 / 函数 | 时点 | 所需量逐项来源 |
|---|---|---|---|
| **P-FS** | `ok0/m1.e(tj/k)Z before`，**新挂独立 guard code `SCD-r86cfs`**（`StockCdnProbe.install()` 内，`SCD-c2c` guard 之后；回调首行 `if (!r86cEnabled) return`） | 模块「转发（模块）」上传 task 下发前，`field_fullpath` 已被 j2.O 写好 | `task`=`param.args[0]`；`root` = `XposedHelpers.getObjectField(task,"field_fullpath")` → `Regex("/MicroMsg/[0-9a-fA-F]{32}/")` 取数据根（复用 `ForwardCdnFieldHook.kt:221` 范式，不硬编码 hash）；**不需要** talker/msgSvrId/imgLocalId/o8/r0。扫描 `root+/image2/` 两级分桶 `*.jpg_hevc`（纯 `java.io.File` API）。 |
| **P-R0** | `ForwardCdnFieldHook.arm(talker,msgSvrId,imgLocalId)`（`:142`），在 `o8` 拿到后（`:151` 之后）插 gated 块 | 每目标发送前、`q1` 构造前（早于 `FileSender.kt:236 q1.a()`、`:252 l1.a()`） | `talker`=`ctx.sourceChat`、`msgSvrId`=`ctx.msgSvrId`、`imgLocalId`=`ctx.imgLocalId`（三者均由 `FileSender.kt:160` 实参传入）；`o8`=`XposedHelpers.callStaticMethod(d3cls,"O8")`（arm `:151`）；探针自建 `r0o2=callMethod(o8,"o2",imgLocalId)`（不依赖 arm 旧码 `:174` 的 `img`，因 Q4 修复将删该行）；字段 `e/f/g/j`=`getObjectField(r0o2, field)`；四路路径=`callMethod(o8,"z3", fieldVal, "", "", false)`（模板见 Q2.4）。 |
| **P-R0ID** | 同 P-R0，同一个 gated 块内（一次转发打一组） | 同 P-R0 | `talker/msgSvrId/imgLocalId/o8` 同上；`r0a3=callMethod(o8,"a3",talker,msgSvrId)`（arm `:152` 已有同款调用，探针自建以取对象身份）；`r0o2=callMethod(o8,"o2",imgLocalId)`；`identityHashCode` 用 `System.identityHashCode(r0a3/r0o2)`；关键字段 `e/f/g/j/l` 用 `getObjectField`。 |

### 1.3 只读红线如何随接入点落地（逐探针自检）

- P-FS：独立 `SCD-r86cfs` callback 首行 `if (!r86cEnabled) return`，与 `SCD-c2c`/`SCD-m1e` 互不干扰；只读 `File.exists/length/readBytes` + `MessageDigest`（JDK 类，非宿主业务方法）；**不写 `task`、不调宿主方法**；每个候选字段独立 `runCatching`。
- P-R0/P-R0ID：`a3/o2/z3` 三调用均为**只读**（`a3/o2` 是 DB SELECT，`z3` 反汇编证实为纯路径构造、无 `iput/sput`，见 Q2）；**不写 `param.args`/宿主字段、不发消息、不建 db 记录、不调 `j6/R5/B8/FileSender.send`**；`e/f/g/j/l` 每字段独立 `runCatching`。
- 日志只打 `tail=path.takeLast(16)`/`len`/`md5pre=前8`/`identityHashCode`；字段值 `take(80)`；**不打完整 hash/路径/msgSvrId**。
- 三探针常驻 hook 但默认关：P-FS 的 SCD-c2c callback 首行 `if (!enabled && !r86cEnabled) return`；P-R0/P-R0ID 用 `if (probeEnabled) probeR86c(...)` 包裹，关时零反射、零拼接（详见 Q3/Q4）。

`[DONE Q1]`

---

## Q2 dex 可达性亲跑（偏移自算）

> 本节全部为本代理**本轮亲跑**，偏移由 `_r86c_off.py` 自算（非照抄 verdict Q3.2 的历史偏移）；命令与关键原始输出一并留档。dex 在 `_cache/dex/`。

### 2.1 亲跑命令清单
```
python _r35_adnd_dex.py fields 'tj/k'
python _r86c_off.py 'modelimage/b1' z3
python _r86c_off.py 'modelimage/b1' a3
python _r86c_off.py 'modelimage/b1' o2
python _r86c_off.py 'modelimage/r0' b
python _r86c_off.py 'modelimage/d3' O8
python _r35_sns_dis.py bodyx 'modelimage/b1' z3
python _r35_sns_dis.py bodyx 'modelimage/b1' a3
python _r35_sns_dis.py bodyx 'modelimage/b1' o2
python _r35_sns_dis.py bodyx 'modelimage/r0' b
python _r35_sns_dis.py bodyx 'modelimage/j2' O
```

### 2.2 `tj/k` task 全字段（决定候选①生死）——【dex 实证】
`Ltj/k;`（classes7.dex，extends `Ljava/lang/Object;`）字段全集（实测输出，按原名归类）：
- 单字母短名：`B C E F G H I J K L M N P Q R S T U V W d e f g h i j n o p q r s t u v w x y z`
- `field_*`（节选关键）：`field_talker:String`、`field_fileId:String`、`field_filemd5:String`、`field_fullpath:String`、`field_thumbpath:String`、`field_midimgpath:String`、`field_mediaId:String`、`field_aesKey:String`、`field_svr_signature:String`、`field_wxmsgparam:String`、`field_startTime:J`、`field_lastProgressCallbackTime:J`、`field_onlycheckexist:Z`、`field_enable_hitcheck:Z`、`field_trysafecdn:Z` …（另 `field_*` 约 30 项，全为文件任务字段）
- 混杂首行 `La02/d;->a : La02/g;` 为 `fields_of` 对混淆 dex 的解析旁支，不影响结论。

**结论**：
- ✅ P-FS 所需 `field_fullpath:String` 存在，`field_talker:String` 也存在。
- ❌ **不存在** `field_msgSvrId` / `field_imgLocalId` / `field_toUser` 或任何语义等价字段；唯一 `long` 字段是 `W:J`/`t:J`/`field_startTime:J`/`field_lastProgressCallbackTime:J`，与「服务端 msgSvrId / 本地 imgLocalId」无静态对应 ⇒ **候选①（P-R0/P-R0ID 全塞 SCD-c2c）dex 不可达，否决**。
- ⇒ P-R0/P-R0ID 参数只能来自 `arm(talker,msgSvrId,imgLocalId)` 调用点（`FileSender.kt:160`），Q1 选型闭合。

### 2.3 方法签名复核（自算偏移，非照抄）——【dex 实证】
| 方法 | 自算 code_off | 签名（dex 实测） | 备注 |
|---|---|---|---|
| `b1.z3` | `0x2a6ae4`（classes7） | `(String,String,String,Z)→String` | regs=16 **ins=5**（this+4 参 ⇒ 实例方法） |
| `b1.a3` | `0x2a36b0`（classes7） | `(String,J)→r0` | ins=4（this+2） |
| `b1.o2` | `0x2a52a0`（classes7） | `(Ljava/lang/Long;)→r0` | ins=2（this+1）；**第 1 参是装箱 `Long`，不是原生 long** |
| `r0.b` | `0x2b3ca0`（classes7） | `(Cursor)V` | insns=171 |
| `d3.O8` | `0x2a8eec`（classes7） | `()→b1` | ins=0（静态方法） |

与 verdict Q3.2 完全一致；本轮为独立自算复核。

### 2.4 `XposedHelpers.callMethod(o8,"z3",…)` 反射可行性 ——【dex 实证·高】 + 【推断·高（第 4 参）】
- `o8` = `d3.O8()` 返回的 `b1` 实例（arm `:151`，本轮自算 `O8()→b1`）；`z3` 声明在 `b1` 自身、实例方法、签名 `(String,String,String,Z)→String` ⇒ `callMethod(o8,"z3", fieldVal, "", "", false)` 精确命中，无需 `hookAllMethods`，无签名漂移风险。【dex 实证·高】
- **反汇编证实 z3 为只读纯函数**：全 body `grep iput/sput` **零命中**；内部只有 `a8.F0`(空判断)、`String.startsWith/substring`、`Y2(J)`(另一次只读 DB 查)、`qi2/a.b()`(取 image2 根)、`g1.a(...)`(拼路径)、`l2.e`(打日志) ⇒ 探针调用它**不改宿主状态**，符合只读红线。【dex 实证·高】
- **z3 四参精确模板（本轮 35c 解码）**：j2.O 四处 `invoke-virtual {v_this, v_field, v12(""), v12(""), v8}, z3`，即 `z3(r0.<field>, "", "", <boolean>)`；结果寄存器 `v4/v11/v13/v14` 分别对应 `r0.e/r0.f/r0.g/r0.j`。第 4 个 boolean 在 j2.O 现场是 `v8`（`iput-boolean v8, field_trysafecdn`，`:00dd`）。**探针默认传 `false`**；该 boolean 是否影响最终拼出的路径 =【UNKNOWN 未闭合】，故规格要求：若四路在 `false` 下全不命中，允许**只读重试一遍四路 `true`**（仍只读，不改判据），仍不命中才判 no-go。【推断·高（模板）+ UNKNOWN（第 4 参影响）】
- `callMethod` 装箱匹配：现码 `callMethod(o8,"a3",talker,msgSvrId)`（`msgSvrId:Long` → 原生 long 参）已在生产链真机跑通；`o2` 第 1 参是 `Ljava/lang/Long;`、Kotlin `Long` 自动装箱为 `java.lang.Long` 直接匹配 ⇒ 反射调用范式可行。【推断·高，基于现码真机已跑通 + dex 签名】

### 2.5 `a3` 与 `o2` 确为两条不同查询（P-R0ID 判据的技术根）——【dex 实证·高】
本轮 `bodyx` 复核：
- `a3`：`i=0x000a const-string 'msgSvrId=?'` → `i=0x0023 invoke-virtual b1.w7(...)` → `r0.b(Cursor)`；按 `msgSvrId=?` 查，方法 `w7`。
- `o2`：`i=0x000e const-string 'id=?'` → `i=0x0028 invoke-virtual b1.B7(...)` → `r0.b(Cursor)`；按 `id=?`（imgLocalId）查，方法 `B7`。
- ⇒ 不同 where + 不同查询方法，返回 r0 **不保证同一行**。故 P-R0ID 必须实测 `identityHashCode` + `e/f/g/j/l` 才闭合 G3；禁止假设 a3 的 r0 == o2 的 r0。【dex 实证·高】

### 2.6 `r0.b` 列映射复核（P-R0/P-R0ID 只读字段）——【dex 实证·高】
本轮 `bodyx modelimage/r0 b` 见 `e/g/u/m/f/j` 均由 `Cursor.getString(...)` 写入（String），`a←getInt→J`、`b←getLong→J`；与 verdict Q3.2 一致。`e/f/g/j/l` 全为 String 相对标识/URI/摘要，**无绝对路径** ⇒ P-R0 的路径必须由 `z3` 拼出，不能从 r0 字段直接读。【dex 实证·高】

### 2.7 `j2.O` 里 z3 → `field_fullpath` 数据流（P-R0 判据落点）——【dex 实证·高】 + 【推断·中】
本轮 `bodyx modelimage/j2 O` 亲跑（`O(r0,I)Z`，ins=3，r0 即入参 v1）：
- 四路：`iget v11←r0.e → z3 @0x0076`；`v13←r0.f → z3 @0x0080`；`v14←r0.g → z3 @0x008a`；`v15←r0.j → z3 @0x0094`（绝对偏移 `0x2acc54+2·i` 与 verdict 自算一致）。
- 写 task：`new Ltj/k`（`@0x0098`）后 `iput v4/v13/v11 → field_fullpath/thumbpath/midimgpath`（`@0x0124/0x0126/0x0128`，另 `@0x013f/0x0141/0x0143` 同形态），hevc 分支 `@0x01f8` 单独再写一次 `field_fullpath`。
- **寄存器映射**：四路 z3 返回值落 `v4(e)/v11(f)/v13(g)/v14(j)`；普通分支 `field_fullpath=v4=Z3(r0.e)`，hevc 分支 `@0x01f8` 写 `v14=Z3(r0.j)`。⇒ verdict「预期 r0.e」在普通 jpg 分支成立；**hevc 分支对应哪一路需 P-R0 实测锁定**（本轮数据流只到寄存器级，分支条件的数据依赖未全展）【推断·中，不写成已证】。
- ⇒ P-R0 判据必须**四路全打**（e/f/g/j 各自的 z3 tail + len + md5pre），由真机命中反推是哪一路写进 `field_fullpath`，禁止预设只有 e。【dex 实证·高（四路调用与写点）+ 推断·中（hevc 对应字段）】

### 2.8 go/no-go（dex 可达性层）
机制层 **go**：P-FS 文件系统 API 可达、P-R0/P-R0ID 的 `a3/o2/z3` 反射可达且只读、task 无三参已由 Q1 选型绕开。**但** r0 身份 / z3 哪路命中 cff4f5a5 仍【UNKNOWN 未闭合 → 真机探针闭合】，不宣称修复可落码生效（对齐 verdict Q3.3）。

`[DONE Q2]`

---

## Q3 开关与隔离

### 3.1 唯一开关约定
**独立 op `r86c`，`--es op r86c --es sub on|off|stat`**（无 sub 默认 stat 回显）。一个开关同时控三探针 —— G1/G2/G3 必须在**同一次模块转发**取证，拆三个开关会错配窗口。

| 项 | 约定 |
|---|---|
| op 名 | `r86c`（在 `EntryReceiver.kt` 的 `ACTION_PROBE` 分支新增，位置建议紧邻 `stockcdn` 分支 `:261-270` 之后） |
| sub=on | `StockCdnProbe.turnR86c(true)` + `ForwardCdnFieldHook.turnProbe(true)`，打 `R86C OP on fs=true r0=true` |
| sub=off | 两者置 false，打 `R86C OP off fs=false r0=false`（**取证完必须发**） |
| sub=stat/缺省 | 回显 `R86C OP stat fs=<bool> r0=<bool> installed=<bool>` |
| 持久化 | **不写 Kv/Keys**，两个 flag 是对象内 `@Volatile var` 内存态；冷启复位 false ⇒ 冷启静默，免「探针常开污染热路径」 |
| 真机用法 | `adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op r86c --es sub on` → 做一次模块「转发（模块）」→ `dev.py q` 按 `R86C` 过滤 → 立刻 `--es sub off` |

### 3.2 为什么不复用现有 op / 不跟随 forwardCtx 自动触发
- **不复用 `stockcdn`**：该开关一开会同时打十几行 `SCD2 c2c/g9c/x0cb…` 日志，取证窗口被污染、`dev.py q` 过滤困难；且 `SCD-c2c` 的 `if (!enabled) return` 会把 P-FS 一起挡掉，仍要另立 flag。独立 guard + 独立 flag 反而更干净。
- **不复用 `b6step`**：`B6CheckExistProbe.arm()` 会**真的下发一次 checkExist 网络请求**（`:278-283`，`dispatch(task)` 走 `mq/k.B8` 或 `m1.e`），这不是只读（会产生网络副作用），与本轮只读红线直接冲突；且其 enabled 只控自己，覆盖不到 P-FS/P-R0。
- **不跟随 `forwardCtx` 自动触发**：那样每次模块转发都会跑探针，无法把取证限定到「指定那一次」；关停需改代码或写 Kv，回退成本高。独立 op 可**即时开/即时关**，窗口可控。

### 3.3 普通发图 / 非本次转发零日志零开销（三重闸门）
1. **hook 层**：`SCD-r86cfs` 独立 callback 首行 `if (!r86cEnabled) return` —— 默认 false 时零反射、零字符串拼接、零文件枚举。既有 `SCD-c2c`/`SCD-m1e` 完全不改（P-FS 不塞进它们的 callback，故 `stockcdn` 开关状态与 P-FS 互不串扰）。
2. **业务层**：`ForwardCdnFieldHook.arm()` 只在 `FileSender.send` 的 `forwardCtx != null && imgLocalId>0 && msgSvrId>0` 时被调用（`FileSender.kt:158-160`）；普通发图 `forwardCtx==null` ⇒ **根本不进 arm** ⇒ P-R0/P-R0ID 块连判断都不会执行。再叠 `if (probeEnabled)` 包裹（默认 false），双重保险。
3. **消费层**：探针**不写** `pending`/`enabled`/`field_*`，不参与 `inject` 的注入与 TTL，故不改变 `ForwardCdnFieldHook` 现有闸门语义；`clear()` 照常工作。

### 3.4 取证后关停与回退
- 标准关停：`--es op r86c --es sub off`；若忘关，冷启即静默（flag 不持久化）。
- 若探针导致异常：单钩 `guard` + 回调体 `runCatching` 保证只 WARN 不崩微信（沿用 `StockCdnProbe.guard()` 与 `ForwardCdnFieldHook.arm()` 现成全包）；回退 = `sub=off` 或卸载重装模块，**不需要改生产逻辑**。
- 日志前缀统一 `R86C`（`dev.py q` 用 `grep R86C` 一把捞全），与 `SCD`/`SCD2`/`B6X` 严格区分。

`[DONE Q3]`

---

## Q4 逐行落码规格（Flash lane 照写）

### 4.0 改动面总览（3 文件，最小）
| # | 文件 | 改动 | 插入锚点（现码行号） |
|---|---|---|---|
| 1 | `probe/StockCdnProbe.kt` | ① 新增 `r86cEnabled` flag ② `hookLabels` 加 1 项 ③ 新增独立 guard `SCD-r86cfs`(P-FS) ④ `turnR86c()` ⑤ `status()` 加 1 行回显 | `:51` 后 / `:75` 后 / `:499` 后 / `:545` 后 / `:552` |
| 2 | `core/ForwardCdnFieldHook.kt` | ① 新增 `probeEnabled` flag ② `arm()` 内插 1 行 gated 调用 ③ `clear()` 后加 `turnProbe()` ④ `skip()` 后加 `probeR86c()` + `r0Fields()` + `idh()` | `:47` 后 / `:151` 后 / `:202` 后 / `:209` 后 |
| 3 | `entry/EntryReceiver.kt` | `ACTION_PROBE` 的 `when(op)` 内新增 `"r86c"` 分支 | `:270`（`stockcdn` 分支末 `}`）后 |

**明确不改**：`HookEntry.kt`（两个对象已 install：`:140`/`:365`）、`FileSender.kt`（`arm` 调用签名不变）、`B6CheckExistProbe.kt`、`ForwardCdnFieldHook` 的 `Pending`/`inject`/`TTL`/`clear`/fileId·aesKey 注入语义、P3AdmitGate / q1.j / x0.u / P3 字节落盘 / 任何生产逻辑。**只加只读 gated 块，不碰 verdict Q4 的修复规格**。

---

### 4.1 文件 1 · `StockCdnProbe.kt`

**(a) `:51` 后**（`var enabled = false` 之后）插入：
```kotlin
    /** R86c Q4b P-FS 只读探针开关（默认关、冷启静默；与 stockcdn 的 enabled 完全独立）。 */
    @Volatile
    var r86cEnabled = false
```

**(b) `:75` 后**（`hookLabels` 的 `"SCD-g9c" to ...` 之后、闭合 `)` 之前）插入：
```kotlin
        // R86c Q4b：P-FS 独立只读 guard（与 SCD-c2c 同 method、独立 callback，不污染既有日志）。
        "SCD-r86cfs" to "ok0/m1.e(R86c P-FS 只读)",
```

**(c) `:499` 后**（`guard("SCD-c2c") { ... }` 结束之后，`// ---- R86 B2-a E3 · SCD-g9c` 之前）插入**独立 guard**：
```kotlin
        // ---- R86c Q4b P-FS · ok0/m1.e(tj/k)Z before —— image2 两级分桶只读扫描 ----
        // 独立 callback + 独立 flag（同 method 多 callback 范式见 :421-422）；默认关首行 return。
        // 只读：仅 java.io.File / MessageDigest；不写 task、不调宿主方法、每个量独立 runCatching。
        guard("SCD-r86cfs") {
            hookRobust(
                "SCD-r86cfs", findCls("ok0.m1", cl), "e",
                sig("e", listOf("tj.k"), "boolean"),
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!r86cEnabled) return
                        runCatching {
                            val task = param.args?.getOrNull(0)
                            val fullpath = runCatching {
                                XposedHelpers.getObjectField(task, "field_fullpath") as? String
                            }.getOrNull()
                            if (fullpath.isNullOrEmpty()) {
                                LogX.w("R86C FS skip reason=no_fullpath")
                                return@runCatching
                            }
                            // 从 fullpath 取 MicroMsg/<32hex> 数据根（不硬编码 hash；范式 ForwardCdnFieldHook:221）。
                            val hit = Regex("/MicroMsg/[0-9a-fA-F]{32}/").find(fullpath)
                            if (hit == null) {
                                LogX.w("R86C FS skip reason=no_root tail=${fullpath.takeLast(16)}")
                                return@runCatching
                            }
                            val root = fullpath.substring(0, hit.range.last + 1)
                            var scanned = 0; var a45522 = 0; var cff4 = 0; var gohit = 0
                            runCatching {
                                java.io.File(root + "image2").listFiles()?.forEach { d1 ->
                                    if (!d1.isDirectory) return@forEach
                                    d1.listFiles()?.forEach { d2 ->
                                        if (!d2.isDirectory) return@forEach
                                        d2.listFiles()?.forEach { f ->
                                            if (!f.name.endsWith(".jpg_hevc")) return@forEach
                                            scanned++
                                            val len = runCatching { f.length() }.getOrDefault(-1L)
                                            val md5pre = runCatching {
                                                if (!f.isFile || f.length() > 32L * 1024 * 1024) "NA"
                                                else {
                                                    val dg = java.security.MessageDigest.getInstance("MD5")
                                                    f.inputStream().use { ins ->
                                                        val buf = ByteArray(64 * 1024)
                                                        while (true) {
                                                            val n = ins.read(buf)
                                                            if (n <= 0) break
                                                            dg.update(buf, 0, n)
                                                        }
                                                    }
                                                    dg.digest().copyOfRange(0, 4)
                                                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                                }
                                            }.getOrDefault("ERR")
                                            if (len == 45522L) a45522++
                                            if (md5pre == "cff4f5a5") cff4++
                                            if (len == 45522L && md5pre == "cff4f5a5") gohit++
                                            LogX.i("R86C FS cand tail=${f.absolutePath.takeLast(16)} " +
                                                "len=$len md5pre=$md5pre")
                                        }
                                    }
                                }
                            }
                            LogX.i("R86C FS verdict scanned=$scanned a45522=$a45522 " +
                                "cff4=$cff4 hit=$gohit ambiguous=${gohit > 1}")
                        }.onFailure { LogX.w("R86C FS err: ${it.javaClass.simpleName}") }
                    }
                }
            )
        }
```
> 判据对齐：存在 `len==45522 && md5pre==cff4f5a5` ⇒ `hit>=1` go；`hit==0` no-go；`hit>1` 歧义（`ambiguous=true`，按 mtime/path 排除或报错）。**只读**，不写 `param.args`、`field_fullpath` 等任何宿主字段。

**(d) `:545` 后**（`turn(on)` 结束之后）插入：
```kotlin
    /** R86c Q4b：P-FS 探针开关（仅本对象用；P-R0/P-R0ID 在 ForwardCdnFieldHook.turnProbe）。 */
    fun turnR86c(on: Boolean) {
        r86cEnabled = on
        LogX.i("R86C FS enabled=$on")
    }
```

**(e) `:552`**（`status()` 里 `lines.add("SCD STATUS ...")` 之后）加一行回显：
```kotlin
        lines.add("R86C FS enabled=$r86cEnabled")
```

---

### 4.2 文件 2 · `ForwardCdnFieldHook.kt`

**(a) `:47` 后**（`private var enabled = false` 之后）插入：
```kotlin
    /** R86c Q4b P-R0/P-R0ID 只读探针开关（默认关；不参与 pending/inject/TTL 闸门）。 */
    @Volatile
    var probeEnabled = false
```

**(b) `:151` 后**（`val o8 = XposedHelpers.callStaticMethod(d3cls, "O8")` 之后、`val r0 = ...a3` 之前）插入**唯一一行** gated 调用（放在 a3 之前，保证后续 `skip` 时探针仍采到数）：
```kotlin
            // R86c Q4b：默认关时只多一次 volatile 读；开时只读 a3/o2/z3，不改 pending/宿主。
            if (probeEnabled) probeR86c(talker, msgSvrId, imgLocalId, o8)
```

**(c) `:202` 后**（`clear()` 结束之后）插入：
```kotlin
    /** R86c Q4b：P-R0/P-R0ID 只读探针开关（默认关，冷启静默）。 */
    fun turnProbe(on: Boolean) {
        probeEnabled = on
        LogX.i("R86C R0 enabled=$on")
    }
```

**(d) `:209` 后**（`skip()` 结束之后、`resolveOriginFullpath` 的长注释之前）插入三个方法：
```kotlin
    /**
     * R86c Q4b P-R0 + P-R0ID 只读取证（前缀 R86C）。
     * 只读 a3(只读DB查询)/o2(只读DB查询)/z3(纯路径构造，dex 证无 iput/sput)；
     * 不写 param.args/宿主字段、不发消息、不建 db 记录；字段与路径各自独立 runCatching。
     */
    private fun probeR86c(talker: String, msgSvrId: Long, imgLocalId: Long, o8: Any) {
        runCatching {
            // ---- P-R0ID：a3 的 r0 与 o2 的 r0 是否同一对象/同一行 ----
            val r0a3 = runCatching { XposedHelpers.callMethod(o8, "a3", talker, msgSvrId) }.getOrNull()
            val r0o2 = runCatching { XposedHelpers.callMethod(o8, "o2", imgLocalId) }.getOrNull()
            LogX.i("R86C R0ID a3id=${r0a3?.let(::idh) ?: "NA"} " +
                "o2id=${r0o2?.let(::idh) ?: "NA"} sameObj=${r0a3 != null && r0a3 === r0o2}")
            LogX.i("R86C R0ID a3Fields=${r0Fields(r0a3)} o2Fields=${r0Fields(r0o2)}")

            if (r0o2 == null) {
                LogX.w("R86C R0 skip reason=no_o2_r0")
                return@runCatching
            }
            val hits = mutableListOf<String>()
            // 四路 z3：第4参 boolean 默认 false（j2.O 现场为 trysafecdn flag，影响路径与否 UNKNOWN）。
            fun one(f: String, b: Boolean, retry: Boolean) {
                val raw = runCatching { XposedHelpers.getObjectField(r0o2, f) as? String }.getOrNull()
                val path = runCatching {
                    XposedHelpers.callMethod(o8, "z3", raw, "", "", b) as? String
                }.getOrNull()
                val len = runCatching {
                    if (path.isNullOrEmpty()) -1L else java.io.File(path).length()
                }.getOrDefault(-1L)
                val pre = runCatching {
                    if (path.isNullOrEmpty()) "NA" else {
                        val file = java.io.File(path)
                        if (!file.isFile || file.length() > 32L * 1024 * 1024) "NA"
                        else md5PrefixOfFile(file) ?: "ERR"
                    }
                }.getOrDefault("ERR")
                if (len == 45522L && pre == "cff4f5a5") hits.add("${if (retry) "true:" else ""}$f")
                LogX.i("R86C R0${if (retry) "retryTrue" else ""} field=$f " +
                    "raw=${raw?.take(80) ?: "NA"} tail=${path?.takeLast(16) ?: "NA"} " +
                    "len=$len md5pre=$pre")
            }
            listOf("e", "f", "g", "j").forEach { one(it, false, false) }
            LogX.i("R86C R0 verdict hitFields=${hits.joinToString(",").ifEmpty { "none" }}")
            // 第4参 UNKNOWN：四路 false 全不命中时，只读重试一遍 true（不改判据），仍不命中才 no-go。
            if (hits.isEmpty()) {
                listOf("e", "f", "g", "j").forEach { one(it, true, true) }
                LogX.i("R86C R0 verdict retryTrue hitFields=${hits.joinToString(",").ifEmpty { "none" }}")
            }
        }.onFailure { LogX.w("R86C R0 err: ${it.javaClass.simpleName}") }
    }

    /** 只读打 r0 的 e/f/g/j/l（各 take(80)），供 P-R0ID 逐字段比对。 */
    private fun r0Fields(r0: Any?): String {
        if (r0 == null) return "null"
        return listOf("e", "f", "g", "j", "l").joinToString(",") { f ->
            val v = runCatching { XposedHelpers.getObjectField(r0, f) as? String }.getOrNull()
            "$f=${v?.take(80) ?: "NA"}"
        }
    }

    private fun idh(o: Any): Int = System.identityHashCode(o)
```
> 判据对齐：P-R0 四路全打，命中 `len==45522 && md5pre==cff4f5a5` 的 field 即 G2 答案（预期 `e`，但**以实测为准**，hevc 分支 dex 显示可能写 `j`）；P-R0ID 的 `a3id/o2id/sameObj` + `e/f/g/j/l` 比对即 G3 答案。**`o2` 与 `a3` 不可互换**（dex 实证不同查询键，见 Q2.5）。`md5PrefixOfFile` 复用现成 `:262`，口径与 SCD `fmd5pre` 一致。

---

### 4.3 文件 3 · `EntryReceiver.kt`

在 `:270`（`stockcdn` 分支的 `}`）之后插入：
```kotlin
                    // R86c Q4b 三只读探针（默认关、冷启静默、运行时广播开）。
                    //   --es op r86c --es sub on|off|stat
                    //   on/off 同时翻转 P-FS(StockCdnProbe.r86cEnabled) 与 P-R0/P-R0ID(ForwardCdnFieldHook.probeEnabled)。
                    //   stat 回显两个 flag；取证完务必发 off（否则本进程持续打日志，冷启才复位）。
                    "r86c" -> {
                        val sub = intent.getStringExtra("sub") ?: "stat"
                        when (sub) {
                            "on" -> {
                                com.lx.wxgroup.probe.StockCdnProbe.turnR86c(true)
                                com.lx.wxgroup.core.ForwardCdnFieldHook.turnProbe(true)
                                LogX.i("R86C OP on fs=true r0=true")
                            }
                            "off" -> {
                                com.lx.wxgroup.probe.StockCdnProbe.turnR86c(false)
                                com.lx.wxgroup.core.ForwardCdnFieldHook.turnProbe(false)
                                LogX.i("R86C OP off fs=false r0=false")
                            }
                            else -> LogX.i(
                                "R86C OP stat fs=${com.lx.wxgroup.probe.StockCdnProbe.r86cEnabled} " +
                                    "r0=${com.lx.wxgroup.core.ForwardCdnFieldHook.probeEnabled}"
                            )
                        }
                    }
```

---

### 4.4 日志契约（统一前缀 `R86C`，`dev.py q` 用 `grep R86C` 一把捞全）

| 前缀 + 字段顺序（严格照此顺序，便于脚本解析） | 来源 |
|---|---|
| `R86C OP on fs=true r0=true` / `R86C OP off fs=false r0=false` / `R86C OP stat fs=<bool> r0=<bool>` | EntryReceiver |
| `R86C FS enabled=<bool>` | StockCdnProbe.turnR86c |
| `R86C FS skip reason=no_fullpath\|no_root tail=<16>` | SCD-r86cfs |
| `R86C FS cand tail=<16> len=<long> md5pre=<8>` | SCD-r86cfs（每候选一行） |
| `R86C FS verdict scanned=<n> a45522=<n> cff4=<n> hit=<n> ambiguous=<bool>` | SCD-r86cfs（汇总，判 G1） |
| `R86C R0 enabled=<bool>` | ForwardCdnFieldHook.turnProbe |
| `R86C R0ID a3id=<int> o2id=<int> sameObj=<bool>` | probeR86c（判 G3） |
| `R86C R0ID a3Fields=e=<80>,f=…,g=…,j=…,l=… o2Fields=…` | probeR86c |
| `R86C R0 field=<e\|f\|g\|j> raw=<80> tail=<16> len=<long> md5pre=<8>` | probeR86c（判 G2） |
| `R86C R0 verdict hitFields=<field\|none>` | probeR86c |
| `R86C R0 retryTrue field=… tail=… len=… md5pre=…` + `R86C R0 verdict retryTrue hitFields=…` | probeR86c（第4参 UNKNOWN 兜底） |
| `R86C FS err:` / `R86C R0 err:` / `R86C R0 skip reason=no_o2_r0` | 异常/降级 |

> 全部只打 `tail(16)`/`len`/`md5pre(8)`/`identityHashCode`/`字段值 take(80)`；**不打完整 hash、路径、msgSvrId、fileId**。

### 4.5 单测锁建议（字符串锚点 + 变异有效性）

单测用**源码文本断言 + mock 替身跑日志字段顺序**（不依赖真机/gradle 外挂）。以下锚点必须被断言锁住；**每个锚点都要做变异测试：改一处 → 至少一个测试 FAIL，否则锚点无效**：

| 锚点字符串/常量 | 锁什么 | 变异有效性（改后必须 FAIL） |
|---|---|---|
| `"R86C"`（统一前缀） | 日志过滤契约 | 改成 `SCD2` ⇒ 过滤测试 FAIL |
| `"R86C FS cand"` / `"R86C FS verdict"` | P-FS 候选行/汇总行 | 任一改字符 ⇒ 解析测试 FAIL |
| `"R86C R0ID"` / `"R86C R0 verdict"` / `"R86C R0 field="` | P-R0ID/P-R0 行 | 改字符 ⇒ 解析 FAIL |
| `"cff4f5a5"` | 内容 md5 判据 | 改任一 hex ⇒ verdict 测试 FAIL |
| `45522L` | 体积判据 | 改 `9554L` ⇒ FAIL |
| `".jpg_hevc"` / `"/MicroMsg/"` / `"image2"` | 扫描范围 | 任一改 ⇒ 扫描路径测试 FAIL |
| `"field_fullpath"`（P-FS root 来源） | root 来源 | 改 `field_thumbpath` ⇒ FAIL |
| `"a3"` / `"o2"` / `"z3"` | 反射方法名 | `o2`↔`a3` 互换 ⇒ FAIL（dex 已证两查询键不同）；`z3` 改名 ⇒ FAIL |
| `listOf("e","f","g","j")` | 四路 z3 | 漏一路 ⇒ FAIL |
| `listOf("e","f","g","j","l")` | P-R0ID 五字段 | 漏 `l` ⇒ FAIL |
| `XposedHelpers.callMethod(o8, "z3", raw, "", "", b)` | z3 四参模板 | 删第4参或改参序 ⇒ FAIL |
| `takeLast(16)` / `take(80)` | 裁剪纪律 | 改全量打印 ⇒ FAIL |
| `System.identityHashCode` | P-R0ID 对象身份 | 改成 `toString().hashCode()` ⇒ FAIL |
| `if (!r86cEnabled) return` / `if (probeEnabled)` | 默认关隔离 | 删掉或改 `\|\|` ⇒ 隔离测试 FAIL |
| `"r86c"` / `sub on\|off\|stat` | op 契约 | 改 op 名 ⇒ 路由测试 FAIL |
| `md5PrefixOfFile` | 复用现成口径（与 SCD `fmd5pre` 一致） | 换成新 MD5 实现且前8口径不一致 ⇒ FAIL |

**额外硬锁**：① 三探针不出现 `setObjectField`/`setIntField`/`setBooleanField`/`param.args[..] =`/`callMethod(...,"j6"|"R5"|"B8"|"send"...)`（只读红线静态检查）；② `arm()` 中 gated 调用必须位于 `a3` 之前（顺序断言）；③ `SCD-r86cfs` 必须是独立 `guard` code，不得并入 `SCD-c2c`/`SCD-m1e` 的 callback（隔离断言）；④ `status()` 回显含 `R86C FS enabled=`。

`[DONE Q4]`

---

## Q5 go/no-go（探针落码判定）

**总判定：探针落码 = GO（条件：Flash lane 照 Q4 写 + 4.5 单测锁/变异测试全过）；修复落码 = 仍 NO-GO（G1/G2/G3 三项 UNKNOWN 必须真机跑探针后才闭合）。** 判据只对着「探针能否只读打印出判 G1/G2/G3 所需的量 + 是否污染宿主」，不对内部计数器，也不预设探针跑出的结论。

| 判定项 | 取证结论 | 裁决 |
|---|---|---|
| P-FS 接入点 dex 可达 | `tj/k.field_fullpath:String` 存在【dex 实证 Q2.2】；regex `/MicroMsg/[0-9a-fA-F]{32}/` 取 root 在 A/B 两态真机 raw 均命中（`_r86c_q4b_fullpath_raw.txt:4/6`）【真机实证】；`File.listFiles/length/readBytes` + `MessageDigest` 纯 JDK | **GO** |
| P-R0 接入点 dex 可达 | `o8=d3.O8()→b1`、`o2(Long)→r0`、`z3(String,String,String,Z)→String` 签名自算核准；`z3` 反汇编无 `iput/sput` 为只读纯函数；`callMethod(o8,"z3",…)` 精确命中可行【dex 实证·高 Q2.3/2.4】；四路字段 `e/f/g/j` 均 String【dex 实证 Q2.6】 | **GO** |
| P-R0ID 接入点 dex 可达 | `a3(String,J)→r0` 与 `o2(Long)→r0` 两条不同查询（w7/`msgSvrId=?` vs B7/`id=?`）【dex 实证·高 Q2.5】；`System.identityHashCode` + `e/f/g/j/l` 反射全可达 | **GO** |
| 只读红线可保证 | 三探针均不写 `param.args`/宿主字段、不调 `j6/R5/B8/send`、不建 db 记录、不发消息；P-FS 用独立 callback、P-R0/R0ID 用 `if(probeEnabled)` 包裹；默认关、不持久化、冷启静默（Q3.3 三重闸门） | **GO** |
| 判据量可打印 | G1 ← `R86C FS verdict` 的 `hit/a45522/cff4/ambiguous`；G2 ← `R86C R0 field=… len/md5pre` + `verdict hitFields`；G3 ← `R86C R0ID a3id/o2id/sameObj` + `a3Fields/o2Fields`；全部只打 tail/len/md5pre/identityHashCode/take(80) | **GO** |
| 单测锁可机械验证 | 4.5 给出 16 个字符串锚点 + 变异有效性要求 + 4 项硬锁（只读静态检查/顺序/隔离/回显） | **GO** |

**带闭合的 UNKNOWN（不阻塞探针落码，但必须在真机取证时如实记录、禁止假设通过）**：
1. **z3 第 4 参 boolean** 是否影响拼出的路径 =【UNKNOWN 未闭合】；Q4.2 已给「false 全不命中 → 只读重试 true 一遍」的兜底，仍不命中才判 no-go。
2. **j2.O hevc 分支写 `field_fullpath` 对应 e 还是 j** =【推断·中，Q2.7】；P-R0 四路全打，以真机命中为准，禁止预设「只有 r0.e」。
3. **P-FS 与 P-R0/R0ID 是否严格同一毫秒** =【推断·高】；相隔一次本地 q1/j2 构造，文件系统在该窗口不变是合理前提；若真机两探针结论矛盾，按「P-FS 为文件系统事实、P-R0/R0ID 为当场对象事实」分别记录，不强行对齐。

**no-go 触发条件（任一成立即停，不硬凑）**：① 某接入点 dex 实测不可达（本轮已排除）；② 只读红线无法静态保证（4.5 硬锁②/③任一失败）；③ 单测锁/变异测试未全过；④ 探针真机跑出 `R86C FS verdict hit=0` / `R86C R0 verdict hitFields=none` / `R86C R0ID sameObj=false` 且无兜底命中 ⇒ 判据层 no-go，按 verdict Q4.2 互斥表处置，**不修改判据、不写「已可落码生效」**。

`[DONE Q5]`

---

## 三件套

**① 三探针接入点唯一选型一句话？**
**方案②**：P-FS 在 `ok0/m1.e(tj/k)Z` 上另挂**独立只读 guard `SCD-r86cfs`**（同 method 多 callback，复用 `field_fullpath` 取 MicroMsg 根，不污染 `SCD-c2c` 日志）；P-R0+P-R0ID 挂 `ForwardCdnFieldHook.arm()`（`FileSender.kt:160` 转发当场，`o8=d3.O8()` 现成，`talker/msgSvrId/imgLocalId` 全齐）；三探针共用一个默认关的独立 op `r86c`，**不新建 probe 类、不改 HookEntry**。方案①被 `tj/k` 无 msgSvrId/imgLocalId 字段否决，方案③被「仍须改 arm() 且多出类+HookEntry+双钩生命周期、零额外隔离」否决。

**② dex 亲跑确认 task 字段与 z3 反射可达性结论？**
【dex 实证·高】`Ltj/k;` 全字段：**有 `field_fullpath`/`field_talker`**，**无** `field_msgSvrId`/`field_imgLocalId`/`field_toUser`（long 字段仅 `W/t/field_startTime/field_lastProgressCallbackTime`，无语义对应）⇒ 候选①死。签名自算复核：`d3.O8()→b1`（静态）、`b1.a3(String,J)→r0`（按 `msgSvrId=?` 走 `w7`）、`b1.o2(Long)→r0`（按 `id=?` 走 `B7`，两不同查询键）、`b1.z3(String,String,String,Z)→String`（code_off `0x2a6ae4`，ins=5 实例方法）、`r0.b(Cursor)V`（e/f/g/j/l 均 String）。**z3 反射可行**：`callMethod(o8,"z3", field, "", "", false)` 精确命中，反汇编 z3 全 body 无 `iput/sput`（只读纯函数，`Y2/j2.O` 调用范式一致），符合只读红线；第 4 参 boolean 影响路径 =【UNKNOWN】，规格给 false 全不命中时只读重试 true 一遍的兜底。j2.O 四路 z3→`field_fullpath` 数据流 dex 可达，hevc 分支对应 e 还是 j =【推断·中】，由 P-R0 四路全打以真机命中闭合，禁止预设。

**③ Flash lane 最小改动清单 + 日志过滤前缀 + 单测锁锚点？**
**3 文件**：`StockCdnProbe.kt`（`:51` 后 `r86cEnabled` flag；`:75` 后 hookLabels 1 项；`:499` 后独立 guard `SCD-r86cfs` 实现 P-FS；`:545` 后 `turnR86c()`；`:552` status 1 行）；`ForwardCdnFieldHook.kt`（`:47` 后 `probeEnabled` flag；`:151` 后 `if (probeEnabled) probeR86c(...)` **一行**；`:202` 后 `turnProbe()`；`:209` 后 `probeR86c()`/`r0Fields()`/`idh()` 实现 P-R0+P-R0ID）；`EntryReceiver.kt`（`:270` 后 `"r86c"` op 分支 `on|off|stat`）。**不动** HookEntry/FileSender/Pending/inject/fileId 注入/q1.j/P3AdmitGate。
**日志过滤前缀**：统一 `R86C`（`dev.py q` 用 `grep R86C`）；关键行 `R86C FS verdict scanned/a45522/cff4/hit/ambiguous`（G1）、`R86C R0 field=… tail/len/md5pre` + `R86C R0 verdict hitFields`（G2）、`R86C R0ID a3id/o2id/sameObj` + `a3Fields/o2Fields`（G3）、`R86C OP on/off/stat`。
**单测锁锚点（每项须变异改一处即 FAIL）**：`"R86C"`/`"R86C FS cand"`/`"R86C FS verdict"`/`"R86C R0ID"`/`"R86C R0 verdict"`/`"R86C R0 field="`；`"cff4f5a5"`、`45522L`、`".jpg_hevc"`、`"/MicroMsg/"`、`"image2"`、`"field_fullpath"`；`"a3"`/`"o2"`/`"z3"`（o2↔a3 互换须 FAIL）、`listOf("e","f","g","j")`、`listOf("e","f","g","j","l")`、`callMethod(o8,"z3",raw,"","",b)` 四参、`takeLast(16)`/`take(80)`、`System.identityHashCode`、`if (!r86cEnabled) return`/`if (probeEnabled)`、`"r86c"`、`md5PrefixOfFile`。硬锁：只读静态检查（禁 set*/j6/R5/B8/send）、gated 调用在 a3 之前、`SCD-r86cfs` 必须独立 guard、status 含 `R86C FS enabled=`。

> 交付声明：五问 Q1~Q5 + 三件套全部落盘，`[DONE ALL]` 为真（非骨架预置假标记）。全程未改源码、未跑 gradle、未碰真机 adb；未推翻任何已核事实（NO-GO 三 UNKNOWN、A=45522/cff4f5a5、B=9554/0ef205c1、a3≠o2 查询键、x0.u 零 filemd5、fileId 非决定量）。

`[DONE ALL]`
