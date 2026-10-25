## 🟡 K8-file「不依赖 UI 发**文件**消息」—— 2026-10-20 静态定位完成（未上真机）

> 服务于 **6.6 强制转发**的 `resendFile`（`ForceForward.kt:648`，原「v1 恒 false / G3 缺口」）。
> 与上文 S9（文本）**同一条 builder 链路**，只是换枚举 + 多设一个文件名。
> 取证工具：`tools/_r11_filesend.py`（新写）；反汇编缓存 `_cache/_r11_dex7_dis.txt`（233MB，gitignored）。

### ⚠️ 先说方法论坑：**别用 `type_names` 判断类定义在哪个 dex**
`_r4_dexref.py` 的 `cmd_methods` 遍历的是 **type_ids**（被引用的类型）而不是 **class_defs**（定义）。
一个类名会同时出现在「引用它的 dex」和「定义它的 dex」里 ⇒ 直接 grep `type_names` 会得到 3 个假命中。
✅ 只认 `class_defs`：`python tools/_r11_filesend.py where Lhl0/r1;`
（实测 `hl0/r1` 只在 `classes7.dex` 定义；`classes4/classes8` 里那些是**引用**。）

### ✅ 完整链路（逐条反汇编得来，全部 PUBLIC）

```
hl0/o1            // enum：d=TEXT e=IMAGE f=VIDEO g=CDN_IMAGE h=EMOJI i=APPMSG(数组)
                  // ⚠️ APPMSG 那个常量没有独立字段名，只能取 o1.i[5]

hl0/r1.a(o1) : hl0/q1                    // PUBLIC STATIC，按「消息类别」造 builder
                                          // 字节码：new q1 → iput-object v1,q1.B:Lhl0/o1;
hl0/r1.b(String talker) : hl0/q1         // == r1.c(talker, null, 2, null)，预设 o1.d(TEXT)
hl0/r1.c(String, o1, int, Object) : q1   // 通用版；`and-int/lit8 v2,v2,#2` ⇒ 第3参是 Kotlin 默认参掩码

q1.g(String fileName) : q1   // iput-object -> q1.b:String   ← **只是文件名，不是路径！**
q1.h(String talker)   : q1   // iput-object -> q1.a:String   ← 目标会话
q1.e(String content)  : q1   // iput-object -> q1.c:String   ← 正文
q1.i(int type)        : q1   // iput      -> q1.d:I
q1.f(hl0/m)           : q1   // iput-object -> q1.t:Lhl0/m  ← 转发信息（可省，见下）
q1.a() : hl0/l1              // 组装 SendMsgCgiFactory 入队对象
l1.a() : boolean             // 是否成功入队
MsgRetransmitUI.g6(l1) : void  // PUBLIC STATIC，提交（内部只做两件事：
                               //   l1.a 非空且 getType()==522 → j2.S() 取 msgId 上报
                               //   getType()==110 且 instanceof j2 → 同上；其余什么都不做）
```

> ⚠️ **2026-10-20 复核订正（原文写成「文件名/路径」是错的）**：
> `hl0.q1` 全类**只有 10 个方法**（`<init>` / `a()` / `b()` / `c(vr4/l)` / `d(...)` /
> `e(String)` / `f(hl0/m)` / `g(String)` / `h(String)` / `i(int)`）——
> **没有任何写 `j` 字段的 setter**，而 `q1.j` 才是**完整路径**。铁证两条：
>
> 1. `t81/g.a`（图片 CGI 构造，`8bb740` 起）**开头第二段**（`8bb794`）就拿 `q1.j` 去
>    `com/tencent/mm/vfs/o6.l()` **取文件大小**，据此判「要不要当大图不压缩」
>    （`8bb7cc` 日志原文 `[createImageCgi] send as big no compress`）。
>    文件名拿不到大小 ⇒ 这个字段必须是真实可读的路径。
> 2. 微信自己每一处 `q1.g(X)` 后面都紧跟 `iput q1.j = Y`，且 **X ≠ Y**。最清楚的一处是
>    `552ddc`：`q1.g(a.d.m())` → `q1.j = a.d.q()`（`m()` 取名、`q()` 取路径，两个不同方法）。
>    其余 `2b2bb8` / `2b3130` / `31c08e` / `4e6dde` / `52cbe6` / `55181c` / `5862ea` /
>    `618e08` / `62d4c6` / `62d68c` 全部同构。
>
> ⇒ 复刻时**必须**：`q1.g(文件名)` + 直写字段 `q1.j = 完整路径`
> （`XposedHelpers.setObjectField(q1, "j", path)`）。只设 `q1.g(path)` 会让
> `q1.j` 保持 `""`（`<init>` 里初始化为空串）⇒ 文件大小取到 0 ⇒ 走进错误分支。

`hl0/m` 的 6 个字段（`toString` 里有全部字段名，直接可读）：
`a=forwardFromImgId(J)` / `b=forwardMsgId(J)` / `c=forwardSnsLocalId` / `d=forwardMsgUuid` / `e=forwardMsgSvrId` / `f=talkerUsername`。
构造时 `a=-1, b=0, c=""`, 其余为 null ⇒ **纯转发用 `new hl0/m()` 就够，不必填**。

### ✅ 微信自己怎么发文件（`qf4/o4`，tag `MicroMsg.SendMsgMgr`）
| 方法 | 签名 | 说明 |
|---|---|---|
| `o4.O8` | `(Context, String toUser, String fileName, int, String, String, l10.m, ScanCodeInfo)` | **发图片**（`845720`）。完整字段序列见下「O8 逐字段复刻清单」 |
| `o4.N8` | 同上但少 `ScanCodeInfo` | 直接转 `O8(..., null)` |
| `o4.Q8` | `(Context, String, String, int, String, String, String, l10.m)` | 同上多一个 String 参数 |
| `o4.m9` / `o4.o9` / `o4.e9` / `o4.c9` | `(..., 4~6, II, ZZ, String, String[, J], mo2/k0)` | **发视频**（日志原文 `send video`）。层层转调，最终 `c9` 里 `AsyncTask.execute` |
| `o4.z8` | `(String,[B,String,String)` | 传字节流，调用点只有 3 处（`fav/ui/p5`、`finder/feed/ac`、`chatting/m4`） |
| `o4.W8` / `H8` / `V8` | 见上文 S9 段 | 文本 |

### ✅ `O8` 逐字段复刻清单（2026-10-20 复核补齐，地址 `845720~84582a`）

原文只记了 3 个 setter，**漏了决定成败的 `q1.h`**。逐条字节码如下：

| 地址 | 指令 | 字段 | 必须性 |
|---|---|---|---|
| `84579a` | `sget-object o1.e` | enum IMAGE | 必须 |
| `84579e` | `r1.a(o1)` | 造 builder | 必须 |
| `8457a8` | `const/4 4` → `iput` | `q1.i = 4` | 建议（分支 11/13/19 会读） |
| `8457ac` | `q1.g(fileName)` | `q1.b` = **文件名** | 必须 |
| `8457b2` | `q1.h(toUser)` | `q1.a` = 目标会话 | 必须 |
| `8457b8` | `iput-object` | `q1.j` = **完整路径** | **必须**（无 setter，直写字段） |
| `8457bc` | `iput` | `q1.k` = arg3(int) | 建议（`8bb78e` 读它判「大图不压缩」） |
| `8457c0` | `iput-object` | `q1.l` = null | 可省（默认即 null） |
| `8457c6` | `const/4 0` → `iput` | `q1.n = 0` | 可省 |
| `8457d4` | `const/4 1` → `iput-boolean` | `q1.p = true` | 建议（各分支末尾 `if-eqz q1.q` 之外还读 p） |
| `8457de` | `const 0x7f0803d6` → `iput` | `q1.o`（**int！**） | 建议 |
| `8457f2` | `q1.f(new m())` | `q1.t` | 可省（`<init>` 已 new） |
| `8457fc` | `const/16 13` → `iput` | **`q1.h = 13`** | **必须**（constructorId，决定走哪个 j2 构造） |
| `845800` | `iput-object` | `q1.D` = ScanCodeInfo | 可省（null） |
| `845804` | `q1.a()` → `l1` | 组装 | 必须 |
| `84580c` | `MsgRetransmitUI.g6(l1)` | 提交（只读 l1） | 建议，**须在 `l1.a()` 之前** |
| `845812` | `l1.a()` → `boolean` | 真正 execute | 必须 |

#### ⚠️ `q1.o` 是 `int`，不是 float（容易读错）

dexdump 把它注释成 `#float 1.80795e+38`，**那是把 `0x7f0803d6` 这串比特按 float 解释的产物**，
不是真值。字段签名是 `Lhl0/q1;.o:I`，`0x7f08xxxx` 是**字符串资源 id 段**。
写成 `setFloatField` 会直接抛 `IllegalArgumentException`（类型不符）。
⇒ 用 `XposedHelpers.setIntField(q1, "o", 0x7f0803d6)`。
（资源 id 跨微信版本会变；但 `q1.o` 只在 `h=10/11/13/14/default` 分支作为普通 int 传入 `j2`，
不设它 = 0，风险低于「猜错一个 id」，故列为「建议」而非「必须」。）

#### ⚠️ `q1.h=13` 为什么是必须的

`t81/g.a`（`8bb740`）按 `q1.h` 分派：`2/3/6/9/10/11/13/14/19/default`。
而 `q1.<init>`（`712350`）**不初始化 `h`**（只初始化 `a/b/c=""`、`d=1`、`j/r/s=""`、`t=new m()`）
⇒ 不设就 `h==0`，落到 **default 分支**（`8bbb14`）：

- default 分支把 `q1.x`、`q1.A` 两个 **null String** 传给 `j2.<init>(19 参)`（`8bbba0`/`8bbbb0`）；
- `h==13` 分支（`8bba04`）传的是 `""`/`""`（`8bba6a`/`8bb9ec` 附近的 `const-string v19, ""`）。

⇒ **不设 `h=13` = 构造出非法图片 CGI**。而这只是**同步层**的错（`j2` 构造 NPE，被
`runCatching` 吞掉，表现成「发不出去」）；更糟的是**异步层**：非法 CGI 经
`l1.a()` 里的 `modelbase/p1.g(cgi, 0)` 入队后由微信网络线程处理，
此时抛的异常**不在我们的 `runCatching` 保护范围内 ⇒ 真崩微信**。

### ⛔ VIDEO 为什么不能用 `r1.a(o1.VIDEO)`

`q1.a()` 对 VIDEO 分支（`712248` `sget o1.f` → `invoke-interface l.i(q1)`）调
`t81/g.i(q1)`，而后者**第一句**就是：

```
8bbca6  instance-of v1, q1, com/tencent/mm/modelimage/o3
        if-eqz v1, 006b          ; ★ 不是 o3 子类就直接跳走
8bbd72  return-object v0          ; k1.a 从未赋值 = null
```

`l1.a()` 随即在 `71039c` 用 `Objects.requireNonNull(l1.a)` 抛 NPE ⇒
被 `runCatching` 吞掉 ⇒ **恒 false，只留一行 NullPointerException**。

正解是 `com.tencent/mm/modelimage/o3.j()` 工厂（`2b34d4`：`new o3` → `sget o1.f` → `iput q1.B`），
`o3` 是 `q1` 子类（`Superclass: 'Lhl0/q1;'`，额外字段 `E/F/G/H/I/J/K`），
调用点 `245cc2` 随后设 `o3.E/F/K/H` 与 `q1.h=20`。

### ✅ 原生转发入口 = `MsgRetransmitUI` + `Select_Conv_User`（2026-10-24 R16-P1r 取证，主会话复算）

**`nativeForward()` 不需要反射任何「转发方法」** —— 微信的原生转发就是一个 Activity：
`com.tencent.mm.ui.transmit.MsgRetransmitUI`，靠一整套 `Retr_*` Intent extras 反序列化源消息与目标。

**两条可用路径**：

| | 路径 A（推荐） | 路径 B |
|---|---|---|
| 做法 | 组 Intent 启 `MsgRetransmitUI` | 直调 `qf4/o4.O8(...)` 8 参 |
| 适用 | 文本 / 卡片 / **任意 DB 已有消息** | 图片 / 视频（带转发标记） |
| 目标 | `Select_Conv_User` 传 talker | 第 2 参 talker |
| 前提 | 源消息 msgId 需在库 | 单例 `mo2/w0.a()` |

**关键实证 1 · `Select_Conv_User` 能跳过选择器直达发送**（这条原先只被标为「强推测」）

`MsgRetransmitUI.onCreate` 尾部：

```
[1426] const-string   v2  'Select_Conv_User'
[1428] Intent->getStringExtra(Ljava/lang/String;)Ljava/lang/String;
[1442] new-instance   v3  Ljava/util/ArrayList;
[1447] iput-object    v3, v0  MsgRetransmitUI;->h:Ljava/util/List;   ★ 目标列表进 this.h
[1449] ArrayList->add(Ljava/lang/Object;)Z                            ★ add(talker)
[1452] MsgRetransmitUI->l6()V                                        ; scene 分发
[1455] iget-object    v1, v0  MsgRetransmitUI;->h:Ljava/util/List;
[1457] MsgRetransmitUI->z6(Ljava/lang/String;Ljava/util/List;)V       ★ 直接带目标发送
```

`z6(String, List)`（`code_off=0x631160`）内：
```
[  17] List->get(I)  → check-cast String
[  38] Lhl0/s1;->a(String)Z
[  44] invoke-static  Lhl0/r1;->b(Ljava/lang/String;)Lhl0/q1;   ★★ 本项目已熟的发送锚点
[  48] q1->e(Ljava/lang/String;)Lhl0/q1;
```

⇒ **传一个 talker 就一路走到 `r1.b(talker)` → `q1.e(内容)`**，完全不过选择器。

**关键实证 2 · `qf4/o4.O8` 是「发图」不是「发视频」，且路径字段是 `q1.r` 不是 `q1.j`**

签名（`classes7.dex`，`code_off=0x845720`，`registers=12 / ins=9` ⇒ **`this=v3`**，形参落 `v4..v11`）：

`qf4/o4.O8(Context, String, String, int, String, String, hl0/m, ScanCodeInfo)V`

| # | 类型 | 寄存器 | 语义 | 字段落点 |
|---|---|---|---|---|
| 0 | Context | v4 | UI 上下文 | — |
| 1 | String | v5 | **目标 talker** | `q1.h(v5)` → `q1.a` |
| 2 | String | v6 | **文件名** | `q1.g(v6)` + `iput q1.j` |
| 3 | int | v7 | msgType | `iput q1.k` |
| 4 | String | v8 | **本地完整路径** | **`iput q1.r`** |
| 5 | String | v9 | 附言/文案 | `iput q1.s` |
| 6 | hl0/m | v10 | 转发标记（`m.b`=源 msgId） | `q1.f(v10)` |
| 7 | ScanCodeInfo | v11 | 扫码信息 | `iput q1.D` |

内部写死：`[59] q1.i=4`、`[81] q1.p=true`、`[87] q1.o=2131231702`、**`[100] q1.h=13`**（图片 constructorId），
最后 `[110] MsgRetransmitUI.g6(l1)` 上报；失败日志为 **`'sendImg: args error, toUser[%s], fileName[%s]'`**。

⛔ **两条同构链不能混用**（这是最容易踩的坑）：
- `O8`（发图）：路径 → **`q1.r`**，`q1.h=13`，`q1.i=4`。
- `FileSender.kt`（发普通文件）：路径 → **`q1.j`**，走 `t81/g` 的另一条 constructor 分派。
两者字段落点不同，**把 `q1.j` 当路径塞进 `O8` 那条链 = 塞错字段**。

**关键实证 3 · `Retr_*` 键名与字段映射**（33 条，完整表见 `docs/_r16_g1_native_forward.md` §1.1）

最要紧的三个：
- `Retr_MsgTalker` → `iput-object X:Ljava/lang/String`（源会话）
- `Retr_Msg_Id` → `getLongExtra(-1)` → `iput-wide f:J`（源消息 id）
- `Retr_Msg_Type` → `getIntExtra` **默认 `-1`**（原文 `12f5` = `const/4 v5, #int -1`；⚠️ 曾因工具 bug 误记 3）

**⚠️ 唯一待真机项**：`Retr_Msg_Id` 是否**必须**指向库里真实消息。
`l6()` 会 `storage/j9.u3(msgId)` 查库，查不到是否走不到转发分支 —— 静态看不出来，需真机定（`_r16_g1_native_forward.md` §4 探针 3）。

**⛔ 别把 `g6/h6` 当入口**：它们只做「转发完成后的 msgId 上报」，`g6(l1)` 内部仅读 `l1` 且只在
`getType()==522` / `==110` 时取 msgId，其余什么都不做。

### ✅ VIDEO 的正解链路（2026-10-23 补完，静态取证已闭合）

上一轮只记了「`245cc2` 设 `o3.E/F/K/H` + `q1.h=20`」，没确认这些值**各自是什么**，
所以停在「未取证不写」。这轮补完，链路已完整：

**所在方法**：`bm0.u0.N:()Z`（`classes7.dex`，方法体起点 `245908`，1372 code unit，26 寄存器）
—— 从日志 tag `MicroMsg.NetSceneUploadVideo` 与 `const-string "upvideo"` 可确认这是**视频上传场景**。
`v1 = v25 = this`（`245918` `move-object/from16 v1, v25`，`ins=1` ⇒ v25 就是 this）。

**逐字段（地址 `245cc2`~`245cfa`）**：

```
245a10  iget-object v8, v1, Lbm0/u0;.f:Ljava/lang/String;   ; ★ v8 = u0.f（视频本地路径）
...
245cc2  invoke-static {}, Lcom/tencent/mm/modelimage/o3;.j:()Lcom/tencent/mm/modelimage/o3;
245cc8  move-result-object v4                 ; v4 = o3 实例
245cca  iput-object v8, v4, o3;.E:Ljava/lang/String;        ; E = 视频本地路径
245cd0  const/4 v5, #int 0
245cd0  iput v5, v4, o3;.F:I                                ; F = 0
245cd4  iget-boolean v5, v1, Lbm0/u0;.q:Z                  ; q 是 u0 的布尔字段
245cd8  iput-boolean v5, v4, o3;.K:Z                        ; K = u0.q
245cdc  new-instance v5, Lbm0/c1;
245ce0  invoke-direct {v5, v1}, Lbm0/c1;.<init>:(Lbm0/u0;)V  ; c1(this)
245ce6  iput-object v5, v4, o3;.H:Lcom/tencent/mm/modelimage/d0;  ; H = c1 实例（d0 = 回调接口）
245cea  const/16 v5, #int 20
245cee  iput v5, v4, Lhl0/q1;.h:I                            ; ★ q1.h = 20（不是 13！）
245cf2  invoke-virtual {v4}, Lhl0/q1;.a:()Lhl0/l1;           ; 组装入队对象
245cf8  move-result-object v4
245cfa  invoke-virtual {v4}, Lhl0/l1;.a:()Z                  ; 真正 execute
```

**`o3` 七个字段的类型**（`tools/_r12_dexclass.py --class 'Lcom/tencent/mm/modelimage/o3;'` 实测）：

| 字段 | 类型 | 上表的赋值 | 说明 |
|---|---|---|---|
| `E` | `Ljava/lang/String;` | `u0.f` | **视频本地路径**（必须） |
| `F` | `I` | `0` | 字面量 0（必须） |
| `G` | `Ltj/f;` | **未赋值** | 本次调用点不设，保持 null |
| `H` | `Lcom/tencent/mm/modelimage/d0;` | `new bm0/c1(u0)` | **上传回调接口**（必须，且要有 `u0` 才能造） |
| `I` | `Ltj/f;` | **未赋值** | 保持 null |
| `J` | `Ljava/lang/String;` | **未赋值** | 保持 null |
| `K` | `Z` | `u0.q` | 布尔，取自上传场景对象 |

**`u0.f` / `u0.q` 的语义**（`Lbm0/u0;` 22 个实例字段）：`f` 是 `String`（视频路径），
`q` 是 `Z`；`u0.R()`（`0x247680`，27 code unit）拼 `'_' + hashCode` 作为 CDN 临时名，
`u0.O(String)` 是**取缩略图并落盘**（内部 `vfs/j6` 读写 + `u0.R` 拼名）。

#### ⛔ 为什么这条链路**仍然不能直接照抄**

`o3.H` 的类型是 `com.tencent/mm/modelimage/d0`（抽象回调接口），
微信自己用 `new bm0/c1(u0)` —— **`bm0/c1` 的构造需要一个现成的 `u0` 实例**，
而 `u0` 是「视频上传场景对象」，它的 `n` 字段是 `bm0/t2`（消息对象）、`f` 是路径、
`q` 是场景布尔，全都是从**真实待发视频消息**里来的。

⇒ 我们要发的是「本地已有的一个视频文件」，没有 `u0`，就造不出 `c1`，也就填不了 `o3.H`。
**可以用 `XposedHelpers` 反射 `new bm0/c1` 传一个假 `u0`**，
但 `u0.N()` 内部还会读 `u0.n`（`bm0/t2`，真消息对象）做 `h4.M3/L3` 场景判定，
**假对象一进去就 NPE**。

**结论（2026-10-23 定档）**：`o3` 链路**依赖微信内部消息对象**，
和 `resendFile` 的「从本地文件发」语义不匹配。
⇒ **视频保持 `canResendFile = false`，不动**。
真要发视频，正确路线是 `pluginsdk/model/app` 的 `WXVideoObject`（与普通文件同一套 `sendAppMsg`），
而不是复刻 `o3`。这条已在 `_cache/r9_cdn_plan.md` 之外单列，见下节「普通文件」。

#### ✅ 「假 u0 一进去就 NPE」已由字节码证实（2026-10-24 R16-P2r，主会话复算）

上面那条 2026-10-23 写的是**推断**；本轮读到了确证字节码，升级为**已证**。

**证据 1 · `u0.N()` 不判空直接调 `t2.h()`**（`Lbm0/u0;->N()Z`，`acc=0x0011`，`code_off=0x245908`）：

```
[   0] op08          v1
[   2] iget-object   v0, v1  Lbm0/u0;->n:Lbm0/t2;              ; 取字段，无判空
[   4] invoke-virtual {n=0}   Lbm0/t2;->h()Ljava/lang/String;   ; ★ 直接调 h() ⇒ u0.n=null 即 NPE
[   8] invoke-static {n=0}    Lvj0/e2;->P(Ljava/lang/String;)Z  ; 之后才判空串
[  15] if-eqz        v2  -> +16 (abs 31)                       ; 空 ⇒ 'cdntra not use cdn user:%s' return 0
```

**证据 2 · `u0.n` 的来源就是 `b3.i(path)`**

- `u0.<init>`：`[82] iput-object v7, u0.f` → `[84] invoke-static Lbm0/b3;->i(Ljava/lang/String;)Lbm0/t2;` → `[88] iput-object v7, u0.n` → `[90] if-eqz v7 → abs 97`（构造里判了，但只用来跳过赋值）。
- `b3.i` 实现（`acc=0x0009` STATIC）：`a8.F0(s)` 为真 ⇒ `const/4 v1 #0; return-object`（**直接 null**）；否则 `m2.O8().f(s)`。
- `t2.h()`：`iget-object v0, v1, Lbm0/t2;->q` ，`if-nez` ⇒ 返回 `''`。即 `t2.q` = talker。

⇒ **`path` 若不命中消息库 ⇒ `u0.n=null` ⇒ `u0.N()` 在 `[4]` NPE。**

**证据 3 · 另一条分支（`bm0/p1`）也不宽容**（`Lbm0/p1;->doScene`，`code_off=0x23eecc`）：

```
[   2] iget-object   v0, v6  Lbm0/p1;->f:Ljava/lang/String;
[   4] invoke-static {n=0}   Lbm0/b3;->i(Ljava/lang/String;)Lbm0/t2;
[  11] if-nez        v1  -> +27 (abs 38)          ★ 非 null 才继续
--- null 分支 ---
[  15] const-string  v2  'Get info Failed file:'
[  27] invoke-static {n=0}  Ll2;->e(...)
[  30] iget-object   v0, v6  Lbm0/p1;->j:Lcom/tencent/mm/modelimage/d0;
[  32] const/4       v1  #3                       ; errType = 3
[  33] const/4       v2  #-1                      ; errCode = -1
[  34] invoke-interface {n=0}  Ld0;->a(II)V       ; 回调上报失败
[  37] return        v2                           ; doScene 返回 -1
```

（`Lcom/tencent/mm/modelimage/d0` 是接口，`virtual_methods=1`，`a` = `0x401` public abstract `(II)V`。）

⇒ **两条分支都要求 `path` 命中消息库**：`u0` 路线 NPE，`p1` 路线 `doScene` 返回 -1 且回调 `a(3,-1)`。
**「降级重发到任意新目标」与视频链路根本不兼容，不是「难」，是语义不匹配。**
`canResendFile` 对 VIDEO 保持 `false` 是有字节码支撑的决定，不是保守估计。

#### ✅ 补充：即便 path 命中库，talker 也是**原会话** ⇒ 必然发错方向（2026-10-24 实证）

这条把「不行」补成了「**怎么都不行**」。`t2.q` = talker，四条独立证据：

1. **`u0.N` 的日志把它叫 user**：`const-string '%s cdntra not use cdn user:%s'`，
   第二个 `%s` 就是 `[2] iget u0.n` → `[4] t2.h()` 的返回（`[17]` 传 `u0.R()`，`[21] filled-new-array n=2`）。
2. **`t2.h()` 直接返回 `t2.q`**（`iget-object v0, v1, Lbm0/t2;->q`，null 时给 `''`）。
3. **`t2.q` 从 DB 读取**：`t2.a(Cursor)` 的 `[121] const/16 v1 #16` → `[123] Cursor.getString(16)` → `[127] iput-object v1, t2.q`。
   即 `q` 是**消息表第 16 列**。
4. **`t2.q` 在插入消息时被赋值**：`b3.s(...)` 方法签名首参 `String a`、第 3 参 `String q`：
   ```
   [   5] iput-object v2, v0  Lbm0/t2;->a:Ljava/lang/String;
   [   7] iput       v3, v0  Lbm0/t2;->m:I
   [   9] iput-object v4, v0  Lbm0/t2;->q:Ljava/lang/String;   ; 第 3 参 = talker
   ```
   `b3.s` 是**按 msgId 插入/反查一条消息元数据**的内部 API，`q` 就是这条消息所属会话。

**⇒ 结论（最终定档）**：
- `path` **不在**库 ⇒ `u0.N()` NPE / `p1.doScene` 返回 -1。**发不出去**。
- `path` **在**库 ⇒ 能发出去，但 talker 取自 `t2.q` = **这条老消息的原会话**。**发错人**。

⛔ 所以「视频降级重发到新目标」在本链路下**没有任何一条可行路径**。
不要试图给 `canResendFile` 放宽 VIDEO，也不要试图改 `t2.q` 绕过去 ——
`t2` 是 DB 实体的缓存副本，改它不落库，`m2` 层按下的是真库。
真要做视频，正解是 `WXVideoObject` + `sendAppMsg`（与普通文件同一套），从零构造 appmsg，**不碰 `o3`/`t2`**。


⇒ **关键结论**：`qf4/o4` 里**没有**「发普通文件（appmsg type=6）」的公开方法。
能确认的只有 IMAGE / VIDEO / TEXT 三种 builder 用法；`o1.i[APPMSG]` 这个枚举在
`classes7.dex` 全文**零 sget 引用**（实测 `grep -c` = 0）⇒ 微信自己在 classes7 里
**没有直接用 APPMSG 枚举造 builder**，它走的是另一条路（`pluginsdk/model/app/u0` 那一套，
37 个方法，含 `WXFileObject` + `WXMediaMessage`，属 `sendAppMsg` 体系）。

---

## ✅ K8-file 正解：`qf4/r5` + `WXFileObject` —— 2026-10-23 静态取证**完全闭合**

> 上一版的结论「`qf4/o4` 里没有发文件的方法」是对的，但**推论错了**：没在 `o4` 里，
> 在 **`qf4/r5`**（`SendWXMediaMessageHelper`）里。这条链路才是微信**自己**发文件的正路，
> 且比 B 档（`o4.z8` 字节流）**干净得多** —— 我们只需造两个 SDK 对象，不用去碰 `u0` 的 14 个参数。

### 完整链路（逐条反汇编确认，`classes7.dex`）

```
hl0/o1            ← 不用碰，这条链路不经过 q1 builder

com/tencent/mm/opensdk/modelmsg/WXFileObject
    <init>()V                       // 默认构造，contentLengthLimit/fileData(filePath) 全置 null
    setFilePath(String)             // 只写 filePath 字段（实测就一条 iput-object，无副作用）
    type()I                         // ★ 硬编码返回 6，与 r5.j 的 getType()==6 分支对上

com/tencent/mm/opensdk/modelmsg/WXMediaMessage
    <init>()V
    .mediaObject = <WXFileObject>   // IMediaObject 接口
    .title       = 文件名
    .description = 文件名
    getType()I                      // 实测读 mediaObject.type() ⇒ 得 6

qf4/r5                        // ★ SendWXMediaMessageHelper，**单例**
    static Lqf4/r5; a                    // 单例字段
    a()Lqf4/r5;                          // 静态工厂（PUBLIC）
    l(Context, Intent, app/m, WXMediaMessage, LinkedList,
      I, Z, Z, String, I, String, I, a5, b5) : void    ← 类型分派器（14 参）
    j(app/m, WXMediaMessage, LinkedList, String, String,
      I, Z, Z, String, String, Object, a5, b5) : void   ← 真正的执行体（13 参）
    c(String path, vr4/p) : void                        ← 拷贝 + 异步投递
```

### `r5.l` 是分派器：按 `getType()` 选 object 包装类

```java
// 8480d4, 197 行；每个分支都是 new XxxObject包装 + r5.c(path, 回调)
type == 6  → new qf4/c5(WXImageObject)      ...
type == 6  → new qf4/d5(WXFileObject)       ← ★ 文件走这里
type == ... → new qf4/e5(WXEmojiObject)
type == ... → new qf4/f5(WXVideoFileObject)
type == ... → new qf4/g5(WXGameVideoFileObject)
type == ... → new qf4/h5(WXAppExtendObject)
type == ... → new qf4/i5(WXMusicVideoObject)
...
// 全部收敛到：r5.c(filePath, 回调包装)，由 c 内部投递到线程池再调 j(...)
```

`qf4/d5` 实测只有 2 个实例字段：`d` = `WXFileObject`、`e` = `vr4/l`（Kotlin 回调），
`invoke()` 里只做 `Number.intValue` + `l.invoke` —— 是纯适配器，不含业务。

### `r5.j` 的完整逻辑（`847af0`，232 行）

```java
// 签名：(app/m appInfo, WXMediaMessage msg, LinkedList<String> talkers,
//        String 未知4, String 未知5, int 未知6, Z 未知7, Z 未知8,
//        String 未知9, String 未知10, Object 未知11, a5, b5)
if (msg.getType() != 6) goto L_talkerLoop;          // 非文件不做拷贝处理

WXFileObject fo = (WXFileObject) msg.mediaObject;
boolean hasData = (fo.fileData != null && fo.fileData.length != 0);
boolean hasPath = (fo.filePath != null && fo.filePath.length() != 0);
if (!(hasData || hasPath)) {                        // 原始逻辑：
    Log.e("MicroMsg.SDK.WXFileObject", "checkArgs fail, both arguments is null");
    ok = false;
} else ok = true;
if (!ok) { Log.e("MicroMsg.SendWXMediaMessageHelper",
                 "check security problem %s", fo.filePath); return; }   // ★ 静默丢弃

if (!a8.F0(fo.filePath)) {                          // 路径不存在/不可读
    // 842bcc 起：把 filePath copy 到 app/u0.i() 默认附件目录
    // 用 ou4/d0.v(src, dstDir, false) 判是否在目录内，不在就 copyFile
    // 失败则打 "copyFile failed: <ret>, src=<path>" + "path2uri: ..."
    // 并上报 al/a.a("errFile2Uri", ...)
    fo.filePath = copyiedPath;                      // ★ 用副本路径覆盖
}

L_talkerLoop:
for (String talker : talkers) {
    app/a appInfo2 = r5.h(未知7, 未知9, 未知10, b5); // 846c00 单例、84697c h
    app/u0.A(msg, appInfo.field_appId, appInfo.field_appName,
              talker, 2, 未知4, 未知5, 未知6, appInfo2);  // ★ 真正入队
}
```

### ✅ 对我们的可用性判定

| 环节 | 判定 | 依据 |
|---|---|---|
| `new WXFileObject()` + `setFilePath(path)` | ✅ 可用 | 两个方法都 PUBLIC，`setFilePath` 只有一条 `iput-object`，无校验 |
| `msg.mediaObject = fo` | ✅ 可用 | 字段 PUBLIC（`iput-object` 在微信自己代码里就是直写） |
| `msg.getType()` 返回 6 | ✅ 自动 | `WXFileObject.type()` 硬编码 `const/4 v0,#6` |
| 安全门 `w.d(path)` | ✅ 通过 | 只校验 `content://` URI 的 authority 是否含 `com.tencent.mm`/`com.tencent.wmpf`；普通 `/sdcard/...` 路径走 `a8.F0` 存在性检查分支，不拦 |
| 路径不存在时的自动 copy | ⚠️ 会触发 | 我们的路径来自 `ForceForward` 的 `localPath`，**必须保证真实存在**，否则它会复制到微信附件目录再发（内容不变但多一次 IO） |
| `r5.j` 的 appInfo（`app/m`） | ✅ **可造**（已解） | `app/m` 的 `Superclass` 是 `Ljj/k0;`（实测），`r5.j` 里 `iget-object ... Ljj/k0;.field_appId` 因此合法。`r5.h` 是**纯 POJO 拼装、零查库零校验**（见下） |
| `u0.A(...)` 9 个参数 | ✅ **已解**（实测实参装载） | 见下「`u0.A` 实参逐条」 |
| `u0.D` 真正落库 | ⚠️ **面很宽，未逐行审计** | 461 行，含 `storage/h9` 建行 + `u0.l(q)` 推 type（492 行）+ `hl0/h1.x8` 取 talker + `bm0/p0.securityVerificationChecked` 安全校验 |

### ✅ `u0.A` 实参逐条（`r5.j` 的 `847e06`~`847e5c`，实测）

| 位置 | 类型 | 实参来源 | 说明 |
|---|---|---|---|
| 1 | `WXMediaMessage` | `v19`（`r5.j` 入参 2） | 我们造的那个 |
| 2 | `String` | `if-nez v2 || if-eqz v25` ⇒ 跳 0190 保留 `appInfo.field_appId`；两者都不满足才 **`""`** | ★ **空串兜底，不是 null** ⇒ 没有 appId 也安全（详见 §「p7 / p8 的真实语义」） |
| 3 | `String` | `appInfo.field_appName` | 同上 |
| 4 | `String` | `v7` = 循环变量 | **talker** |
| 5 | `int` | `const/4 v8, #2` | 字面量 **2**（`r5.j` 硬编码） |
| 6 | `String` | `v21`（`r5.j` 入参 9） | AppBrand 场景传 `custom_send_text`；我们传 `""` |
| 7 | `String` | `v22`（`r5.j` 入参 10） | 同上，传 `""` |
| 8 | `int` | `v23`（`r5.j` 入参 6） | AppBrand 场景是 subtype；我们传 `0` |
| 9 | `app/a` | `r5.h(v2, v14, v15, v12)` 的返回 | `appInfo2`（POJO） |

### ✅ `r5.h` 是纯 POJO 拼装（`84697c`，33 行，零查库）

`r5.h(Z, String, String, b5) → app/a`，逐条字节码：

```
new-instance app/a ; <init>()V
iput v2  → a.a:I              // 入参 1（Z，实为 int 位）
iput v3  → a.b:String         // 入参 2
iput v4  → a.c:String         // 入参 3
a.d = (b5 == null ? null : b5.b)   // 回调容器字段，可为 null
a.e = (b5 == null ? null : b5.c)
a.f = (b5 == null ? null : b5.d)   // LiteAppBizDataInfo
return a
```

`app/a` 实测只有 6 个实例字段（`a:I`、`b/c/d/e:String`、`f:LiteAppBizDataInfo`）——
**没有查库、没有白名单、没有副作用**，可以放心用 `XposedHelpers` 直接 `new` 再填字段。

### ✅ `r5.c` 的回调状态机（写探针时必须读它）

`r5.c(path, vr4/p)` 内部先投 `hn4/t0` 线程池（tag `SendWXMediaMessageCopyFile`），
再由 `qf4/q5.invoke()` 按回调码分派（`8465a8`~`846630`，实测）：

| 回调码 | 分支 | 日志原文 | 含义 |
|---|---|---|---|
| `3` | `8465ba` | `sendWXMediaMessage fail, WXMediaMessage file check fail` | **文件校验失败**（`w.d` 或 `checkArgs` 不过）；随后回调 `cb_key_file_check_invalid` |
| `2` | `8465de` | — | 走 `r5.e(...)` 17 参，末三参硬编码 **`0 / 8192 / 0`** |
| `0` | `846632` | — | 同样走 `r5.e(...)`，另一组参数 |
| 其它 | `8465b8` | — | `goto 008e` 直接返回，**什么都不做** |

`r5.e` 只做一件事：委托给 `r5.d`（812 行那个枢纽）。

**探针判据（B1 用）**：
1. 有 `checkAndCopyFileIfNeed  callBack:` 日志 ⇒ 已进 `r5.c` 的异步链；
2. 有 `sendWXMediaMessage fail, WXMediaMessage file check fail` ⇒ **回调码 3，路径/校验有问题**；
3. 有 `MicroMsg.AppMsgLogic` 的 `summerbig content url:` 行（`4d35c4`）⇒ 已进 `u0.D`，链路真正走通；
4. 以上都没有 ⇒ 连 `r5.j` 的 `getType()==6` 分支都没进（检查 `WXFileObject.type()` 是否真返回 6）。

### ✅✅ 补充闭合（2026-10-23 R13）：`u0.l` / `u0.A`→`B`→`D` 全部审计完，**type=6 链路可达且自洽**

上一版这里写「`u0.D`/`u0.l`/`r5.d` 面很宽，没审计完」。R13 把这层审完了，**结论是翻过来的：不难，而且已经被微信自己验证过**。

#### 🔑 决定性证据：微信原生发文件根本不走 `u0.A`

`com.tencent.mm.ui.chatting.q6.onActivityResult`（`0x5963b4`）是**聊天页「+」→ 选文件 → 返回**的原生实现，
它 `requestCode == 227` 后直接在 UI 线程把每个文件发出去，**完全绕开 `u0.A/B/D/r`**：

```
0052  if-eqz v13, 028c                  ; ArrayList selected_file_lst 为空则跳出
0060  check-cast v6, String             ; v6 = 每个文件的本地路径（或 content:// URI）
0062  a8.F0(v6)                         ; 判是否空串
006c～008e  content:// 分支：取 "selected_file_title_lst"[i] 当标题，否则取路径 basename
00f9  iterator: 按 "," 切 talker 列表（v10 = x5.c）
0105  new-instance v2, storage/h9       ; ★ 直接 new MsgInfo
010c  new-instance v4, te0/q            ; ★ 直接 new appmsg 体
0111  iput-object v5, v4, te0/q;.d      ; d = ""        （v5 在 0068 就是 ""）
0113  iput-object v5, v4, te0/q;.F      ; F = ""
0115  const/4 v5, #4
0118  iput v5, v4, te0/q;.r:I           ; r = 4         ★ <androidsource>
011a  new-instance v5, WXFileObject
011f  WXFileObject.setFilePath(v6)      ; ★ 只用本地路径，不读 fileData
0124  new-instance v8, WXMediaMessage
0129  iput-object v5, v8, WXMediaMessage.mediaObject
0131～0170  vfs q7 规范化路径 + 取 basename → WXMediaMessage.title
0174  WXMediaMessage.title = basename
0184～0196  vfs 取文件长度 → a8.b0(J) 转可读字符串 → WXMediaMessage.description
019c  IMediaObject.type()               ; （结果被丢弃，纯为了跑一次接口）
01a3  te0/q.e = WXMediaMessage.sdkVer
01a7  te0/q.f = title
01ab  te0/q.g = description
01af  te0/q.V = mediaTagName
01b3  te0/q.W = messageAction
01b7  te0/q.X = messageExt
01bb  IMediaObject.type() → move-result v5
01bf  iput v5, v4, te0/q;.i:I           ; ★ i = 6（WXFileObject 硬编码）
01c1  const/4 v8, #6
01c2  if-ne v5, v8, 01f0                ; ★★ 只有 type==6 才做下面的文件专属处理
01c6  (WXFileObject)fileData
01c8  a8.H0(fileData)                   ; byte[] 非空？
01d5    真：te0/q.l = fileData.length   ; l = 文件大小（字节）
01d8    假：(WXFileObject)filePath
01da    vfs.o6.l(filePath) → long
01df    te0/q.l = (int) len              ; ★ l = 文件大小
01e4    vfs.o6.o(filePath) → md5
01ee    te0/q.n = md5                    ; ★ n = 文件 md5
01f0  ××× 文件专属处理结束 ×××
01f1  te0/q.t(q, "", null) → String      ; ★ 序列化成 XML
01f5  MsgInfo.N0(xml)                    ; 写 content
01f9  MsgInfo.j1(1)                      ; isSend = 1
01fc  MsgInfo.X0(1)
01ff  const v5, #41000031
0202  MsgInfo.setType(0x41000031)        ; ★★★ 硬编码，不经过 u0.l()
0205  MsgInfo.k1(talker)  /  O0(createTime)
020f～022c  企业微信 / TalkerId 相关的旁路
022f～023c  storage/j9.fa(MsgInfo) → 落库返回 msgId
0241  HashMap.put(talker, msgId)
0256  goto 00f9                          ; 下一个 talker
0262～027b  new hn4/t0 Runnable(chatting/a6) 投线程池去真正发送
```

**只用到的 `te0/q` 字段一共 14 个**：`d` `F` `r` `e` `f` `g` `V` `W` `X` `i` `l` `n` + 序列化内部写的。
**没有 `H`、没有 `x1`、没有 `D1`、没有 `Y`、没有 `Z`** —— 也就是说 `u0.l` 那 492 行依赖的字段，
原生路径**一个都没填**，因为它压根不调 `u0.l`。

#### 🔑 `u0.l(q)` 的 492 行审计结果：返回值是**魔数**

`u0.l` 前 20 条指令就把结构说清了：

```
0000  const/16 v0, #49          ; 默认返回 49（= 0x31）
0002  if-nez v11, 0005
0004  return v0                 ; ★ q == null → 49
0005  iget v1, q.i               ; ★ v1 = te0/q.i（= mediaObject.type()）
0007  iget v2, q.H
0009  iget v3, q.Y
000b  iget v4, q.Z
000d  iget v5, q.x1
000f  iget v6, q.D1
0011  const/16 v7, #76           ; 先过几个硬编码 type：76 / 92 / 93 / 3 / 2001 / 2002 / 2003 / 66
...
00c1  sparse-switch v1, 0x398   ; ★ 大表在这
00c4  packed-switch v1, 0x40e
00c7  packed-switch v1, 0x418
00ca  packed-switch v1, 0x426
00cd  packed-switch v1, 0x430
00d0  return v0                 ; 都没命中 → 49
```

`sparse-switch` 的 29 个 key（直接从 `classes7.dex` 的 payload 解出来，`0x4d4550`）：

| key（`i` = `mediaObject.type()`） | target | 返回值 |
|---|---|---|
| **6** | **`0x02dd`** | **`0x41000031`** ← 十进制 `1090519089`，**正是原生路径 `setType` 的那个常量** |
| 8 | `0x02d9` | `0x00100031` |
| 10 | `0x02d5` | `0x14000031` |
| 13 | `0x02d1` | `0x16000031` |
| 20 | `0x02cd` | `0x18000031` |
| 36/50/51/52 | `0x0339` 等 | 走 `fj1/z`、`fj1/y.ql` 那套（表情/游戏） |
| 2000/2011 | `0x01bd` | 其它 |

⇒ **`te0/q.i == 6` ⇒ `u0.l` 返回 `0x41000031`。** 而 `u0.D` 第 166 条指令就是
`MsgInfo.setType(u0.l(q))`（`0158`/`015c`）—— 和原生路径的 `setType(0x41000031)` **完全等价**。

#### 🔑 `u0.l` 读 `q.d` 只在 `i == 3` 的分支，与 type=6 无关

上一版担心「`q.d` 为 null 会让 type 推错」。实测 `i == 3` 分支（`0x002a`→`0x004c`）：

```
002a  if-ne v1, v7, 004d        ; i != 3 → 跳过
002c  check-cast v9, gx/r0 ...  ; （这段是 Kotlin 单例取用，不影响）
0034  iget-object v10, q.d
003b  xs2/i2.i(d)  → Z          ; ★ 判 d 是不是「系统内置 appId」格式
003f  if-nez Z, 004c
0041  "wx485a97c844086dc9".equals(d) → Z   ; ★ 或者是不是这个特定 appId
004a  if-eqz Z, 004d
004c  return v8                  ; v8 = 0x3e000031  ← 只有 i==3 才返回这个
004d  ...
```

**`i == 6` 在 `0x002a` 就跳过了整段。** 所以 `q.d` 填 `""` 对 type=6 路径**零影响**。

#### 🔑 `u0.A → u0.B → u0.D → u0.r` 四个方法的寄存器映射（全部解出）

```java
// u0.A 是 9 参便利版，只把 5 个额外参数补 null 转 u0.B
static int A(WXMediaMessage msg, String appId, String appName, String talker,
             int n, String s1, String s2, int i2, app/a info) {
    // 0000~0004: v9..v13 = 0（null）  ← 恰好是 u0.B 的后 5 个参数
    return B(msg, appId, appName, talker, n, s1, s2, i2, info,
             null /*Map*/, null, null, null, null);
}

// u0.B 建 te0/q，填 3 个字段，转 u0.D
// registers=26, ins=14 ⇒ 实参落在 v12..v25
static int B(WXMediaMessage msg, String a, String b, String c, int n,
             String d, String e, int r, app/a info,
             Map m, String s1, String s2, String s3, String s4) {
    te0/q q = new te0/q();          // 0000
    q.d = v13;                      // 0006 ← 参数 1  p1 = a
    q.F = v14;                      // 0009 ← 参数 2  p1 = b
    q.r = v16;                      // 000d ← 参数 4  p4 = r（I）
    return u0.D(q /*v0*/, msg /*v1*/, c /*v2*/, d /*v3*/, e /*v4*/, n /*v5*/,
                info /*v6*/, m /*v7*/, s1, s2, s3, s4);
}

// u0.D: registers=? ins=12 ⇒ 实参 v20..v31
// 0000~0013: 重排到 v0..v15，其中 v25 = 参数 5（int n）
// 0015: if-ne v15, 1 → 只有 n==1 才走 ui/tools/j0 那条（进度条类）
// 0023: u0.r(v0=q, v1=msg, v2=?, v8=info)  ★ 先填
// 0158: u0.l(v20=q) → v2                  ★ 后读
// 015c: MsgInfo.setType(v2)
```

#### 🔑 `r5.j` 的 13 个形参 → v 寄存器（用 `tools/_r13_sigmap.py` 实测，不是手算）

```
qf4.r5.j(...)V   PUBLIC FINAL  ⇒ 非 static，ins 含隐式 this
registers=31  ins=14  ⇒  this=v17，形参 v18..v30
```

| # | 类型 | 寄存器 | 备注 |
|---|---|---|---|
| 0 | `this` | `v17` | ★ 漏算这个会让所有形参偏 1 位 |
| 1 | `app/m` | `v18` | 只读 `field_appId` / `field_appName`（父类 `jj/k0`） |
| 2 | `WXMediaMessage` | `v19` | |
| 3 | `LinkedList` | `v20` | talker 列表 |
| 4 | `String` | `v21` | → 透传 `u0.A` p6 |
| 5 | `String` | `v22` | → 透传 `u0.A` p7 |
| 6 | `int` | `v23` | → `u0.A` p8 |
| 7 | `boolean` | `v24` | **`TokenValid`**（见下 §「p7/p8 的真实语义」） |
| 8 | `boolean` | `v25` | 第二门控，只在 `p7 == false` 时才被读到 |
| 9 | `String` | `v26` | → `r5.h` 的入参 2 |
| 10 | `String` | `v27` | → `r5.h` 的入参 3 |
| 11 | `Object` | `v28` | |
| 12 | `a5` | `v29` | |
| 13 | `b5` | `v30` | → `r5.h` 的第 4 参；传 null ⇒ `app/a` 的 d/e/f 全 null |

**`u0.A` 的 9 个形参**（`PUBLIC STATIC` ⇒ ins 不含 this）：

```
registers=23  ins=9  ⇒  v14..v22
```

| # | 类型 | 寄存器 | `r5.j` 实际传什么 |
|---|---|---|---|
| 1 | `WXMediaMessage` | `v14` | `v19` = 形参 2 |
| 2 | `String` | `v15` | `v4` = `app/m.field_appId`，`p8==false` 时强制 `""` |
| 3 | `String` | `v16` | `app/m.field_appName` |
| 4 | `String` | `v17` | `v7` = 当前 talker（iterator） |
| 5 | `int` | `v18` | **`const/4 #2`** ← ★ `q.r = 2` 的真正来源，**不是** `r5.j` 的第 4 参 |
| 6 | `String` | `v19` | `v21` = 形参 4 |
| 7 | `String` | `v20` | `v22` = 形参 5 |
| 8 | `int` | `v21` | `v23` = 形参 6 |
| 9 | `app/a` | `v22` | `r5.h(...)` 的返回值 |

**`u0.B` 的 14 个形参**（`PUBLIC STATIC`）：

```
registers=26  ins=14  ⇒  v12..v25
```

`u0.B` 建 `te0/q` 后只写 3 个字段，然后补 5 个 null 转 `u0.D`：

| `te0/q` 字段 | 值来源 | 走 `r5.j` 时实际是什么 |
|---|---|---|
| `q.d` | `u0.B` p2 = `v13` | `app/m.field_appId`（或 `""`） |
| `q.F` | `u0.B` p3 = `v14` | `app/m.field_appName` |
| `q.r` | `u0.B` p5 = `v16` | **`2`**（`<androidsource>`） |

`u0.A` 补的 5 个 null 正好落在 `u0.B` 的 p10..p14（`Map` + 4 个 `String`），
⇒ `u0.D` 收到的 `Map` / 后 4 个 `String` 全是 null。**原生路径的 `b5` 也是 null**，一致。

#### ⛔ 一条容易犯的错（我本轮先犯过，已修正）

手算参数位置时**漏掉隐式 `this`**，于是把「`r5.j` 第 6 参 `int`」误当成「第 4 参」，
进而把 `v21`（第 4 参 String）和 `v23`（第 6 参 int）的角色搞混，
得出「`const/4 #2` 是 `r5.j` 第 4 参」的错误结论。
**判据：实例方法 `ins = 1 + 形参占位`；`access` 的 `0x8` 位为 0 才需要减这个 1。**
现在这一步已经固化成 `tools/_r13_sigmap.py`，以后别手算。

#### 🟡 与原生路径的**唯一实质差异**：`te0/q.r` 是 `2` 而不是 `4`

`q.r` 的语义在 `te0.q.c` 里查实（`0x0b62`）：

```
0b5d  const-string v2, "<androidsource>"
0b62  iget v2, v8, te0/q;.r:I
0b64  StringBuilder.append(I)
0b67  const-string v2, "</androidsource>"
```

**`q.r` = appmsg XML 的 `<androidsource>`**，表示「这条消息从哪个客户端发出」。

- 原生「+→选文件」路径写 `4`（Android 端「文件」入口）
- `r5.j` 经 `u0.A` 固定写 `2`（`<androidsource>2` = Android 兼容来源）

两者都是合法值，微信自己的「转发/分享文件给好友」（`r5.l`/`r5.j` 那条）用的就是 `2`。
**唯一可见影响**：接收端「来源」信息不同，不影响能否收到、能否下载打开。

#### 🟡 第二个差异：`q.d` / `q.F` 的值

| | 原生路径 | `r5.j` 路径 |
|---|---|---|
| `q.d` | `""`（硬编码空串，`0111`） | `app/m.field_appId`（可兜底 `""`） |
| `q.F` | `""`（硬编码空串，`0113`） | 参数 2（`r5.j` 里是 `v26`） |

`q.d` 在 `te0.q.c`（序列化）里**不被读取**（实测 0 处），只在 `u0.l` 的 `i==3` 分支读 ——
type=6 不走那条。`q.F` 在全库只有 31 处读取，主要给「消息重发 / 收藏」用。

⇒ 对**首次发送**无影响。若后续要做「转发已存在的文件消息」，`q.F` 会被 `u0.w` 用到（`0075` 处）。

#### ✅ 因此 R13 的定档：**B1 的把握从「中」升到「高」，且不需要再审计 812 行的 `r5.d`**

`r5.d` 812 行之所以之前被视为阻塞，是因为假设 type=6 要穿过它。**实际不需要**：
`r5.j` 在 `getType()==6` 时先做完路径规范化，然后在 talker 循环里**每个 talker 各自**
调 `u0.A`（已解完，见上），`u0.A` 的存在本身就证明这条链是微信自己复用的「造 appmsg + 入网」路径。

仍然未知但不阻塞的：`r5.d` 812 行里 `getType()==2`（图片）/`==5`（表情）/`==4`（视频）那几条分支细节 ——
**A 档走的是 `r1.a(o1.e/f)`，根本不进 `r5.d`。**

### 🟢 因此 `resendFile` 的正确落法（本轮修订）

**A 档不变**（图片/视频走 `r1.a(o1.e/f)` + `q1.g` + 直写 `q1.j` + `q1.h=13`），因为那条链路
已逐字段对齐微信自己的 `O8`/`c9`，可信度最高，且**不依赖 `te0/q` 那套 190 字段**。

**普通文件（B 档）改为两段式验证**，不要一次写完全部：

1. **B1（便宜，先做）**：只造 `WXFileObject` + `WXMediaMessage` + `new app/m()`（只填 `field_appId`/`field_appName`），
   取 `qf4/r5` 的**静态字段 `a`** 拿单例后反射调 `r5.j(...)`，参数 4/5/9/10 传 `""`、6 传 `0`、7 传 `true`、8 传 `false`。
   **判据**：日志里出现 `MicroMsg.AppMsgLogic` 的 `summerbig content url:` 行（`4d35c4`，说明
   `u0.D` 已被执行到），且 `a8.F0(path)` 分支没错进 `copyFile failed`。

   > ⛔ **取单例不要写 `r5.a()`** —— `qf4/r5` **没有无参的 `a()` 工厂**。它的 Direct methods 只有
   > `<clinit>()V`、`a(Lqf4/r5;Lte0/q;Lq54/vb6;)V`（3 参 void）、`e`、`i`、`l`。
   > 单例在 `<clinit>` 里 `new-instance + <init>()V + sput-object a` 建好（`846a84`~`846aa2`），
   > 正确取法是 `XposedHelpers.getStaticObjectField(r5Cls, "a")`。
2. **B2（贵，看 B1 结果再定）**：查 `message` 表 `isSend=1 and type=6` 是否落库，且对端收到。

⛔ ~~**`u0.l(q)` 的 492 行不逐个审计完，不许承诺「普通文件一定能发」**。~~

✅ **R13 已审计完**（见上一节「补充闭合」）。`u0.l` 在 `i == 6` 时返回 `0x41000031`，与微信原生
「+ → 选文件」路径 `MsgInfo.setType(0x41000031)` 的常量**完全一致**；`u0.l` 读 `q.d`
只在 `i == 3` 分支，与 type=6 无关。风险从「字段 null 导致静默失败」降级为
「`q.r` 写 2 而非 4（`<androidsource>` 不同）」，**不影响送达**。

### 📌 与原 B 档说法的差异

| | 原 B 档（作废） | 本轮修正 |
|---|---|---|
| 入口 | `o4.z8(byte[], ...)` | **`qf4/r5` 静态字段 `a` → `.j(...)`**（⚠️ 不是 `a()`） |
| 载体 | 字节流全量读内存 | `WXFileObject.setFilePath(path)` 走路径 |
| 参数 | 4 个 | `r5.j` 13 个（其中 8 个可传 `""`/`0`，已实测有兜底） |
| appInfo | 「需要 14 参 `u0.A`」 | **`app/a` 纯 POJO 6 字段，直写即可** |
| 微信自己在用 | 3 个调用点 | AppBrand 分享文件**就是这条** |

### 🟢 `resendFile` 的落地档位（2026-10-23 **R13 修订版**）

| 档 | 做法 | 把握 | 代价 |
|---|---|---|---|
| **A（先做）** | 只支持「文件实为图片/视频」：`kind==IMAGE` 走 `r1.a(o1.e)`+`q1.g(fileName)`；`kind==VIDEO` 走 `r1.a(o1.f)` | **高**：微信自己就是这么发的（`O8`/`c9` 逐字段对上） | 普通文件仍然发不了 |
| **B（第二档）** | `new WXFileObject()`+`setFilePath` → `WXMediaMessage` → `new app/m()` → 取 `qf4/r5.a` 静态字段后反射调 `j(...)` | **高**（R13 升级）：`u0.l`=492 行已审计完，`i==6 → 0x41000031`；`u0.A→B→D→r` 四层寄存器映射全部解出；`r5.d` 812 行**无需**穿过 | 需真机验证落库 type（探针即可） |
| ~~B2~~ | ~~先审计 `u0.l` 再写 B~~ | **已合并进 B**：R13 已审计完 | — |
| ~~C~~ | ~~`o1.i[APPMSG]` 造 builder~~ | **已废**：`q1.a()` 对 APPMSG 分支走 `t81/g.i(q1)`，第一句就 `instance-of o3` ⇒ 恒得 null ⇒ NPE 被吞 | — |

**B 档现在只剩 1 个未验证点**：`message` 表里落库的 `type` 是不是 `0x41000031`（十进制 `1090519089`），
以及对端能否正常显示/下载。这两条**都只能真机验**，静态已到顶。

**已知与原生路径的 2 处差异（都不阻塞）**：

| 字段 | 原生「+→选文件」 | `r5.j` 经 `u0.A` | 影响 |
|---|---|---|---|
| `te0/q.r` | `4`（`0118` 硬编码） | `2`（`r5.j` 内部 `const/4 #2` → `u0.A` p5 → `u0.B` p5） | 只是 XML `<androidsource>` 不同，**不影响送达** |
| `te0/q.d` / `.F` | 都是 `""`（硬编码） | `field_appId` / `field_appName` | `q.d` 只在 `u0.l` 的 `i==3` 分支读，type=6 不走；`q.F` 首发送无影响 |

⛔ **仍然不要做的事**：在没有 A 档真机验证之前，直接写 B。理由同 `nativeForward`——
「严禁猜微信内部 API，猜错就是崩微信」。A 档顺带能验出 `q1` builder 本身的正确性，
B 档再上时只需要验 `r5.j` 这一层。

### 建议的实现顺序（下一轮真机批之后）
1. 先落 **A 档**（图片/视频转发降级），真机验「落库 type=3/43 + 对端收到」；
2. A 档通了再上 **B1**（探针：一次点发，看 `MicroMsg.AppMsgLogic` 是否执行到）；
3. 无论哪档，`resendFile` 都要保持「链路不可用 → 返回 false → 上层按失败计数」的契约。

### ✅ B 档探针规格（R13 定稿，参数已全部落定，可直接写代码）

```kotlin
// 目标：不经过任何 UI，只调 qf4/r5 把本地文件发给 talker
// 全部字段/签名来自 classes7.dex 反汇编，逐条有地址

fun sendFileProbe(talker: String, filePath: String): Int {
    val WXFileObject = XposedHelpers.findClass(
        "com.tencent.mm.opensdk.modelmsg.WXFileObject", cl)
    val WXMediaMessage = XposedHelpers.findClass(
        "com.tencent.mm.opensdk.modelmsg.WXMediaMessage", cl)
    val AppInfo = XposedHelpers.findClass(          // 注意：app/a，不是 app/m
        "com.tencent.mm.pluginsdk.model.app.a", cl)

    // ── 1. WXFileObject：只 setFilePath，不碰 fileData ──
    val fileObj = XposedHelpers.newInstance(WXFileObject)
    XposedHelpers.callMethod(fileObj, "setFilePath", filePath)
    // type() 硬编码 return 6，不用设

    // ── 2. WXMediaMessage：只设 mediaObject + title + description ──
    val msg = XposedHelpers.newInstance(WXMediaMessage)
    XposedHelpers.setObjectField(msg, "mediaObject", fileObj)
    XposedHelpers.setObjectField(msg, "title", filePath.substringAfterLast('/'))
    XposedHelpers.setObjectField(msg, "description", "")   // 原生路径这里是可读文件大小，可省
    XposedHelpers.setIntField(msg, "sdkVer", 0)            // → te0/q.e，可 0
    // mediaTagName / messageAction / messageExt 不设 → null，u0.r 会原样搬进 q.V/W/X

    // ── 3. app/a：6 字段纯 POJO（r5.h 的产物），直 new + 全填空串 ──
    //    字段 a=入参1(String) b=入参2(String) c=入参3(String)
    //         d/e/f = qf4/b5 的 b/c/d，b5 传 null 则这三个都是 null
    val appInfo = XposedHelpers.newInstance(AppInfo)
    // 实测 u0.A 只读 field_appId / field_appName（来自 app/m），appInfo 里的 6 字段
    // 仅被 u0.D 搬进 appmsg，填 null 也能跑通（原生路径的 b5 就是 null）

    // ── 4. 取 r5 单例 + 调 j(...) ──
    val R5 = XposedHelpers.findClass("qf4.r5", cl)
    val inst = XposedHelpers.callStaticMethod(R5, "a")   // 单例工厂，无参

    // 形参顺序见上一节「13 个形参 → v 寄存器」表，逐条对齐
    XposedHelpers.callMethod(inst, "j",
        /* 1  app/m   */ appMStub,        // ⚠️ 见下方注意 1
        /* 2  msg     */ msg,
        /* 3  talkers */ LinkedList<String>().apply { add(talker) },
        /* 4  String  */ "",               // v21 → u0.A p6
        /* 5  String  */ "",               // v22 → u0.A p7
        /* 6  I       */ 0,                // v23 → u0.A p8
        /* 7  Z       */ true,             // v24 = TokenValid（见 §「p7/p8 的真实语义」）
        /* 8  Z       */ false,            // v25 只有 p7==false 时才被读到，这里给 false 是双保险
        /* 9  String  */ "",               // v26 → r5.h 入参 2
        /* 10 String  */ "",               // v27 → r5.h 入参 3
        /* 11 Object  */ null,             // v28
        /* 12 a5      */ null,             // v29 回调；null 时 r5.j 内部走默认
        /* 13 b5      */ null              // v30 → r5.h 第 4 参；null ⇒ app/a 的 d/e/f 全 null
    )
    return 0
}
```

**注意 1（`app/m` 这个唯一需要编造的对象）**：
`r5.j` 只读它的两个字段 —— `field_appId`（`0183`）和 `field_appName`（`0191`），
这两个字段**定义在父类 `jj/k0`** 上（实测 `app/m` 的 `Superclass = Ljj/k0;`）。
所以直接 `XposedHelpers.newInstance(appMClass)` 造一个**未初始化**的实例，然后写这两个字段即可，
不必走 AppBrand 初始化：

```kotlin
val appM = XposedHelpers.newInstance(XposedHelpers.findClass(
    "com.tencent.mm.pluginsdk.model.app.m", cl))
XposedHelpers.setObjectField(appM, "field_appId", "wx0")     // 任意合法 appId 形态
XposedHelpers.setObjectField(appM, "field_appName", "probe")
```

⚠️ **`p7`/`p8` 的语义下一节单独讲**，此处旧说法「传 `false` 会强制 `""`」**已作废**（方向搞反过两次）。

---

### 🔴 `r5.j` 的 p7 / p8 —— 真实语义（R13c 定案，我前三次全错）

#### 0. 一切的根：Dalvik 这两条指令的语义

| 指令 | 含义 |
|---|---|
| `if-eqz  vAA, +BBBB` | **为零**则跳转（`e` = equal zero） |
| `if-nez  vAA, +BBBB` | **非零**则跳转（`n` = not equal zero） |

自证（`r5.j` 自己的 `fileData` 空判，`_cache/_r13_r5j.txt` `0018`~`0037`）：

```
0018  iget-object v0, v4, WXFileObject.fileData:[B
001c  if-eqz v0, 0026      ; fileData == null → 跳 0026（去查 filePath）
001e  array-length v0, v0
001f  if-nez v0, 0023      ; length != 0 → 跳 0023
0021  move v0, v5  (#1)    ; 落到这里 = length == 0
0023  move v0, v6  (#0)    ; 跳来 = length != 0
0024  if-eqz v0, 0036
0026  iget-object v0, v4, WXFileObject.filePath   ← 只有 fileData 为空才查 filePath
```

若 `if-nez` 是「为零跳转」，上面就变成「fileData 非空时才去查 filePath」，
与紧接的 `checkArgs fail, both arguments is null` 完全说不通。
**只有 `if-nez` = 非零跳转才自洽。**

#### 1. 门控真代码（`0183`~`0190`）

```
0183  iget-object v4, v1, Ljj/k0;.field_appId      ; v4 = appInfo.field_appId
0187  o.f(v4, "field_appId")                       ; Kotlin 非空断言（null 会 NPE）
018a  if-nez v2, 0190      ; p7 != 0  → 跳 0190【保留 appId】
018c  if-eqz v25, 0190     ; p8 == 0  → 跳 0190【保留 appId】
018e  const-string v4, ""  ; ★ 落到这里 = p7 == 0 且 p8 != 0 → 清空
0190  move-object v5, v4   ; v5 = 最终 appId → u0.A 第 2 参
```

**⇒ 清空条件是 `!p7 && p8`。**

#### 2. 我错过的三个版本（全部留作反面教材）

| 版本 | 当时的说法 | 错在哪 |
|---|---|---|
| R13 初版 | 「传 `false` 会把 appId 强制成 `""`」 | 只看见 `018c`，把 `if-eqz` 当成「为真才跳」 |
| R13b 第一稿 | 「传 `true` 才保留 appId」 | 结论碰巧对，但理由仍错（还是把 `if-eqz` 读成「为真才跳」） |
| **R13b 提交版** | **「`if-nez`/`if-eqz` 相邻且目标相同 ⇒ OR，清空条件是 `p7 && p8`」** | **整条结论是错的**：`if-nez` 是「非零跳」，我读成「为零跳」，于是把门控关系彻底搞反了 |
| **R13c（本节）** | **`if-nez` = 非零跳；清空条件 `!p7 && p8`** | ✅ 经独立复核 + 自证确认 |

> ⛔ **判据沉淀（这条比结论本身更值钱）**：
> 相邻两个 `if-*z` 跳向**同一个 label**，**不等于**它们是 OR 关系。
> 关系取决于**各自的跳转条件**。`if-nez` + `if-eqz` 指向同一 label
> ⇒ 条件分别是「非零」「为零」，凑在一起是 `p7==0 || p8!=0` ⇒ 落到清空分支的是两者的**补**。
> **正确做法：先把每条指令的跳转条件写成布尔表达式，再合并。永远不要靠「相邻」「同目标」这种形态特征推断语义。**

#### 3. p7 = `TokenValid`

```
WXEntryActivity.t6           0520  putExtra("SendAppMessageWrapper_TokenValid", WXEntryActivity.i)
SendAppMessageWrapperUI.onCreate  639fca  iput-boolean → UI.p:Z
SendAppMessageWrapperUI.s6   63acb6~63ad0e  UI.p → r5.l 第 6 参(Z) → r5.d p7 → n5.h → r5.j p7
```

⚠️ 两个细节（R13c 复核补正）：
- **`r5.l` 是 `PUBLIC STATIC`，`TokenValid` 在 `r5.l` 里是第 6 参 `v22`，不是第 7 参。**
  第 7 参 `v23` 才是 `blockInvalidToken`。之前我把 `r5.l` 和 `r5.d` 的序号混着说，是错的。
- 默认值 `getBoolean(key, const/4 #1)` = `true` 只在 **Bundle 缺该 key** 时成立；
  微信经 `WXEntryActivity` 时写入的是 `WXEntryActivity.i` 的**真实值**，不保证为 true。

#### 4. p8 = `blockInvalidToken`（不是我上一版说的「无关字段」）

```
SendAppMessageWrapperUI.s6   63acba~63ad0e  q6() → r5.l 第 7 参(Z) → r5.d p8 → n5.i → r5.j p8
q6() 实现 638ad8~638afe：返回 UI.t:Z，日志原文 "blockInvalidToken block %b"
```

上一版说「p8 在链路上没有任何字段读点，只参与 `018c`」——
**读点数量那半句没错**（`v25` 在 `r5.j` 全方法只出现 1 次，就是 `018c`），
但**语义那半句是瞎猜**，实际它就是 `blockInvalidToken`，有明确出处。

#### 5. 业务含义（把两边接上）

`TokenValid == false && blockInvalidToken == true` ⇒ 把 appId 清成 `""`。
即：**token 无效、且调用方要求拦截无效 token 时，才抹掉 appId**（不让它冒用别人的 appId 发消息）。

#### 6. 探针为什么传 `(p7=true, p8=false)`

清空条件是 `!p7 && p8`。取 `p7=true` ⇒ `018a` 直接跳到 `0190`，
**`018c` 根本不会执行** ⇒ appId 一定保留，与 p8 取值无关。

这个选择的目的是**保住自己填的 `field_appId` 不被清成 `""`**，
不是为了复刻微信某个场景。而 `q.d=""` 对 type=6 路径**零影响**
（`u0.l` 只在 `i==3` 分支读 `q.d`），所以两种取值都不影响「能不能发出去」。

> ⛔ **若将来把这条链接进正式业务**，必须按真实场景重算 p7/p8，
> 不能沿用探针这两个值。

#### 7. 探针最终取值

```kotlin
/* 7  Z */ true,    // TokenValid=true ⇒ 018a 直接跳 0190，appId 必保留
/* 8  Z */ false,   // blockInvalidToken；p7=true 时 018c 不执行，此值无影响
```

**判据（4 条，按顺序）**：

| # | 观察点 | 期望 |
|---|---|---|
| 1 | `WXFileObject.type()` | 返回 `6`（硬编码 `const/4 v0,#6`） |
| 2 | 日志 `MicroMsg.AppMsgLogic` | 出现 `summerbig content url:` 行（`u0.D` 的 `4d35c4` 处，证明 walk 到 appmsg 组装） |
| 3 | 日志 `errFile2Uri` / `copyFile failed` | **都不出现**（出现即 `r5.j` 的路径规范化失败，是参数问题不是链路问题） |
| 4 | `message` 表 | `select type,isSend from message where talker=? order by createTime desc limit 1` ⇒ `type=1090519089, isSend=1` |

⚠️ **关于 `0x41000031` 的符号**（实测复核，别被「看着很大」误导）：
`0x41000031 = 1,090,519,089`，`Int.MAX_VALUE = 2,147,483,647` ⇒ **没溢出，是正数**。
所以 `setType(0x41000031)` 和数据库里读出来的 `1090519089` 是同一个值，比对时**不要**做符号转换。
（对比：`sparse-switch` 里 key=20 返回的 `0x90000009 = -1,879,048,183` **是**负数 —— 那种才需要
按有符号处理。这也是为什么不能拿「看着像魔数」当判据，必须实算。）

### 探针建议（A 档部分，等 A 档落地时一并做）
`op=forward sub=filetest --es val <talker>` → 用 `raw` 里的本地路径走一次 A 档，打印
`l1.a()` 的返回与日志 `FORWARD resend-file kind=... ok=...`，再查 `message` 表确认
`type in (3,43) and isSend=1`。

---

