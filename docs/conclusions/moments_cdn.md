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

