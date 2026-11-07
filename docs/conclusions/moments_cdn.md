## ⑫ 🟢 朋友圈图片 CDN 链路（2026-10-20 01:4x **全离线静态定位** · **02:22 升级为 R9 主线**，待真机）

> ✅ **2026-10-20 02:22 用户决定：朋友圈图片下载排进 R9**，本节由「B 线只读归档」升级为**本轮主线**。
> ⑫.7 的五条真机验证 = **R9 真机段的全部内容**；方案与脚本 lane 见 `docs/并行开发轮次计划.md` R9。

**范围声明**：本节全部为**静态 dex 取证**结论，**无一条真机验证**。凡涉及运行时行为的均标 🟡/⛔。
详细证据见 `docs/_r10_cdn_chain.md`（调用链）与 `docs/_r10_cdn_api.md`（API 与 hook 点枚举）。

### ⑫.1 ⛔ 更正：`createSNSDownload` **不在**朋友圈链路上

此前（本会话 01:1x 口头汇报）据 `CdnLogic.createSNSDownload` 字面推断它是 Sns 图片下载入口 —— **错了**。

- ✅ 全 APK 只有 **2 个调用点**，均与朋友圈无关：
  `Lhj0/t;->invokeSuspend`（实为 **MJTemplate Flutter 插件的 Maas 音乐短视频 demo 素材下载**，
  同方法体有 `'MicroMsg.MJTemplateApiFlutterPlugin'` / `'maas_music_demo_resource'`）+ `createGroupNoticeDownload` 内部互调。
- ⇒ **直接 hook `createSNSDownload` 抓不到朋友圈图片请求。**

### ⑫.2 ✅ 真正的 Sns 图片下载链路

```
Ljf3/f;->k([String)Integer        ← SnsCdnDownloadBase#doInBackground（✅ SnsMethodCalculate 原名实测）
   └ new BatchSnsReqImageData 并 iput 全部 6 字段（url/fileKey/decryptKey/imageCachePath/picIndex/totalFileSize）
      └ SubCoreCdnTransport.addRecvTask       ← 日志 '[%s][media:%s]SubCoreCdnTransport addRecvTask succ/failed mediaId:%s'
         └ Lok0/x0;->o(Ltj/h;)I               ← CdnTransportEngine（✅ log tag 实测）
            └ CdnLogic.startSNSDownload(...)  ← ⛔ NATIVE
```
`Ljf3/d;->C2(...)` 是 `BatchSnsReqImageData` 的**唯一字段读取点**（读 `picIndex`×5 / `retry`×1）⇒ 可作"批量回调是否真触发"的探针。

### ⑫.3 ⛔ 硬约束：所有 `start*Download` 都是 native，Xposed 直接 hook 不了

`CdnLogic` 的 `startSNSDownload` / `startC2CDownload` / `startURLDownload` / `startHttpsDownload` /
`startHttpVideoStreamingDownload` / `allowBatchImageDownload` 等，**access_flags = 0x109（ACC_NATIVE）**，无 Java 实现体。
⇒ 只能 hook **调用方**（`ok0.x0#n`）或**回调实现**（`ok0.x0#onC2CDownloadCompleted`），
或改走 native inline hook（Dobby/AndHook，本项目未引入）。

### ⑫.4 ✅ 推荐 hook 点（按价值降序，均为 **java** 方法，可 hook）

| # | hook 点 | 能拿到什么 |
|---|---|---|
| 1 | `ok0.x0#n(Ltj/g;)I` | ★**批量请求全量**：`req.l1 = [BatchSnsReqImageData]`（url/fileKey/decryptKey/imageCachePath/picIndex/totalFileSize）+ feedId/feedPicCount。**在调 native 之前**，可读写入参 |
| 2 | `ok0.x0#onC2CDownloadCompleted(String, CdnLogic$C2CDownloadResult)` | ★**结果全量**：`errorCode`、`picCachePath`（**图片落盘路径**）、`fileSize`/`recvedBytes`、`picIndex`、`realUsedURL`、`httpStatusCode` |
| 3 | `jf3/f#k([String)Integer` | 唯一 `BatchSnsReqImageData` **构造点**，可改 `imageCachePath` 控制落盘位置 |
| 4 | `com.tencent.mm.plugin.sns.ui.dk#a()Z` | 批量链路**开关**（见 ⑫.5），强制返回 true 可试开批量下载 |
| 5 | `ok0.x0#onDownloadProgressChanged(String,long,long,boolean)` | 判断任务是否真的在跑 |

⚠️ 「hook `C2CDownloadRequest.setXxx` 系列 setter」**抓不到主链路** —— `ok0.x0#n` 里是**直接 `iput` 字段赋值**，不走 setter（✅实测）。

### ⑫.5 ✅ 批量开关是**两道门**（与关系），不是一道

```java
dk.a()  ==  CdnLogic.allowBatchImageDownload()  &&  dk.j
```
任一为假 ⇒ `return false` 并打印 `'groupDownloadDisable!!!'`；两者都真才 `true`。
- `allowBatchImageDownload()` 是 **native**（只能 hook `dk.a()` 本身绕过去）。
- `dk.j` 是**非 final 静态布尔**，在 `dk.<init>` 里由一次服务调用结果赋值（🟡赋值来源未逐条确认）。

### ⑫.6 🔴 对既有结论「图片存不了」的影响（**可能翻案，但必须真机**）

`6.4b` 定案「三条路全关、图片存不了」是在**没有这条链路信息**时下的。现在静态显示：
微信内部**有**批量图片下载通路，且**有落盘路径**（`imageCachePath` 入参 / `picCachePath` 出参）。

⇒ R4「点开大图不落盘」的**另一种解释**：不是"图片存不了"，而是**批量下载这道开关当时是关的**（`groupDownloadDisable!!!`），
所以微信走流式显示、不下盘。

**新路线（比"自己解密"便宜得多）**：翻 `dk.a()` 开关（hook 强制 true）→ 让微信**自己**批量下载并落到 `picCachePath`
→ 我们只读文件，**不需要自己发 HTTP、不需要自己解 aeskey**。
⛔ 这是 🟡**推断**，未真机验证；若 `dk.j` 由服务端下发且恒 false，则此路不通。

### ⑫.7 待真机验证（缺一不可）

1. `dk.a()` 运行时返回 true 还是 false？（决定批量通路是否默认关）
2. 强制 true 后，是否真的有图片落到 `picCachePath`？路径在哪、是否可读（app 私有目录需 `chown` 或 `run-as`）？
3. `BatchSnsReqImageData.imageCachePath` 我们改写后是否生效（能否指定到 `/sdcard` 可读目录）？
4. 落盘的图片是**明文 jpg** 还是仍需解密？（若 native 侧已解，直接拷走即可）
5. `jf3/d#C2` 的触发时机（⚠️ 该 dex 内**直接调用点 = 0**，🟡 疑为多态/接口回调触发）。

> 与 6.4b 的关系：本节**不推翻** 6.4b 的三条实测（md5 全盘搜不到、点开不落盘、裸 url 恒 400 都是真的），
> 但指出它们**没有覆盖**「批量下载通路」这一条新分支。真机验完再决定是否改写 6.4b 的定案措辞。

---

## 🟢⑫.8 真机确证（2026-10-24，设备 467cf5bc / 微信 2588-8.0.48）

> 本节是**真机实测**，与上面的 🟡推断 / ⛔未验证要明确区分。
> 来源：R14 真机段，`python tools/_r9_cdn.py --apply --force`（滚 3 屏）。

### ✅⑫.8-I-0：`ok0/x0`（CdnTransportEngine）回调**确实在主进程** —— 生死项过了

```
CDN stat hooked=1 proc=com.tencent.mm pid=15330     ← 钩子装在主进程
滚 3 屏后：hitK=+9  hitCB=+10                        ← ok0.x0#n 命中 9 次、回调命中 10 次
```

**判据口径**（别搞反）：`proc=` 字段只能证明「**广播**到达主进程」，
**真判据是 `hitK` / `hitCB` 的增量** —— 增量 > 0 才说明 CDN 回调确实发生在装钩子的进程里。
本次增量 +9/+10 ⇒ **H3 触发条件成立**，⑫.7 后续各条才有讨论意义。

### ✅⑫.8-I-1：`dk.a()` 运行时返回 **`true`**

```
CDN dk ret=true nativeOk=true staticJ=true
```

- `nativeOk=true` ⇒ 反射 `CdnLogic.allowBatchImageDownload()` 成功（不是 native 调用失败）。
- ⇒ **批量下载开关默认就是开的**，不需要再想办法强制打开。
- ⚠️ 这条**不依赖任何 hook**（纯反射），所以即使 I-0 未过它也成立；脚本旧版曾误把它一起废掉。

### ⚠️⑫.8-I-2：落盘路径**仍未拿到**（`pic=none`）

滚屏后 `CDN paths pic=none`，5 个候选目录（`.../cache/sns`、`snsimage`、`/sdcard/tencent/MicroMsg/sns` 等）
`files=0`。回调命中 10 次却取不到 `picCachePath` ⇒

**卡点**：`probe/SnsCdnProbe.kt` 的 `cbHook` 取 `param.args[1]` 再读字段 `picCachePath`，
而 `readString` 是 `runCatching { getObjectField(...) }.getOrNull()` ⇒ **异常被吞**，
「参数位错」与「字段名错」都表现成同一个静默 null，看不出区别。

⇒ 已派静态取证子代理确认 ① `onC2CDownloadCompleted` 的真实签名（参数个数/类型、result 在第几位）
② 结果类落盘字段的真实名字。结论落 `docs/_r14_cdn_cb.md`。
**在字段名确证前，I-3 / I-4 都不用做**（I-3 改路径、I-4 判明文都以「先看到落盘」为前提）。

### 🔴⑫.8-I-2 根因（R14 静态取证定案）：`picCachePath` **存在但没被填值**

结论来自静态取证（`docs/_r14_cdn_cb.md`），已由主 agent 用修好的正式工具独立复验。

```
Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;   @ classes13.dex class_defs#1569
  instance_fields = 73（全 public），methods = 1（只有 <init>()V，纯数据壳）
  picCachePath : Ljava/lang/String;   ✅ 字段存在，类型也对
```

**但全 15 个 dex 滑窗搜 `iget/iput/sget/sput-object`：`picCachePath` 只有 1 条写、0 条读。**
那唯一一条写在 `<init>()V`（insn=0x23837a），写的是 `strings[0]` == **空串 `''`**
（71/73 字段都在 `<init>` 被赋默认值：数值 0 / String `''`）。

⇒ **值只能由 native（Mars）用 JNI `SetObjectField` 填**。真机拿到的是 `""` 而不是 null，
而 `File("").parentFile == null` ⇒ `lastPicParent` 为 null ⇒ 打 `pic=none`。

> ⚠️ **`pic=none` 有两种可能，含义完全相反**：
> ① 字段不存在（`NoSuchFieldError`）⇒ 类名/字段名漂移，要改常量；
> ② **字段存在但值是 `''`**（本版本的实际情况）⇒ 常量没错，是 native 不填。
> 探针的 `readString` 用 `runCatching{...}.getOrNull()` 把两者**都吞成 null**，看不出区别。
> ⇒ 已派 L3 把这两种情况分开计数（`hitPicE` / `hitNoField`）。

### ✅ 请求侧才是该去的地方

`Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;`（7 个字段）
含 **`imageCachePath : String`** 与 **`fileKey : String`** ——
**路径由微信 Java 侧拼出，不依赖 native 填不填 `picCachePath`**。

相关签名均已确证（静态）：

| 项 | 值 |
|---|---|
| `ok0.x0#n` | `(Ltj/g;)I` ✅ |
| `tj/g.l1` | `[Lcom/tencent/mars/cdn/CdnLogic$BatchSnsReqImageData;` ✅ **确实是 Array** |
| `jf3.f#k` | `([Ljava/lang/String;)Ljava/lang/Integer;` —— `imageCachePath` 的 2 条写点之一，**代码里已在 hook**（现只当计数用） |

⚠️ `ok0.x0#onC2CDownloadCompleted` 的 `arg[0]` 那个 String 是 **fileid**，**不是路径** —— 别拿它当路径。

### ⚠️ 当前最大的观测缺口

`hitK=9` 计的是 `jf3.f#k`，**`ok0.x0#n` 根本没有独立计数**（`hitN`）。
加上 `reqHook()` 里两处 `?: return` 静默退出 ⇒
真机无法区分「**`n` 压根没被调用**」和「**`n` 调了但 `l1` 数组为空**」。
⇒ 已派 L3 补 `hitN` + 把每个退出点打成 `CDN reqerr reason=<...>`。

### 🟢⑫.9 真机终局（2026-10-24 01:59，L3 探针上线后实测）

滚 10 屏后 `CDN stat`：

```
hooked=1 proc=com.tencent.mm pid=30961
hitK=1 hitCB=7 hitN=0 hitPicE=7 hitPicNE=0 hitDir=0 hitNoField=0
```

**对账恒等式成立**（探针自洽性验证）：`hitCB(7) = hitPicE(7) + hitPicNE(0) + hitNoField(0)` ✅

#### 结论 A：`picCachePath` **字段存在**，但**值恒为空**

- `hitNoField=0` ⇒ **字段不存在的次数是 0** ⇒ 常量 `picCachePath` **没写错**，静态取证正确。
- `hitPicE=7` / `hitPicNE=0` ⇒ 7 次回调里它**每一次都是空串或 null**，一次都没被填上。

⇒ 与静态结论完全吻合：Java 层只有 `<init>` 写 `''`、**0 条读**，值只能由 native（Mars）用 JNI 填，
而**本版本 native 没有填**。

#### 结论 B：**批量下载通路 `ok0.x0#n` 一次都没被调用**（`hitN=0`）

这是本次最重要的发现，也解释了之前所有的困惑：

- 不是「`l1` 读不到」，是**压根没有批量请求发生** ⇒ `observedDirs` 为空、`CDN req` 零行，全部说得通。
- 那 7 次回调是**单张图下载**的回调，不是批量通路。
- ⚠️ 因此 `hitK`（`jf3.f#k` 构造点）与 `hitN`（`ok0.x0#n` 请求点）**不是一回事**，
  之前把 `hitK=9` 当成「批量请求命中 9 次」是**误读**。

#### 结论 C：与 6.4b 的三条实测**相互印证**，不是矛盾

6.4b 的「md5 全盘搜不到 / 点开不落盘 / 裸 url 恒 400」都是真的；
⑫ 假设的「批量下载通路」在当前触发条件下**没被触发**，且即使触发，落盘路径字段也恒空。
⇒ **不要再往「批量通路可落盘」这条路上投入**，先解决「怎么触发 `ok0.x0#n`」或改走单张图路径。

#### 下一步（按优先级）

1. **找到 `ok0.x0#n` 的触发条件**（`hitN` 现在是现成的判据：不为 0 才算触发成功）。
   注意 `dk.a()=true` 只说明开关开着，**不等于**批量请求会发生。
2. 若确需走单张图：回调侧已证实 `picCachePath` 恒空 ⇒ 得另找出路（例如 `onPreloadCompletedWithResult`
   交叉验证，或用 fileid/batchImageFileKey + `imageCachePath` 目录按 mtime 兜底找文件）。
3. ⛔ 在有 `hitN>0` 的证据之前，**I-3（改写 imageCachePath）与 I-4（判明文）都不用做** —— 做了也是空转。

> 📌 方法论沉淀：这次能一次看清，靠的是 L3 把「静默 `?: return`」改成可见计数。
> 之前三轮真机都是在猜「是没调用还是读不到」，**区别在于有没有把失败分支暴露出来**。

---

## ⛔ 工具坑：`_r14_walk.py dis` 的 `const/4` 字面量曾整体打错（2026-10-24 实测定位并修复）

**症状**：`const/4` 指令打印出来的 `#N` 不是该指令自己的字面量，而是**下一条指令**的字节。
在 `MsgRetransmitUI.onCreate` 里，**30 条 `const/4` 有 25 条打错**。

**根因**：`const/4`（opcode `0x12`）是 **11n 格式，单 code unit**：
`w0 = op | A<<8 | lit<<12`（A = 寄存器，lit = **有符号 4 位**）。
旧代码写的是 `u1 >> 12`，而 `u1` 是 `each_insn` 返回的**第 2 个 code unit** ——
即下一条指令的首个 unit，跟本条毫无关系。

**正确写法**（已修入 `_cache/_r14_walk.py`）：
```python
_lit = hi >> 4          # hi = w0 高字节，其高 4 位即 lit
if _lit > 7: _lit -= 16  # 4 位二补码
```

**已实测造成的错误结论**（凡引用过这些值的都要重核）：

| 位置 | 曾记 | 实际（dexdump 原文） |
|---|---|---|
| `Retr_Msg_Type` 默认值 | `3` | **`-1`**（`12f5` = `const/4 v5, #int -1`） |
| `Retr_Big_File` 默认值 | `3` | **`0`** |

**判据**：凡经该工具读出的 `const/*` 立即数，都要回 `_cache/_r11_dex7_dis.txt` 原文复核；
或用修好的工具重跑（现在 `#-1` / `#0` 已正确）。

> 同族坑（同一次排查发现，未修，只记症状）：
> `each_insn` 对 **11n（`const/4`）** 仍会把 `u1` 读成下一 unit —— 这只影响「谁去读」，
> 已经被上面的 `hi >> 4` 绕过；但**任何新加的单 unit 指令解码都要留意同一问题**。

---


---

## ✅⑫.9 朋友圈**视频**解密定案（R45，2026-11-08 · 真机闭环）

> 上一节处理的是**图片**（`picCachePath` / 缓存索引直扫）。本节是**视频**，两者链路不同，
> 别再混：视频走 HTTP 直下 + **前 128KB 加密**，图片走缓存索引。

### 结论一句话

朋友圈视频 CDN 下载回来的是**只加密前 131072 字节**的 MP4，其余明文；
**不要自研算法**（ISAAC-64 复现 126 组合全败），直接调微信自己的
`com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI.transFor()` 原地解密。

### ⑫.9-I-0 密文结构（离线实测，`_kb/`）

| 项 | 值 |
|---|---|
| 加密长度 | **131072 字节（128KB）**，之后全明文 |
| 密钥流 | `明文 XOR 密文`；前 128KB 内 16384 个 8 字节块**全唯一无周期**，熵 7.9985 |
| 结论 | **流密码 / 块无关**，且密钥流本身不可推导 ⇒ 只能借微信的 native 算 |

CDN 响应头会直接给出这两个数（可用于替代硬编码）：

```
X-encflag: 1                # 已加密
X-enclen: 131072            # 加密长度
X-snsvideoflag: xV0
X-Verify-Code: f005d69e…    # 与 key 无派生关系（已验证）
```

> ⛔ UA 必须是 `WeChat/arm64`；其它 UA 返回 `HTTP 400 X-retmsg: sns illegal ua http download2`。

### ⑫.9-I-1 微信官方解密 API（**运行时真身**，非静态猜测）

`com.tencent.mm.plugin.imgenc.MMIMAGEENCJNI`（classes7.dex），native 实现在 `libwechatcommon.so`。

```java
public static native long   free(long)                        // free(J)J
public static native long   open(long)                        // open(J)J
public static native long   open(String)                      // open(Ljava/lang/String;)J
public static native byte[] readByte(long, byte[])            // readByte(J[B)[B
public static native long   seek(long, long, int)             // seek(JJI)J
public static native long   transFor(long h, byte[] buf, long off, long len)  // transFor(J[BJJ)J
```

三条铁律（都踩过）：

1. ⛔ **静态 dex 解析会把它们显示成 `direct` 且不标 `static`**，容易误判成实例方法。
   运行时 `api` 子命令实测：**6 个方法全是 `public static native`** ⇒ invoke 的 receiver 传 `null`。
   但生产代码仍要按 `Modifier.isStatic` 兜底（版本漂移风险）。
2. ⛔ 必须用**宿主的 ClassLoader**（`SnsGuard.hostLoaderHandle()`），模块自己的 loader 找不到这个类。
3. `open` 有 `(J)` / `(String)` 两个重载，**必须按 `parameterTypes[0]` 精确挑**，不能 `getMethod` 猜。

`transFor` 是**原地改写**：把 `buf` 传进去，返回后 `buf` 内容已是明文，无需接返回值。

### ⑫.9-I-2 真机决定性验证（反向对照通过）

```
VIDDEC try enc=.../enc.mp4 key=1370298124 n=131072 r=0
           hit256=256 firstDiff=-1  allSame=true  head8=00 00 00 1c 66 74 79 70
VIDDEC try enc=.../enc.mp4 key=1370298125 n=131072 r=0
           hit256=0   firstDiff=0   allSame=false head8=43 35 a6 bb f8 8a 28 10
```

| key | hit256 | firstDiff | allSame | head8 |
|---|---|---|---|---|
| `1370298124`（真值） | **256** | **-1** | **true** | `00 00 00 1c 66 74 79 70` = `ftyp` |
| `1370298125`（减 1） | **0** | 0 | false | 随机 |

`hit256` = 解密后前 256 字节里命中 MP4 特征（`ftyp` / `moov` / `mdat` / `free` / `wide`）的字节数。
`firstDiff=-1` 表示前 131072 字节整段与已知明文样本逐字节相等。
⇒ **不是巧合**：key 差 1 就完全失效，说明解密链路对 key 敏感、算法正确。

### ⑫.9-I-3 key 来源定案：`SnsInfo.content` → `f8` → `f5` → `f25`

`content` 是 **protobuf BLOB**，取 key 只需三层：

```
content (SnsInfo)
  └─ f8   (SnsObject, length-delimited)
       └─ f5  (MediaObject, length-delimited)   ← 可能有多个（图文混排），取第一个带 f25 的
            ├─ f1  mediaId      (string)
            ├─ f4  url          (string)
            ├─ f25 encKey       (varint)  ★
            └─ f26 32hex 串     (string)   ← 与缓存文件名第二段对应
```

Python 参考实现 `_r45_key.py` 已 **PASS**：

```
blob size = 1547
encKey   = 1370298124
mediaId  = 15049717216742806078
url      = http://shzjwxsns.video.qq.com/102/20202/snsvideodownload?encfilekey=...
```

**兜底来源**：微信明文缓存文件名 `sight_<mediaId><32hex>&enc<十进制key>[_V2]` 里的 `&enc` 后数字。

> ⛔⛔ **`&enc` 后缀的语义别搞反**（我曾推断错）：`&enc<数字>` 是「**下载时用的解密参数**」，
> **不是**「这个文件是密文」。实测 `snsu_...&enc2400862626` 头是 `ff d8 ff e0 … JFIF` = **明文 JPEG**。

### ⛔⑫.9-I-4 踩过的坑（会浪费半天，全记下）

1. **`cast(content as text)` 做内容检索必假阴性**。SQLite 的 `cast(BLOB as TEXT)` 遇到 protobuf 里的
   `0x00` **在第一个 NUL 处截断**，`instr(cast(content as text), "1370298124") = 0` 会让人误判
   「key 不在 content 里」。**BLOB 列一律 `hex()` 导出后本地解析，禁止 `cast as text` 检索。**
2. **`Long.TYPE` 在 Kotlin 里不存在**（那是 Java 的 `java.lang.Long.TYPE`），编译直接失败
   `Unresolved reference 'TYPE'`。Kotlin 写法：`Long::class.javaPrimitiveType!!`。
3. **探针读文件权限**：`/sdcard/_r45_enc.mp4` 属 `u0_a269 media_rw`，微信进程是 `u0_a363` ⇒ `read-fail`。
   放 `/sdcard/Android/data/com.tencent.mm/files/` 下并 `chown u0_a363:u0_a363`。
4. **自研 ISAAC-64 复现已正式作废**：96 + 30 组合，最高命中 3/256 = **随机期望**，不要再试。

### ⑫.9-I-5 微X 的对照（参考，非依赖）

- 微X v2.44 **确实有保存朋友圈视频功能**（此前我说「只有转发语义」是**错的**，用户已纠正）。
- 落点在 **app 私有目录**：`/sdcard/Android/data/com.tencent.mm/files/WechatXposed/`
  （`moments/<昵称 (wxid)>/<yyyy-MM-dd-HHmm>/{text.txt,img_N.jpg,video.mp4,url.txt}`），**必须 `su` 才能看**。
- 它导出的 `video.mp4` 是**明文**：13,885,521 字节，box 结构自洽
  （`ftyp 32 + moov 15490 + free 8 + mdat 13869991` ），`mdat` 首字节是 H.264 SPS（profile=100）。
  ⇒ 它也在某处做了解密，与本节方案等价。
- 微X 是**加密壳架构**：`classes.dex` 只是壳，真逻辑在 `assets/` 下 7 个 `92 c5 01 00` 魔数文件里，
  native 用 `InMemoryDexClassLoader` 内存加载 ⇒ **静态逆向不可行，不用在这上面花时间**。

### ⑫.9-I-6 生产接入（唯一正确形状）

```
1. 下载密文到 .tmp（downloadVideo 现有逻辑不变）
2. key = snsVideoEncKey(content)            // f8.f5.f25
3. 若 key <= 0 → 不做解密，直接放行（可能本身就是明文 / 旧版本）
4. decryptInPlace(tmp, key)                 // 下面 5~8
5.   if (isFtyp(前 8 字节)) → 已是明文，直接返回 true（幂等）
6.   h = MMIMAGEENCJNI.open(long key)
7.   buf = 读前 min(131072, size) 字节
8.   transFor(h, buf, 0, buf.size) → buf 已就地变明文；finally free(h)
9.   把 buf 覆盖回文件前段（RandomAccessFile "rw" + seek(0) + write）
10. renameTo(dst)
```

⚠️ 幂等检查（第 5 步）不能省：`decryptInPlace` 会被重试路径重复调用，二次解密会把明文再 XOR 回密文。
⚠️ `isFtyp` 的判据要宽松：`00 00 00 ?? 66 74 79 70`（第 4 字节可以是任意 box 长度），
不要硬编码 `00 00 00 1c`。

### 🟢⑫.9-I-7 `f25 == 0` 是**合法的「无需加密」**（双向实证，最重要的一条）

> 这条推翻了我之前的隐含假设「解不出 key 就是解析有问题」。**不成立。**

**全库统计**（本地完整库 `_kb/sns.db`，748MB，`SnsInfo` 共 **262647** 条有 content）：

| `type` | 有条数 | `f25 > 0`（加密） | `f25 == 0`（明文） | 解析不出 `f8` |
|---|---|---|---|---|
| **15（朋友圈原生视频）** | **24525** | **22377（91.24%）** | **2148（8.76%）** | **0** |

**关键旁证：`f25 > 0` 的 22377 条**全部**落在 `type=15`**，
其它类型（1 图文 / 3 链接 / 2 纯文字 / 28 视频号 / 7 …）**一条都没有 key**。
⇒ `f25` 是视频专有字段，不会在图文动态上误命中 —— 取 key **不需要先判 type**，
取到 `>0` 就一定是该解密的那条。**解析失败 0 条**（24525/24525 全部能解出 `f8`）。

**抽样 3000 条**（`order by createTime desc`）：

```
样本数(content 非空) = 3000
取到 key(>0)      = 2960   (98.67%)
key == 0          =   40   ( 1.33%)
```

那 40 条 **不是算法漏判**：`f25` **显式存在且值就是 `0`**（40/40 条，没有一条是「字段缺失」）。

**真机下载双向对照**（`WeChat/arm64` UA）：

| `content.f25` | 样本 | `X-encflag` | `X-enclen` | 落盘文件头 |
|---|---|---|---|---|
| `0` | 1 条实下 | **0** | **0** | `00 00 00 1c 66 74 79 70` = `ftyp` **明文** |
| `1370298124` | 实下 | 1 | 131072 | 密文 |
| `3660390825` | 实下 | 1 | 131072 | 密文 |
| `3784293545` | 实下 | 1 | 131072 | 密文 |

⇒ **`content.f25` 就是 CDN 加密标志的权威镜像**，不必再去读响应头 `X-encflag`：

- `f25 > 0` ⇒ 加密，`X-enclen` **恒为 131072**（3/3 样本一致，可以硬编码）
- `f25 == 0` ⇒ **完全没加密，直接是明文 MP4**，**绝不能拿去 XOR**（会把好文件毁掉）

**生产语义钉死**：

| 返回值 | 含义 | 动作 |
|---|---|---|
| `key > 0` | 该视频加密 | `decryptInPlace(tmp, key)` |
| `key == 0` | 无需解密（或无法确定） | **直接放行，不解密** |

⚠️ 「解析失败」也归到 `0`，因为**失败时的正确动作和不加密时一样**：宁可存原样，不能 XOR 坏。
⚠️ `decryptInPlace` 内部 `key <= 0 → return false` 是**双保险**，保留。

### ⛔⑫.9-I-8 拉库取证的正确姿势（踩过）

```bash
# ❌ adb exec-out + su cat 重定向 → 本地文件 0 字节（管道被 su 吃掉）
adb exec-out "su -c 'cat .../SnsMicroMsg.db'" > local.db

# ✅ 先 cp 到 /sdcard 再 pull
adb shell su -c "cp .../SnsMicroMsg.db /sdcard/_kb_sns.db && chmod 666 /sdcard/_kb_sns.db"
adb pull /sdcard/_kb_sns.db _kb/sns.db
```

⛔ `adb pull` 前必须 `export MSYS_NO_PATHCONV=1`，否则 Git Bash 把 `/sdcard/...`
改写成 `F:/Program Files/.../sdcard/...`，报 `failed to stat remote object`。
⛔ 用 `timed out` 的 `find /data/data/... -iname '*sight*'` 会卡死（748MB 库 + 深递归），
耗时用 `ls` 定向看，别全树 find。

### ✅⑫.9-I-9 生产定案（2026-11-08 04:11 · 真机闭环）

**唯一解密判据 = `content.f25 > 0`**，代码落点 `MomentsExport.downloadVideo`：

```kotlin
if (encKey > 0L) {                 // 唯一 gate，不看 X-encflag
    SnsVideoDecProbe.decryptInPlace(tmp, encKey, encLen)   // 失败只打日志，保留密文
}
tmp.renameTo(dst)
```

⛔ **曾写错、已修**：`if (encKey > 0L && encFlag != 0)`。
`readHeaderInt` 对**响应头缺失/非法**返回 0 ⇒ 头一旦没拿到，22377 条加密视频
全部静默留成密文（不报错、`outcome=COPIED`、计数照常）。
**`f25` 来自本地 DB 是权威信号；`X-encflag` 只是同源副本，只能进日志，不能当 gate。**
`X-enclen` 同理属于不可信输入，进 `decryptInPlace` 后 `coerceAtMost(131072)`。

**真机双向对照**（`op=moments sub=viddl`，生产同链路）：

| | 开关 | key | 日志 | 落盘文件头 |
|---|---|---|---|---|
| 正向 | `true` | 3805912539 | `VIDDEC ... ok=true why=decrypted` | `00 00 00 1c 66 74 79 70`，box 结构自洽 |
| 反向 | `false` | 0 | `vid decrypt skip (encKey=0 encflag=1)` | `83 2f bc fc 7b d2 85 8b`（密文） |

⇒ 明文确实来自解密；关开关只停解密，下载/落盘/计数不变（自变量唯一）。

⚠️ 该修复消除的是**潜在**静默失效，真机 CDN 稳定返回 `X-encflag: 1`，
新旧判据在本次实测中行为一致 —— 如实标注，不写成「已复现故障」。

**全文（含独立复核、4 组变异验证、待办、真机踩坑）见 `docs/_r44_chain.md` 末节。**
