# R75 独立反汇编锚点复核（重新取证）

> 本轮纪律：不读任何历史结论文档的 code_off/锚点数值，从 `_cache/dex/classes*.dex` 二进制**自行解析** dex 并**自行计算** code_off。
> 反汇编器为本人从零编写的 `r75_dexparse.py`（存放于系统临时目录，非仓库），逐字节解码，不引用任何现成反汇编文本中的地址结论。

## 0. 方法论与工具

- 数据源：`_cache/dex/classes.dex .. classes15.dex`（微信 8.0.48 / versionCode 2588，权威源 `_cache/mm.apk`）。
- 工具：自写 Python dex 解析器 + Dalvik 反汇编器。
  - 解析 header / string_ids / type_ids / proto_ids / field_ids / method_ids / class_defs / class_data。
  - code_off 直接取自 class_data 中 encoded_method 的 `code_off` uleb 字段（该值即 dex 文件内 code_item 的绝对字节偏移）。
  - 反汇编覆盖全部 opcode（0x00–0xff），35c/3rc/45cc 等格式的 method ref 与寄存器 nibble 布局按官方 Dalvik 规范解码。
- **关键发现（影响全部锚点）**：微信 dex 的 method_ids 表存在**跨类复用/错位**现象——某类的 class_data 所引用的 method_idx，其 method_id 的 class_idx 可能指向**无关类**（例：`classes.dex` 中 `Lcom/tencent/mm/plugin/finder/live/widget/fa;` 类的方法 `F0(String)Z` 复用了 `a8.F0` 的 method_id 条目）。
  - 后果：仅凭「method_id 的类名 + 方法名 + 签名」检索会命中大量**假阳性**（同签名方法在其他 dex 里被复用为别的类的不同方法体）。
  - 对策：**以 class_def.class_data 为权威**（即：先找到 class_def 的 class_idx 对应目标描述符，再列其 class_data 里声明的方法）来定位唯一真正的 code_off。
- 每个锚点结论给出：dex 文件 + code_off（十六进制/十进制）+ 逐字节解码证据 + 结论 + 置信度。

## 1. 锚点 A：`com.tencent.mm.sdk.platformtools.a8.F0(String):boolean`

**dex 文件**：`classes8.dex`
**code_off**：`0x49d544`（十进制 4838724）
**定义类**：`Lcom/tencent/mm/sdk/platformtools/a8;`（class_idx=8058，super=Object），method_idx=27801，access_flags=0x9（public static），proto=`(Ljava/lang/String;)Z`。

**逐字节解码**（code_item 头部：registers=1, ins=1, outs=1, insns_size=13）：

| pc | 字节(hex) | 指令 | 说明 |
|----|-----------|------|------|
| 0x00 | `0038 000b` | `if-eqz v0, +11` | op=0x38，若 v0==null 跳到 0x0b |
| 0x02 | `106e a012 0000` | `invoke-virtual {v0}, String.length()I` | op=0x6e(35c)，method#0xa012=`Ljava/lang/String;->length()I` |
| 0x05 | `000a` | `move-result v0` | v0 = length |
| 0x06 | `003c 0003` | `if-gtz v0, +3` | op=0x3c，若 v0>0 跳到 0x09 |
| 0x08 | `0328` | `goto +3` | 跳到 0x0b |
| 0x09 | `0012` | `const/4 v0, #0` | v0=0(false) |
| 0x0a | `000f` | `return v0` | 返回 false |
| 0x0b | `1012` | `const/4 v0, #1` | v0=1(true) |
| 0x0c | `000f` | `return v0` | 返回 true |

**控制流还原**：
- `v0 == null` → 0x0b → 返回 **true**
- `v0.length() > 0`（非空）→ 0x06 分支到 0x09 → 返回 **false**
- `v0.length() == 0`（空串）→ 0x06 不分支，落到 0x08 `goto` → 0x0b → 返回 **true**

**结论**：语义为 `return (s == null) || (s.length() == 0);`，即 **isNullOrEmpty**（空指针或空字符串返回 true）。方法体内**不含**任何 `"nil"` 之类字符串常量的比较，因此不是带 nil 特殊值判断的变体。

**置信度**：高（逐指令解码，无歧义；method ref 0xa012 已解析为 `String.length()I`）。

> 附带发现：在 classes.dex / classes2.dex / classes7.dex 等文件中，`find_method` 亦命中同签名 `a8.F0(String)Z`，但那是**微信 method_id 跨类复用**造成的假阳性（如 classes.dex 中 `Lcom/tencent/mm/plugin/finder/live/widget/fa;` 的某个真实方法 `F0(String)Z` 复用了 `a8.F0` 的 method_id 条目，其方法体是 Kotlin Paint 绘制逻辑，与 a8 无关）。真正唯一的定义类在 classes8.dex。

## 2. 锚点 B：`hl0.j2.O()`

**重要澄清（命名纠正）**：锚点所写 `hl0.j2` 实为 **`Lcom/tencent/mm/modelimage/j2;`**（图片 CDN 上传 NetScene，super=`Lcom/tencent/mm/modelbase/k1;`），并非 `Lhl0/j2;`（后者是 `classes10.dex` 里的 `MicroMsg.SyncServiceHandler`，方法为 a/b/c/d，与图片链路无关）。证据：`modelimage/j2` 的 method_ids 中恰好存在 `O(Lcom/tencent/mm/modelimage/r0;I)Z` 与 `R()Lcom/tencent/mm/modelimage/r0;`，且其代码体与锚点 B/C 描述逐条吻合（见下）。

**dex 文件**：`classes7.dex`
**方法**：`Lcom/tencent/mm/modelimage/j2;->O(Lcom/tencent/mm/modelimage/r0;I)Z`（method_ids #7706）
**code_off**：`0x2acc44`（十进制 2804804）
**签名**：入参 `(Lcom/tencent/mm/modelimage/r0; I)`，返回 `Z`（boolean）。code_item：registers=29, ins=3, outs=8。

**逐项回答**（均来自 0x2acc44 逐指令解码）：

**(1) 开头是否读 `r0.u`？——否。** 方法体前两条：
```
0004: iget-object v2, v0, Lcom/tencent/mm/modelimage/j2;->u:Lcom/tencent/mm/storage/h9;   ← 读 this.u（消息对象 h9），非 r0.u
```
`r0->u`（`Ljava/lang/String;`）首次被读是在 `0x0036`（`iget-object v4, v1, Lcom/tencent/mm/modelimage/r0;->u:Ljava/lang/String;`），位于首个 `ok0/m1.n(1)` 短路闸门（0x0030）**之后**，属方法中段而非开头。

**(2) `a8.F0` 的返回值如何参与分支**：
```
0036: iget-object v4, v1, r0->u:String
0038: invoke-static {v4}, a8->F0(Ljava/lang/String;)Z
003b: move-result v4
003c: if-eqz v4, +26        ← F0 返回 false（即 r0.u 非空）时跳 +26 → 0x0056（构造 CDN task 段）
003e: invoke-static {}, Lok0/s2;->e9()Lok0/m1;   ← F0=true（r0.u 为空）时不跳，落此处
0042: invoke-virtual {v2, v8}, Lok0/m1;->n(I)Z    ← 二次 n(1)，仅用于日志
0050: const-string v2, "cdntra not use cdn flag:%b getCdnInfo:%s"
0055: return v6                                     ← early return false
```
即：`a8.F0(r0.u)` 返回 **true**（r0.u 为 null/空）→ `if-eqz` 不跳 → 落到 `0055 return v6(0)`（early return **false**，打 "not use cdn"）；返回 **false**（r0.u 非空）→ 跳 0x0056，进入「构造 CDN task / 解析 z3(r0.e)」段。与历史 §9.5 纠错后的措辞一致。

**(3) `ok0/m1.n(1)` 短路位置**：
```
002b: invoke-static {}, Lok0/s2;->e9()Lok0/m1;    ← 获取 ok0/m1 单例
0030: invoke-virtual {v4, v8}, Lok0/m1;->n(I)Z    ← n(1)，v8=1
0033: move-result v4
0034: if-nez v4, +34        ← n(1) 返回【非 0】（CDN 全局开关开）时短路跳 +34 → 0x0056，直接构造 CDN task，跳过 r0.u/a8.F0 判空段
```
即：`ok0/m1.n(1)!=0` → 短路到 CDN task 构造；`n(1)==0` → 不跳，进入 (2) 的 `r0.u` 判空段。`ok0/m1.n(I)Z` 定义于 `classes7.dex`，code_off `0x7b55a0`。

**(4) `field_fullpath` 是否等于 `z3(r0.e)`？** —— 是（字段真实存在，且确由 `z3(r0.e)` 结果填充）。
- `z3` = `Lcom/tencent/mm/modelimage/b1;->z3(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)Ljava/lang/String;`（method_ids #7615，code_off 0x2a6ae4，即锚点 D 的 SERVERID:// 处理器）。
- O() 中连续四次调用 `b1.z3(...)`（0x0076/0x0080/0x008a/0x0094），分别以 `r0.e / r0.f / r0.g / r0.j` 作为首个参数，把「相对/服务端 ID」解析为磁盘全路径。
- 结果写入 `Ltj/k;`（CDN 上传任务 `task_NetSceneUploadMsgImg`）的字段：`field_fullpath`（`Ljava/lang/String;`，真实存在于 tj/k 字段表）、`field_fileId`、`field_thumbpath`、`field_midimgpath` 等。
- 结论：`field_fullpath`（tj/k 字段）由 `z3(r0.e)` 等结果填充，语义成立。

**置信度**：高（逐指令解码 + 字段表佐证）。

## 3. 锚点 C：`hl0.j2.R()` 与 doScene 读图链

**命名纠正同上**：`hl0.j2` = `Lcom/tencent/mm/modelimage/j2;`。

**`R()` 方法**：`Lcom/tencent/mm/modelimage/j2;->R()Lcom/tencent/mm/modelimage/r0;`（method_ids #7709），`classes7.dex`，code_off **`0x2ad864`**。
逐指令（registers=4, ins=1, outs=2）：
```
0000: iget-object v0, v3, j2->q:Lcom/tencent/mm/modelimage/r0;     ← 读缓存字段 q
0002: if-nez v0, +18                                            ← 非空则直接返回
0004: invoke-static {}, d3->O8()Lcom/tencent/mm/modelimage/b1;   ← 取 ImgInfoStorage 单例
0008: iget-wide v1, v3, j2->i:J                                 ← 读 j2.i（long，imgLocalId）
000a: invoke-static {v1,v2}, Long.valueOf(J)
000e: invoke-virtual {v0,v1}, b1->o2(Long)Lcom/tencent/mm/modelimage/r0;  ← o2(j2.i)
0012: iput-object v0, v3, j2->q:r0                              ← 写回缓存字段 q
0016: return-object v0
```
**结论**：`R()` 语义 = `if (q==null) q = b1.o2(Long.valueOf(i)); return q;`，即 `o2(j2.i)` 的带缓存封装。`b1.o2` = `Lcom/tencent/mm/modelimage/b1;->o2(Ljava/lang/Long;)Lcom/tencent/mm/modelimage/r0;`（method_ids #7601，code_off 0x2a52a0）。**「R() 是不是 o2(j2.i)」→ 是。**

**doScene 读图链**：`doScene(Lcom/tencent/mm/network/q;Lcom/tencent/mm/modelbase/s0;)I`（method_ids #7717），code_off **`0x2ad99c`**（registers=35, insns=1529）。关键字节：
```
028c: iget-object v4, v13, r0->e:String
0291: invoke-virtual {v3,v4,v6,v6,v7}, b1->z3(String,String,String,Z)String   ← z3(r0.e)
0299: iget-object v9, v13, r0->g:String
029b: invoke-virtual {v4,v9,...}, b1->z3(...)                                  ← z3(r0.g)
029f: iget-object v9, v13, r0->f:String
02ad: invoke-virtual {v9,v10,...}, b1->z3(...)                                 ← z3(r0.f)
02b0: invoke-static {v3}, Lcom/tencent/mm/vfs/o6;->l(Ljava/lang/String;)J     ← o6.l(路径) 取长度
02ba: invoke-static {v4}, Lcom/tencent/mm/vfs/o6;->l(Ljava/lang/String;)J
0569: invoke-static {v2,v3,v1}, Lcom/tencent/mm/vfs/o6;->N(Ljava/lang/String;II)[B  ← o6.N 读字节
```
**结论**：读图字节链 = `b1.z3(r0.e/f/g) → vfs/o6.l(path)（长度）→ vfs/o6.N(path,off,len)[B（真正读字节）`。即锚点所述 `z3(r0.e/f/g) → o6.l()` 成立，且补充：真正读字节的是 `o6.N(String,int,int)[B`（`com/tencent/mm/vfs/o6;`）。
- `o6.l` = `Lcom/tencent/mm/vfs/o6;->l(Ljava/lang/String;)J`
- `o6.N` = `Lcom/tencent/mm/vfs/o6;->N(Ljava/lang/String;II)[B`

**`ori`(arg3) 流向**：doScene 入参仅 2 个（`network/q` + `modelbase/s0`），无「arg3」。锚点所述 `ori` 未在 `classes7.dex` 方法/字段名中出现（全 dex 无 `ori` 相关方法，仅 r73 反汇编中出现常量字符串 `'.msg.img.$originsourcemd5'`，对应「原图 source md5」概念）。据此推断 `ori` = 上一轮反编译产物里的局部变量/「原图(original)」语义，并非 dex 中的符号；doScene 内「原图」对应 `r0.f`（经 `z3(r0.f)` 解析后被 `o6.l/o6.N` 当文件读取）。此项在对照节再与历史结论核对。

**置信度**：R() 与 doScene 链为高；`ori` 命名映射为中（受上一轮工具命名影响）。

## 4. 锚点 D：`b1.R5` / `b1.z3`(SERVERID://) / `b1.Q2` / `b1.a3`

**命名澄清**：`b1` = `Lcom/tencent/mm/modelimage/b1;`（super=`Lk74/q0;`，即 `ImgInfoStorage` 单例），定义于 **`classes7.dex`**。

四个方法（均 method_ids 有效条目，code_off 由 class_data 逐条解出，并经「寄存器 ins 数 + 代码内容」双重校验）：

| 方法 | method_ids# | 签名 | code_off | 校验 |
|---|---|---|---|---|
| `R5` | #7576 | `R5(String,String,int,int,int,PString,PInt,PInt)J` | **0x2a710c** | ins=9（this+8 参），`new-instance Lfv/t0` + 写字符串/int 字段（图片落盘入参组装） |
| `z3` | #7615 | `z3(String,String,String,Z)String` | **0x2a6ae4** | ins=5，首行即 `a8.F0` + `"SERVERID://"` + `startsWith` |
| `Q2` | #7575 | `Q2(String,J)r0` | **0x2a35c4** | ins=4（String 1 + long 2 + this 1），`new r0` + `a1.b(String)`（按 msgSvrId 查 ImgInfo2） |
| `a3` | #7583 | `a3(String,J)r0` | **0x2a36b0** | ins=4，同构 `new r0` + `a1.b(String)` |

**`z3` 遇 `SERVERID://` 是否调 `Y2(msgSvrId)`？——是。** z3（0x2a6ae4）关键字节：
```
0000: invoke-static {v12}, a8->F0(String)Z            ← 先判空
0008: const-string v0, "SERVERID://"
000a: invoke-virtual {v12,v0}, String->startsWith(String)Z
000e: if-eqz v0, +49                                    ← 非 SERVERID:// 跳到缩略图分支
0012: invoke-virtual {v12,v0}, String->substring(I)     ← substring(11) 取 msgSvrId
0016: invoke-static {v0}, Long.valueOf(String)
001a: invoke-virtual {v0}, Long->longValue()J
001e: invoke-virtual {v11,v2,v3}, b1->Y2(J)Lcom/tencent/mm/modelimage/r0;   ← Y2(msgSvrId)！
0022: iget-object v0, v0, r0->e:String                 ← 取 r0.e
```
`Y2` = `Lcom/tencent/mm/modelimage/b1;->Y2(J)Lcom/tencent/mm/modelimage/r0;`（method_ids #7581），code_off **0x2a363c**（其体内含 `const-string "ImgInfo2"`、`"msgSvrId=?"`，即按 msgSvrId 查 ImgInfo2 表）。

**「我方 P3 拿字节」可反射入口**：图片字节读取的反射入口为
- `Lcom/tencent/mm/modelimage/b1;->q1(J[B Lcom/tencent/mm/pointers/PString;Lcom/tencent/mm/pointers/PInt;Lcom/tencent/mm/pointers/PInt;)Z`（method_ids #7603，code_off 0x2a2fa0，体内构造 `"SERVERID://"+msgId` → `z3(...)` → `x.B([B)Bitmap`），参数 `(long msgId, byte[] 输出缓冲, PString/PInt/PInt 出参)`，返回 `boolean`；
- 或底层 VFS 读：`Lcom/tencent/mm/vfs/o6;->N(Ljava/lang/String;II)[B`（`(String path, int offset, int length) → byte[]`）。
> 注：`classes7.dex` 中唯一字面名为 `P3` 的方法是 `Lcom/tencent/mm/storage/h4;->P3(Ljava/lang/String;)Z`（返回 boolean，非取字节）。故「P3」更可能是上一轮分析的阶段代号（P1/P2/P3…），而非方法名；此处在对照节再与历史结论对齐。

**置信度**：四个方法 code_off + z3→Y2 为高；「P3」具体所指为中。

## 5. 锚点 E：`r0.l` / `r0.n` / `b1.u8`

**命名澄清**：`r0` = `Lcom/tencent/mm/modelimage/r0;`（即 `ImgInfo2` 数据对象），定义于 `classes7.dex`。

**(1) `r0.l` 写哪个字段**：`Lcom/tencent/mm/modelimage/r0;->l(Ljava/lang/String;)V`（method_ids #7853），code_off **0x2b3e94**：
```
0000: iput-object v1, v0, r0->j:Ljava/lang/String;    ← 写字段 j（String）
0003: iput-boolean v1, v0, r0->P:Z                     ← 置 P=true（脏标记）
```
**结论：`r0.l` 写字段 `j`（非 f）**，同时置 `P`。

**(2) `r0.n` 写哪个字段**：`Lcom/tencent/mm/modelimage/r0;->n(Ljava/lang/String;)V`（method_ids #7855），code_off **0x2b3ed4**：
```
0000: iput-object v1, v0, r0->f:Ljava/lang/String;    ← 写字段 f（String）
0003: iput-boolean v1, v0, r0->B:Z                     ← 置 B=true
```
**结论：`r0.n` 写字段 `f`**，同时置 `B`。

字段类型确认：`r0.j` 与 `r0.f` 均为 `Ljava/lang/String;`。

**(3) `b1.u8` 是否持久化 ImgInfo2**：`Lcom/tencent/mm/modelimage/b1;->u8(Ljava/lang/Long;Lcom/tencent/mm/modelimage/r0;)I`（method_ids #7610），code_off **0x2a5398**，ins=3。关键字节：
```
0008: invoke-virtual {v8}, r0->c()Landroid/content/ContentValues;   ← r0 转 ContentValues
000c: invoke-virtual {v1}, ContentValues->size()I
0010: if-nez v2, +4                                              ← 空则返回 1
0014: invoke-virtual {v7}, Long->longValue()J
0018: invoke-static {v2,v3}, a1->a(J)String                       ← 取表名（ImgInfo2 表）
002e: iget-object v3, v6, b1->o:Lz84/z;                          ← DB 句柄
0030: const-string/jumbo v4, "id=?"
0033: invoke-virtual {v3,v2,v1,v4,v7}, z84/z->c(String,ContentValues,String,[String)I  ← UPDATE ... WHERE id=?
005b: invoke-virtual {v8}, r0->a()V                              ← 清脏
005e: invoke-virtual {v6}, k74/q0->doNotify()V                   ← 通知观察者
```
**结论：`b1.u8` 确持久化 ImgInfo2**——把 `r0`（ImgInfo2）转 ContentValues 后按 `id=?` UPDATE 进 ImgInfo2 表，再 `r0.a()` 清脏 + `doNotify()`。

**置信度**：高（逐指令解码，字段/方法名均已解析）。

## 6. 与历史结论对照（`_r73_ma_residual.md` §九 / §9.5）

> 本轮对照仅读 `_r73_ma_residual.md` §九（含 9.1–9.5），未读其它段落/其它结论文档。先说明一个**偏移约定**：历史文档的十六进制偏移有两种用法——①「方法 code_off」（如 `j2.R() 0x2ad864`、`j2.<init> 0x2aef10`、`b1.R5 2a710c`、`b1.u8 2a5398`）；②「指令偏移 = code_off + 16(code_item 头部) + pc×2」（如 `2accb4 m1.n(1)`、`2b3ea4→r0.j`、`2a6b30 Y2(msgSvrId)`）。我的 code_off 一律取 class_data 里的**真实 code_off**（即 code_item 起点）。换算后全部吻合。

| 锚点 | 我的独立结论 | 历史 §九/§9.5 | 对照 |
|---|---|---|---|
| A `a8.F0` | `classes8.dex`，code_off `0x49d544`，**isNullOrEmpty**（null/空→true，非空→false） | §9.5：`classes8.dex` `code_off=0x49d544`，`F0(s)=isNullOrEmpty`，字节 `0038 000b 106e a012 0000 000a 003c 0003 0328 0012 000f 1012 000f` | **一致**（dex、code_off、语义、字节序列全同） |
| B `j2.O()` | `modelimage/j2.O(r0;I)Z`，`classes7.dex`，`0x2acc44`；开头读 `j2.u`(h9) 非 r0.u；F0(r0.u) `if-eqz` 分支；`n(1)!=0` `if-nez` 短路；`field_fullpath=z3(r0.e)` | §9.1/9.4/9.5：`j2.O` 同址，`2accb4 n(1)`/`2acccc if-eqz`；`field_fullpath=z3(r0.e)`(2ace9c)；§9.5 纠错后 `n(1)!=0` 短路措辞 | **一致**（历史 `2accb4`=0x2acc44+16+0x30×2，与我的 pc 0x0030 相同） |
| C `j2.R()`+doScene | `R()r0`，`0x2ad864`=「q 缓存 o2(j2.i)」；doScene `0x2ad99c`：`z3(r0.e/f/g)→o6.l()→o6.N()`；`ori` 无 dex 符号（=「原图」概念，r0.f 被 z3 解析后当文件读） | §9.1(d)：`j2.R() 0x2ad864`、`R()=o2(j2.i)`、`z3(r0.e/f/g)→o6.l()`；`ori(arg3)→j2.L/t0.b`（仅元数据/埋点，非读图） | **一致** |
| D `b1.R5/z3/Q2/a3` | `R5=0x2a710c`、`z3=0x2a6ae4`（SERVERID://→`Y2(msgSvrId)`，Y2 定义 `0x2a363c`）、`Q2=0x2a35c4`、`a3=0x2a36b0`（均 `classes7.dex`） | §9.3/9.4：`R5=2a710c`、`z3=2a6ae4→2a6b30 Y2(msgSvrId)`；Q2/a3 写作 `62ca30/62ca8a` | R5/z3/**Y2 完全一致**；Q2/a3 的 `62ca30/62ca8a` 是 `MsgRetransmitUI.B6` 内**调用点**（非方法定义），我给的 `0x2a35c4/0x2a36b0` 是**方法定义 code_off**，两者不矛盾 |
| E `r0.l/r0.n/b1.u8` | `r0.l=0x2b3e94` 写字段 **j**（+P=true）；`r0.n=0x2b3ed4` 写字段 **f**（+B=true）；`b1.u8=0x2a5398` 转 ContentValues 按 `id=?` UPDATE 持久化 ImgInfo2 | §9.2/9.3：`r0.l(2b3ea4)→r0.j`、`r0.n(2b3ee4)→r0.f`、`b1.u8(2a5398)` 持久化 | **一致**（`2b3ea4/2b3ee4` = 0x2b3e94/0x2b3ed4 + 16 头部，即首条指令偏移；u8 的 `2a5398` 与我的 code_off 完全相同） |

### 6.1 逐条结论

- **A**：独立重验与历史完全一致（`a8.F0`=isNullOrEmpty，`classes8.dex 0x49d544`），连 13 个 code unit 的字节序列 `0038 000b 106e a012 0000 000a 003c 0003 0328 0012 000f 1012 000f` 都逐字相同。
- **B**：一致。我独立确认 O() 开头读的是 `j2.u`（storage/h9 消息对象）而非 `r0.u`；`r0.u` 在首个 `n(1)` 短路闸门之后才读；`n(1)!=0 → if-nez 短路 → 直接构造 CDN task`；`F0(r0.u)` 分支方向与历史 §9.5 纠错后一致。
- **C**：一致。`R()` = `o2(j2.i)` 带缓存（缓存字段 `q`）；doScene 读图字节链 `z3(r0.e/f/g) → o6.l(长度) → o6.N(读字节)`；`ori` 无 dex 符号（历史亦指出其仅作 source 元数据 `j2.P/ot5.o` 与埋点 `t0.b`，不被当文件读）。
- **D**：一致。四个方法均定位成功，`z3` 遇 `SERVERID://` 确调 `Y2(msgSvrId)`（我独立解出 `0x2a6ae4` 内 `0x001e` 处 `invoke-virtual b1.Y2(J)`，历史同点 `2a6b30`）。「P3」为上一轮分析阶段代号（非方法名），「拿字节」反射入口 = `b1.q1(J,[B,PString,PInt,PInt)Z`（`0x2a2fa0`）或底层 `vfs/o6.N(String,II)[B`。
- **E**：一致。`r0.l` 写 `j`、`r0.n` 写 `f`、`b1.u8` 持久化 ImgInfo2，三者 code_off 与历史吻合。

### 6.2 命名澄清（本轮唯一「不一致」性质项）

锚点所写 **`hl0.j2` 实为 `com/tencent/mm/modelimage/j2`**（图片 CDN 上传 NetScene）。真正的 `Lhl0/j2;` 在 `classes10.dex`，是 `MicroMsg.SyncServiceHandler`（方法 a/b/c/d，与图片转发无关）。历史文档 §九通篇用 `j2`（正确指代 `modelimage/j2`），锚点表把包名写成 `hl0` 属笔误/转述误差，**不影响任何 code_off 结论**。

### 6.3 总体裁决

**独立重验通过，第四轮（§九/§9.5）锚点全部无误**：5 个锚点的 dex 归属、code_off、语义、关键分支方向、字段写入均与历史一致，无一处实质性冲突。历史文档的十六进制偏移在「方法 code_off」与「指令偏移(+16 头部)」两种约定间混用，是本轮唯一需要换算的点，换算后完全吻合。

[DONE]
