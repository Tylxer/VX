# R85 裁决：文件传输助手选人器可用，但模块转发又是缩略图

> 角色：r85-thumb-verdict（Pro 档只读裁决）。会话临时规则：model = hy4flash-free，不写入任何规则文件。
> 红线：只读；不改源码、不跑 gradle、不碰 adb/设备、不提交。
> 证据纪律：只把日志里真实出现的行当证据；推断与 UNKNOWN 显式标注，不把期望写成事实。
> 一手日志源：`_dbg_pull.txt`（本次独立 grep 复核，非转述）。

---

## 1. 两类失败机制的判定（A / B），真机坐实 vs 推断

### 1.0 一手事实（逐行引自 `_dbg_pull.txt`）

| 编号 | 日志原文（节选） | 出处 |
|---|---|---|
| F1 | `01:19:48.028 main FORWARD P3 download fired localId=884577 msgId=19747667 r=true` | `_dbg_pull.txt:590` |
| F2 | `01:19:48.031 main FORWARD P3 origin-by-md5 path=b10e4e26765247a2ac7f.jpg size=15906` | `:591` |
| F3 | `01:19:48.035 wxg-hdimg HDIMG fired msgId=19747667 localId=884577 t=0 img=7ccddbfb97a8b10e4e26765247a2ac7f.jpg` | `:592` |
| F4 | `01:19:48.038 wxg-forward FORWARD P3 bigimg ready=false path=b10e4e26765247a2ac7f.jpg fresh=false target=filehelper` | `:593` |
| F5 | `01:19:48.245 wxg-forward FORWARD resend-file kind=IMAGE ok=true imgLocalId=884577 p3=true ctx=19747667/wxid_xlrbelf6hv0w22:/ target=filehelper` | `:597` |
| F6 | `01:20:05.386 main FORWARD P3 origin-by-md5 miss reason=thresholds` / `.389 ... copy skip: source fails thresholds name=th_82e5692a0257472c1e949` / `.482 ... resend-file ... p3=false` | `:666-672` |
| F7 | `01:20:47.701 ... download fired localId=883190 msgId=19733550 r=true` / `.703 HDIMG ... img=216b1f7ccd59b1268349fd0eb3c46801.jpg` / `.705 origin-by-md5 miss reason=thresholds` / `.707 ... bigimg ready=false path=cd59b1268349fd0eb3c46801 fresh=false` / `.708 ... copy skip: source fails thresholds name=th_216b1f7ccd59b1268349f` / `.821 ... p3=false` | `:745-753` |
| F8 | `01:20:48.017 main FORWARD P3 bigimg ready=false path=none cb=l0` | `:757` |
| F9 | `01:20:53.559 ... download fired ... r=false` + 同 F7 的 miss / copy skip / p3=false | `:776-783` |
| F10 | 主会话只读 `ls`：`image2/b1/0e/` 有 `b10e2ba904513f64043d52923d454330.jpg`=882950B、`th_b10e2ba904513f64043d52923d454330`=4320B；216b 桶只有 `216b1f7c...jpg⌖`=36B、`th_216b1f7c...`=15906B、`th_216b1f7c...hd⌖`=36B，无 335644B 原图 | 任务简报（主会话核） |
| F11 | 主会话补证：`b10e4e26765247a2ac7f.jpg`(15906B) 在当前 `image2/b1/0e/` **已不存在**，同目录也**无** `th_b10e4e267...` 兄弟；同目录真实大图样本 882950B vs 其 `th_` 兄弟 4320B ≈ 200× 体积差 | 主会话补证消息（11-23，只读回传） |

### 1.1 一处必须纠正的取证口径（影响后续所有判定）

日志的 `path=` 与 `size=` 都是 `takeLast(24)` / `File.length()`：`origin-by-md5 path=b10e4e26765247a2ac7f.jpg` **不是完整文件名**，而是完整 md5 `7ccddbfb97a8b10e4e26765247a2ac7f` 的第 5~36 位（F3 的 `img=` 字段给出完整 32 位）。

- ⇒ 该文件真实分桶是 `image2/7c/cd/`，**不是**主会话按 tail 误读的 `image2/b1/0e/`。
- `b1/0e` 里的 `b10e2ba9...jpg`=882950B 是**另一张图**（仅前 4 位十六进制与本次 tail 巧合相同），不能作为本案原图证据，也不能据此说「原图还在」。
- 同理，F7 的 `path=cd59b1268349fd0eb3c46801` 是 `.../image2/21/6b/th_216b1f7ccd59b1268349fd0eb3c46801` 的尾部（与 `.708` 的 `name=th_216b1f7ccd59b1268349f` 逐字自洽：文件名无扩展名，35 位 → tail24 恰为 `cd59...`）。此 UNKNOWN 在 §5 按「已由双日志交叉锁定，仍需 snap 字段探针终证」处理。

### 1.2 机制 A：15906B 裸 md5 文件越过 `>15KB` 门槛被误认原图

**判定链（真机坐实，纯代码路径推导，无假设）**：

1. F1 触发下载；F2 `origin-by-md5` 命中并打印 `size=15906`。
2. `ForceForward.kt:2457` 的门禁为 `f.exists() && f.length() > 15 * 1024 && !f.name.startsWith("th_")`。`15*1024=15360`，`15906 > 15360` 成立；命中文件名是 32 位 md5 + `.jpg`，不带 `th_` 前缀 ⇒ 第二项也成立。函数返回该路径（`:2459`）。
3. `:2320 p3BigImgSrc = src`；F4 的 `path=` 与该路径 tail 完全相同 ⇒ `chosenP3Src` 就是它；`fresh=false` 只说明异步回调未置 ready，不影响 `chosenP3Src = p3BigImgSrc` 的取值。
4. F5 `p3=true` ⇒ `copyP3Temp(chosenP3Src)` 的准入三门槛（`:2484`）同样通过，字节被复制进 `cacheDir/_<uuid>.jpg` 并作为 FileSender 第 6 参 `bigImgPath` 发出。

**坐实 vs 推断的分界**：

- ✅ **真机坐实**：15906B 裸 md5 文件通过了三门槛、被当成 bigImgPath、以 `p3=true` 发出。这是「判据误放行」的确定事实。
- ⚠️ **推断（高置信，未字节级取证）**：该 15906B 文件是缩略图而非「恰好 15.9KB 的真实原图」。依据三条：① 15906B 与 R76/R82 多次记录的坏图缩略图量级（`~15KB`）一致；② 本案同类坏图在 R83 成功时的真实原图是 335644B，相差 21 倍；③ 15906 仅比 15360 高 546B，呈「贴着阈值」形态。缺少 `md5sum`、`th_7ccd...` 对照、宽高解析，故不升级为真机确定。
- ⚠️ **推断（未取证）**：01:19 这一条对端肉眼模糊。用户「又是缩略图」的反馈与此一致，但日志里没有该 msgId 的 `field_filemd5`、没有对端字节数，不能当逐消息判据（见 §5 U2）。

**结论 A**：机制 A = **「判据误判」已坐实；「被误判对象是缩略图」为高置信推断**。它解释「p3=true 却仍模糊」这一类假成功。

### 1.3 机制 B：本地原图已不存在，fallback 必失效

**判定链（真机坐实）**：

1. F7/F9 对 216b：`origin-by-md5 miss reason=thresholds`。`p3OriginFromMd5`（`:2454`）**只拼一个路径** `image2/21/6b/216b1f7ccd59b1268349fd0eb3c46801.jpg`。F10 显示该桶无 335644B 原图（仅 36B 占位 + 15906B `th_` + 36B `th_...hd`）⇒ 构造路径不存在或 36B ⇒ 三门槛必失败，无任何备选候选。
2. F7/F9 `copy skip: source fails thresholds name=th_216b...` ⇒ 回退链最终落到 `snap.localPath`（`th_` 缩略图），`copyP3Temp:2484` 因 `!name.startsWith("th_")` 失败反转而拒绝 ⇒ `bigImgPath=null`（`:2649`）⇒ `p3=false`。
3. F8 `cb=l0 ready=false path=none` ⇒ 异步下载回调确实回来了，但回调里 `:2360-2364` 重算路径后三门槛仍不过 ⇒ ready 未置。下载 `r=true` 不等于「可用原图已落盘到本地候选路径」。
4. F9 二次 `r=false`，同样 miss ⇒ 重试也未救回。

**关键推理（确定性，非概率）**：`p3OriginFromMd5` 的输入只有 img 字段与 localPath，输出只有一个由 md5 直算的绝对路径；当该文件在磁盘上不存在时，**任何纯本地判据都无法凭空产生原图字节**。判据再严格只会更早、更诚实地放弃，不会把它变成清晰图。

- ✅ **真机坐实**：216b 原图缺失、`origin-by-md5` 零命中、`copyP3Temp` 拒绝 `th_`、异步回调 ready=false、`p3=false`。
- ⚠️ **推断**：F6（`f64b3059...`）与 F2（`7ccd...`）两图也属「本地无原图」同族——依据是它们的 `origin-by-md5 miss` 与 `th_` copy skip；未在对应桶做 `ls`，故不把「原图绝对不存在」升级为真机确定（见 §5 U4/U5）。

**结论 B**：机制 B = **真机坐实**。它与 R83 成功态构成完整 A/B 因果对照：R83 时 335644B 存在 ⇒ 命中 ⇒ 对端清晰；今日同一 216b 原图被清理 ⇒ 必 miss ⇒ 模糊。

### 1.4 两机制的统一根因与边界

A 与 B 不是两个独立 bug，而是同一事实的两面：**本地大图字节已缺失，磁盘上只剩约 15.9KB 的缩略图，且其中一部分以「裸 md5 + .jpg」命名（不带 `th_`）**。

- A = 判据**错误接受**这个缩略图（裸 md5 名绕过 `!th_`，15906B 贴着 15360 越过下限）⇒ 假 `p3=true`。
- B = 判据**正确拒绝**但无原图可退 ⇒ 诚实 `p3=false`。

两者都无法靠调阈值把「缩略图」变成「原图」。阈值修复只能消除 A 的假成功信号；要让用户肉眼收到清晰图，必须拿到原图字节（见 §3 路线 B）。

### 1.5 主会话补证的证据分层（回写，不改变 A/B 结论）

主会话 11-23 补证三条，按证据等级归档如下，**不把任何一条升级为「字节相同已证」**：

1. **【真机确定·限错桶】**：`b10e4e26765247a2ac7f.jpg`(15906B) 在 `image2/b1/0e/` 当前不存在，且该目录无 `th_b10e4e267...` 兄弟 ⇒ 在 `b1/0e` 这个**错误桶**里无法做字节比对。证据价值仅限于「该桶无对照样本」，**不能**推出「真候选已被清理」——因为 §1.1 已由 F3 的完整 md5 证明真候选在 `image2/7c/cd/`。
2. **【推断·体积量级，非字节证明】**：`b1/0e` 同目录大图样本 `b10e2ba9...jpg`=882950B 与其 `th_` 兄弟=4320B 相差约 200×，加上本案 15906B 贴着 15360 上限、与 R76/R82 的 `~15KB 缩略图`量级一致 ⇒ **「15906B 更像缩略图」是合理的高置信推断**，但 `b10e2ba9...` 是另一张图，不能当本案的字节对照，故不升级为真机确定。
3. **【会话真值·非技术证据】**：lane transcript 的 `requestModelId` 真值已回填为 `hy4flash-free`。仅用于确认模型身份口径，不写入任何规则文件（会话临时规则），也不作为 A/B 判据证据。

**净效果**：F11 既不否定 A 的「判据误放行已坐实」，也不把 A 的「对象身份=缩略图」从推断升级为确定；它只是把 U1 的补证范围从「b1/0e」进一步收窄到「必须用 7c/cd」。§5 U1 已据此改写。

[DONE] 1. 两类失败机制的判定（A / B），真机坐实 vs 推断

---

## 2. 现有判据 / 测试审查：15KB 为何不成立，严格判据如何设计

### 2.1 三处判据的实际形态（源码逐行）

| 位置 | 判据 | 作用 |
|---|---|---|
| `ForceForward.kt:2398-2412 p3SrcOf` | 扫 ImgInfo2 所有 String 字段，取第一个 `startsWith("/")` 且 `exists && length>15*1024 && !name.startsWith("th_")` 的路径 | 从记录字段选「原图路径」 |
| `ForceForward.kt:2427-2467 p3OriginFromMd5` | 从 String 字段抠 32 位 md5（扩展名可选，缺省 `.jpg`）→ `MicroMsg/<hash>/` 动态根 → `image2/<2>/<2>/<md5>.jpg` → 同一 `exists && >15KB && !th_` | R83 fallback，只试**单一**构造路径 |
| `ForceForward.kt:2363` listener / `:2409` p3SrcOf / `:2457` fallback / `:2484` `copyP3Temp` 准入 / `:2505` 复制后复核 / `:2640` 发送时 `p3ReadyPath` | 同一三门槛被复制 6 份 | 全链路一致的「>15KB 且非 th_」 |

`pickImageFile`（`ForwardLogic`，经 `ForceForward.kt:1268` 用于 resolveImage）是另一条 R73 纯选择判据：它按「大图 > hd > 缩略图」排序并滤 36B 坏文件（`:1267-1272`），只服务于 `THUMBNAIL_DIRPATH://` 解析，**不是 P3 原图判据**；但它同样把「非 th_ 且 >0」当大图候选，遇到本案裸 md5 的 15906B 缩略图会选中它作为「大图」。两处判据共享同一个盲点。

### 2.2 15KB 阈值的来源假设，以及它为什么不成立

来源（可查，非猜）：`docs/_r76_p3_bytes_landing_plan.md:217` 原文——

> `length() > 15 * 1024` —— 坏图本地缩略图 ~15KB、占位 36B（§2 背景），阈值把二者挡在外面；

同文档 `:45`：`本地 36B 占位 + ~15KB 缩略图，无大图`。即设计者把「缩略图 ~15KB」当作**已知小于阈值**的背景常量，用 `>15KB` 一刀切掉「36B 占位 + 15KB 缩略图」，只放行原图。

三条假设逐条失效：

1. **「缩略图 ~15KB < 15360」失效**：真机 `origin-by-md5 size=15906`（F2），而 15906 > 15360。差 546B。阈值取的是一个**没有真机样本标定的整数**，把真实缩略图量级切在了门的同一侧。R82 也把 15906B 记为「15KB 缩略图」（`_r82_b2_netscene_forensics.md:62/97`），是同一个数量级口径的混乱。
2. **「缩略图一定带 `th_` 前缀」失效**：F2 命中的是 32 位裸 md5 + `.jpg`，`!name.startsWith("th_")` 形同虚设。微信缓存里既有 `th_<md5>`（4320B/15906B）也有裸 `<md5>.jpg` 的缩略图，命名不是可靠的身份标识。F10 的 `th_216b...hd⌖`=36B 还说明 `hd` 后缀也可能只是占位。
3. **「>15KB 的正样本就是原图」失效**：它只有下限没有上限、没有尺寸/分辨率语义。真原图 335644B/882950B，缩略图 15906B/4320B，二者差 20~200 倍；一个只看是否大于 15360 的布尔量不可能表达「比缩略图大一个数量级且分辨率匹配源图」。

补充：`R83OriginByMd5Test.kt:238-242` 把三门槛**逐字锁死**（`f.length() > 15 * 1024` 与完整条件行都要求字符串命中），M2 变异（`15*1024 → 5*1024`）只验证「字面量没被改小」，并不验证「这个字面量能区分原图与缩略图」。测试锁的是实现一致性，不是判据有效性——这是本轮回退能穿过测试的直接原因。

### 2.3 可执行的严格判据候选（每条含失效场景与验证成本）

以下判据可单独或组合使用；`成本` 指真机取证 + 落码验证成本，不含 gradle（本轮不跑）。

| # | 判据 | 具体做法 | 失效场景 | 验证成本 |
|---|---|---|---|---|
| C1 | **同桶 `th_` 兄弟对照** | 候选 `image2/<2>/<2>/<md5>.jpg` 所在目录若存在 `th_<md5>*`，比较字节数：候选 ≤ 兄弟 × N（建议 N=2~3）或字节完全相同 ⇒ 判缩略图拒绝；候选显著大于兄弟（如 ×10 以上）才放行 | 原图被清理后只剩 `th_`（216b 现状）⇒ 候选不存在，C1 无从触发，只能靠 B 路线；`th_` 也被清理 ⇒ 无对照；合法小图恰好与缩略图同量级 ⇒ 误杀 | 低：一次同目录 `exists/length`；主会话 `ls -l image2/7c/cd/` + `md5sum` 即可对照 |
| C2 | **图片头宽高 vs 本条消息 ImgInfo2 宽高** | 读候选 JPEG/PNG/WebP 头拿像素宽高；从 `O8().o2(localId)` 的 ImgInfo2 取 width/height 字段；候选宽高 < 源图宽高 × 0.8 ⇒ 缩略图拒绝 | ImgInfo2 字段名未取证（需探针）；动图/multi-frame WebP 头解析复杂；原图与缩略图同分辨率（少见）⇒ 误判；字段缺失 ⇒ 需降级策略 | 中高：先只读探针定位字段，再写 header 解析单测；本轮不跑 gradle，需主会话另派实现 lane |
| C3 | **下限上调 + 加入「显著大于缩略图」语义** | 把 `15*1024` 换成「≥ 64KB 且 ≥ 同桶 `th_` × 8」之类；不再用单一常数 | 合法小原图（<64KB 的截图/小图）被误杀 ⇒ 仍模糊；若某真原图就是 20KB ⇒ 误杀。无原则性依据，属止血非根治 | 极低：改常数 + 单测变异；但需真机样本标定数值，否则只是把 15906 挡住、把 16000 放进来的挪门 |
| C4 | **与消息记录的原图字节数对照** | 若 ImgInfo2 / 消息表存有原图 `totallen`/`size` 字段，要求 `File.length()` 与记录值相符（容差内） | 字段是否存在/是否可信未取证（R82 已证 task 层 filemd5 来自 xml，但 ImgInfo2 内部 size 字段未知）；记录过期 ⇒ 误杀合法原图 | 中高：需 dex/探针取证字段名，再落码 |
| C5 | **严格路径形态白名单 + 扩展名与文件头一致** | 只接受 `image2/<2hex>/<2hex>/<32hex>.<ext>` 且 ext 与 JPEG/PNG/WebP magic 一致；拒绝任何 `snap.localPath` 直灌、`th_` 前缀、无扩展名形态 | 本案 15906B 裸 md5 **恰好符合此形态**，单独使用挡不住 A；只能挡 B 里 `snap.localPath`/`th_` 混入 | 低：正则 + magic 判定；真机 `ls` 可核对文件名形态 |
| C6 | **同 msgId 的 CDN/下载元数据交叉** | 有 `cdnbigimgurl`/CDN fileId 时，要求喂给 FileSender 的字节来自本次下载落盘文件，而非本地旧候选 | 属 §3 路线 B（主动下载/原生转发），不是本地判据；需要异步消费与 hook 落点 | 高：dex 锚点 + 异步正确性 + 多目标竞态 |

**组合建议**：C1 + C5 是最小可落地组合（挡住 A 的裸 md5 缩略图与 B 的 `th_` 混入，零新dex锚点）；C2/C4 待取证字段后作为增强；C3 仅作临时止血且必须配真机样本标定；C6 单独列为路线 B，不混进阈值修复。

### 2.4 现有测试覆盖缺口（`R83OriginByMd5Test.kt` 审查）

- 已锁：函数存在、md5 分桶拼接、裸 md5 补 `.jpg`、三门槛字面量、动态 hash、日志尾 24、只读红线、两处接线顺序、7 个变异（M1~M7）判红。
- 未锁（本轮回退穿过的口子）：
  1. 没有任何用例断言「`15906` 这类贴阈值的文件应被拒绝」——判据有效性零覆盖；
  2. 没有 `th_` 兄弟对照（C1）相关断言与变异；
  3. 没有宽高/`size` 记录对照（C2/C4）；
  4. `threeThresholdsOk`（`:238-242`）锁的是**门槛被写进代码**，不是**门槛能分类**；放宽到 5KB 会红，但「15906B 仍被放行」在现有测试下是绿的。
- 结论：新增测试必须是「判据分类测试」而非「字面量锁死测试」，且要带真实变异点（见 §4.3）。

[DONE] 2. 现有判据 / 测试审查：15KB 为何不成立，严格判据如何设计

---

## 3. 路线对照表（A / B / C，并回答「本地无原图能否靠纯本地判据救回」）

### 3.0 先回答决定性问题

**不能。本地无原图时，纯本地判据不可能救回清晰图。**

理由不是「阈值还不够巧」，而是信息论层面的：`p3OriginFromMd5` 的全部输入 = img 字段字符串 + localPath，输出 = 一个由 md5 直算的磁盘路径；当该路径上的文件不存在/是 36B 占位时，本地不存在原图字节，任何 `exists/length/宽高/magic/md5sum` 组合都只能回答「这个文件不是原图」，不能凭空生成原图字节。C1/C2/C3/C4/C5 全部属于「判别器」，不是「字节来源」。

- 判别器的正确收益：把 A 的假 `p3=true` 变成诚实 `p3=false`，消除「以为修好了」的误判；对 B 只能更早放弃，不能改变对端模糊。
- 这**不是阈值修复的失败**，而是需求边界：用户要的「对端清晰」需要「拿到原图字节」这一新能力，对应路线 B。按任务要求，明确把它单列为另一个需求，不混进阈值修复。

### 3.1 路线对照

| 维度 | **路线 A：严格判据止血（推荐的最小修复）** | **路线 B：主动取原图字节（另立需求）** | **路线 C：诚实降级 + UX 提示** |
|---|---|---|---|
| 核心动作 | 把散在 6 处的 `exists && >15KB && !th_` 收敛为单一 `isRealOriginal()`：C5 严格路径形态 + C1 同桶 `th_` 兄弟对照（候选须显著大于兄弟）+ 失败原因码日志；`p3SrcOf`/`p3OriginFromMd5`/listener/`copyP3Temp`/`p3ReadyPath` 全走同一判据 | 两条子路：B1 正确消费 `b1.R5`/`DownloadImgService` 异步落盘（修 F8「r=true 但回调拿不到原图」）；B2 复刻 B6 + checkExist 让消息带 `cdnbigimgurl` 走服务端秒传（R79/R82 方向） | 本地无原图时明确告诉用户「原图已过期，模块转发将发缩略图」，引导用微信原生转发（R77/R78 已证原生清晰）；P3 失败不再假装成功 |
| 代码量 | 小：`ForceForward.kt` 约 1 个新判据函数 + 6 处替换 + 日志 reason；新增 1 个测试文件约 8~12 例 | 大：B1 需异步回调正确性 + 多目标竞态；B2 需 dex 锚点、自建 task/listener、消息 xml 写入、0/0 清理（R80/R82 已评估工程量大且 R80 曾否决整套复刻） | 极小：仅文案 + 失败分支，零新 hook |
| 是否额外写库 | **否**（只读 File.exists/length + 同目录 list；不写 ImgInfo2/消息表，不写宿主对象） | B1 可能需让下载结果落宿主缓存（写文件，非写库）；B2 需写消息 xml/建记录（写库） | 否 |
| 竞态 | 低：判据只读；异步 listener 沿用现有 `p3BigImgPath`→`p3BigImgReady` Volatile 顺序（`:2366-2368`），不引入新共享状态 | 高：异步下载回调与发送时点竞态（F4/F8 已暴露「ready=false 但仍发」）；B2 多目标 task/listener 生命周期、0/0 清理 | 无 |
| 失效场景 | ① 本地无原图（B 类）⇒ 仍模糊，只是不再假 p3=true；② 合法小原图与缩略图同量级 ⇒ 被 C1/C3 误杀；③ `th_` 兄弟被清理 ⇒ C1 无对照，退化为仅 C5（挡不住裸 md5 缩略图）；④ 若缩略图与真原图同字节（极端）⇒ 无法区分 | ① `cdnbigimgurl` 缺失 ⇒ B2 秒传无从引用；② 服务端二次校验策略变更；③ 下载仍 r=false（F9）；④ 样本/账号隔离导致 hook 挂错类（R78 曾挂错 f2） | ① 用户仍得不到清晰图；② 文案属于产品行为变更，需主会话/用户拍板 |
| 验证成本 | 低：单测纯 JVM 判据分类 + 变异；真机 `ls`/`md5sum`/宽高只读核 + 一轮正反发送 | 高：dex 自算锚点 + 真机多轮 + 对端肉眼 + 反向开关对照（历史 R76/R77/R78/R80/R81/R82 六轮均卡在此） | 低：真机看提示文案 + 用户确认 |
| 能否让用户收到清晰图 | **不能**（对 B 类）；对 A 类只是把假成功变诚实失败 | **能**（唯一能） | 不能 |

### 3.2 路线关系（不是三选一的等价选项）

- A 与 C 可叠加：A 修判据、C 修表达，二者都零新能力、低风险，可在同一轮做。
- B 是**另一个需求**：只有当主会话确认「本轮目标就是让坏图重新清晰」且愿意承担 R76~R82 已反复卡住的取证/竞态成本时才启动；一旦启动，A 仍是前置（否则 B 拿到的原图也会先被旧判据误判/误发）。
- 因此推荐顺序：**先 A（+可选 C）止血并恢复判据可信度 → B 另立需求独立拍板**。绝不把 B 的方法论写进 A 的 commit message 或测试名，避免 Again「阈值修复」被误读成「清晰度修复」。

[DONE] 3. 路线对照表（A / B / C，并回答「本地无原图能否靠纯本地判据救回」）

---

## 4. 推荐的最小修复路线 + 精确落码规格

### 4.1 推荐一句话

**推荐路线 A（严格判据止血，可选叠加 C）——把散在 6 处的三门槛收敛成单一 `p3Admit` 判据，以「同桶 `th_` 兄弟字节比 + 严格路径形态/扩展名 magic + 兜底尺寸下限」替代「>15KB 单一布尔」，并把拒绝原因打进日志；明确不承诺对端清晰，清晰另立路线 B 需求。**

范围纪律：只修「把缩略图误认原图」的判据；不改 FileSender、不动 R84 选人器、不碰 q1/m.a/fullpath 已闭环链、不提交、不跑 gradle。

### 4.2 精确落码规格（给实现 lane，不自行改源码）

**(a) 新增单一判据函数**（建议置于 `p3SrcOf` 之前，`ForceForward.kt:2397` 附近）：

```
private fun p3Admit(f: File, strictShape: Boolean): String?   // null = 放行；非 null = 拒绝原因码
```

判定顺序（短路，reason 固定便于日志断言）：

| 序 | 条件 | 拒绝原因码 |
|---|---|---|
| 1 | `!f.exists()` | `no-exist` |
| 2 | `f.name.startsWith("th_")` | `th-prefix` |
| 3 | `f.length() <= 64 * 1024`（兜底下限，见下） | `below-floor` |
| 4 | `strictShape && !IMAGE2_RE.matches(相对 dataDir 的尾段)` | `bad-shape` |
| 5 | `strictShape && !magicMatchesExt(f)` | `magic-mismatch` |
| 6 | 同桶存在 `th_<md5>*` 兄弟且 `f.length() < 8 * siblingMaxLen`（或字节完全相同） | `thumb-sibling` |

- `IMAGE2_RE` = `(?i)^image2/[0-9a-f]{2}/[0-9a-f]{2}/[0-9a-f]{32}\\.(jpg|jpeg|png|webp)$`，只匹配 `MicroMsg/<hash>/` 之后的尾段（不硬编码 hash，沿用 `:2444-2446` 动态根）。
- `magicMatchesExt`：JPEG `FF D8 FF`、PNG `89 50 4E 47`、WebP `RIFF....WEBP`，只读文件头前 16 字节。
- 兄弟查找：`f.parentFile?.listFiles()` 内 `name.startsWith("th_" + md5)`（`md5` 取 `f.name` 的 32 位主体），取 `maxOf(length)`；同桶多账号只在本 root 内找，不跨 root。
- 下限 `64KB` 是**临时止血值**，不是原则值：R83 真原图 335644B、b1/0e 样本 882950B，缩略图 15906B/4320B；8× 兄弟比也是由此标定（21× / 204×）。必须在 §4.4 真机正向确认不误杀合法小图；若误杀，转 C2 宽高判据（需先补 §5 U7 字段探针），不得靠继续挪常数掩盖。
- `strictShape` 取值：`p3OriginFromMd5` 与 listener 传 `true`（路径由代码自己拼）；`p3SrcOf` 与 `copyP3Temp` 传 `false`（它们接收记录里来路不明的绝对路径，强制 shape 会误杀合法缓存路径），但仍走 1/2/3/6。

**(b) 替换 6 处判据调用点**（逐行现状 → 改法）：

| 行 | 现状 | 改为 |
|---|---|---|
| `:2363` newP3Listener | `f.exists() && f.length() > 15*1024 && !f.name.startsWith("th_")` | `p3Admit(File(path), true) == null` |
| `:2409` p3SrcOf | 同上（内联 firstOrNull 条件） | `p3Admit(File(p), false) == null` |
| `:2457` p3OriginFromMd5 | 同上 | `p3Admit(f, true) == null`，失败时把 reason 累进日志：`origin-by-md5 miss reason=<reason>`（现只有字面 `thresholds`） |
| `:2484` copyP3Temp 准入 | 同上 | `p3Admit(srcFile, false)?.let { reason -> LogX.w("... reason=$reason name=..."); return null }` |
| `:2505` 复制后复核 | `temp.length() <= 15*1024` | `p3Admit(temp, false) != null` |
| `:2640` 发送时 p3ReadyPath | 同上（takeIf 条件） | `p3Admit(File(p), false) == null` |

副作用要求：`p3OriginFromMd5` 末尾 `origin-by-md5 miss reason=thresholds`（`:2462`）改为携带具体 reason；`copy skip`（`:2486`）同样带 reason。这是本轮最重要的可观测性产物——当前 A/B 两类失败在日志里都塌缩成 `thresholds`/`source fails thresholds`，无法区分。

**(c) 不改动清单**（防止越界）：`pickImageFile`/`imageBucket`（ForwardLogic，R73 已闭环）、`prepareBigImg` 的下载触发、`newP3Listener` 的回调方法名/err 解析、`FileSender.send` 六参语义、`q1.*`/`m.a`/`fullpath`/j6 闭环字段、R84 `pickerFriends`/`pickerDisplayName`、开关 `chat.forward_p3_bigimg` 默认值。

**(d) C2 宽高判据**：本轮不落码，列为取证后增强（需 §5 U7 先定位 ImgInfo2 宽高字段与 filename 字段对应关系）。

### 4.3 单测用例清单（新文件 `R85P3AdmitTest.kt`，纯 JVM 临时目录，带真实变异点）

延续 `R83OriginByMd5Test` 的「读去注释代码行 + 内存变异」范式，但**增加行为级测试**（在 `tempDir` 造真文件跑 `p3Admit`，而不是只断字面量）。

| 用例 | 场景（真实变异点） | 期望 |
|---|---|---|
| T1 `bareMd5ThumbWithEqualThSiblingRejected` | `7ccd...jpg`=15906B + `th_7ccd...`=15906B | reject `thumb-sibling` |
| T2 `bareMd5ThumbWithSmallerThSiblingRejected` | 候选 15906B + `th_7ccd...`=4320B（比 3.7 < 8） | reject `thumb-sibling` |
| T3 `realOriginalWithThSiblingAdmitted` | 335644B + `th_216b...`=15906B（比 21） | admit（回归保护） |
| T4 `realOriginalWithoutThSiblingAdmitted` | 335644B、无 th_ 兄弟 | admit（防 C1 过杀） |
| T5 `thPrefixAlwaysRejected` | `th_<md5>.jpg` 任意大小 | reject `th-prefix` |
| T6 `badShapeRejectedWhenStrict` | `/cache/_uuid.jpg` 传 `strictShape=true` | reject `bad-shape`；同文件 `false` 时不受此项约束 |
| T7 `magicMismatchRejected` | 名义 `.jpg` 实为 PNG magic | reject `magic-mismatch` |
| T8 `belowFloorRejected` | 无兄弟、50000B | reject `below-floor`；65536B 放行（边界） |
| T9 `allSixCallSitesUseP3Admit` | 源码字符串断 6 处均已替换 | 通过；任一处漏改即红 |
| T10 `admitIsReadOnly` | 跑前后目录文件集合/字节一致、无宿主字段写入 | 通过 |

真实变异验证（对源码字符串做内存变异，断言对应用例转红）：

- M1 删掉兄弟比较分支 ⇒ T1/T2 红；
- M2 兄弟比 `8` 改 `1` ⇒ T2 红；
- M3 `strictShape=true` 时跳过 shape 检查 ⇒ T6 红；
- M4 删 magic 检查 ⇒ T7 红；
- M5 下限 `64 * 1024` 改 `15 * 1024`（退回旧阈值）⇒ T1/T8 红；
- M6 把 `:2457` 一处改回旧三门槛 ⇒ T9 红；
- M7 让 `p3Admit` 写标记文件 ⇒ T10 红。

补充：保留 `R83OriginByMd5Test` 全部现有断言不动（它锁的是闭环链），新测试只加不减；若 `threeThresholdsOk` 的旧字面量与新实现冲突，**改判据函数而非删旧测试**，并在本文件记录冲突原因。

### 4.4 真机正反判据（只读优先；发送前须主会话另派 lane，本 agent 不碰设备）

**前置取证（先于任何发送）**：主会话用只读命令核 §5 U1/U4/U5 的桶内容；确认 7c/cd 是否存在 `th_7ccd...` 及字节数。若 U1 显示 15906B 与 `th_7ccd...` 同字节 ⇒ A 坐实升级为真机确定；若不同字节/无兄弟 ⇒ A 的「缩略图身份」仍为推断，需要在结论里保留。

**正向（A 类，目标 = 不再假成功，不要求清晰）**：对 F2/F3 同一张图重发，期望日志：

```
origin-by-md5 miss reason=thumb-sibling  (或 below-floor / bad-shape)
bigimg ready=false path=... fresh=false
copy skip ... reason=<具体码>
resend-file ... p3=false
```

判据：`p3=true` 且 `size=15906` 的组合**不再出现**；reason 码可区分 A/B。若出现 `p3=true` 且 size 仍 15906 ⇒ 判据未生效，停。

**正向回归保护（B/R83 成功态，目标 = 不误杀）**：若仍能找到 335644B 原图尚在的样本，期望 `origin-by-md5 path=... size=335644` + `p3Admit` admit + `bigimg ready=true` + 回包 `field_filemd5=cff4f5a...`（非 `0ef205`）+ 对端清晰。若该样本已被清理（F10 显示 216b 已无原图），此正向**无法在真机跑**，只能靠 T3/T4 单测保护，必须在结论中显式标注这个缺口，不能宣称「正向已闭环」。

**反向（开关唯一自变量）**：`kvset chat.forward_p3_bigimg=false` → 回读 `now=false` → 冷启 → 同图 `p3=false`；再恢复 true → 回读 `now=true`。由于严格判据与关开关都导致 `p3=false`，反向只能证明「开关语义未坏」，**不能**单独证明严格判据生效；严格判据的证明靠正向的 reason 码。

**终极肉眼口径（诚实写法）**：本轮验收 = 「A 类不再假 p3=true、B 类 reason 明确、R83 成功态不被误杀」。对端是否清晰**不在本轮验收范围**；若用户要求清晰，另起路线 B 需求并重走 R76~R82 级别的取证与拍板。

[DONE] 4. 推荐的最小修复路线 + 精确落码规格

---

## 5. UNKNOWN 清单与补证路径（当前无数据，不猜）

> 以下命令均由**主会话**执行；本 agent 不碰 adb/设备。`tools/dev.py q` 只读拉日志；`probe sql` 只读查库；`ls`/`md5sum` 沿用主会话前次查桶的只读通道（`tools/_lib.py:22 sh("shell", ...)`）。

| ID | UNKNOWN | 当前证据边界 | 需要主会话用哪条只读命令补证 | 补证后可升级为什么 |
|---|---|---|---|---|
| U1 | F2 的 15906B 裸 md5 文件是否与 `th_7ccd...` 同字节/同尺寸 | 只有 `size=15906` 与完整 md5（F3）；F11 已确认**错桶 b1/0e 里文件与 th_ 兄弟都不存在**，这既不能证明字节相同，也不能证明真候选已清理；**真桶 7c/cd 仍未 ls** | `ls -l /data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/7c/cd/`；若存在 `th_7ccd...`，再 `md5sum` 两个文件；`file` 看宽高（若设备有）。**不要再查 b1/0e** | 7c/cd 内同字节 ⇒ 「A 的误判对象是缩略图」升级为真机确定；7c/cd 内文件已无 ⇒ 与 B 同族、纯本地无解；不同字节且候选显著大于 th_ ⇒ 重审 A |
| U2 | 为什么 01:19 的 `p3=true` 仍模糊 | 机制链闭合（15906B 通过三门槛→copyP3Temp→第6参），但缺该条 msgId 的结果证据 | `python tools/dev.py q '19747667\|field_filemd5\|m1e\|resend-file' 80`；对端长按保存看字节数；发送窗口内 `ls -l /data/user/0/com.tencent.mm/cache/_*.jpg` 看临时文件 size | `field_filemd5` 若等于缩略图 md5（如 `0ef205`/`15906B` 文件 md5）⇒ A→模糊因果真机坐实 |
| U3 | `01:20:47 path=cd59b1268349fd0eb3c46801` 的来源 | **已由双日志交叉锁定**：`.707` 是 `takeLast(24)`，`.708` `name=th_216b1f7ccd59b1268349f` 逐字自洽 ⇒ `snap.localPath=.../image2/21/6b/th_216b1f7ccd59b1268349fd0eb3c46801`（无扩展名，35 字符，tail24 恰为 `cd59...`）。终证仍缺 snap 全字段 | `python tools/dev.py q 'FileSender queued\|bigimg ready=false path=cd59' 20`（拿相邻行的 queued 路径）；若要 `snap.localPath`/`snap.srcBigImgPath` 全量，需只读探针输出 snap 字段——属实现 lane，不是本轮 | 升级为真机确定；同时确认 B 类回退落点是 `snap.localPath`（`th_` 无扩展名形态），支撑 §4 的 `strictShape=false` 分流设计 |
| U4 | 7ccd 图是否存在真原图在别处（其他账号 root / `hd` 后缀 / 未清理副本） | 无数据；若存在，则「本地无原图」前提对该图不成立 | 各 `MicroMsg/<hash>/` root 下只读 `find . -name '7ccd*'`（或 `ls image2/7c/cd/` 看 `<md5>hd`/`<md5>.jpghd`） | 若找到 ≫15KB 真原图 ⇒ 该图属「可本地救回」，A 的修复价值上升（C1 兄弟对照可在同桶命中）；若确认无 ⇒ 与 B 同族，纯本地无解 |
| U5 | 01:20:05 的 `f64b30599f41a9ced81e37fe59cfee98` 原图是否存在 | 只有 `origin-by-md5 miss reason=thresholds` 与 `th_82e5692a...` copy skip；《当前桶清单》未含该图 | `ls -l /data/user/0/com.tencent.mm/MicroMsg/89476c7fc82a9f977bc8a901d68b7d37/image2/f6/4b/` | 同 U4，判定 F6 是 A 族（有原图被误判）还是 B 族（原图已无） |
| U6 | `01:20:48.017 cb=l0 ready=false` 是 err 非 0 还是路径不达门槛 | listener 仅在 `errType==0 && errCode==0` 时重算（`:2351`），两种失败在日志里都表现为 `ready=false path=none`；当前日志未打 err 码 | 当前**无法纯只读补证**（需 §4 修改时加 `cb=$name err=$errType/$errCode reason=<码>` 后重装一轮） | 区分「下载失败」与「下载成功但文件不合格」，决定路线 B1 的优先级 |
| U7 | ImgInfo2 的 width/height 字段名、以及哪个字段存 md5 文件名（C2 前置） | R74/R76 已知 `O8().o2(localId)` 返回 ImgInfo2、String 字段可扫出 md5，但宽高字段未取证 | 只读字段扫描探针或直接 `python tools/dev.py probe sql --q "select * from ImgInfo2 where ..." --grep SQLX`（表结构可用只读 `pragma table_info(ImgInfo2)`） | 拿到字段名后 C2 可落码，A 的「缩略图身份」可脱离字节比直接判宽高，避免 U1/U4 的样本依赖 |
| U8 | 7ccd 图历史上是否有过 335644B 量级原图 | 216b 有 R83 历史（335644B，对端清晰）；7ccd 图无历史大小记录 | 在 `docs/派发审计台账.md` / `_dbg_pull.txt` 只读 `grep '19747667\|7ccddbfb'`，看是否有早期 `origin-by-md5 size=` 大值 | 若有 ⇒ 「原图被清理」时间线可钉死；若无 ⇒ 该图可能从来只有缩略图，A/B 归类需再议 |
| U9 | R83 成功态正向当前能否真机复跑 | F10 显示 216b 原图已被清理（无 335644B），R83 的 A/B 对照样本已失效 | 只读确认 216b 桶（已做）；若有其它保留了原图的样本，按 §4.4 正向跑一轮 | 若无法复跑 ⇒ 必须在结论标注「正向仅单测 T3/T4 保护，真机正向缺口」，不得宣称正向闭环 |

### 5.1 取证纪律复核（自检）

- 本裁决中引用的日志行全部来自 `_dbg_pull.txt` 的实际 grep 输出，未把「期望出现的日志」当证据。
- 跨文档/跨会话转述是 F10 与 F11（主会话 `ls` / 补证），已在 §1.0 标明来源；§1.1 纠正了其分桶误读，§1.5 已将 F11 按「错桶确定 / 量级推断 / 会话真值」三层归档，**未写成「字节相同已证」**。
- A 的「误判链路」= 坐实；A 的「对象身份」= 高置信推断（F11 的 200× 体积比只增量级、不增确定性）；B = 坐实；U1~U9 = 未取证，全部给了补证命令或未取证原因。
- `hy4flash-free` 仅为 lane transcript 真值回填，未写入任何规则文件，也不作为判据证据。
- 未运行 gradle、未改源码、未碰设备、未提交。

[DONE] 5. UNKNOWN 清单与补证路径（当前无数据，不猜）

[DONE]
