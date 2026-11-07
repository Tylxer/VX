# R44 · 主会话侧独立取证（与 `_r44_chain.md` 交叉对照用）

> 本文是主会话在派发 Pro lane 期间**并行做的、不碰设备**的静态取证。
> 目的：给子代理结论提供一份独立来源，避免「只有一个视角」。
> ⛔ 与 `_r44_chain.md` 结论冲突时，**不要取平均**，要回到反汇编现场逐条复核。

---

## 一、`l0` = `com.tencent.mm.plugin.sns.model.DownloadManager` [DONE]

### 1.1 方法语义表（反汇编 `const-string` 日志 tag 直读）

| 方法 | 日志 tag | 推断语义 |
|---|---|---|
| `b(String, SparseArray, SparseArray, x7, II)V` | `addBatchDownloadSns` | 批量下载**小图**（SparseArray 里是 mediaId→path 映射） |
| `d(vm3, I, m0, x7)Z` | `addDownLoadSns` | 「加一条下载」门面，21 units，只转调 `e` |
| `e(vm3, I, m0, x7, String)Z` | `addDownLoadSns` | `const-string ''` ×2 + `const/4 v10, 0xa`，转调 `f` |
| `f(vm3, I, m0, x7, S, S, S, I)Z` | `addDownLoadSns` | 有 `vm3 == null` 判空（日志 `unknow case media is null`）→ 转调 `h` |
| `g(vm3, I, m0, x7, S, S)Z` | `addDownLoadSnsAd` | 广告专用，同样判空后转调 `h` |
| `h(vm3, I, m0, x7, S, S, S, S, S)Z` | **`addDownLoadSnsImpl`** | ⭐ 真正干活：算路径、判 sd 卡、建 `bf3/n0`、入 `LinkedList` 队列 |
| `i(String, String, String, I, Z, I, Ltj/l;)V` | **`addDownloadStreamVideoTask`** | ⭐ 视频流任务：`new Ltj/m` → `ok0/m2#e(Ltj/m,Z)` 提交 |
| `t(I, vm3, I, Z, String, I, I, Z)V` | `onTaskFinish` | 回调收尾（`0x0007d000` 是个大小阈值） |
| `m()` | `cleanQueue` | 清空全部队列 |
| `o() / p()` | — | 返回某个 String（`o` 在 `h` 里被调用） |

### 1.2 ⭐ 不依赖 UI（全后台成立的硬前提）

`l0#<init>` 尾部：

```
0x007a  invoke-static Lcom/tencent/mm/plugin/sns/model/f4;->Na {v0}
0x007e  iput-object v32, v2, Lcom/tencent/mm/plugin/sns/model/l0;->a:Lcom/tencent/mm/sdk/platformtools/o3;
0x0080  invoke-virtual Lcom/tencent/mm/plugin/sns/model/l0;->m {v0,v1}
```

⇒ `l0->a` 这个 Handler（`com.tencent.mm.sdk.platformtools.o3`）来自 **`f4#Na()`**
（微信自己的 SnsCore 级 Handler），**不是** Activity 的 Handler。
⇒ 下载调度**不需要 Activity / UI 上下文**，`postDelayed` 走的是微信自己的线程。
⇒ **「全后台无感知」在架构上成立**（前提只剩：调用方线程不能是主线程阻塞点 + 微信进程活着）。

### 1.3 `f4#ea` = `getSnsDownManager`（`Lcom/tencent/mm/plugin/sns/model/f4;`）

```
0x0000  const-string 'getSnsDownManager'
0x0002  const-string 'com.tencent.mm.plugin.sns.model.SnsCore'
0x0012  iget-object v2, Lcom/tencent/mm/plugin/sns/model/f4;->E:Lcom/tencent/mm/plugin/sns/model/l0;
0x0014  if-nez v2, +13
0x001a  new-instance Lid3..., Lcom/tencent/mm/plugin/sns/model/l0;
0x001c  invoke-direct l0;-><init>
0x001f  iput-object, f4;->E:Lcom/tencent/mm/plugin/sns/model/l0;
```

⇒ **`f4.E` 是 `l0` 单例字段**，`f4#ea()` 是懒加载 getter。
⇒ 模块里取 `l0` 实例：`XposedHelpers.callStaticMethod(findClass("com.tencent.mm.plugin.sns.model.f4"), "ea")`。
⚠️ 反射调 `ea` 需要它 public/可访问；**真机先验一次**（失败退化为直接读 `f4.E` 静态字段）。

---

## 二、⭐ `no1` 字段顺序 = protobuf field 号（已解出映射）[DONE]

### 2.1 判据

`Lq54/no1;#op` 是 protobuf 的 `writeFields` 分发器，字段**按 field 号升序**依次
`iget` 后 `invoke kv4/a;->j(字符串)` / `->d(浮点)` / `->i(整数)`。
所以「`iget` 出现顺序」= 「proto field 号顺序」。

### 2.2 实测顺序（`op` 前 32 个 iget，逐字）

```
0x001f  no1.d  (String)   → field 1
0x0026  no1.e  (String)   → field 2
0x002d  no1.f  (String)   → field 3
0x0034  no1.g  (String)   → field 4
0x003b  no1.h  (String)   → field 5
0x0042  no1.i  (String)   → field 6
0x0049  no1.j  (F)        → field 7
0x004e  no1.n  (F)        → field 8
0x0053  no1.o  (String)   → field 9
0x005a  no1.p  (String)   → 10
0x0061  no1.q  (String)   → 11
0x0068  no1.r  (String)   → 12
0x006f  no1.s  (String)   → 13
0x0076  no1.t  (I)        → 14
0x007d  no1.u  (I)        → 15
0x0084  no1.v  (I)        → 16
0x008b  no1.w  (String)   → 17
0x0094  no1.x  (String)   → 18
0x009d  no1.y  (I)        → 19
0x00a4  no1.z  (I)        → 20
0x00ab  no1.A  (String)   → 21
0x00b4  no1.B  (String)   → 22
0x00bd  no1.C  (I)        → 23
0x00c4  no1.D  (String)   → 24
0x00cd  no1.E  (Lq54/if1;)→ 25（子消息）
0x00df  no1.F  (String)   → 26
0x00e8  no1.G  (String)   → 27
0x00f1  no1.H  (String)   → 28
0x00fa  no1.I  (String)   → 29
0x0103  no1.J  (String)   → 30
0x010c  no1.K  (String)   → 31
```

### 2.3 与 R43 §A 的**真机观测值**对照（同一份 protobuf）

R43 用 protobuf 解析器在真机 dump 出一条动态的 `8.5.x`，得到：

| proto 路径 | R43 真机观测内容 | 本文推出 Java 字段 | 吻合？ |
|---|---|---|---|
| `8.5.1` | mediaId `15049650948694479362` | `no1.d` | ✅ |
| `8.5.2` | media type `6` | `no1.e`（String，但值是 `"6"`） | ✅ 数值型 String |
| `8.5.3` | 标题「给家长带盐」 | `no1.f` | ✅ |
| `8.5.4` | **主视频 URL（/20202/）** | `no1.g` | ✅ |
| `8.5.6` | 封面 URL（/20250/） | `no1.i` | ✅ |
| `8.5.19` | 32-hex 候选 `6b487fcf…` | `no1.y`(19) 是 int ⇒ **不吻合**，说明 19 号在另一分支 | ⚠️ 见 2.4 |
| `8.5.26` | 32-hex 候选 `7e15613d…` | `no1.F` | ✅ |
| `8.5.33` | 32-hex 候选 `6228b801…` | 超出前 32 个 | 待续 |

⇒ **`8.5.4` = `no1.g` = 视频 URL 这条已双证**（一份真机 protobuf 值 + 一份 dex 字段序）。
⇒ 这是 Route C **不需要自己拼 URL** 的根据：直接 `getTimeLine().ContentObj.h.get(0).g` 就是播放地址。

### 2.4 `8.5.19` 的例外必须交代

`no1.y` 是 `I`（int），而 R43 在 `8.5.19` 观测到 32-hex 字符串。
两种解释，**都不影响结论**：
1. `8.5.19` 在一个 `oneof` 分支里，`op` 里出现的 `y`(I) 与另一分支共用序号占位；
2. R43 的 `8.5.19` 其实是 `8.5.20`（解析器报号偏移 1）。
⇒ ⚠️ 无论如何，**aeskey 不要靠字段号猜**。Route C 不需要 aeskey（`l0` 内部自己用），
所以这一条**不是**阻塞项。

---

## 三、`x7` = 朋友圈**场景枚举**（`l0#d` 第 4 参语义）[DONE]

`Lcom/tencent/mm/storage/x7;<clinit>` 建了 9 个单例，每个带一个场景字符串：

| 静态字段 | 场景串 |
|---|---|
| `c` | `timeline` |
| `d` | `album_friend` |
| `e` | `album_self` |
| `f` | `album_stranger` |
| `g` | `profile_friend` |
| `h` | `profile_stranger` |
| `i` | `comment` |
| `j` | `comment_detail` |
| `k` | `other` |
| `l` | `snssight`（`x7#c()` 现造 `snssight`） |

⇒ `l0#d(..., x7)` 的第 4 参是**上报/统计用的场景标签**，不是行为开关。
⇒ Route C 传 `x7.c`（`timeline`）或 `x7.l`（`snssight`）**都不影响是否下载**；
选 `timeline` 更贴近「这是时间线上看到的动态」的真实语境。
⚠️ 但 `l0#f` 里有 `x7.c.equals(x)` 分支（广告 h265 统计用），若传 null 可能 NPE ⇒ **传单例，不传 null**。

---

## 四、`i40` = `TimeLineObject.ContentObj`（媒体容器）[DONE]

```
Q54/i40 字段（classes3.dex）：
  h  Ljava/util/LinkedList;   ← ⭐ 媒体列表（proto field 8）→ 元素是 Lq54/no1;
  d  Ljava/lang/String;       ← field 1
  e  I                        ← field 2
  f  Ljava/lang/String;       ← field 3
  ...
```

⇒ **`TimeLineObject.ContentObj.h` 是 `LinkedList<no1>`**，
和 `TimeLineObject.ContentObj` 是 `h` 的位置对得上 proto `8.5`（8=ContentObj，5=media list）。
⚠️ `i40.h` 是 `LinkedList` 不是 `ArrayList` ⇒ 反射取值用 `get(0)` 即可（`List` 接口）。

⇒ **拿 `vm3` 的最短路径**（不需要反序列化！）：
```java
TimeLineObject tl = snsInfo.getTimeLine();            // SnsInfo 自带
Object contentObj  = tl.ContentObj;                   // i40
List<?> medias     = contentObj.h;                    // LinkedList<vm3>
Object vm3         = medias.get(0);                   // 第一个媒体
```
⚠️ `SnsInfo#getTimeLine()` 在 dex 里有 4 个重载/重复定义（classes2/3/7/12），
但都返回 `Lcom/tencent/mm/protocal/protobuf/TimeLineObject;` ⇒ 反射按名字找唯一即可。

---

## 五、待子代理回答 / 待真机验证

| # | 问题 | 归属 |
|---|---|---|
| 1 | `l0#d` 除 `id3/e0#run` 外还有谁调（尤其**播放器侧**） | 子代理 §2 |
| 2 | `l0#f` 的第 5/6/7 个 String 形参与第 8 个 int 具体语义 | 子代理 §1 |
| 3 | `l0#d` 第 2 个 int 参数（`l0#e` 传 `0xa`）语义 | 子代理 §4 |
| 4 | `bf3/m0` 能否传 null（`l0#f` 里有 `if-nez` 判空后 return 0） | 子代理 §4 |
| 5 | `f4#ea` 反射可调性（public？） | **真机** |
| 6 | `l0#d` 在**后台线程**调用是否安全（有无主线程断言） | **真机** |
| 7 | 落盘是否真的生成 `sight_*` 明文 MP4 | **真机** |
| 8 | `i40.h` 为空时（纯文字/纯图动态）的分支 | 判据设计 |

---

## 六、主会话结论（不等子代理也成立的部分）

1. **`l0` 不依赖 UI**（Handler 来自 `f4#Na`），全后台架构成立。
2. **`vm3` 不需要反序列化**：`SnsInfo.getTimeLine().ContentObj.h.get(0)` 直接拿到活对象。
   这**大幅降低** Route C 的最大门槛（原计划 §5.6 第 1 步「vm3 字段映射」直接免掉）。
3. **不需要 aeskey**：`l0` 内部从 vm3 自己取。
4. **视频走 `l0#i`（`addDownloadStreamVideoTask`）**，图片走 `l0#h`（`addDownLoadSnsImpl`）。
   ⇒ 若只想下视频，目标方法是 **`i`**，不是 `d`。
   ⚠️ 但 `id3/e0#run` 实测调的是 `d` ⇒ 广告路径下视频也走 `d`。
   **普通视频到底走 `d` 还是 `i`，必须真机 hook 确认**（这条是唯一的岔路）。
