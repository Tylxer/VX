# R16 D3 取证：视频降级发送链

> 任务：把「微信视频消息降级重发」的静态取证补完，给出 `sendVideo(path, talker)` 的可实现签名表。
> 工作目录：`C:\Users\lx\WorkBuddy\2026-09-29-20-23-21`

## 0. 结论先行

- **⛔ 最终判定：视频降级重发不可行，`canResendFile` 对 VIDEO 保持 `false`。** 本链路（`o3.j()` → `q1.h=20` → `bm0/p1`）在任何输入下都走不通：`path` 不在库 ⇒ NPE / 返回 -1 发不出去；`path` 在库 ⇒ 发往原会话，发错人（详见 §4）。
- **能做（但没用）**：视频的入口 `modelimage/o3.j()`（静态工厂，**无参**）造出 `o3`（`super=hl0/q1`），设字段后走 `q1.a() → t81/g.i(q1)` 按 `q1.h` 分派，`h=20` 落 `bm0/p1`（`NetSceneUploadVideoOrigin` 原视频上传）—— 但这套只能「转发自己收到的视频回原会话」，不是「降级重发到新目标」。
- **关键修正**：`o3.j()` 本身**不是** `sendVideo(path, talker)` 的完整入口 —— 它是**无参**工厂，只 `new o3` + `q1.B=o1.VIDEO`，其余字段（E/F/K/H）由调用方逐字段 `iput`。
- **正解方向（team-lead 已证）**：要「发到新目标」只能走 `WXVideoObject` + `sendAppMsg`（与普通文件 `FileSenderB` 同一套 appmsg 链），从零构造，**不碰 `o3`/`t2`**。
- **F 字段取值陷阱**：`_r14_walk.py dis` 对 `const/4` 立即数解析有 bug（根因见 1.3：`const/4` 是 11n 格式单 code unit，旧代码误读第 2 个 code unit，即下一条指令的字节），u0.N 里 `o3.F` 真实值是 **0** 不是 4（以 dexdump 原始 `1205 #int 0` 为准，team-lead 已修工具并复验一致）。

## 1. 逐条反汇编证据

### 1.1 `modelimage/o3.j()` 工厂方法

类定义（`_r12_dexclass.py --class 'Lcom/tencent/mm/modelimage/o3;'`）：
`super=Lhl0/q1;`，`static_fields=0`，`instance_fields=7`，`direct_methods=1`（只有 `j`），`virtual_methods=0`。

反汇编（`_r11_dex7_dis.txt` 行 2127940，`code=0x2b34d4`）：

```
2b34d4:                                        |[2b34d4] com.tencent.mm.modelimage.o3.j:()Lcom/tencent/mm/modelimage/o3;
2b34e4: 2200 590b                              |0000: new-instance v0, Lcom/tencent/mm/modelimage/o3; // type@0b59
2b34e8: 7010 8c1e 0000                         |0002: invoke-direct {v0}, Lcom/tencent/mm/modelimage/o3;.<init>:()V // method@1e8c
2b34ee: 6201 ce8c                              |0005: sget-object v1, Lhl0/o1;.f:Lhl0/o1; // field@8cce
2b34f2: 5b01 e18c                              |0007: iput-object v1, v0, Lhl0/q1;.B:Lhl0/o1; // field@8ce1
2b34f6: 1100                                   |0009: return-object v0
```

**签名**：`public static o3 j()` —— **零形参、零 this**（`access=0x0009 PUBLIC STATIC`），返回 `Lcom/tencent/mm/modelimage/o3;`。
- `registers=2, ins=0, outs=1`：ins=0 佐证无 this 无实参。
- 行为只有三步：`new o3` → `o3.<init>()`（继承 `q1.<init>` 初始化 `a/b/c="" d=1 ...`）→ `q1.B = o1.f`。
- `o1.f` 来自 `hl0/o1.<clinit>`（行 1306986，`P2#26`）：`"VIDEO"` 枚举，ordinal=**2**。故工厂只做了「标记这是视频消息」，其余全交给调用方 `iput`。

> 注意：`_r13_findclass.py` 会误报「无 <init>，不能用 newInstance」，实际反汇编里 `o3.<init>()V` 存在（`method@1e8c`，继承自 `q1` 的无参构造）。工厂 `j()` 是唯一合法入口，别用 `XposedHelpers.newInstance(o3Cls)`。

### 1.2 `bm0/u0.N` 调用 `o3.j` 的字段来源

`u0.N()Z`（`code=0x245908`）是 `NetSceneUploadVideo` 的准备逻辑。`o3` 赋值段在地址 `01d5~01eb`（`_r11_dex7_dis.txt` 行 82991 起，见下）：

```
245cc2: 7100 8d1e 0000                         |01d5: invoke-static {}, Lcom/tencent/mm/modelimage/o3;.j:()Lcom/tencent/mm/modelimage/o3;
245cc8: 0c04                                   |01d8: move-result-object v4
245cca: 5b48 0f14                              |01d9: iput-object v8, v4, Lcom/tencent/mm/modelimage/o3;.E:Ljava/lang/String;   // E = v8
245cce: 1205                                   |01db: const/4 v5, #int 0 // #0
245cd0: 5945 1014                              |01dc: iput v5, v4, Lcom/tencent/mm/modelimage/o3;.F:I                            // F = 0
245cd4: 5515 9c07                              |01de: iget-boolean v5, v1, Lbm0/u0;.q:Z
245cd8: 5c45 1514                              |01e0: iput-boolean v5, v4, Lcom/tencent/mm/modelimage/o3;.K:Z                    // K = u0.q
245cdc: 2205 0105                              |01e2: new-instance v5, Lbm0/c1;
245ce0: 7020 e90f 1500                         |01e4: invoke-direct {v5, v1}, Lbm0/c1;.<init>:(Lbm0/u0;)V
245ce6: 5b45 1214                              |01e7: iput-object v5, v4, Lcom/tencent/mm/modelimage/o3;.H:Lcom/tencent/mm/modelimage/d0;  // H = new c1(u0)
245cea: 1305 1400                              |01e9: const/16 v5, #int 20 // #14
245cee: 5945 eb8c                              |01eb: iput v5, v4, Lhl0/q1;.h:I                                            // q1.h = 20
245cf2: 6e10 83a8 0400                         |01ed: invoke-virtual {v4}, Lhl0/q1;.a:()Lhl0/l1;
245cf8: 0c04                                   |01f0: move-result-object v4
245cfa: 6e10 5da8 0400                         |01f1: invoke-virtual {v4}, Lhl0/l1;.a:()Z   // 真正 execute
```

字段来源归纳（`u0.N` 里 `v1`=this `u0`，`v8` 见 `01d5` 之前的赋值）：

| `o3` 字段 | 值来源 | 语义 |
|---|---|---|
| `E` (String) | `v8`（来自 `u0.f`，见下） | **视频源路径**（`u0.f` 是构造参数 `String`，`u0.<init>` 末尾 `iput v7, u0.f`，v7=构造第一参） |
| `F` (int) | `const/4 0` | 分派用 flag，**恒 0**（见 1.3 修正说明） |
| `K` (boolean) | `u0.q`（`<init>` 默认 true） | 开关位 |
| `H` (d0) | `new bm0/c1(u0)` | 回调（`d0` 接口实现），`c1.a(II)` 内部 `iget u0.f` 上报 |
| `q1.h` (int) | `const/16 20` | constructorId = 20 → `t81/g.i` 走 `bm0/p1` 分支 |

**关键：时长/缩略图不在这里**。`o3.j` 调用处**不传任何参数**，也没有 `G`/`I`/`J`（缩略图 `tj/f` 与时长）的赋值 —— 那些是 `r1.C2` / `m1.C2` / `n3.a` 等**其它调用点**才设的（见 1.3 的 G/J/I 引用表）。

`v8` 的来路（向上回溯 `u0.N`）：
- `[124] iget-object v8, v1, Lbm0/u0;->f:Ljava/lang/String;` —— `u0.f` 即 `u0.<init>(String)` 的第一参，是**视频本地完整路径**（`u0.<init>` 里 `[82] iput-object v7, u0.f`，随后 `[84] b3.i(f)` 反查 `t2` 存到 `u0.n`）。

### 1.3 `o3.E / o3.F / o3.K / o3.H` 字段签名与语义

字段声明（`_r12_dexclass.py`，均 `access=0x1 PUBLIC`）：

| 字段 | 签名 | 语义（据赋值点归纳） |
|---|---|---|
| `E` | `Ljava/lang/String;` | 视频源路径（`u0.f`）。被 `t81/g.i` 在 `h==0/2/20` 三个分支都读（`[24][48][84]`） |
| `F` | `I` | 分派 flag。`t81/g.i` 的 `h==2` 分支读它做第三参。**u0.N 赋值 0；f1.C2 赋值 0；r1.C2 赋值 0**（见下修正） |
| `G` | `Ltj/f;` | 缩略图/转码结果对象。仅 `f1.C2` / `m1.C2` / `r1.C2` 等**转码回传**路径赋值（`field_retCode` 非零时为空） |
| `H` | `Lcom/tencent/mm/modelimage/d0;` | 上传回调（`d0` 接口，只有 `a(II)V`）。u0.N 用 `new c1(u0)`，f1.C2 用 `new e1(f1, tj/f)` |
| `I` | `Ltj/f;` | 另一 `tj/f`（`t81/g.i` `h==2` 分支第 6 参）。u0.N **不赋值** |
| `J` | `Ljava/lang/String;` | 仅 `r1.C2` 赋值 + `t81/g.i` `h==2` 分支读（`[50]`）。u0.N **不赋值** |
| `K` | `Z` | 开关（u0.q / u0 构造默认 true）。`t81/g.i` `h==20` 分支读（`[92]`） |

**⚠️ F 字段取值的修正（重要，`_r14_walk.py dis` 的 `const/4` bug，机理以本节为准）**：
- 现象：旧工具输出 `[476] iput v5, v4, o3.F` 且上一行 `[475] const/4 v5 #4`，看起来 F=4。
- **真实根因（不是「寄存器号误印成字面量」）**：`const/4`（opcode `0x12`）是 **11n 格式、单 code unit**，编码 `w0 = op | A<<8 | lit<<12`，`lit` 是**有符号 4 位**。旧代码写 `u1 >> 12`，而 `each_insn()` 返回的 `u1` 是**第 2 个 code unit**（= 下一条指令的首个 unit），与本条指令无关 —— 所以打出来的是「下一条指令的字节」，偶尔碰巧等于寄存器号只是巧合。正确解码：`lit = hi >> 4`（`hi` 是 w0 高字节），再按 4 位二补码取符号。team-lead 已修入 `_cache/_r14_walk.py`，实测 `MsgRetransmitUI.onCreate` 30 条 `const/4` 原来 25 条打错，修复后全部正确。
- dexdump 原文（`245cce`）：`1205 |01db: const/4 v5, #int 0 // #0`，即 v5=**0**；team-lead 用修好的工具独立复验，`[475] const/4 v5 #0` → `[476] iput v5, o3.F`，结论一致。
- `f1.C2` 的 `[158] iput v13, o3.F` 里 v13 最终值也是 0（原推理路径受同一 bug 影响，本条以**修复后工具 / dexdump 原文**为准，不再展开寄存器覆盖推演）。
- 结论：`o3.F` 在这条原视频链路上恒 **0**，不是 4。手写伪代码填 `0`。

### 1.4 `hl0/q1.h = 20` 的含义

- `q1.h` 字段签名：`Lhl0/q1;->h:I`（`_r12_dexclass.py`），`q1.<init>` **不初始化 h**（只初始化 `a/b/c="" d=1 j/r/s="" t=new m()`）。
- `h` 是 **constructorId**（构造器分派键），与图片链路的 `q1.h=13`（见 `FileSender.kt` `CONSTRUCTOR_ID_IMAGE=13`）同义，是 `t81/g.i(q1)` 的 switch 键。
- `t81/g.i`（`code=0x8bbc8c`）的分派分支：
  - `h==0` → `new bm0/u0(E)` 直接发（旧逻辑，`k1.a=u0`）；
  - `h==2` → `new bm0/o1(E, J, F, G, I, H)`（`NetSceneUploadVideoForCdn`，CDN 视频，6 参构造）；
  - `h==20` → `new bm0/p1(E, F, G, H, K)`（`NetSceneUploadVideoOrigin`，**原视频上传**，5 参构造 `p1.<init>(String,int,Ltj/f,d0,Z)`）。
- 对比值：图片 `h=13`（`qf4/o4.O8`）；视频原链 `h=20`；CDN 视频 `h=2`；旧直发 `h=0`。所以 **20 = 原视频（origin）上传模式**。
- `p1.<init>`（`code=0x23f3f4`）把 `(String f, int g, tj/f i, d0 j, boolean q)` 分别落到 `p1.f/g/i/j/q`，`p1.doScene`（`code=0x23eecc`）再用 `b3.i(p1.f)` 反查 `t2`、`t2.d()` 取路径去上传。

### 1.5 辅助方法

- **`bm0/b3.i(String) → bm0/t2`**（`code=0x2338d8`，`P2#43`）：按**文件名**反查消息实体。`a8.F0(s)` 判空 → `m2.O8().f(s)`。`b3.j(String,String,String)` 是三参版（多了 talker/其它 key）。
- **`bm0/u2.f(String) → bm0/t2`**（`code=0x248f80`，`P2#57`）：拼 `.filename = '...'` 的 SQL 去消息库查 `t2`。所以 **`u0.f` 这个"路径"实为 filename 键**，`t2` 里才带 talker、时长、`upvideo` 路径。
- **`bm0/m2.O8() → bm0/u2`**（`code=0x239e50`，`P2#57`）：单例 `m2.d`（懒加载，`new u2(z84/z)`）。
- **`bm0/t2` 关键字段**（据 `u0.N` / `f1.C2` 大量 `iget` 归纳）：`h()→talker`、`d()→文件名/路径`、`j:J`→本地 id、`n:J`→`msgId`、`t:I`→cdnInfo、`w:I`→视频类型（7=小视频）、`G:q54/rl3`→MMSightExtInfo。

### 1.6 `b3.i` 的查库与空路径行为（本任务最关键判据，逐字节）

**`bm0/b3.i` 签名**：`public static bm0/t2 i(String)`（`code=0x2338d8`，1 形参、无 this、返回 `bm0/t2`）：

```
2338e8: invoke-static {v1}, a8.F0(Ljava/lang/String;)Z   // 判空
2338f0: if-eqz v0, 0008                                  // 非空 → 继续查
2338f4: const/4 v1, #0 ; return-object v1               // 空 → return null
2338f8: invoke-static {}, m2.O8()Lbm0/u2;
233900: invoke-virtual {v0, v1}, u2.f(Ljava/lang/String;)Lbm0/t2;
233908: return-object v1
```

**`bm0/u2.f` 查库实现**（`code=0x248f80`）：
- 拼 SQL：`select <表>.<列…> where <表>.filename = '<转义后的入参>'`。
- **表名**：`u2.k(String)`（`code=0x249e88`，PUBLIC STATIC）拼 SELECT 列，`z2.a(String)`（`code=0x24bd60`）按**文件名后缀**从 `z2.a`（`ArrayList<y2>`）选表，**默认回退 `'videoinfo2'`**。⇒ 查的是 **`videoinfo2` 表**（`VideoInfoStorage`，日志 tag `MicroMsg.VideoInfoStorage`）。
- **查询**：`z84/z.g(sql, [String], 2)` → `Cursor`。
- **结果处理（关键，三段）**：
  1. `[70] if-nez cursor → return null`（cursor 为 null）；
  2. `[73] moveToFirst()` 返回 **false（空结果集）→ `[88] op07 v0, v2` 即 `v0 = null`** → close → `return null`；
  3. `[93] move-exception`（SQL 异常）→ 记日志 `getByFileName error[%s]` → `return null`。

**结论（已证实，非推测）**：`b3.i(path)` 在 **`path` 不在消息库（`videoinfo2.filename` 无匹配行）时返回 `null`**，不抛异常（异常被 catch 吞掉）、不返回空对象。

**对 `u0` 的连锁**：`u0.<init>(String)`（`code=0x2476e0`）里 `[84] b3.i(f)` 存 `u0.n`，`[90] if-eqz n → u0.h=6`（无有效 t2 时降级 flag）。而 `u0.N()` 开头 `[2] iget u0.n` 后**直接** `invoke t2.h()` —— 若 `n==null` 则此处 **NPE**。

**决定性证据：`p1.doScene` 对 `b3.i` 返回 null 的处理**（`code=0x23eecc`，dexdump 原文 `_r11_dex7_dis.txt` 行 77411 起，tag `MicroMsg.NetSceneUploadVideoOrigin` string@9af7）：

```
23eee0: 5460 1d07                              |0002: iget-object v0, v6, Lbm0/p1;.f:Ljava/lang/String; // field@071d   ; 源文件路径
23eee4: 7110 d60f 0000                         |0004: invoke-static {v0}, Lbm0/b3;.i:(Ljava/lang/String;)Lbm0/t2; // method@0fd6
23eeea: 0c01                                   |0007: move-result-object v1
23eef2: 3901 1b00                              |000b: if-nez v1, 0026 // +001b                  ★ 非 null 才走正常路径
---- 以下 = b3.i 返回 null 的失败分支 ----
23eef6: 2201 3b3b                              |000d: new-instance v1, Ljava/lang/StringBuilder;
23eefa: 1a02 d932                              |000f: const-string v2, "Get info Failed file:" // string@32d9
23eefe: 7020 e4ae 2100                         |0011: invoke-direct {v1, v2}, ...<init>:(Ljava/lang/String;)V
23ef04: 6e20 edae 0100                         |0014: invoke-virtual {v1, v0}, StringBuilder.append:(Ljava/lang/String;)...
23ef12: 7130 9668 0807                         |001b: invoke-static {v8, v0, v7}, l2.e:(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)V   ; 打错误日志
23ef18: 5460 2107                              |001e: iget-object v0, v6, Lbm0/p1;.j:Lcom/tencent/mm/modelimage/d0; // field@0721   ; 失败回调
23ef1c: 1231                                   |0020: const/4 v1, #int 3  // #3   ← errType = 3
23ef1e: 12f2                                   |0021: const/4 v2, #int -1 // #ff  ← errCode = -1
23ef20: 7230 d11d 1002                         |0022: invoke-interface {v0, v1, v2}, Lcom/tencent/mm/modelimage/d0;.a:(II)V // method@1dd1
23ef26: 0f02                                   |0025: return v2                                ; doScene 返回 -1
23ef28: 5b61 2307                              |0026: iput-object v1, v6, Lbm0/p1;.o:Lbm0/t2; // field@0723   ← 正常路径从这里继续
```

⇒ `p1.doScene` 在 `b3.i(path)==null` 时**不会崩溃，而是显式失败**：记日志 `Get info Failed file:` → 回调 `d0.a(3, -1)`（errType=3 / errCode=-1）→ `doScene` 返回 `-1`。这与 `u0.N`（无判空直接 NPE）形成对照，但**两条链殊途同归：path 不在库 = 发不出去**。

## 2. 可实现签名表

### 2.1 已实证的调用链（静态反汇编锁定，非推测）

```
sendVideo(path, talker)  目标：造 o3 并触发「原视频上传」
  1. o3 = modelimage/o3.j()                       // static, 无参, 返回 o3
  2. o3.E = path                                  // String（源路径/filename）
  3. o3.F = 0                                     // int（本链恒 0）
  4. o3.K = true                                  // boolean（u0.q 默认 true）
  5. o3.H = new bm0/c1(u0)   ⚠️ 需一个 bm0/u0 实例（且 u0.n 必须非 null，否则 u0.N() 入口 NPE，见 §4.x）
        // 或更贴近 f1.C2：new bm0/e1(f1, tj/f) —— 但 u0 链用 c1
  6. o3.h = 20                                    // q1.h = constructorId（原视频）
  7. l1 = o3.a()                                  // q1.a() → hl0/l1
  8. l1.a()                                       // 真正 execute，返回入队是否成功
```

### 2.2 关键类的可反射签名（均已用 `_r13_sigmap.py` 口径核对：静态方法无 this）

| 调用 | 签名 | ins | 说明 |
|---|---|---|---|
| `o3.j()` | `()Lcom/tencent/mm/modelimage/o3;` | 0 | PUBLIC STATIC，无参 |
| `q1.a()` | `()Lhl0/l1;` | 1 (this) | virtual |
| `l1.a()` | `()Z` | 1 (this) | virtual，真 execute |
| `t81/g.i(q1)` | `(Lhl0/q1;)Lhl0/k1;` | 2 | 分派器，h 决定走 u0/o1/p1 |
| `b3.i(String)` | `(Ljava/lang/String;)Lbm0/t2;` | 1 | static |
| `m2.O8()` | `()Lbm0/u2;` | 0 | static 单例 |
| `u2.f(String)` | `(Ljava/lang/String;)Lbm0/t2;` | 2 | virtual |
| `p1.<init>` | `(Ljava/lang/String;ILtj/f;Lcom/tencent/mm/modelimage/d0;Z)V` | 6 | h=20 时 `t81/g.i` 走这条 |

### 2.3 ⚠️ 尚未闭合的依赖（做伪代码前必须承认）

1. `o3.H` 需要 `bm0/u0` 实例（`new c1(u0)`），而 `u0.<init>(String)` 会立即 `b3.i(f)` 反查 `t2` —— **`u0.f` 必须是消息库 `videoinfo2` 里已存在的视频 filename**，否则 `t2` 为 null，`u0.N` 开头 `t2.h()` 直接 NPE（见 1.6）。
2. **talker 不从 `o3` 字段来**：`t81/g.i` 的三条分支（u0/o1/p1）都不设 talker，talker 在 `t2.h()` 里（`t2` 来自按 filename 查库）。⇒ `sendVideo(path, talker)` 若要「发到新 talker」，靠造 `o3` 字段**无法改 talker**，需另走 `p1` 的 `t2` 或确认 `u0.f` 本身带 talker 语义（⚠️ 推测，见 §4）。

### 2.4 `canResendFile` 对 VIDEO 的可判定结论

**不能放宽**（✅ 已证，非推测）。结论一句话写死：

> **VIDEO 不能放宽 —— `path` 必须是消息库（`videoinfo2.filename`）中已存在的视频 filename；否则 `p1.doScene` 在 `[11]` 判 null，走 `Get info Failed file:` 分支，回调 `a(3,-1)` 且 `doScene` 返回 -1。**

完整链条（逐字节实证）：

- 「降级重发到新目标」输入的是**本地文件路径**，不是 `videoinfo2.filename` 库键 ⇒ `b3.i(path)` 返回 `null`（§1.6 三种 null 分支已证实）。
- 走 `u0` 链：`u0.N` 开头 `iget u0.n` 后 `invoke t2.h()` 无判空 ⇒ **NPE**（§1.6）。
- 走 `p1` 链（`h=20` 分派到的正是这条）：`p1.doScene` `[11]` 判 null → 记 `Get info Failed file:` → 回调 `a(3,-1)` → 返回 -1（§1.6 决定性证据）。
- 即便 path 恰好命中库（转发自己收到的视频），`t2.h()`（= `t2.q`，talker）也是**原会话** talker，会发错方向，仍不是「发到新目标」。

⇒ 唯一能放宽的前提是**闭合一条「不查库、显式传 talker」的第二条链**（如直接构造 `bm0/t2` 或让 `p1`/`u0` 走显式 talker 路径）。**该第二条链当前未取证**，故保持 false。

## 3. 与已通路径对照（音频/图片/普通文件 vs 视频）

| 维度 | 图片 (type=3) | 普通文件 (appmsg type=6) | 视频 (type=43) |
|---|---|---|---|
| 入口 | `qf4/o4.O8` → `r1.a(o1.IMAGE)` 造普通 `q1` | `qf4/r5.j` → `u0.A/B/D` → `MsgInfo.setType(0x41000031)` | `o3.j()` 工厂 → `q1.h=20` → `t81/g.i` → `bm0/p1` |
| builder 类 | `hl0/q1`（普通） | 无（走 `te0/q` + WXFileObject） | `modelimage/o3`（`q1` 子类） |
| constructorId (`q1.h`) | **13** | 不适用（`r5.j` 不设 q1.h） | **20**（原视频） |
| 目标 talker 来源 | `q1.h(toUser)` 即 `q1.a`（**显式字段**） | `r5.j` 第 3 参 LinkedList（**显式参数**） | ⚠️ `t2.h()`（按 filename 查库，**不显式**） |
| 源路径字段 | `q1.j`（显式，需 `setObjectField`） | `WXFileObject.filePath`（显式） | `o3.E`（显式，但须是库里已有 filename） |
| 是否已真机验证 | ✅ `FileSender.send` 已实现 | ✅ 探针 `FileSenderB.probe` 已实现（未接正式业务） | ❌ 仅静态取证，未实现 |
| 关键缺口 | 无 | 需验落库 type=1090519089 | 已证：`path` 必须命中 `videoinfo2`，否则 u0.N NPE / p1.doScene 返回 -1 |

**音频 (type=34)**：任务背景说「音频有已通路径可参照」，但 `ForwardLogic.canResendFile` 里 `VOICE` 判 false，且本轮未单独取证音频链路 —— 音频与视频**共用** `bm0/` 上传框架（`t2` / `u0`），但无独立 `o3` 工厂对应物，是否走 `r1.a(o1.???)` 未闭合。⚠️ 音频不纳入本次结论。

**视频差在哪一步（一句话）**：视频不是「缺 builder」，builder（`o3`）与分派（`t81/g.i`）都已闭合，**差在 talker 与源文件这两者的耦合** —— 图片/文件都把 talker 当显式参数传，视频却靠 `b3.i(path)` 查库拿 `t2`，`path` 必须命中消息库，这与「降级重发到新目标」的诉求存在张力，是唯一未闭合点。

## 4. ⛔ 边界声明

### 实证（静态反汇编逐字节锁定，可信度高）

1. `o3.j()` 是无参静态工厂，返回 `o3`，只设 `q1.B=o1.VIDEO`。
2. `o3` 七个字段签名：`E:String / F:int / G:Ltj/f; / H:Lcom/tencent/mm/modelimage/d0; / I:Ltj/f; / J:String / K:Z`。
3. `u0.N` 的 o3 赋值段：`E=v8(u0.f)`, `F=0`, `K=u0.q`, `H=new c1(u0)`, `q1.h=20`。
4. `q1.h` 是 constructorId，`t81/g.i` 按 `h` 分派 `0→u0`、`2→o1(CDN)`、`20→p1(原视频)`。
5. `t81/g.i` 第一句 `instance-of q1, o3`，非 o3 直接返回 —— 这是 `ForwardLogic.kt:287` 描述「普通 q1 走 VIDEO 会 NPE」的根因，已被本次反汇编直接证实（`8bbc8c` 起）。
6. **talker 耦合（✅ 已证，team-lead 独立闭合）**：`b3.i(path)` 查 `videoinfo2` 表，path 不在库返回 `null`；`p1.doScene` `[11]` 判 null → `Get info Failed file:` → 回调 `d0.a(3,-1)`（errType=3/errCode=-1，dexdump `1231`/`12f2` 已核对）→ `doScene` 返回 -1。`t2.h()` 返回 `t2.q`（talker），空则 `""`。`d0.a` 是 `public abstract (II)V`（NO-CODE 失败回调）。

### 4.x ⛔ o3 自构造的硬阻塞：`u0.n` 必须非 null

`u0.N()` 开头逐条（`acc=0x0011`，`code_off=0x245908`，主会话已复算）：

```
[   0] op08          v1                                        // move-object/from16 v1, v?
[   2] iget-object   v0, v1  Lbm0/u0;->n:Lbm0/t2;              // 直接取字段，无判空
[   4] invoke-virtual {n=0}   Lbm0/t2;->h()Ljava/lang/String;   // ★ 直接调 h()
[   7] move-result-object v0
[   8] invoke-static {n=0}    Lvj0/e2;->P(Ljava/lang/String;)Z  // 判空/空白串
[  11] move-result    v2
[  12] const-string   v3  'MicroMsg.NetSceneUploadVideo'
[  14] const/4        v4  #0
[  15] if-eqz         v2  -> +16 (abs 31)                      // 空 ⇒ 记日志 'cdntra not use cdn user:%s' 后 return 0
[  17] invoke-virtual/range {n=1} Lbm0/u0;->R()Ljava/lang/String;
```

**精确结论**（照抄自 team-lead 判空结果）：

> `u0.N()` 在 `[2]` 取 `u0.n` 后**不判空**，`[4]` 直接 `invoke-virtual t2->h()`。
> 所以 `u0.n == null` 时是 **NPE**，不是「拿到空字符串」。
> 而 `u0.n` 由 `u0.<init>` 的 `[88] iput-object v7, u0.n` 写入，其值就是 `[84] b3.i(u0.f)` 的返回 —— `b3.i` 对空/非法路径返回 `null`。
> ⇒ **`u0.f` 必须是非空且能在消息库查到的视频 filename，否则构造 `u0` 时 `u0.n` 就是 null，随后 `u0.N()` 必 NPE。**

补充说明：

1. `b3.i` 返回 null 的两个条件：`a8.F0(s)` 为真（空串/空白）直接 `return null`；或 `m2.O8().f(s)`（`u2.f` 查 `videoinfo2.filename`）查不到 → `moveToFirst()==false` → `return null`（§1.6 已逐字节证实）。
2. 即便绕过 `u0`、直接走 `p1`，`b3.i` 为 null 一样在 `p1.doScene [11]` 失败返回（`Get info Failed file:` + `a(3,-1)` + `return -1`，§1.6 决定性证据）。
3. 这条正好解释了「`c1` 构造会拿到什么未证」—— 实际**不是 `c1` 拿到什么，而是 `u0` 先 NPE，根本走不到 `c1`**（`o3.H = new c1(u0)` 里的 `u0` 在 `u0.N()` 入口就崩了）。

**一句话结论**：两条分支（`u0` 路线 NPE、`p1` 路线失败返回）都要求 `path` 命中消息库 ⇒ **VIDEO 不能放宽**。

### 4.y ✅ 已证：即便 `path` 命中库，talker 也是原会话 ⇒ 必然发错方向

`t2.q` 就是 talker，四条独立证据（team-lead 已逐条反汇编核实）：

1. `u0.N` 的日志 `'%s cdntra not use cdn user:%s'` 把它称为 **user**（第二个 `%s` 就是 `[2] iget u0.n` → `[4] t2.h()` 的返回）；
2. `t2.h()` 直接 `iget-object t2.q`；
3. `t2.a(Cursor)` 的 `[123] Cursor.getString(16)` → `[127] iput t2.q`（= **消息表第 16 列**）；
4. `b3.s(String,int,String,String,I,J)Z`（`acc=0x0009`）插入消息时把**第 3 个 String 形参** `iput` 到 `t2.q`：

```
[   5] iput-object v2, v0  Lbm0/t2;->a:Ljava/lang/String;
[   7] iput       v3, v0  Lbm0/t2;->m:I
[   9] iput-object v4, v0  Lbm0/t2;->q:Ljava/lang/String;   ; 第 3 个形参
```

⇒ **`path` 不在库 ⇒ NPE / 返回 -1，发不出去；`path` 在库 ⇒ 能发，但发往原会话，发错人。**

「视频降级重发到新目标」在本链路下**没有任何一条可行路径**。

⛔ **不要试图改 `t2.q` 绕过** —— `t2` 是 DB 实体的内存副本，改它不落库。
正解是 **`WXVideoObject` + `sendAppMsg`**（与普通文件同一套 appmsg 链，见 `FileSenderB`），从零构造，不碰 `o3`/`t2`。

### 推测（⚠️ 标注，未逐字节证实）

- ⚠️ **第二条链是否存在**：`p1.doScene` 到 CGI 是否还有「显式传 talker、不查库」的路（当前只看到 `t2.h()` 一条），未追完 —— 这是未来放宽 VIDEO 的唯一方向。
- ⚠️ **`F` 字面量**：已用 dexdump 原文修正为 0，但 `_r14_walk.py dis` 的 `const/4` 显示 bug（11n 单 code unit，旧代码误读第 2 个 unit）提示：**所有经该工具读出的 `const/*` 字面量都要回 dexdump 原文或修复后工具复核**，本次只复核了 F 相关几条。
- ⚠️ **音频链路**：未取证，不纳入结论。

### 明确不做

- 不改任何 .kt 源码（本任务只写文档）。
- **`ForwardLogic.canResendFile` 对 VIDEO 保持 `false`**（✅ 已证，见 §2.4 / §4.x / §4.y）：`path` 不在库 ⇒ NPE/返回 -1 发不出去；`path` 在库 ⇒ 发往原会话发错人。**本链路下「降级重发到新目标」无任何可行路径**。**建议在 `ForwardLogic.kt` 的 `canResendFile` 注释里引用此判据**（代码改动由 team-lead 定，本任务不动代码）。正解是 `WXVideoObject + sendAppMsg`（appmsg 链），与普通文件同套。

## 5. 真机验证方案（最小探针集）

> 均为 `am broadcast` 设计，**不真跑**。每条写明「什么日志算通过」。

### 探针 1：就绪性（类+签名可反射）

```
am broadcast -a com.lx.wxgroup.PROBE \
  --es op forward --es sub videordy
```

- **通过判据**：日志出现 `videordy=ok`；否则按失败顺序报 `missing:<类名>`。
- 要探测的类：`com.tencent.mm.modelimage.o3`（有 `j`）、`hl0.q1`、`hl0.l1`、`bm0.u0`、`bm0.c1`、`bm0.t2`、`bm0.b3`、`bm0.m2`、`bm0.p1`、`t81.g`。
- 目的：把「类名漂移 / 版本变了」和「参数错」分开，别让 `videordy` 假阳性。

### 探针 2：o3 工厂 + 字段写 + 分派（不真发，只走到 `t81/g.i`）

```
am broadcast -a com.lx.wxgroup.PROBE \
  --es op forward --es sub videobuild --es path "<库里已有视频 filename>"
```

- 行为：`o3=j()` → 写 `E=path, F=0, K=true, h=20`（**不设 H**，纯验证分派）→ 调 `q1.a()` 拿 `l1`，打印 `l1` 非空 + `l1.getClass()`。
- **通过判据**：日志 `videobuild l1=<类名> `（应含 `hl0/l1` 或 `bm0/p1` 相关字样）且**无 NPE**。
- 若此处 `l1` 为 null 或抛 NPE，说明 `t81/g.i` 的 `instance-of` 判定 / `q1.B` 枚举不对 —— 定位「工厂是不是真的造出 o3 子类」。

### 探针 3：完整原视频入队（查库确认）

```
am broadcast -a com.lx.wxgroup.PROBE \
  --es op forward --es sub videosend --es path "<库里已有视频 filename>"
```

- 行为：完整走 2.1 的 8 步（含 `new c1(u0)` + `l1.a()`）。
- **通过判据**（逐条，缺一不可）：
  1. 日志 `videosend queued=true`（`l1.a()` 返回 true = 入队成功）；
  2. 日志出现 `MicroMsg.NetSceneUploadVideoOrigin`（`bm0/p1` 的 tag，证明 `h=20` 分派正确）；
  3. `select type,isSend from message where talker=? order by createTime desc limit 1` ⇒ 视频消息 `isSend=1`；
  4. 对端能收到并播放。
- ⚠️ 若 `queued=true` 但库里无新消息：判定「入队 ≠ 送达」，回到 `t2` 的 talker 耦合（§4 推测项）排查。

### 探针约束

- 三条串行，别并发（防 TPM）。
- 每条先 `videordy` 再上，避免在类缺失时误判「链路不通」。

## 进度日志

- [DONE] 建文档骨架（0~5 节 + 进度日志占位）
- [DONE] 结论先行（o3.j 无参工厂、h=20 分派、F=0 修正、talker 耦合缺口）
- [DONE] 1.1 o3.j() 反汇编与签名（无参静态、只设 q1.B=o1.VIDEO）
- [DONE] 1.2 u0.N 调 o3.j 字段来源（E=u0.f, F=0, K=u0.q, H=new c1, h=20）
- [DONE] 1.3 o3.E/F/K/H/G/I/J 签名与语义（含 F 字面量 bug 修正）
- [DONE] 1.4 q1.h=20 含义（constructorId，0/2/20 三路分派，20=原视频）
- [DONE] 1.5 辅助方法 b3.i / u2.f / m2.O8 / t2 字段
- [DONE] 1.6 b3.i 查库与空路径行为（videoinfo2 表、path 不在库返回 null、u0.N 无判空 NPE）
- [DONE] 2.4 canResendFile 对 VIDEO 可判定结论（不能放宽，理由已证实）
- [DONE] 修正 const/4 bug 机理（11n 单 code unit、lit=hi>>4、team-lead 已修工具）
- [DONE] 升级 talker 耦合为已证（p1.doScene null 分支，errType=3/errCode=-1 已核对 dexdump 1231/12f2）
- [DONE] §4.x 新增 u0.n 硬阻塞小节（u0.N 无判空必 NPE，c1 并非关键、u0 先崩）
- [DONE] §2.4/§4 落死「VIDEO 不能放宽」，建议 ForwardLogic 注释引用判据（代码由 team-lead 定）
- [DONE] §4.y 补入 t2.q=talker 四条证据（发错方向铁证，本链路无可行路径，正解 WXVideoObject+sendAppMsg）
- [DONE] §0 结论先行最终判定「⛔ 视频降级重发不可行，canResendFile 保持 false」—— 本轮收工
- [DONE] 2 可实现签名表（调用链 + 反射签名 + 未闭合依赖）
- [DONE] 3 与已通路径对照（图片 13 / 文件 r5 / 视频 20，talker 耦合差异）
- [DONE] 4 边界声明（实证 5 条 / 推测 4 条）
- [DONE] 5 真机探针集（videordy / videobuild / videosend 三条）