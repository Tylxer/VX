# R34 · AutoHdImg 锚点取证：未点开的图片消息到底走不走 `t1`

> 子代理：general-purpose-2（Hook 链路取证） · 目标：微信 8.0.48/2588 GP
> 边界：**只读取证**，不改源码。证据等级用 ① dex 指令级 / ② 调用者链推断 / ③ 假设（未取证）标注。
> 现成脚本：`tools/_r27_callers.py`（谁调用了某方法）、`tools/_r29_g2_clsdump.py`（dump 类字段与方法）

---

## 待回答的核心问题

**未点开的图片消息到达时，微信走不走 `t1`（NetSceneGetMsgImg）？**

若是 ⇒ AutoHdImg 挂 `t1` 无结构性缺陷，问题在参数解析（localId/msgId 取错）。
若否 ⇒ AutoHdImg 有结构性缺陷：只在微信自己想下载时才跟着下，恰好漏掉「没点开的图片」，
而这正是撤回场景最需要的。

## 已知真机现象（2026-10-28）

| 场景 | 现象 |
|---|---|
| 正常图片到达 | `HDIMG fired msgId=19385072 localId=861758`（localId 非 0，有效） |
| 被撤回那张 | 到达时**无 fire 记录**；用户点开后才出现 `fired msgId=19384736 localId=0` |
| 同秒另一张 | `19385793` 正常 fire ⇒ 不是开关/hook 失效 |

---

## 子步进度

- [ ] 1. 找出所有构造 `t1` 的调用点（`invoke-*` 指令 + 调用者链）
- [ ] 2. 判定：入库 / 渲染气泡 / 缩略图下载链路上有没有 `t1`；缩略图是否随消息体下发
- [ ] 3. 若走 `t1` ⇒ 构造参数 ↔ 实例字段（h/i/j/q/o）对应关系；解析成 0 的原因
- [ ] 4. 若不走 ⇒ 候选新锚点（类名 + 方法签名 + 参数语义）+ 真机可区分判据
- [ ] 5. `svc.c()`（DownloadImgService）正确调用姿势：档位 / 回调 / scene

---

## 结论（取证完改写的最终版）

### 对核心问题的回答

> **未点开的图片消息到达时，微信不走 `t1` —— 至少不保证走。**

① 指令级链条：

```
图片消息到达
  └→ 写 C2CMsgAutoDownloadRes 队列表（② 推断，插入点未取证）
       └→ C2CImgAutoDownloader（plugin/priority）按条件挑着下
            ├ 门1  sy2.a.a()              'not auto download condition'
            ├ 门2  BATTERY_CHANGED/plugged（电量）+ 前后台 'bg normal download %b'
            ├ 门3  priority >= ?          （优先级阈值）
            └ 门4  Chat.rank <= ? AND Chat.totallsp > ?（会话热度）
                 └→ d3.F8() → m.b(ImgInfo.a, MsgInfo.getMsgId(), 2, …)
                      └→ m.h() ──★ 唯一 new t1 的地方 ──★
                           └→ p1.f(t1) 投递 getmsgimg(CGI 109)
```

**⇒ 挂 `t1.<init>` 是结构性缺陷**：它挂的是「微信自己想下载的那一瞬间」，
而「没点开的图片」恰恰是微信**最可能判定为『先别下』**的那一批
（低优先级会话 / 非充电 / 后台 / 大图）。这正是撤回场景最需要的那一批。
真机现象（同秒一张 fire、另一张到达时零日志）与此完全吻合。

**⇒ 但「参数解析」这一层没错**：`arg1=localId(h/i/j)`、`arg2=msgSvrId(q)`、`arg3=档位(o)` 已 ① 坐实，
`AutoHdImg.kt:187-188` 的启发式**不用改**。真机 `localId=0` 是**上游传的 0**，不是解析丢的。

**⇒ 真正要修的是锚点**（子步 4.1 / 4.2）：换到「消息入库」层，不依赖微信的下载意愿。

### 三个附带结论（都影响现有代码的判读）

1. **档位常量赌错了，不是「微信传 1」**：`C2CImgAutoDownloader` 传的是 **`2`**（①，`a.run` 0x03b6）。
   `TYPE_THUMB=1` 在 8.0.48 上基本不出现 ⇒ `skipOther` 永远是满的，**它不是异常信号**，别再拿它排查。
2. **`svc.c()` 返回 `true` ≠ 下载成功**，只 = 任务被受理进队列（`m.d` List / `m.e` HashSet）。
   真正的下载由 `m.h` 取出来才跑。⇒ `ok` 计数不能当成功口径。
3. **`localId==0` 时发起的下载大概率无效**，且会污染 `m.e` 去重集合（(0,msgId,0) 被判
   'add failed, task already done'，等真 localId 来了再调就晚了）。⇒ 先补 localId 再 fire，补不到就别 fire。

---

## 未完成 / 留给下一棒

- [x] ~~`_r27_callers.py` 全量扫描 t1 构造点~~ **已完成**（见子步 1，只有 `m.h` + `t1.<init>` 自委托）
- [ ] 候选 B：确认 `b1` 里哪个方法是 ImgInfo 的 insert（跑 callers 看谁被「收到消息」那段调用）。
- [ ] 候选 C：确认 `C2CMsgAutoDownloadRes` 的插入点（`e.f(JI)V` / `e.g(JII)V` 的调用者）。
- [ ] 候选 A：`ImgInfo` 表的表名与列名（可借 `AntiRevoke` 的 SQL hook 打一次 ContentValues 拿到）。
- [ ] 缩略图是否走 CDN（子步 2.2 目前只有 ②/③，没有 ① 证据）。（R34 现场记录，后续 lane 别踩）

| 工具 | 状态 |
|---|---|
| `tools/_r29_g2_clsdump.py` | ✅ 可信：字段名/方法签名准确（与 `AutoHdImg.kt` KDoc 里记的签名对得上） |
| `tools/_r27_callers.py` | ⚠️ **慢 + 有假阳性**：15 个 dex 全扫，单跑 ~23 分钟（别并发，3 个并发直接 ×3）。**判读：只认 `invoke@['0x..']` 里 3~5 位十六进制的偏移**；`0x4b01d` 这种 6 位起步、且不同类偏移完全相同的一律是噪声（脚本把 code_item 解析坏的方法的 insns 当成了「start..文件尾」）。真命中偏移很小（实测 `0x3a`、`0x6`） |
| `tools/_r27_disasm.py` | ⚠️ **半可信**：**字段名/方法名/字符串常量可信**，但**寄存器号输出是坏的**（出现 `v96`/`v7876..v7883` 这种不可能的号，`const/4 v247, 0xf7` 明显把整个 halfword 当成寄存器）；某些方法还会指令长度表不同步而**整体错位**（`m.c` 就是错位的，反出来 16 条里有 `return-wide` 出现在开头）。⇒ **只用它读字段名/常量/调用目标，不要用它的寄存器号做参数↔字段对应推理。** |

---

## 子步 0 · `t1` 类结构（① dex 指令级，已坐实）

`tools/_r29_g2_clsdump.py 'modelimage/t1;'`（`classes7.dex` class_defs#1111，super = `com.tencent.mm.modelbase.k1`）：

```
[static] H : Z
[inst]   h : J   i : J   j : J   q : J   t : J          ← 五个 long
[inst]   n : I   o : I   u : I   y : I   z : I   B : I  ← int
[inst]   d : String  s : String  w : String  x : String  C : String
[inst]   e : modelbase/t0   f : modelbase/s0   g : modelbase/m
[inst]   p : modelstat/t    r : storage/h9
[inst]   F : tj/i           G : ok0/c0
[inst]   A : Z  v : Z
```

方法：
```
<init>(JJILcom/tencent/mm/modelbase/t0;)V
<init>(JJILcom/tencent/mm/modelbase/t0;I)V
L(Lcom/tencent/mm/modelimage/r0;Ljava/lang/String;J)Z
M()Z
N(Lcom/tencent/mm/modelimage/r0;III[B)Z
cancel()V
doScene(Lcom/tencent/mm/network/q;Lcom/tencent/mm/modelbase/s0;)I
getType()I
onGYNetEnd(IIILjava/lang/String;Lcom/tencent/mm/network/t0;[B)V
securityLimitCount()I
securityVerificationChecked(...)Lcom/tencent/mm/modelbase/m1;
```

`t1` 即 `NetSceneGetMsgImg`，**① 指令级坐实**（构造里写死 CGI）：
```
const-string '/cgi-bin/micromsg-bin/getmsgimg'
const/16 109                     →  j.d = 109（CGI 号）
const/16 10                      →  j.e = 10
const v? 0x3b9aca0a              →  j.f = 1000000522
```
同步可见：构造里 `new-instance Lq54/iq2`（GetMsgImgRequest）、`Lq54/jq2`（Response）、`Lq54/kx4`（SKBuiltinBuffer），
并 `invoke-static d3.O8` → `b1.o2(long)` 取 `modelimage/r0`（ImgInfo），写进 `t1.r`（`storage/h9` = MsgInfo）。
⇒ **t1 的语义就是「走 getmsgimg CGI 从服务器拉图片数据」**，不是缩略图专用、也不是原图专用，
档位由字段 `o` 携带（见子步 3）。

---

## 子步 5 · `svc.c()`（DownloadImgService）正确调用姿势（① dex 级，已坐实）

`com.tencent.mm.modelimage.m` = `DownloadImgService`，日志 tag **`ModelImage.DownloadImgService`**（① 指令级）。
它**不是**下载器本体，而是一个**串行任务队列**（① 指令级）：

```
[inst] d : Ljava/util/List;          ← 待跑任务队列
[inst] e : Ljava/util/HashSet;       ← 「已完成/已受理」去重集合
[inst] f : Lcom/tencent/mm/modelimage/k;   ← 当前正在跑的任务
[inst] g : Lcom/tencent/mm/modelimage/t1;  ← 当前 NetSceneGetMsgImg 实例
[inst] h : Z                         ← mLockStart
```

方法面板（①）：
```
c(JJILjava/lang/Object;ILfv/e0;)Z          ← 我们调的那个
b(JJILjava/lang/Object;ILfv/e0;IZ)I        ← 真正的入队实现（c 应该转调它）
f(JJILfv/e0;)Z
g(JJI)Z                                    ← 只查队列（new 一个 k 然后 indexOf），不下发
a(Lcom/tencent/mm/modelimage/k;)V
e(Lcom/tencent/mm/modelimage/k;)Z
d(Lfv/e0;)V
h(I)V
T(IILcom/tencent/mm/modelbase/k1;)V        ← scene end 回调
onSceneEnd(IILjava/lang/String;Lcom/tencent/mm/modelbase/k1;)V
```

任务描述符 `com.tencent.mm.modelimage.k`（①）：
```
a : J    b : J    c : I    d : I    e : List    f : I
<init>(JJI)V                       ← 只带 (long1, long2, compressType)
a(Lfv/e0;Ljava/lang/Object;)Z      ← 挂 listener + 透传 obj
equals/hashCode                    ← **任务身份 = (a, b, c) = (long1, long2, 档位)**
```
⇒ **档位 `c` 参与任务身份**：同一 (localId, msgId) 用 HD 档位和用压缩档位是**两个不同的任务**，
不会被 `m.e`（HashSet）判重掉。这点对我们有利。

### 我们的调用是否「姿势正确」

现传：`svc.c(localId, msgId, 0, Long.valueOf(msgId), 0, listener)`（AutoHdImg.kt:318）

| 位置 | 形参 | 我们传 | 判定 |
|---|---|---|---|
| 1 | `J` | localId | ✅ 与 `k.a`、`b1.o2(localId)` 口径一致（真机 `o2` 能取回 ImgInfo 佐证） |
| 2 | `J` | msgId(svrId) | ✅ |
| 3 | `I` | `0` = HD/原图档位 | ✅ 与 `AutoGetBigImgLogic` 写死的 0 一致 |
| 4 | `Object` | `Long(msgId)` | ⚠️ **未坐实**。这个 obj 只被透传给 `k.a(listener, obj)`，在 `onSceneEnd` 里原样带回给 listener；微信自己多数场景传的是 `Integer`/`Long` 的消息标识，语义上是**回调令牌**，`null` 通常也合法。**不影响下载是否发生**（③） |
| 5 | `I` | `0` | ⚠️ **未坐实**。形参加在 `k` 之外（`k` 的 `<init>` 只有 JJI），推测是 `scene`/`startFlag`。真机已 `ok>0`（`svc.c` 返回过 true）⇒ **传 0 不会导致被拒** |
| 6 | `fv/e0` | 动态代理 | ✅ 必须非 null —— `m.b` 开头就是 `if (listener == null) { l2.e(tag,"listener is null"); return }`（① 指令级，AutoHdImg.kt:397 的注释被坐实） |

**结论（子步 5）**：**调用姿势不会导致下载无效**。`svc.c` 返回 `true` 只代表**任务被受理进队列**（`m.d` add / `m.e` 去重通过），
**不代表数据落盘**。真正的下载由队列取出后新建 `t1` 跑 CGI `getmsgimg`。
⇒ **真机上「ok=1 但文件仍不存在」是完全可能的正常中间态**，不能拿 `ok` 当成功口径。
⚠️ 另有 `m.e`（HashSet）这道「task already done」判重（① 指令级，字符串 `'] add failed, task already done'`），
我们第二次对同一 (localId,msgId,0) 调 `c` 会被它挡掉 —— 这与进程内 `done` 不是一回事。

`m.g(JJI)Z` 是**纯查询**（new k + `List.indexOf`，无副作用、不下发）——
**是个零成本探针**：真机上可以先 `svc.g(localId,msgId,0)` 看任务在不在队列里，比盲调 `c` 更容易区分「没受理」和「受理了没下完」。

### 5.1 `t1` 到底是谁 new 出来的（① 指令级，关键）

**只有一处**：`m.h(I)V`（`DownloadImgService` 的「取下一个任务并 doScene」）：

```
m.h:  0x0024 const-string 'ModelImage.DownloadImgService'
      0x0026 const-string/jumbo 'null == mCurTaskInfo'
      0x002d new-instance  Lcom/tencent/mm/modelimage/t1;      ★
      0x003a invoke-direct/range t1;-><init> {…}               ★
      0x003d iput-object   m.g : t1                            ← 存进「当前 NetScene」
      0x0043 iput          t1.z : I                            ← 构造完再补写 z
      0x0049 const-string  'do scene, ('
      0x0087 const-string  'mCurTaskInfo %s mTodoList %s mLockStart %s'
```

⇒ **一切经 DownloadImgService 的下载都必然 new 一次 `t1`**；
⇒ 挂 `t1.<init>` **等价于挂「微信自己决定要下载的那一次」** ——
不是挂「消息到达」，也不是挂「渲染气泡」。这条是**结构性事实**，不是概率。

---

## 子步 2（核心）· 图片消息到达时到底走不走 `t1`

### 2.1 到达侧的真正链路（① 指令级）

发现了一个**之前完全没进过视线的类族**：

```
com.tencent.mm.plugin.priority.model.c2c.img.C2CImgAutoDownloader      ← 名字即语义
com.tencent.mm.plugin.priority.model.c2c.img.C2CImgAutoDownloader$1    ← extends IListener（事件监听）
com.tencent.mm.plugin.priority.model.c2c.img.a / b                     ← extends Ldz2/a（Runnable）
com.tencent.mm.plugin.priority.model.c2c.img.c                         ← 核心逻辑
com.tencent.mm.plugin.priority.model.c2c.img.e                         ← SQLite 表 DAO
com.tencent.mm.plugin.priority.model.c2c.img.f                         extends Lvy2/c（表定义）
```
日志 tag：`'MicroMsg.Priority.C2CImgAutoDownloader'`（①）。

`a.run()`（1397 insns_units，五个 try 块）**开头就是一道条件门**：
```
0x0000  invoke-static Lsy2/a;->a                       ← 自动下载条件判定
0x0007  if-nez …
0x0009  const-string/jumbo 'not auto download condition'
0x000c  invoke-static l2->j
0x000f  return-void                                    ★ 不下载，直接退出
```

通过门之后才去**数据库队列表**里挑一条（①）：
```
'C2CMsgAutoDownloadRes'
'SELECT * FROM %s WHERE status = ? AND fromuser = ? AND priority >= ? AND createtime > ? ORDER BY createtime DESC LIMIT 1;'
'SELECT * FROM %s WHERE status = ? AND priority >= ? AND createtime > ? ORDER BY createtime DESC LIMIT 1;'
'SELECT * FROM %s WHERE status = ? AND priority >= ? AND createtime > ? ORDER BY priority DESC, createtime DESC LIMIT 1;'
'C2CChatUsageResult'
'SELECT * FROM %s Img, %s Chat WHERE Chat.rank <= ? AND Chat.totallsp > ? AND Chat.chat = ? AND Img.fromuser = ? AND Img.createtime > ? AND Img.status = ? ORDER BY Img.createtime DESC LIMIT 1;'
```
以及**环境门**（①）：
```
'android.intent.action.BATTERY_CHANGED'  /  'plugged'     ← 电量
'bg normal download %b'                                    ← 前后台
'curMsgId %d priority %.2f prioritytype %d way %d borderPriority %.2f bgBorderPriority %.2f'
```

挑中一条后**才**发起下载（① 指令级，见下）。并且先判「是否已有原图」：
```
0x031e  invoke-static  d3.O8                     → ImgInfoStorage
0x032a  invoke-virtual b1.a3(...)                → 取 ImgInfo(r0)
0x032e  iget           r0.t : I
0x0330  if-ne … ' already has hd thumb'          ★ 与 AutoHdImg.kt:289 的 ImgInfo.t==1 口径完全一致
0x0356  invoke-virtual e.f(...)                  → 队列表里标记完成
0x035d  invoke-virtual c.d()                     → 迭代下一条
0x0360  return-void                              ★ 已有原图 ⇒ 不发下载
```

然后才是真正的 `svc.b()`：
```
0x03ac  invoke-static  d3.F8                     → DownloadImgService (m)
0x03b0  iget-wide      r0.a : J                  ★ 实参1 = ImgInfo.a（localId）
0x03b2  invoke-virtual jj/m7.getMsgId            ★ 实参2 = MsgInfo.getMsgId()（msgSvrId）
0x03b6  const/4  2                               ← 一个 int 常量（② 见 3.5，档位或 scene 之一）
0x03b7  iget-wide      c.d : J   → Long.valueOf  ★ 实参4 = Object 令牌
0x03bd  iget           c.e : I
0x03d6  invoke-virtual/range m.b{…}              ★ 入队
0x03fa  const-string   'start download cdnautostart %d %s %s result=%d'
```
（另一分支 0x0412~0x042a 同样 `d3.F8` + `r0.a` + `m.b`，日志 `'start download cdnautostart %d %s result=%d'`）

**⇒ 对核心问题的直接回答（① 指令级）**：

> **未点开的图片消息到达时，微信「不一定」走 `t1`。**
> 到达 ⇒ 写 `C2CMsgAutoDownloadRes` 队列表 ⇒ `C2CImgAutoDownloader` **按条件挑着下**
> ⇒ `m.b` 入队 ⇒ `m.h` 才 new `t1`。
> 中间隔着 **至少 4 道门**：① `sy2.a.a()` 总条件 ② 电量/前后台 ③ 优先级阈值 `priority >= ?`
> ④ 会话热度 `Chat.rank <= ? AND Chat.totallsp > ?`。
> 任何一道没过 ⇒ **不建 `t1`** ⇒ AutoHdImg **结构性漏掉这张图**。

这正好解释了真机现象：同秒另一张正常 fire（它过了门），被撤回那张到达时**一行日志都没有**（它压根没进 `m.b`，`t1` 从未构造 —— 不是解析失败，是**没发生**）。

**注意一个反直觉的点**：`a.run()` 调 `m.b` 前只出现**一个** int 常量 `const/4 … 0x2`（0x03b6），
而 `m.b` 有两个 int 形参（档位 + scene）。**② 推断**（`m.b` 是 `invoke/range`，寄存器号不可信，
无法定位它落在第 3 参还是第 5 参）：微信自动下载那一路用的档位**大概率是 `2`**，不是 `1`、也不是 `0`。
⇒ `AutoHdImg.kt` 那句「档位不是 TYPE_THUMB(1) ⇒ skipOther++」在真机上几乎必然全是 skipOther，
**`skipOther` 不是异常信号**。（⚠️ ② 不是 ①，别当定论 —— 真机直接看 `sig n=? type=?` 日志即可确认）

### 2.2 缩略图到底下不下（② 调用者链推断 + ③ 假设）

- `C2CImgAutoDownloader` 管的是**原图/大图的自动预下载**，走的是 `C2CMsgAutoDownloadRes` 队列 ——
  **不是**气泡渲染必需的缩略图路径。
- `a.run()` 在 `m.b` 之前有 `'image_' + getMsgId()` 加进 `ok0/m1.u : HashSet`（经 `ok0/s2.e9()`、`nq/e0`）——
  `ok0/*` 是 **CDN** 包（`t1` 自己的字段里也有 `G : Lok0/c0;`）。**② 推断**：图片数据实际搬运大概率走 CDN，
  `t1`（getmsgimg CGI）只承担其中一部分/兜底。
- **③ 假设（未取证）**：8.0.48 收到的图片消息，缩略图由消息 XML 里的 `cdnthumburl/aeskey` 经 CDN 拉取，
  **不经过 `m.b`/`t1`**。⇒ 只挂 `t1` 抓不到「渲染气泡」这一层。
  ⚠️ 这条**没有** dex 指令级证据，只是推断，别当定论。

---

## 子步 3 · 构造参数 ↔ 实例字段（① 指令级，链条已闭合）

### 3.1 `m.h` 里喂给 `t1.<init>` 的三个实参（①，最硬的一条）

```
m.h:  0x002d  new-instance Lcom/tencent/mm/modelimage/t1;
      0x002f  iget-object m.f : Lcom/tencent/mm/modelimage/k;   ← 当前任务
      0x0031  iget-wide   k.a : J                               ★ 实参 1
      0x0033  iget-wide   k.b : J                               ★ 实参 2
      0x0035  iget        k.c : I                               ★ 实参 3
      0x003a  invoke-direct/range t1;-><init> {…}
      0x003d  iput-object m.g : t1
      0x003f  iget-object m.f : k
      0x0041  iget        k.d : I
      0x0043  iput        t1.z : I                              ★ k.d 不进构造，构造后补写 t1.z
      0x007f  iget-object m.g : t1
      0x0081  invoke-virtual p1.f(t1)                           ← 投递 NetScene
```

而 `k.<init>(JJI)V` 只设 `a/b/c`，`k.d` 是 `m.b` 的第 5 个 int（我们传的 `0`）事后写入。

### 3.2 `t1.<init>` 内部：参数 → 字段（①，寄存器号经「减 102」校正确认）

`t1.<init>(JJILt0;I)V` 的形参布局：`v0=this, v1..v2=arg1(J), v3..v4=arg2(J), v5=arg3(I), v6=arg4(t0), v7=arg5(I)`。

把 `_r27_disasm` 输出的寄存器号统一减 102 后，5 条 `iput` **类型全部对得上**：

| 偏移 | 指令（原始输出） | 减 102 后 | 语义 |
|---|---|---|---|
| 0x004f | `iput-object v108 → t1.e : modelbase/t0` | v6 = arg4 | ✅ 回调 |
| 0x0051 | `iput v107 → t1.o : I` | v5 = arg3 | ✅ 档位 |
| 0x0053 | `iput-wide v103 → t1.h : J` | v1 = arg1 | ✅ |
| 0x0055 | `iput-wide v103 → t1.i : J` | v1 = arg1 | ✅ |
| 0x0057 | `iput-wide v103 → t1.j : J` | v1 = arg1 | ✅ |
| 0x0059 | `iput-wide v105 → t1.q : J` | v3 = arg2 | ✅ |
| 0x005b | `iput v109 → t1.B : I` | v7 = arg5 | ✅ |

⇒ **定论（①）**：
```
arg1(long) → h = i = j        ← localId
arg2(long) → q                ← msgSvrId
arg3(int)  → o                ← 档位
arg4(t0)   → e                ← 回调
arg5(int)  → B                ← 附加 int（m.h 里没传；k.d 另走 t1.z）
```
（`h/i/j` 三个字段初始值都等于 arg1，随后 `j` 在 0x006e 会被 `r0` 的某个 long 字段改写一次 ——
具体是哪个字段因寄存器不可信未能确定，② 推断是 `ImgInfo.b`（msgSvrId）用于日志拼接
`this.d = "MicroMsg.NetSceneGetMsgImg[" + j + "]"`。）

### 3.3 上游再确认：arg1 真的是 localId 吗（① + 真机佐证）

`C2CImgAutoDownloader.a.run()` 里喂给 `m.b` 的前两参（①）：
```
0x03b0  iget-wide   r0.a : J         ← ImgInfo 的 long 字段 a
0x03b2  invoke-virtual jj/m7.getMsgId  ← MsgInfo.getMsgId()
```
且 `ImgInfoStorage`（`b1`）的按主键查询是 **`o2(Ljava/lang/Long;)Lcom/tencent/mm/modelimage/r0;`**
（注意形参是 **`Long` 对象**，`AutoHdImg.kt:284` 用 `callMethod(storage,"o2",localId)` 传 Kotlin `Long`
会自动装箱 —— ✅ 恰好对上，不用改）。

真机佐证：`localId=861758` 时 `o2` 取回了带路径的 ImgInfo；`localId=0` 时 `o2(0)` 必然取不到。
⇒ **arg1 = localId、arg2 = msgSvrId** 成立，`AutoHdImg.kt:187-188` 的「第 1 个 Long=localId、第 2 个=msgId」
**启发式本身没错**。

### 3.4 那为什么会出现 `localId=0`（② 推断 + 真机）

不是解析错，是**上游传的就是 0**：`m.h` 用 `k.a` 建 `t1`，`k.a` 来自调用方喂给 `m.b` 的第 1 参。
真机 `fired msgId=19384736 localId=0` ⇒ 微信那条路径建任务时 **localId 就是 0**
（`AutoHdImg.kt:192` 的注释方向是对的）。

⚠️ **这里有个比「解析成 0」更严重的连带后果**（②）：
`localId=0` 时 `b1.o2(0)` 必然取不回 ImgInfo ⇒ `imgT == null` ⇒ **`ImgInfo.t==1` 判重整条失效**
⇒ 我们照样会 `svc.c(0, msgId, 0, …)` ⇒ `m.b` 建出 `k.a=0` 的任务 ⇒ `m.h` 建出 `h/i/j=0` 的 `t1`
⇒ getmsgimg 请求里 **msgLocalId=0**，服务端大概率无法定位 ⇒ **这次下载是无效的**。

⇒ 建议（不改源码，只给结论）：**`localId==0` 时应先补 localId 再发起**，补法（②，候选）：
`msgSvrId → j9.u3(msgId) 得 MsgInfo → b1.u2(MsgInfo) 得 ImgInfo → ImgInfo.a 即 localId`
（`AutoHdImg.talkerOf()` 已经走通了前半段 `b1.a9() → j9.u3(msgId)`，复用即可。）
**localId 补不到就别 fire** —— 白跑一次且污染 `m.e` 去重集合（同一 (0,msgId,0) 会被判「task already done」，
等真 localId 来了再调就晚了）。

### 3.5 `o`（档位）真机落在哪（②）

`C2CImgAutoDownloader` 调 `m.b` 前只 push 了一个 int 常量 `0x2`（`a.run` 0x03b6，① 指令级看到常量本身，
② 无法确认它映射到 `m.b` 的第 3 参还是第 5 参）。
所以真机上如果看到 `sig n=? type=2` ⇒ 那大概率是**微信自动下载压缩图**那一档；
`type=1`（代码里的 `TYPE_THUMB`）在 8.0.48 上**基本不会出现**。
⇒ `skipOther` 计数从设计上就是「常态」，别再拿它当异常信号。
（顺带：`TYPE_HD=0` 用于我们自己 fire 的那一路是对的，与 `AutoGetBigImgLogic` 一致。）

---

## 子步 1 · 所有构造 `t1` 的调用点（① 指令级，**全量扫描已回，结论已定**）

后台作业 `tools/_r27_callers.py --class "Lcom/tencent/mm/modelimage/t1;" --method "<init>"`
跑了 **23 分钟**返回 475 行。

⚠️ **这个脚本有假阳性**：它对「code_item 解析异常」的方法会把 `insns` 错当成「从 start 到文件尾」，
于是扫出一大堆**偏移大到不可能**的命中（如 `invoke@['0x4b01d','0x1d6b0b','0x269347']`，
一个方法不可能有 30 万条指令），而且不同类的偏移完全相同 —— 一眼可辨。
**判读规则：只认 `invoke@['0x..']` 里偏移为 3~5 位十六进制（方法体内）的命中，其余全是噪声。**

按这条规则筛，475 行里**只有 2 条是真的**（① 指令级）：

```
classes7.dex: Lcom/tencent/mm/modelimage/m;#h            invoke@['0x3a']   ← ★ 唯一对外入口
classes7.dex: Lcom/tencent/mm/modelimage/t1;#<init>       invoke@['0x6']    ← 4 参版转调 5 参版（内部委托）
```

`0x3a` 与子步 5.1 手工反汇编看到的 `m.h: 0x003a invoke-direct/range t1;-><init>` **完全对上**；
`0x6` 正是 `t1.<init>(JJILt0;)V` 那 10 条指令里的 `invoke-direct/range`（转调 5 参版）。

### ⇒ 子步 1 定论（①）

**整个微信里只有 `DownloadImgService.m.h(I)V` 一处会 new `t1`。**
`m.h` 是「从 `m.d`(List 队列) 取下一个任务 → new t1 → `p1.f(t1)` 投递」的唯一出口
（`m.b` 末尾调它、`m.e` 取消完也调它续跑下一个）。

⇒ **挂 `t1.<init>` 严格等价于「挂微信自己决定发起的那一次下载」**：
微信不发起 ⇒ 一次都不构造 ⇒ AutoHdImg 一行日志都不会有。
不是概率问题，是**结构性等价**。

[DONE] 1. 构造 t1 的调用点（① 全量：仅 `m.h` + `t1.<init>` 自委托）

---

## 子步 4 · 若走不到 `t1`：候选新锚点（含证据等级）

结论先行：**挂 `t1` 是结构性缺陷**（理由见子步 2.1 —— 中间隔着 4 道门）。
下面给候选，按推荐度排序。

### 4.1 候选 A（推荐）：SQL 层 —— 复用项目内**已验证可用**的锚点

`AntiRevoke.kt:285/294/302` 已经在 hook：
```
com.tencent.wcdb.database.SQLiteDatabase
  ├─ insertWithOnConflict
  └─ updateWithOnConflict
```
这是本项目**唯一一个真机上确认长期有效**的底层锚点。
AutoHdImg 可以挂同一个 `insertWithOnConflict`，只认写 `ImgInfo`（图片表）/`MsgInfo` 的那次插入，
从 `ContentValues` 里取 `msgId / msgSvrId / talker / type`。

| 项 | 评价 |
|---|---|
| 抗漂移 | ✅ 最强。表名/列名是**数据契约**，比混淆后的 `b1.g6` 稳定得多 |
| 语义 | ✅ 这是真正的「消息入库」，不依赖微信「想不想下」 |
| 成本 | ⚠️ 回调极高频（所有表的所有 insert 都过），**必须先按表名快速短路**再解析 |
| 风险 | ⚠️ 需要在 hook 里 `ContentValues.getAsString(...)`，列名仍需一次取证 |
| 证据等级 | ①（hook 点已存在于项目且在用）+ ③（ImgInfo 列名未取证） |

**真机判据**：开关打开后，收到一张普通图片 ⇒ 立刻应有一条 `anchorSeen` 日志带 `msgId>0 && msgSvrId>0`。
若**连这条都没有** ⇒ 表/列名赌错（未接通）；有 ⇒ 接通，继续看 `t1 seen` 是否同步出现。

### 4.2 候选 B：`com.tencent.mm.modelimage.b1`（ImgInfoStorage）的入库方法

① 类确实存在（`classes7.dex` class_defs#11823，super `k74/q0`），且 `AutoHdImg` 已经在用它做查询
（`o2(Long)`，真机有效 ⇒ 类名没漂移）。

候选入库方法（① 签名已取证，**但哪个是 insert 未取证**）：
```
g6(String,[B,I,J,Z,String,I,String,PString,PInt,PInt)J
j6(String,[B,I,J,Z,String,PString,PInt,PInt)J
R5(String,String,I,I,I,PString,PInt,PInt)J
S6(String,String,J,I,Z,String,I)J
a6(String,String,Lmodelimage/r0;)J
p6(String,String,Lmodelimage/r0;)J
L5(Lfv/t0;)J
```
返回值都是 `J`（= 新 localId），符合「insert 返回主键」的形状 —— ③ 但**必须**先跑
`_r27_callers.py` 确认哪个被「收到消息」那段调用，别直接赌。

### 4.3 候选 C：`C2CMsgAutoDownloadRes` 队列表 DAO `c2c/img/e`

```
com.tencent.mm.plugin.priority.model.c2c.img.e
  a(Cursor)Lq54/ea;   b(IF)Lq54/ea;   c(J)Lq54/ea;   d(I)Lq54/ea;
  e(J)Z               f(JI)V          g(JII)V
```
若「每条图片消息到达都会往这张表插一行」，这里就是**最准的到达锚点**，行里自带
`msgId / fromuser / priority / status / createtime`。
⚠️ **③ 未取证**：`e` 只有 7 个方法，**没有明显的 insert**；`<init>` 里准备了 7 条 `SQLiteStatement`
（字段 `b..h`），插入可能走的是其中某条 statement 但由外部调用。
`py2/m.run()`（①）是**建库**不是插入；`py2/t` / `py2/u` / `dz2/d` 是维护/清理。
⇒ 想用 C 必须先补一次取证：**谁调用了 `e.f(JI)V` / `e.g(JII)V`**。

### 4.4 ❌ 排除：`com.tencent.mm.modelimage.d`（AutoGetBigImgLogic）

① 已取证：`d` 只有 `<init>(Looper)` / `Y` / `l0` / `q`，且三个方法签名
`(JJIILjava/lang/Object;…)` 与 `c2c/img/c` 的 `Y/l0/q` **完全一致** ——
这是一组**任务回调**（`c.Y` 里日志是 `'onImgTaskCanceled %d'`），不是发起入口。
⇒ **不能当锚点**，挂上去只会观察微信自己的下载结果，与 `t1` 同源、同样漏「没点开的图」。

### 4.5 真机「接通 / 未接通」可区分判据（通用）

给新锚点加两个计数（与现有 `stat()` 同风格）：`anchorSeen`（锚点命中）/ `anchorIdOk`（拿全 localId+msgSvrId）。

| 观察 | 判读 |
|---|---|
| `anchorSeen>0` 且 `t1 seen==0` | **坐实 t1 结构性漏图**（微信不想下 ⇒ 我们也不下） |
| 两者都 `>0` | t1 没漏，问题在参数/档位/判重 —— 回子步 3.4 看 `localId==0` |
| `anchorSeen>0` 但 `anchorIdOk==0` | 锚点对、字段赌错 ⇒ 换列名/换方法 |
| 都 `0` | 锚点没接上（类名漂移 / 开关没开 / 线程不对） |

再加一条**零副作用探针**区分「没受理」vs「受理了没下完」：
`svc.g(localId, msgId, 0)`（① 纯查询：`new k` + `List.indexOf`，不下发）。
`true` ⇒ 任务在队列里，只是没跑完；`false` ⇒ 压根没进去。
以及 `fire` 之后**延时复查** `ImgInfo.t==1`（不要只看 `svc.c` 返回的 `true`，见子步 5）。

[DONE] 2. 到达链路上有没有 t1 / 缩略图（① 到达侧链路 + 4 道门；缩略图为 ③ 假设）
[DONE] 3. 参数 ↔ 字段（h/i/j←arg1、q←arg2、o←arg3；localId=0 的连带后果）
[DONE] 4. 候选新锚点 + 接通判据
[DONE] 5. svc.c() 调用姿势
