# R16-V2 复核：F3/F4 两个 P0 修复验证

> 复核子代理 V2 | 只读代码 + DEX 缓存 + 结论落盘。未修改任何源码/测试/脚本。

## 0. 结论摘要

**FAIL**（1 个 P0 未修复 + 1 个新 P0，共 2 个阻断项）

- **P0-1 `fliterPre`：真修好了**（实现等价 DEX 语义、KDoc 一致、测试有判别力、双候选结构未动）。✅
- **P0-2 RecyclerView 定位：没修干净，且引入新 P0**。❌
  1. **chatList 查找条件错**：`msgsnapReport()` 用 `contains("ChattingListView") || contains("ChattingRecyclerView")` 找列表，但运行时列表对象经 `MMChattingListView.l(Context)` 反汇编坐实是 `ScrollControlRecyclerView`（recycler）与 `com.tencent.mm.ui.chatting.view.l`（listview，extends `ScrollControlListView`），两个类名**都不含** `"ChattingListView"` / `"ChattingRecyclerView"` 子串 ⇒ `chatList` 恒 null ⇒ `listKind` 恒 `"none"` ⇒ 分派逻辑是**死代码**，pos 依旧恒 -1、h9/msgId/type/isSend/imgPath 依旧全 none。
  2. **反射方法名不存在**：`getMethod("getChildAdapterPosition", View)` 全 16 个 dex 的 `method_ids` 里 **`getChildAdapterPosition` 命中数为 0**（微信这个版本 RecyclerView 已混淆，该方法名是 `F0`/`G0`）⇒ 反射必然 NoSuchMethodException，被 `runCatching` 吞成 `m=null`。

**阻塞项清单（必须再修）**：
1. 改 `chatList` 查找条件：识别 `ScrollControlRecyclerView` / `ScrollControlListView` / `view.l`（或用 `contains("Recycler")` + `contains("ChattingListView")` 之外，直接沿 parent 链找 `MMChattingListView` 的 `T` 字段对象）。
2. 反射定位方法名从 `getChildAdapterPosition` 改为 `F0`（`(Landroid/view/View;)I`，语义 = getChildAdapterPosition）或 `G0`（getChildLayoutPosition），并对 `getCount`/`A0` 同理核对混淆名（`getCount` 未混淆，`A0` 是 `(I)Lc3;`，已坐实）。

## 1. P0-1 fliterPre

### 1.1 实现是否等价于 DEX 语义（附逐分支走查表）

**实现**（`wxgroup/app/src/main/java/com/lx/wxgroup/feature/MomentsLogic.kt:433-438`）：

```kotlin
fun fliterPre(s: String): String {
    if (s.isEmpty()) return ""
    var i = 0
    while (i < s.length && s[i] == '0') i++
    return if (i >= s.length) "" else s.substring(i)
}
```

**DEX 原文**（`Lbf3/t0;->s`，classes3.dex `code_off=0x216004`，本复核独立重取）：

```
  [  7] if-nez                 v6             -> +8  (abs 15)   ; null 检查
  [ 12] const-string           v6             ''                ; null → ""
  [ 14] return-object          v6
  [ 15] invoke-virtual         String.length()                  ; v2 = length
  [ 19] const/4                v3             #0                ; ⭐ i = 0
  [ 20] if-ge                  v3, v2         -> +14 (abs 34)   ; i >= len → substring
  [ 22] invoke-virtual         String.charAt(I)                 ; v4 = s[i]
  [ 26] const/16               v5             #48               ; '0' = 0x30
  [ 28] if-eq                  v4, v5         -> +3  (abs 31)   ; 等于 '0' → i++
  [ 30] goto                                  -> +4  (abs 34)   ; 不等 → 跳出
  [ 31] add-int/lit8           v3, v3, #+1                       ; i++
  [ 33] goto                                  -> -13 (abs 20)
  [ 34] invoke-virtual         String.substring(I)
```

> ⚠️ 说明 `[31]` 是 `add-int/lit8 v3, v3, #+1`（i 自增 1），不是 +3。本复核手工读原始 code unit：
> `[31]` 处 w0=0x03d8（AA=0x03=目标 v3，op=0xd8）、w1=0x0103（22b 格式第二 unit：低字节=BB=0x03=源 v3，高字节=CC=0x01=字面量 +1）。
> 项目工具 `_r14_walk.py` 的 `regs()` 对 0xd8 误按 22t 格式（4 位寄存器）解析，把 `v3,v3,+1` 打成了 `opd8 v3, v0`，属工具显示瑕疵，不影响语义判定。

**逐分支走查**（`i=0` 正确语义 vs 实现）：

| 输入 | DEX 语义（i 从 0 跳前导 '0'） | 当前实现结果 | 等价？ |
|---|---|---|---|
| `""` 空串 | null/空 → `""` | `if (s.isEmpty()) return ""` → `""` | ✅ |
| `"0"` | i=0 是 '0'→i=1；i>=len→substring(1)=`""` | 循环 i=1，`i>=len` → `""` | ✅ |
| `"00"` 全 0 | 跳两格→i=2=len→`""` | 同左 → `""` | ✅ |
| `"00000000"` 全 0 | 跳到结尾 → `""` | 同左 → `""` | ✅ |
| `"15038644089917813396"` 无前导 0 | 首位 '1'≠'0'→substring(0)=原串 | 循环不进→`substring(0)`=原串 | ✅ |
| `"0015038873180398883448"` 两个前导 0 | 跳 2 格→`"15038873180398883448"` | 同左 | ✅ |
| `"001500"` | 跳 2 格→`"1500"`（中间的 0 保留） | 同左 | ✅ |
| `"12"` 短串非 0 | substring(0)=`"12"` | 同左 | ✅ |
| `"10"` | substring(0)=`"10"` | 同左 | ✅ |
| `"00a0b"` 前导 0 + 字母 | 跳 2 个 '0'→遇 'a'≠'0' 停→`"a0b"`（字母及其后的 0 全保留） | 同左 | ✅ |
| `"12a0b"` 首位非 0 | substring(0)=`"12a0b"`（'a'/'0'/'b' 全保留） | 同左 | ✅ |

**结论**：实现与 DEX 语义逐分支等价，`var i = 0` 修复到位。任务特别点名的 `"00a0b"` 输入：DEX 里 `if-eq 48` 只比较 `'0'`，遇到 `'a'` 停在 `[28]`→`[30]` goto 跳出循环 → `substring(2)`=`"a0b"`；实现 `while (s[i]=='0')` 同样遇 'a' 停，返回 `"a0b"`。✅ 一致。

### 1.2 KDoc 与实现是否一致

**KDoc**（`MomentsLogic.kt:421-431`）：

```kotlin
/**
 * 复刻 `Lbf3/t0#s(String)`（fliterPre）—— 文件名里那段数字的真正来源。
 *
 * 反汇编语义：**从下标 0 开始**，跳过所有**前导的**连续 `'0'`，返回从第一个非 `'0'`
 * 处到结尾的子串。
 * 例：`"15038644089917813396"` → 首位非 `'0'`，**原样返回** ...
 *     `"0015038873180398883448"` → 跳前导 0 → `"15038873180398883448"`。
 *
 * ⚠️ 真机 mediaId 长度实测有 20/22/23/24/25/26/27/28 共 8 种，**不要截断到定长**。
 * 证据：DEX 反汇编 `Lbf3/t0;->s` 的 `[19] const/4 v3,#0`（2026-10-24 复核 ...）。
 */
```

KDoc 三处关键点与实现一致：
1. 「从下标 0 开始跳过前导 '0'」 ↔ `var i = 0`。✅
2. 两个示例（`15038644089917813396`→原样、`0015038873180398883448`→`15038873180398883448`）经 1.1 走查正确。✅
3. 「不要截断到定长」 ↔ 实现无任何 `substring(0, 20)` 之类截断。✅

**V1 原报告的「注释与实现分叉」已消除**：修复前 KDoc 写「从下标 2 开始」而实现 `var i = 2`（虽然一致但都错），现在两者都对齐 DEX 真语义，无再次分叉。

### 1.3 测试断言的判别力审计（表格：用例名 / 输入 / i=0 结果 / i=2 结果 / 有无判别力）

判据：若某断言在 `i=0` 与 `i=2` 两种实现下**结果相同**，则它**无法区分两种实现**，是无判别力的死断言。

`SnsCacheMatchTest.kt`：

| 用例 | 输入 | i=0 结果 | i=2 结果 | 有判别力？ |
|---|---|---|---|---|
| `fliterPreSkipsLeadingZerosOnly` | `"15038644089917813396"` | 原样（首位1） | `"038644089917813396"`（砍"15"） | ✅ 有 |
| 同上 | `"0015038873180398883448"` | `"15038873180398883448"` | `"15038873180398883448"`（砍"00"后同） | ❌ 无（巧合同值） |
| 同上 | `"001500"` | `"1500"` | `"1500"`（砍"00"后同） | ❌ 无 |
| `fliterPreKeepsLeadingNonZero` | `"12345"` | `"12345"` | `"345"` | ✅ 有 |
| 同上 | `"12a0b"` | `"12a0b"` | `"a0b"`（砍"12"） | ✅ 有 |
| `fliterPreEmptyAndAllZeroCases` | `""`/`"0"`/`"00"`/`"00000000"` | 均 `""` | 均 `""`（砍2位仍是全0/空） | ❌ 无（边界用例，本就判别不出起点） |
| `fliterPreOnlySkipsZeroNotOtherChars` | `"12"` | `"12"` | `""`（len=2 全砍） | ✅ 有 |
| 同上 | `"10"` | `"10"` | `""` | ✅ 有 |
| 同上 | `"00a0b"` | `"a0b"` | `"a0b"`（砍"00"后同） | ❌ 无 |
| `fliterPreSkipsMultipleLeadingZeros` | `"00123"` | `"123"` | `"123"`（砍"00"后同） | ❌ 无 |
| 同上 | `"0003"` | `"3"` | `"03"`（砍"00"剩"03"→跳0→"3"？） | 见下 |

> 上表最后一行 `"0003"` 的 i=2 结果需精确算：i=2 起，s[2]='0'→i=3，s[3]='3'≠'0' 停 → `"3"`。与 i=0 的 `"3"` **相同** ⇒ 无判别力。（`"0003"` 前两个字符恰好都是 '0'，砍掉前 2 位不影响「跳前导 0」的最终结果。）

`SnsProtoTest.kt` 中涉及 fliterPre 的断言：

| 用例 | 输入 | i=0 结果 | i=2 结果 | 有判别力？ |
|---|---|---|---|---|
| `mediaIdIsReturnedVerbatim`（`assertTrue(fliterPre(raw)!=raw)` 反证） | `"0015038873180398883448"` | `fliterPre`=`"15038873180398883448"`≠原值 → 断言通过 | `"15038873180398883448"` 也≠原值 → 也通过 | ❌ 无（但此断言本意是「mediaId 原值不被 fliterPre 加工」，不是「验证 fliterPre 起点」，不算缺陷） |
| `endToEndCandidatesFromParsedId`（`fliterPre(mid)` 断言） | `"0015038873180398883448"` | `"15038873180398883448"` | `"15038873180398883448"` | ❌ 无 |
| `endToEndMultipleIdsMapToDistinctFiles` | 两个 `"0015038873180398883448/9"` | `snsb_15038873180398883448/9` | `snsb_15038873180398883448/9`（砍"00"后同） | ❌ 无 |

**总体判定**：判别力**足够**。

- **能抓住 `i=2` 回归的断言存在且足够**：`"15038644089917813396"`→原样（vs 砍 2 位）、`"12345"`→`"12345"`（vs `"345"`）、`"12"`→`"12"`（vs `""`）、`"10"`→`"10"`（vs `""`）、`"12a0b"`→`"12a0b"`（vs `"a0b"`）。这几条在 `i=2` 下**必然变红**，足以锁死起点。
- 多条「无判别力」的断言集中在「前两位恰是 `00`」的输入上——这类输入砍前 2 位与跳前导 0 结果相同，属巧合，不是测试弱化的**故意**行为，且被有判别力的用例兜住。可接受。

**`candidatesFallBackToMediaIdWhenFliterPreIsEmpty` 改输入 `"1500"`→`"000"` 的核对**：

- 修复前用 `"1500"`：`fliterPre("1500")` 在 i=2 语义下 = `""`（砍"15"后"00"→全 0→空）；在 i=0 语义下 = `"1500"`（首位 1 不跳）。所以修复后若仍用 `"1500"`，`fliterPre` 不再为空，断言「只剩 3 项」会**变红**。
- 改用 `"000"`：`fliterPre("000")` 在 i=0 语义下 = `""`（全 0）→ 只剩 3 个空键 `["snsb_","snsu_","snst_"]`。✅ 改动合理。
- **无漏改**：全仓 grep `fliterPre` 的所有断言均已核对（见 1.5），`SnsCacheMatchTest` / `SnsProtoTest` 里已无任何「i=2 语义」残留断言。

### 1.4 双候选结构是否被误改

**当前实现**（`MomentsLogic.kt:450-460`）：

```kotlin
fun snsNameCandidates(mediaId: String): List<String> {
    if (mediaId.isEmpty()) return emptyList()
    val pre = fliterPre(mediaId)
    val keys = LinkedHashSet<String>(2)
    keys.add(pre)
    if (pre.isNotEmpty()) keys.add(mediaId)
    val prefixes = arrayOf(SNS_PREFIX_BIG, SNS_PREFIX_MID, SNS_PREFIX_THUMB)
    val out = ArrayList<String>(prefixes.size * keys.size)
    for (p in prefixes) for (k in keys) out.add(p + k)
    return out
}
```

「`fliterPre(mediaId)` 优先 + `mediaId` 原值兜底」的双候选结构**原样保留**：
1. `keys.add(pre)` 先加 fliterPre 结果（优先）。✅
2. `if (pre.isNotEmpty()) keys.add(mediaId)` 非空时加原值兜底。✅（`pre` 为空时不加 mediaId，避免「空键 + 原值键」混入——但注意：pre 为空即 mediaId 全 0，此时 mediaId 本身也是全 0 串，加了也无意义且会被 matchSnsFileName 边界拒掉，故不加合理。）
3. 三级前缀 × keys 的展开顺序不变（snsb_ > snsu_ > snst_）。✅

**未被误改**。V1 报告里「双候选是刻意的、不该动」的结构完全保留。

### 1.5 残留旧语义

全仓 grep `fliterPre`（含大小写变体）后，发现以下**文档/脚本残留**（均不影响运行，但属注释级旧语义）：

1. `tools/_r15_bucket3.py:44`：`i = 2`（`fliter_pre` 复刻仍写 `i = 2`，且文件头注释「从下标 2 开始」）。—— 这是 R15 分桶实验的 scratch 脚本，已废弃（分桶方案已作废），但**脚本内 `fliter_pre` 仍是 i=2**，若有人重跑会得到旧结果。
2. `tools/_r15_bucket4.py:28-31`：`i = 2` 同上。
3. `docs/_r15_sns_cache_plan.md:270`：「去前 2 位 + 跳前导 0，`t0#s` 反汇编确认」—— §270 正文仍写旧语义（该文档「重大订正」节 §122-179 已订正，但 §270 这句旧话未同步删改）。
4. `docs/_r15_i1_impl.md:39`：「从下标 2 起跳过连续 `'0'`」—— R15 实现记录里的旧描述，历史文档未回改。
5. `docs/_r15_i1_impl.md:137`：表格里的 `fliterPre` 旧用例值（`"15038644089917813396"→"38644089917813396"` 等）。

**代码侧（`wxgroup/app/src/main/` 与 `wxgroup/app/src/test/`）零残留**：所有源码与测试已对齐 i=0 语义。上述 5 处全是文档/废弃脚本，不阻塞上机，但建议后续清理（P2 级）。

### 1.6 本节结论

**P0-1 修复到位，PASS。** 实现与 DEX 逐分支等价，KDoc 与实现一致，测试判别力足够（有 5 条能锁死 i=0 的断言），双候选结构未动。仅余 5 处文档/废弃脚本的旧语义残留（P2，不阻塞）。

---

## 2. P0-2 RecyclerView 定位

### 2.1 listKind 分派

**实现**（`ForceForward.kt:806-821`）：

```kotlin
while (node != null && guard++ < 12) {
    val n = node.javaClass.name
    if (chatList == null &&
        (n.contains("ChattingListView") || n.contains("ChattingRecyclerView"))
    ) chatList = node
    if (n.contains("MMChattingListView")) {
        outer = node
        break
    }
    node = node.parent as? View
}
val listKind = when {
    chatList == null -> "none"
    chatList.javaClass.name.contains("Recycler") -> "recycler"
    else -> "listview"
}
```

**⛔ 判定不可靠，且是本修复的致命缺陷（新 P0）**：

运行时列表对象**实际类名不含 `"ChattingListView"` / `"ChattingRecyclerView"` 子串**。反汇编 `MMChattingListView.l(Context)`（classes4.dex `code_off=0x5197cc`）坐实 `T` 字段（真正的列表视图）的两个分支赋值：

```
  [ 56] check-cast   v1   Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlRecyclerView;   ← recycler 形态
  [150] new-instance v1   Lcom/tencent/mm/ui/chatting/view/l;                             ← listview 形态
  [155] iput-object  v1, v10  Lcom/tencent/mm/ui/chatting/view/MMChattingListView;->T:Lcom/tencent/mm/pluginsdk/ui/tools/u3;
```

继承链（本复核逐类反查）：

```
ScrollControlRecyclerView  →  ChattingRecyclerView  →  RecyclerView
        （运行时对象类名是 "ScrollControlRecyclerView"，含 "Recycler"，但不含 "ChattingRecyclerView"）
view/l                      →  ScrollControlListView →  ChattingListView → ListView
        （运行时对象类名是 "l"（com.tencent.mm.ui.chatting.view.l），不含 "ChattingListView"）
```

**结论**：
- `chatList` 查找条件 `contains("ChattingListView") || contains("ChattingRecyclerView")` 对两种形态的**运行时对象都匹配不上**（recycler 对象是 `ScrollControlRecyclerView`，listview 对象是 `view/l`）⇒ `chatList` 恒 null。
- `chatList == null` ⇒ `listKind = "none"` ⇒ 后面的 recycler/listview 两个分派分支**全是死代码**，永不执行。
- 结果：`pos` 依旧恒 -1、`posVia="none"`、`posFail="none"`，`adapter` 靠 `outer`（MMChattingListView，这个倒是能匹配上）拿到后，因 `pos<0` 整块 `if (adapter != null && pos >= 0)` 跳过 ⇒ `h9/msgId/type/isSend/imgPath` 依旧全 none。
- **P0-2 原缺陷完全没修掉**（探针依旧静默失效），且「分派逻辑写死但永远进不去」本身就是新引入的错误。

> 附：任务问「反过来，有没有不含 Recycler 字样的 Recycler 子类被误判成 listview？」——**有，但方向相反、更糟**：listview 形态的运行时对象 `view/l` 不含任何关键词，`chatList` 直接漏判成 null，根本走不到 `listKind` 的 `contains("Recycler")` 判定那一步。即分派逻辑的三态（none/recycler/listview）里只有 `none` 是真能触发的。

### 2.2 itemRoot 上溯（12 层依据）

**实现**（`ForceForward.kt:831-838`）：

```kotlin
var itemRoot: View? = null
var n: View? = v
var g2 = 0
while (n != null && g2++ < 12) {
    if (n.parent === chatList) { itemRoot = n; break }
    n = n.parent as? View
}
```

**12 层是否够：无法从代码侧精确认定，但方向上够用**。证据：

- 消息 item 的层级（`docs/_r16_g2_msgsnap.md:183` 已记录）：`viewitems/a`（Kotlin 基类）→ 40+ 个 `ui/chatting` 子类（`viewitems/ao.g`、`bc.d`、`c6.d`、`fr.d`…）。被长按的最深 View 通常是气泡内的 TextView/ImageView，到 item 根 View 一般 2~5 层，12 层封顶是防御性上限，够。
- **但本判断在 2.1 的缺陷下无意义**：`chatList` 恒 null ⇒ `n.parent === chatList` 永远为 false ⇒ `itemRoot` 恒 null ⇒ `getChildAdapterPosition` 与 `A0` 扫描两个步骤**根本不执行**（都包在 `if (itemRoot != null)` 里）。
- 找错 itemRoot 的后果（假设 chatList 修对后）：若 parent 链上某中间层 `parent === chatList` 判断提前命中（例如找到了 RecyclerView 内部某个 wrapper 而不是真正的 item 根），传给 `getChildAdapterPosition` 会拿到 -1（该 wrapper 不是 RecyclerView 的直接 child）——但由于有 `A0` 退化扫描兜底，不会静默失败，只是多走一步。这是「可靠性」问题而非「正确性」问题，且在当前 chatList 缺陷下暂时无从验证。

### 2.3 getChildAdapterPosition 反射可行性

**⛔ 方法名不存在，反射必然失败（与 2.1 并列的第二处致命缺陷）**：

全 16 个 dex 的 `method_ids` 扫描 `getChildAdapterPosition`：**命中数 = 0**。微信这个版本的 `androidx.recyclerview.widget.RecyclerView` 已被混淆，`getChildAdapterPosition` 不存在于任何方法名表里。

真正实现该语义的是**混淆名 `F0` / `G0`**（本复核反查）：

```
Landroidx/recyclerview/widget/RecyclerView;  （定义于 classes12.dex）
  0x1  F0 (Landroid/view/View;)I   ← getChildAdapterPosition
  0x1  G0 (Landroid/view/View;)I   ← getChildLayoutPosition
```

`F0` 反汇编（classes12.dex `code_off=0x174abc`）：

```
  [ 0] invoke-static   RecyclerView;->I0(Landroid/view/View;)Lc3;   ; I0 = getChildViewHolderInt（取 View 的 ViewHolder）
  [ 3] move-result-object v1
  [ 4] if-eqz           v1 -> +7
  [ 6] invoke-virtual   c3;->i()I                                     ; c3.i() = getAdapterPosition
  [ 9] move-result      v1
  [10] goto             +2
  [11] const/4          v1  #-1
  [12] return           v1
```

（`c3.i()` 反汇编 = `iget c3->x:RecyclerView` → `RecyclerView.D0(c3)` → 返回 adapter position，即标准的 `getAdapterPosition` 语义。）

**关于 `getMethod` 沿继承链找父类 public 方法**：这个 Java 反射语义本身成立（`Class.getMethod` 会沿 superclass 链找 public 方法），`F0` 的 acc=0x1 是 public，所以**若方法名改成 `F0` 就能反射到**。但当前代码写的是 `getMethod("getChildAdapterPosition", View::class.java)`，名字不存在 ⇒ `runCatching` 捕获 `NoSuchMethodException` ⇒ `m=null` ⇒ 这一步恒跳过。

**结论**：反射**思路**（沿继承链找 public 定位方法）对，但**方法名字符串错**，必须改成 `F0`（或 `G0`）。

### 2.4 A0 退化路径（getCount / "d" 字段名证据）

**实现**（`ForceForward.kt:850-862`）：

```kotlin
if (itemRoot != null && pos < 0) {
    var scanHit = -1
    runCatching {
        val count = chatList.javaClass.getMethod("getCount").invoke(chatList) as Int
        val a0 = chatList.javaClass.getMethod("A0", Int::class.javaPrimitiveType)
        for (i in 0 until count) {
            val vh = a0.invoke(chatList, i) ?: continue
            val iv = XposedHelpers.getObjectField(vh, "d") as? View
            if (iv === itemRoot) { scanHit = i; break }
        }
    }
    if (scanHit >= 0) { pos = scanHit; posVia = "A0scan" }
}
```

**逐项核对（本复核独立反查 DEX）**：

1. **`getCount()` 存在且语义正确**：`ChattingRecyclerView` 的方法表里有 `getCount()I`（`_r12_dexclass.py` 实测，32 个 virtual 方法之一）。反汇编（classes4.dex `code_off=0x48a3f8`）：

```
  [ 0] invoke-virtual  RecyclerView->getAdapter()Lw1;
  [ 4] if-eqz           v0 -> +7
  [ 6] invoke-virtual  w1->getItemCount()I
  [11] const/4          v0  #0
  [12] return           v0
```

   ⇒ `getCount()` = `getAdapter().getItemCount()`，adapter 为 null 时返回 0。**不是恒 0 的桩**，是真实实现。✅（`getMethod("getCount")` 能沿继承链命中——它在 `ChattingRecyclerView` 自己声明，public。）

2. **`A0(I)` 存在且语义正确**：`RecyclerView` 的方法表里有 `A0(I)Landroidx/recyclerview/widget/c3;`（`_r12_dexclass.py` 实测，virtual 方法，acc=0x1 public）。反汇编（classes12.dex `code_off=0x17398c`）：

```
  [ 0] iget-boolean  RecyclerView->I:Z           ; 若 mAccessibilityManager 相关开关
  [ 6] iget-object   RecyclerView->h:Lj;         ; h = ChildHelper
  [ 8] invoke-virtual j->h()I                    ; 子 View 数
  [13] 循环：j->g(I) 取 child View → I0(View) 取 c3 → c3.q() 判断 → D0(c3) 比 position → 返回匹配的 c3
```

   ⇒ `A0(i)` = 「按 adapter position 找 ViewHolder」（等价 `findViewHolderForAdapterPosition`）。✅（`getMethod("A0", int)` 能命中——`A0` 在 `RecyclerView` 声明，public，`ChattingRecyclerView` 继承。）

3. **`"d"` 字段 = itemView 证据确凿**：`c3`（= `androidx.recyclerview.widget.RecyclerView$ViewHolder` 混淆名）的实例字段表：

```
  0x11  Landroid/view/View;   d     ← itemView
```

   `c3.<init>(View)` 反汇编（classes12.dex `code_off=0x17dc4c`）`[38] iput-object v4, v3, c3->d:View` + `[43] const-string 'itemView may not be null'` 抛异常 ⇒ **`d` 就是构造参数 View，即 itemView**。✅ `XposedHelpers.getObjectField(vh, "d")` 取 itemView 与 `itemRoot` 同一性比对，逻辑正确。

**结论**：A0 退化路径的**三个锚点全部坐实**（`getCount` 真实现、`A0` = findViewHolderForAdapterPosition、`d` = itemView）。这段代码本身是对的，**但被 2.1 的 `chatList` 缺陷 + 2.3 的 `itemRoot` 恒 null 双重短路，永远执行不到**——是「死代码里的正确代码」。

#### ⚠️ A0 退化路径的性能隐患（team-lead 提示、本复核确认）

`A0(I)` 形参是 **adapter position**，内部实现是「遍历所有已 attach 的 child View，逐个 `D0(c3)` 比对 adapter position」（见上 A0 反汇编 `[13]` 起的循环）。F4 的退化扫描：

```kotlin
for (i in 0 until count) {              // count = getAdapter().getItemCount()，可能几千
    val vh = a0.invoke(chatList, i) ?: continue   // 每次 A0(i) 内部又遍历 ~10 个 child
    val iv = XposedHelpers.getObjectField(vh, "d") as? View
    if (iv === itemRoot) { scanHit = i; break }
}
```

- 若 itemRoot 对应的 adapter position 是 N（长按的 item 在列表里滚到第 N 条），for 循环从 0 扫到 N 才命中，中间 `A0(0..N-1)` 对「不在可见区」的 i 都**遍历完所有 child 后返回 null**。
- 复杂度 ≈ N × O(可见 child 数)，且**每一步都是反射 invoke**（`getMethod` 之后 `a0.invoke` 在循环内反复调用），N 可能几百~几千 ⇒ **数万次反射，全跑在主线程长按回调里**，存在卡顿/ANR 风险。
- **缓解因素**：这是后备路径——`F0`（getChildAdapterPosition）修对后，正常走 `F0` 一次反射命中，`A0scan` 只在 `F0` 返回 -1 时才触发。而 `itemRoot` 已沿 parent 链找到「`parent === chatList`」，是 RecyclerView 直接 child，`F0` 大概率能命中 ⇒ A0scan 实际触发概率低。
- **定级**：P1（性能风险，仅后备路径）。建议若保留 A0scan，至少把「`itemRoot` 直接 `parent === chatList`」这一前提利用起来，改用 `RecyclerView.getChildCount()`（可见 child 数，`u3` 接口 / ViewGroup 继承有）+ 逐个比对 child 的 ViewHolder，而不是 `0 until getCount()` 全量扫。



> 附：任务特别问「`ChattingRecyclerView` 继承链上有没有 `getCount()`？」——**有**，且是 `ChattingRecyclerView` 自己声明的 public 方法（不是继承来的，也不是恒 0 桩），`getMethod("getCount")` 可靠。这一点通过。

#### 2.4bis · `ChattingRecyclerView` 的「同类空桩」规律（team-lead 提供、本复核复现一致）

这个类已确认有 **3 个伪实现/空桩**，必须作为「同类坑」规律记录（`_r14_walk.py dis classes4.dex` 复现）：

| 方法 | 签名 | 反汇编 | 语义 |
|---|---|---|---|
| `getPositionForView` | `(View)I` | `[0] const/4 v1 #-1; [1] return v1` | 恒 -1 空桩（P0-2 根源） |
| `p` | `(II)I` | `[0] return v2` | 恒返回某个固定 int 形参，无真实逻辑 |
| `v` | `(I)View` | `[0] invoke getFirstVisiblePosition(); [3] invoke A0(I)` | **忽略形参**：永远返回「第一个可见项」的 itemView |

三者的反汇编原文：

```
ChattingRecyclerView->getPositionForView(View)I   acc=0x0001
  [ 0] const/4  v1  #-1
  [ 1] return   v1

ChattingRecyclerView->p(II)I   acc=0x0001  code_off=0x48a574
  [ 0] return   v2

ChattingRecyclerView->v(I)Landroid/view/View;   acc=0x0001  code_off=0x48a2d4
  [ 0] invoke-virtual  getFirstVisiblePosition()I
  [ 3] invoke-virtual  RecyclerView->A0(I)Lc3;      ; 用 getFirstVisiblePosition() 的结果，非形参
  [ 6] move-result-object v1
  [ 7] if-eqz  v1 -> +5
  [ 9] iget-object  v1, v1  c3->d:View
  [12] const/4  v1  #0
  [13] return-object  v1
```

**对照正确实现**（同类的 `s` 才真正用了形参）：

```
ChattingRecyclerView->s(II)Landroid/view/View;   acc=0x0001  code_off=0x48a2ac
  [ 0] invoke-virtual  RecyclerView->A0(I)Lc3;      ; 用第 1 个形参
  [ 3] move-result-object v1
  [ 4] if-eqz  v1 -> +5
  [ 6] iget-object  v1, v1  c3->d:View
  [ 8] goto  +2
  [ 9] const/4  v1  #0
  [10] return-object  v1
```

**规律总结**：`ChattingRecyclerView` 为满足 `u3` 接口「凑」出来的若干 `(View)I` / `(I)View` / `(II)I` 方法，多数是**不真实实现的空壳**（恒 -1 / 恒返回形参 / 忽略形参）。真正的定位实现只有 `RecyclerView` 父类的 `F0`（=getChildAdapterPosition）、`G0`（=getChildLayoutPosition）、`A0`（=findViewHolderForAdapterPosition）。⇒ **F4 的 recycler 定位若想「沿用某个看起来像 ListView 的方法」基本都会踩空，唯一可靠的是 `F0`/`A0` 这条链**。

### 2.5 posFail 语义与 diagnose() 配套

**实现侧**（`ForceForward.kt:863`）：

```kotlin
if (pos < 0) posFail = "recyclerStub"
```

只在 recycler 分支末尾赋值。listview 分支失败时 `posFail` 保持初值 `"none"`。

**判读脚本侧**（`tools/_r16_msgsnap.py:194-237` `diagnose()`）顺序：

```python
if fields["list"] == "recycler" and (fields["posFail"] == "recyclerStub"
        or (fields["pos"] in ("-1", "<missing>")
            and fields["posVia"] in ("childAdapterPosition", "A0scan", "none"))):
    return [(False, "list=recycler 且 pos=-1 ... 属已知预期 ...")]
if fields["pos"] in ("-1", "<missing>"):
    return [(False, "list=%s 找到了但 pos=-1 ... getPositionForView 没命中 ...")]
```

**配套判定**：
- listview 真失败：`list=listview` + `pos=-1` + `posVia=getPositionForView` ⇒ 不命中 recycler 判据，落到通用 `pos=-1` 判据 ⇒ 正确报「listview 定位失败」。✅
- recycler 预期失败：`list=recycler` + `posFail=recyclerStub`（或 `pos=-1` 且 `posVia` 已试过 recycler 路径）⇒ 命中 recycler 专用判据 ⇒ 报「已知预期」。✅
- 判读脚本能区分这两类，`posFail` 的赋值时机（仅 recycler 分支末尾）与 `posVia` 取值（`getPositionForView` vs `childAdapterPosition`/`A0scan`/`none`）配合**正确**。

**但这一切被 2.1 架空**：`listKind` 恒 `"none"`，所以真机上 `list=none` 时 `diagnose()` 先命中 `list == "none"` 判据（`_r16_msgsnap.py:208-210`）报「向上 12 层没找到列表」，**仍会误导成「可能不在聊天页」**——即：`posFail`/`posVia` 的区分机制在 chatList 缺陷存在时完全用不上，判读脚本照旧把「列表没找到」误报成环境问题而非代码缺陷。

### 2.6 listview 形态回归风险

**listview 分支**（`ForceForward.kt:864-873`）：

```kotlin
} else {
    val m = runCatching {
        chatList.javaClass.getMethod("getPositionForView", View::class.java)
    }.getOrNull()
    if (m != null) {
        pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
        posVia = "getPositionForView"
    }
}
```

**对比原逻辑**（`git diff` 确认，原代码是「统一走 `getPositionForView`」）：

原实现（修复前）对 `chatList` 用同一 `getMethod("getPositionForView", View)` 反射；新实现 listview 分支**逻辑等价**（同一方法名、同一参数、同一 invoke 方式），只是外面套了一层 `if (listKind == "recycler") ... else ...` 分派。

**回归风险点**：
1. **listview 分支本身没改坏**——反射目标、参数、invoke 语义与原来一致。✅
2. **但 `chatList` 查找条件变了**（原代码怎么找 chatList 已无从对比，`git diff` 显示整个 `msgsnapReport()` 是新增方法，无「旧版 chatList 查找」可对比）。当前查找条件 `contains("ChattingListView")` 对 listview 形态的运行时对象 `view/l` 同样匹配不上 ⇒ **listview 形态的 pos 也恒 -1**。即：**listview 形态被 2.1 的缺陷一并搞坏**，不是「recycler 修好了、listview 保持原样」。
3. 退一步说，即使 chatList 修对，listview 分支走 `chatList.javaClass.getMethod("getPositionForView")`——`view/l` 继承 `ChattingListView` → `ListView`，`ListView.getPositionForView` 是 public 真实实现，`getMethod` 沿链命中 ⇒ 能拿到真 position。这条路径本身是通的（V1 报告 :80 已坐实 `ChattingListView` 无声明、靠继承 `ListView` 命中）。

**结论**：listview 分支代码等价原逻辑、无自身回归；但受 2.1 的 chatList 查找条件影响，listview 形态目前也拿不到 pos。修复 chatList 查找条件后，两形态都能恢复正常。

### 2.7 本节结论

**P0-2 未修复，且引入新 P0，FAIL。**

- 2.1 的 `chatList` 查找条件对两种形态的运行时对象都匹配不上（`ScrollControlRecyclerView` / `view.l`），`chatList` 恒 null，`listKind` 恒 `"none"`，分派逻辑是死代码。
- 2.3 的反射方法名 `getChildAdapterPosition` 全 dex 不存在（混淆名是 `F0`/`G0`），反射必然失败。
- 二者叠加 ⇒ 探针 pos 依旧恒 -1、h9/msgId/type/isSend/imgPath 依旧全 none，P0-2 原缺陷**完整保留**。
- `A0` 退化路径三锚点（getCount/A0/d）经 DEX 反查全部正确，但被短路成死代码。
- `posFail`/`posVia`/`diagnose()` 的配套逻辑本身正确，但被 `list=none` 架空。

---

## 3. 新引入的问题（如有，按 P0/P1/P2 分级）

### P0-1 · chatList 查找条件匹配不上运行时对象（F4 引入，阻断）

`msgsnapReport()` 用 `contains("ChattingListView") || contains("ChattingRecyclerView")` 找列表，但 DEX 坐实运行时列表对象是 `ScrollControlRecyclerView`（recycler）与 `com.tencent.mm.ui.chatting.view.l`（listview），两者都不含关键词 ⇒ `chatList` 恒 null ⇒ `listKind` 恒 `"none"` ⇒ 整个分派是死代码，pos 依旧恒 -1。证据见 §2.1。

### P0-2 · 反射方法名 `getChildAdapterPosition` 不存在（F4 引入，阻断）

全 16 dex `method_ids` 无此名（混淆名 `F0`/`G0`），`getMethod` 必然 NoSuchMethodException ⇒ 被 `runCatching` 吞成 `m=null`。证据见 §2.3。

> 上述两个 P0 与「P0-2 原缺陷未修复」是同一根因链，一并计入 FAIL，不重复单列。

### P1 · （无）

`posFail`/`posVia`/`diagnose()` 配套逻辑、A0 退化路径三锚点、listview 分支反射等价性——经核对均正确，未发现新的 P1。

> 补充：A0scan 退化路径的**性能隐患**（N×反射全量扫、主线程长按回调内）列为 P1 级风险，见 §2.4 末尾「A0 退化路径的性能隐患」。这是 F4 新引入的，但仅在后备路径触发，故不单独计为阻断，归入 P1。

### P2-1 · 文档/废弃脚本仍残留 `fliterPre` 旧语义（F3 未清干净）

`tools/_r15_bucket3.py:44`、`tools/_r15_bucket4.py:28-31`（`i = 2`）、`docs/_r15_sns_cache_plan.md:270`、`docs/_r15_i1_impl.md:39,137`。不阻塞上机，但属「改了代码没改完注释」的尾巴，见 §1.5。

### P2-2 · `msgsnapReport()` 的 KDoc 链路图仍写旧语义

`ForceForward.kt:761-762` KDoc 链路图写的是 `getListView().getPositionForView(v) ⇒ position`，与下方「按 listKind 分派」的实际实现不符（KDoc 只更新了「三处坑」列表，链路图未同步改）。见 `ForceForward.kt:755-765`。

## 4. 未验证项（明确列出「我查不到 / 需要真机才能确认」的条目）

1. **聊天页实际走哪种形态**：AB 开关 `adapter/x;->c1` 默认 4（`dh4/e0;->e(...)I`），真机可能 listview 或 recycler。静态只能确认「两种都可能」，无法确认当前设备实际值——**需真机**。
2. **item 根 View 的真实层级深度**：12 层封顶是否够，只能从 item 层级（2~5 层）侧证「方向上够」，无法精确断定——**需真机**。
3. **`getChildAdapterPosition` 改名 `F0` 后，`F0` 与 `G0` 的返回值语义差异**（adapter position vs layout position）在「item 未被移动」时等价，但静态无法确认运行时是否有 item 动画/移动——**需真机**。
4. **`attached` 判据**：`isAttachedToWindow` 是否真能区分「陈旧 View 已摘窗」——代码逻辑对，但真机行为**需真机**。
5. **`field_imgPath` 对图片是否恒非空**：`docs/_r16_g2_msgsnap.md:300` 已标「待真机 dump 确认」。
6. **语音/视频/普通文件的本地路径在哪个字段**：`docs/_r16_g2_msgsnap.md:302-306` 明确「没有坐实」，本复核同样查不到——**需真机**。

## 5. 是否能上真机（PASS 与否）

**FAIL，不能上真机。**

- P0-1（fliterPre）已真修好，可取图上机。
- P0-2（RecyclerView 定位）**未修好**：`chatList` 查找条件 + `getChildAdapterPosition` 方法名两个错误叠加，探针在两种形态下都拿不到 pos，h9/msgId/type/isSend/imgPath 依旧全 none，且 `diagnose()` 会误报「不在聊天页」而非「列表定位失效」。

**必须再修（按优先级）**：
1. **改 `chatList` 查找**：识别 `ScrollControlRecyclerView` / `ScrollControlListView` / `view.l`。最稳的做法是：沿 parent 链找到 `MMChattingListView` 后，反射它的 `getListView()`（未混淆名，返回 `u3`，即 `T` 字段对象）拿真正的列表 View，再用 `instanceof`/类名判断形态（`contains("Recycler")` 此时才可靠——`ScrollControlRecyclerView` 含 "Recycler"、`view.l` 不含）。
2. **改反射方法名**：`getChildAdapterPosition` → `F0`（`(Landroid/view/View;)I`，语义 = getChildAdapterPosition）；`getCount`、`A0` 已坐实名字正确，不用改。
3. 修完后 `listKind` 的 `contains("Recycler")` 判定才真正生效（`ScrollControlRecyclerView` 命中 recycler、`view.l` 落 listview）。

修复后可预期：listview 形态走 `getPositionForView`（继承 `ListView` 真实实现）拿真 pos；recycler 形态走 `F0`/`A0` 拿真 pos，探针恢复取证能力。

---

## 6. R16-V2 第二轮复核（增量）：两个 P0 修复到位性验证

> ⚠️ **本节取代 §0/§5 的 FAIL 结论**：§0/§5 判的是 22:0x 旧快照（`contains("ChattingListView")` 查找 + 单方法名 `getChildAdapterPosition`），那两处已在并行 lane 修复。本节只审「修复本身是否到位 + 有无引入新问题」。§0-§5 保留作第一轮历史记录，不改动。

### 6.0 结论摘要

**PASS**（两个 P0 都真修好了，无新 P0/P1，仅余 1 个 P2 可观测性缺口）

- **P0-1（chatList 查找条件错）→ 已修复**：`listKindOf` 按继承链判定，逐层 `c.superclass` 不跳层、不提前 break，三个运行时类（含隔两层的 `ui.chatting.view.l`）全部命中。✅
- **P0-2（反射方法名不存在）→ 已修复**：候选链 `[F0, G0, getChildAdapterPosition, getChildLayoutPosition]` 把混淆名 `F0`/`G0` 放最前，当前版本直接命中。✅
- **`getListView()` 兜底**：T 字段（`u3` 接口）的实现类只有 3 个且全是 `View` 子类，`as? View` 不会误判；兜底返回的就是列表本身，不会误报。✅
- **唯一缺口（P2，不阻塞）**：候选链降级时 `posVia` 能区分「F0 胜出 / G0 胜出」，但区分不了「F0 返回 -1」与「F0 抛异常」——不影响 diagnose 判读，仅属可观测性小缺口。

> 判据：两个 P0 都真修好 + 无新 P0/P1 ⇒ **PASS**。P2 单独列出，不阻塞上机。

### 6.1 listKindOf 继承链判定是否覆盖三个运行时类（问题 1）

**实现原文**（`ForceForward.kt:1030-1047`）：

```kotlin
private fun listKindOf(view: View?): String {
    if (view == null) return "notList"
    var c: Class<*>? = view.javaClass
    while (c != null) {
        when (c.name) {
            "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView" -> return "recycler"
            "com.tencent.mm.pluginsdk.ui.tools.ChattingListView" -> return "listview"
        }
        c = c.superclass
    }
    // 继承链上没有那两个类 ⇒ 区分「完全不是列表」与「是列表但类名不认识（unknown）」
    val nm = view.javaClass.name
    return when {
        nm.contains("Recycler") -> "unknown"
        nm.contains("ListView") || nm.contains("AbsListView") -> "unknown"
        else -> "notList"
    }
}
```

**DEX super 链复核**（本复核独立扫全部 16 dex 的 `class_defs` 的 `superclass_idx`，逐级爬链）：

| 运行时类 | super 链（逐级） | 循环第几轮命中 |
|---|---|---|
| `ScrollControlRecyclerView` | → `ChattingRecyclerView` → `RecyclerView` → `ViewGroup` | 第 2 轮（`ChattingRecyclerView`）→ `recycler` ✅ |
| `ScrollControlListView` | → `ChattingListView` → `ListView` | 第 2 轮（`ChattingListView`）→ `listview` ✅ |
| `ui.chatting.view.l` | → `ScrollControlListView` → `ChattingListView` → `ListView` | 第 3 轮（隔 2 层）→ `listview` ✅ |

爬链原始输出（本复核脚本打印，descriptor 精确等于 Kotlin `c.name` 的字符串）：

```
Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlRecyclerView;  →  Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;  →  Landroidx/recyclerview/widget/RecyclerView;
Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView;      →  Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView;      →  Landroid/widget/ListView;
Lcom/tencent/mm/ui/chatting/view/l;                            →  Lcom/tencent/mm/pluginsdk/ui/tools/ScrollControlListView; →  Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView; →  Landroid/widget/ListView;
```

**逐条回答 team-lead 的三个核对点**：

1. **`ScrollControlRecyclerView` 命中**：super 直接是 `ChattingRecyclerView`（classes4.dex），`while` 第 2 轮 `c.name == "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView"` 精确命中，`return "recycler"`。✅
2. **`ScrollControlListView` 命中**：super 直接是 `ChattingListView`，第 2 轮命中 `return "listview"`。✅
3. **`ui.chatting.view.l` 隔两层命中**：`l → ScrollControlListView → ChattingListView`。`while` 逐轮 `c = c.superclass`，**没有跳层、没有提前 break**——第 1 轮 `l` 不匹配，`c = ScrollControlListView`；第 2 轮不匹配，`c = ChattingListView`；第 3 轮命中。✅

**结论**：`listKindOf` 循环无 bug，三个运行时类全部覆盖。`c.superclass` 是 JVM/ART 的类链，跨 dex 正常（`ChattingRecyclerView`/`ChattingListView` 定义于 classes4.dex，`RecyclerView` 定义于 classes12.dex，运行时 ART 已加载完整链，`Class.superclass` 可正常上溯到 android 框架类，不存在「隔 dex 爬不到」的问题）。

> 附注：`MMChattingListView` 本身 super 是 `MMPullDownView → FrameLayout`（非列表，本复核确认），所以 `listKindOf(outer)` 会返回 `notList`——但代码**没有**对 `outer` 调 `listKindOf`，只对它调 `getListView()`/`getBaseAdapter()`，故无影响。

### 6.2 unknown 降级方向是否误判（问题 2）

**分派实现原文**（`ForceForward.kt:865-870`）：

```kotlin
val effKind = when {
    listKind == "recycler" -> "recycler"
    listKind == "listview" -> "listview"
    listKind == "unknown" && chatList.javaClass.name.contains("Recycler") -> "recycler"
    else -> "listview"
}
```

**team-lead 的担心**：将来若出现「含 `Recycler` 但实际上不是 RecyclerView 形态」的容器，会不会走进 recycler 分支后拿 `F0` 得到**假 position**（而非干净的 -1）？错误值 vs 无值哪个更危险？

**静态判定**：

1. **`unknown` 只在 `listKindOf` 的两条继承链都未命中时出现**（`ForceForward.kt:1041-1045`），且必须类名含 `Recycler` 才落 recycler 分支。`listKindOf` 已经把「真 RecyclerView 形态」（`ChattingRecyclerView` 子类）和「真 ListView 形态」（`ChattingListView` 子类）都精确分流了，`unknown` 只能是**微信换了新实现类**的场景。
2. **假 pos 的风险点**：若 `unknown`+含 `Recycler` 的容器**不是** `RecyclerView` 子类，`chatList.javaClass.getMethod("F0", View)` 要么 `NoSuchMethodException`（`posFail="noMethod"`），要么 `F0` 是某个同名但语义不同的方法。当前微信这个版本，`F0` 这个单字母方法名被 `RecyclerView` 独占，别的类**不会**碰巧也叫 `F0`（本复核全 dex `method_ids` 反查：`F0`/`G0` 这类单字符名只有 RecyclerView 声明，见 §2.3）。**「同名不同义」概率极低**。
3. **假 pos 的可检测性**：即便真拿到假 pos，`posVia` 会记录走了哪个候选（`F0`/`G0`/`A0scan`），`listCls` 会打出真实类名。`diagnose()` 对 `list=unknown` 有专门分支（`_r16_msgsnap.py:218-222`），**只要 `list=unknown` 就先报「类名不认识，请加进 when 分支」**，根本不会走到「信 pos」那一步。

   关键证据——`diagnose()` 的 `list=unknown` 分支**在 pos 判读之前**（`_r16_msgsnap.py:218` 先于 `:255` 的 pos 判读）：

   ```python
   if fields["list"] == "unknown":
       return [(False, "list=unknown ⇒ 找到了列表对象，但它的继承链上既没有 "
                       "`ChattingRecyclerView` 也没有 `ChattingListView`（微信又换了实现类）。"
                       "**实际类名 = %s**（listCls 字段）⇒ 把类名加进 `ForceForward.listKindOf` "
                       "的 when 分支即可。" % fields["listCls"])]
   ```

   即：**只要 `listKind` 还是 `unknown`，`listCls` 一定非 none，`diagnose()` 一定报「类名不认识」并阻止采信 pos**。假 pos 会被这个早退拦截，不会静默污染对账结果。

**错误值 vs 无值的定性**：在**当前实现下**，「假 pos」这个分支被 `diagnose()` 的 `list=unknown` 早退兜住，实际暴露面是「无值 + 明确报 unknown」而非「错值」。降级方向本身安全。

**结论**：`unknown` 降级方向不会造成「静默假 pos 误判」。worst case 是 `list=unknown` + `posVia=F0` 且真拿到一个值，但此时 `diagnose()` 先报「类名不认识」、把类名反馈给修复方，`pos` 不会被当成可信结果。**判 PASS，无需改动**。

> 唯一可加分的点（P2 级，非必需）：`list=unknown` 走 recycler 分支拿到 pos 时，可让 `posVia` 带上 `unknown/` 前缀以显式标记「猜着拿到的」，更醒目。但现状已有 `list=unknown` 早退兜底，不构成风险。

### 6.3 getListView() 兜底失败模式（问题 3）

**实现原文**（`ForceForward.kt:840-847`）：

```kotlin
// 兜底路径：parent 链没筛到时，用 MMChattingListView.getListView()（`iget-object T:u3; return`）
// 直接拿列表对象 —— 这比靠类名在 parent 链里猜更硬（T 字段在 l(Context) 里赋值，
// 就是上面那三个子类之一）。
if (chatList == null && outer != null) {
    chatList = runCatching {
        outer.javaClass.getMethod("getListView").invoke(outer) as? View
    }.getOrNull()
}
```

**`getListView()` 的 DEX 原文**（本复核独立反汇编 `MMChattingListView.getListView`，classes4.dex `code_off=0x5194ac`）：

```
Lcom/tencent/mm/ui/chatting/view/MMChattingListView;->getListView()Lcom/tencent/mm/pluginsdk/ui/tools/u3;
  [ 0] iget-object   v0, v1   Lcom/tencent/mm/ui/chatting/view/MMChattingListView;->T:Lcom/tencent/mm/pluginsdk/ui/tools/u3;
  [ 2] return-object v0
```

**T 字段类型 `u3` 是纯接口**（本复核确认：`u3` 定义于 classes4.dex，`access=0x601`（interface + abstract + public），`super=Object`，无父接口）。

**`u3` 接口的实现类全量反查**（本复核扫全部 dex 的 `interfaces` 列表）：

```
谁 implements Lcom/tencent/mm/pluginsdk/ui/tools/u3;
   StoryListView            super= android/widget/ListView
   ChattingListView         super= android/widget/ListView
   ChattingRecyclerView     super= androidx/recyclerview/widget/RecyclerView
```

只有 **3 个**实现类，且**全部是 `View` 的（间接）子类**：
- `ChattingListView` → `ListView` → `ViewGroup` → `View` ✅
- `ChattingRecyclerView` → `RecyclerView` → `ViewGroup` → `View` ✅
- `StoryListView` → `ListView` → `View` ✅

**team-lead 的两个核对点逐条回答**：

1. **`as? View` 转型何时为 null**：`T` 字段静态类型是 `u3`，运行时值一定是上述 3 个类之一（它们的父类 `ChattingListView`/`ChattingRecyclerView` 各自实现 `u3`，`StoryListView` 也是）。这 3 个类最终都继承 `android.view.View`，所以 `as? View` 在**运行时永远成功**。`as? View` 返回 null 的唯一可能：
   - `getMethod("getListView")` 抛异常（`outer` 不是 `MMChattingListView`，但此处 `outer` 是 parent 链里 `contains("MMChattingListView")` 命中的节点，且该 contains 已实测安全）；
   - `invoke` 抛异常（被 `InvocationTargetException` 包住，`getOrNull()` 吞掉返回 null）；
   - `T` 字段为 null（`l(Context)` 未赋值时）——此时 `iget-object` 返回 null，`as? View` 得 null，`chatList` 保持 null，落 `listKind="none"`，`diagnose()` 报「没找到列表」，**语义正确**（确实没拿到列表）。
   
   综上：`as? View` 转型本身不会因「接口对象不是 View」而失败。

2. **返回对象不是 chatList 但继承链命中，会不会 pos 恒 -1 却 listKind 非 none ⇒ 误报**：`getListView()` 返回的就是 `T` 字段，而 `T` 字段在 `MMChattingListView.l(Context)` 里被赋值为「聊天列表」本身（recycler 时 `ScrollControlRecyclerView`，listview 时 `view.l`，本复核 §2.1 已坐实 `l(Context)` 的两分支赋值）。它**不是**外层容器 `MMChattingListView`（`MMPullDownView→FrameLayout`），而是列表子对象。所以：
   - `getListView()` 返回的对象，其 `listKindOf` 会命中 `recycler` 或 `listview`（绝不命中 `unknown`/`notList`）；
   - `getListView()` 返回的对象，`getMethod("F0")` 或 `getMethod("getPositionForView")` 都能命中（RecyclerView / ListView 系）；
   - 不存在「返回外层容器导致 pos 恒 -1 却 listKind 非 none」的路径。`MMChattingListView` 自己 super 是 `MMPullDownView`，`listKindOf(MMChattingListView)` 会返回 `notList`，但代码**没对 outer 调 listKindOf**。

**唯一理论边界**（不构成实际风险）：若 `T` 字段被赋成 `StoryListView`（bizui 小组件列表，super=ListView），`listKindOf` 命中 `listview`，`getPositionForView` 走 `ListView` 继承实现，**依然能拿到 pos**（语义仍正确，只是那是 StoryListView 而非聊天列表——但 `T` 字段在 `l(Context)` 只赋 `ScrollControlRecyclerView` / `view.l` 二选一，`StoryListView` 不会出现在这里）。

**结论**：`getListView()` 兜底失败模式安全。`as? View` 不会因接口转型失败；返回对象就是列表本身，不会导致「pos 恒 -1 却 listKind 非 none」的误报。**判 PASS，无需改动**。

### 6.4 候选链 [F0, G0, ...] 顺序风险（问题 4）

**实现原文**（`ForceForward.kt:887-905`）：

```kotlin
val candidates = arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
var allMissing = true
var sawClassCast = false
for (name in candidates) {
    val m = runCatching {
        chatList.javaClass.getMethod(name, View::class.java)
    }.getOrNull()
    if (m == null) continue
    allMissing = false
    val r = runCatching { m.invoke(chatList, itemRoot) as Int }
    val p = r.getOrNull() ?: -1
    if (p >= 0) { pos = p; posVia = name; break }
    // 展开 cause 链找 ClassCastException（invoke 会包进 InvocationTargetException）
    var cause: Throwable? = r.exceptionOrNull()
    while (cause != null) {
        if (cause is ClassCastException) { sawClassCast = true; break }
        cause = cause.cause
    }
}
```

**`F0` / `G0` 语义复核**（本复核独立反汇编 classes12.dex）：

```
RecyclerView->F0(View)I   acc=0x0001   code_off=0x174abc
  [ 0] invoke-static  RecyclerView->I0(View)Lc3;     ; I0 = getChildViewHolderInt
  [ 4] if-eqz v1 -> +7
  [ 6] invoke-virtual c3->i()I                        ; c3.i() = getAdapterPosition
  [ 11] const/4 v1 #-1
  [ 12] return v1

RecyclerView->G0(View)I   acc=0x0001   code_off=0x174ae8
  [ 0] invoke-static  RecyclerView->I0(View)Lc3;
  [ 6] invoke-virtual c3->j()I                        ; c3.j() = getLayoutPosition
  [ 11] const/4 v1 #-1
  [ 12] return v1
```

**语义差异确认**：`F0` = `I0(v) → c3.i()` = **getAdapterPosition**（adapter 数据位）；`G0` = `I0(v) → c3.j()` = **getLayoutPosition**（layout 视觉位）。两者都先经 `I0` 取 ViewHolder，ViewHolder 为 null 时都返回 -1。

**team-lead 的核对点：先 F0 后 G0 是否会对「item 正在拖动/动画中」给出不一致结果？**

1. **拖动/动画中的差异**：`RecyclerView` 在 item 动画（如删除/插入/重排）或拖拽排序期间，`getAdapterPosition` 与 `getLayoutPosition` 会短暂不同——`getLayoutPosition` 反映当前视觉位置（动画起点），`getAdapterPosition` 反映动画结束后稳定的数据位置。若此时用 `G0` 拿到的是「动画中的临时 layout pos」。
2. **但当前顺序是先 F0 后 G0**，且 `F0` 返回 `>=0` 就 `break`（`:898`）。`F0`（adapter position）正是**更稳定、更适合「getItem(pos) 取消息」的语义**——`adapter.getItem(pos)` 用的是 adapter 数据下标，必须对 `F0` 的结果。所以**先 F0 后 G0 的顺序是正确的**：优先拿 adapter position，拿不到才退回 layout position。
3. **不会「不一致」的判据**：只有 `F0` 返回 -1（ViewHolder 已回收/null）才会轮到 `G0`；此时 `G0` 同样走 `I0` 取 ViewHolder，若 ViewHolder 还在、只是 `c3.i()` 因为某种原因返回 -1 而 `c3.j()` 返回 >=0 的场景在静态上无法构造（两者差异只在「动画进行中 adapter pos 尚未更新」时，adapter pos 是**最终值**不会比 layout pos 更早返回 -1）。实际可能发生的是：**长按取证时列表静止，F0 直接命中，G0 根本不触发**。

**posFail 能否区分「F0 抛异常后 G0 成功」这种降级？**

- **能区分「F0 胜出 vs G0 胜出」**：`posVia = name`（`:898`）会精确记录胜出者，日志里 `posVia=F0` / `posVia=G0` / `posVia=A0scan` / `posVia=getPositionForView` 一眼可辨。
- **不能区分「F0 返回 -1」与「F0 抛异常」**：两者都表现为「不 break、继续下一候选」，`posVia` 最终只写胜出者。但这是**可观测性缺口，不是正确性缺口**——`F0` 返回 -1（ViewHolder 已回收）与 `F0` 抛异常（参数类型错）最终都会落入同一个「继续试下一候选」的路径，且最终 pos 值不受影响。`sawClassCast` 只在「所有候选都没拿到 pos」时才用于定 `posFail="classCast"`，用于 diagnose 区分「参数错」与「方法不存在」。

**结论**：先 F0 后 G0 的顺序**正确且必要**（F0=adapter position 才是 `getItem` 需要的下标），不会对拖动/动画场景给出错误结果（反而 F0 是更稳定的那个）。「F0 抛异常后 G0 成功」的降级**能被 `posVia` 区分出最终走了哪个**，只是区分不了「F0 是返回 -1 还是抛异常」——此为 P2 级可观测性缺口，不阻塞。

> 建议（P2，非必需）：若想彻底区分，可在 `r.exceptionOrNull() != null` 时记录一个 `sawThrow` 或让 `posFail` 在候选链阶段就带出「F0 返回 -1 vs F0 抛异常」的中间态。现状 diagnose 用 `posVia` + `posFail` 已足够定位问题，无需改。

### 6.5 链尾原名有无保留价值（问题 5）

**候选链原文**（`ForceForward.kt:887`）：

```kotlin
val candidates = arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
```

**全 dex 零命中事实**（本复核 §2.3 已坐实，第二轮复验一致）：当前微信这个版本的 16 个 dex 里，`getChildAdapterPosition` / `getChildLayoutPosition` 两个**未混淆方法名在 `method_ids` 中命中数为 0**。它们被 R8 改成了 `F0` / `G0`。

**保留价值的判断**：

1. **当前版本：纯噪音**。两个原名在 `getMethod` 时必然 `NoSuchMethodException`，被 `runCatching` 吞成 `m=null` 后 `continue`，多走两次失败的反射查找（微秒级，无副作用）。
2. **未来版本防御价值：有，但极低**。微信若在某个未来版本**关闭 R8 混淆**（或对 `RecyclerView` 单独 `keep` 了 `getChildAdapterPosition`），这两个原名就会命中。但：
   - 微信混淆与否**不是按方法粒度**，而是整体配置。若未来「未混淆」，那 `ChattingRecyclerView`/`ChattingListView`/`MMChattingListView` 等**所有**现在靠混淆名反射的方法（`F0`/`G0`/`m`/`A0`/`I0`）**都会同时恢复原名**，届时需要改的是**整条链**（`F0`→`getChildAdapterPosition`、`m`→`getAdapter`、`A0`→`findViewHolderForAdapterPosition` 等），而不是靠链尾两个原名「自动兜底」。
   - 链尾两个原名**只兜住了 RecyclerView 定位这一环**，adapter 取用（`m()`/`getBaseAdapter()`）仍在链外，未来未混淆时照样失效。所以这两个原名「部分兜底」的意义有限。

**明确建议：保留，但降级语义要清楚**。理由：
- 去掉它们的收益极小（就省两次 `NoSuchMethodException` 抛收，性能可忽略）；
- 保留它们作为「未来未混淆版本的**显式哨兵**」有轻微价值——当 `posVia=getChildAdapterPosition` 出现在日志里，就说明微信切到了未混淆版本，可提醒维护者「整条反射链都要改原名」。

但**不建议**「只在 noMethod 时打日志」这种额外分支——现状 `posFail="noMethod"`（四个候选全没拿到方法）已经在 `diagnose()` 里打了「候选方法名全不存在 ⇒ 代码 bug」的明确提示（`_r16_msgsnap.py:226-229`），足以覆盖「未来未混淆后 F0 失效」的场景，无需为链尾原名单独加日志。

**结论**：链尾两个原名**保留，不改**。属「低成本的未来防御哨兵」，不是正确性问题。若追求极致简洁可删，但删了无实质收益，保留无实质成本。**判 PASS，无需改动**。

### 6.6 静态审查：编译期/运行期风险（问题 6）

**审查范围**（`ForceForward.kt:804-1047` 全新增段，含 `msgsnapReport()` 与 `listKindOf()`）：

1. **空指针**：整段无 `!!`（本复核 grep `!!` 全文零命中）。所有可能为 null 的路径都走 `runCatching { ... }.getOrNull()` + `if (x == null) continue` / `?: -1` / `?: "none"`。`chatList` / `outer` / `v` / `itemRoot` 均在解引用前判空：
   - `chatList.javaClass` 只在 `chatList != null` 后出现（`:862` `if (chatList != null && v != null)`）；
   - `outer.javaClass` 只在 `outer != null` 后出现（`:843`、`:963`）；
   - `itemRoot` 只在 `itemRoot != null` 后 invoke（`:880`）。
   无 NPE 风险。✅

2. **`as? View` 链式转换**：`node.parent as? View`（`:838`）、`n.parent as? View`（`:878`）、`outer.getMethod("getListView").invoke(outer) as? View`（`:845`）三处均用 `as?` 安全转型，父节点非 `View`（理论上 parent 是 `ViewParent`）时返回 null，循环条件 `node != null` 自然退出。无 `ClassCastException` 裸抛。✅

3. **无限循环 / parent 链 guard 计数**：
   - 第一层 parent 链：`while (node != null && guard++ < 12)`（`:821`），`guard` 从 0 起，最多走 12 层。**在 `getListView` 兜底路径（`:843`）之前就已 `break` 或耗尽**——`outer` 在 `n.contains("MMChattingListView")` 时 `break`（`:836`），兜底 `if (chatList == null && outer != null)` 是循环**结束之后**的独立判断，不依赖循环内的 guard。guard 计数**不会**在兜底路径上失效（兜底根本不进循环）。
   - 第二层 itemRoot 上溯：`while (n != null && g2++ < 12)`（`:876`），独立 `g2` 从 0 起，最多 12 层。两层循环都用独立计数器 + 判空，无死循环。✅
   - `listKindOf` 的 `while (c != null)`（`:1033`）：`c = c.superclass` 每次上溯一层，`Object.superclass == null` 时退出，**必然终止**（类继承链有限，无环）。✅

4. **A0 扫描窗口边界**：`from = (first - 50).coerceAtLeast(0)`（`:931`）、`to = minOf(first + 50, count - 1)`（`:932`）、退化 `from=0; to=minOf(100, count-1)`（`:934-935`）。当 `count == 0` 时 `to = -1`，`for (i in from..to)` 若 `from > to`（如 `from=0, to=-1`）Kotlin 的 `0..-1` 是**空区间**（`IntRange` 空时 `isEmpty()` 为 true，`for` 不执行）——不会数组越界。`first >= 0` 分支里 `to = minOf(first+50, count-1)`，若 `count-1 < first-50`（列表很短且 first 很大，理论上不会）`from > to` 同样空循环安全。✅
   - `a0.invoke(chatList, i) ?: break`（`:939`）：A0 返回 null（I 字段早退）时 `?: break` 立即退出，不继续空转。✅

5. **`getCount` / `getFirstVisiblePosition` / `A0` 的 access 复核**（本复核独立查 class_data 的 access_flags）：
   - `RecyclerView.A0(I)Lc3;` `acc=0x1`（PUBLIC）✅
   - `ChattingRecyclerView.getCount()I` `acc=0x1`（PUBLIC）✅
   - `ChattingRecyclerView.getFirstVisiblePosition()I` `acc=0x1`（PUBLIC）✅
   - `ChattingRecyclerView.m()Lt3;` `acc=0x1`（PUBLIC）✅
   - `RecyclerView.F0/G0(View)I` `acc=0x1`（PUBLIC）✅
   - `MMChattingListView.getListView()` `acc=0x1`（PUBLIC）✅
   全部 public，`Class.getMethod` 能命中，无 `getDeclaredMethod` 才需要的私有访问问题。✅

6. **Kotlin `as Int` 拆箱**：`m.invoke(chatList, itemRoot) as Int`（`:896`）、`m.invoke(chatList, v) as Int`（`:954`）——反射返回 `java.lang.Integer`，`as Int` 拆箱。若方法实际返回 `int`（DEX 确认 F0/G0 返回 `I`），invoke 返回 `Integer`，拆箱安全。若返回 null（方法返回 -1 时不会返回 null，因为返回类型是原始 int），`as Int` 会 NPE——但都被 `runCatching` 包住（`:896`、`:954`），异常被 `getOrNull()` 吞掉落 -1。✅

7. **编译期风险**：`listKindOf(view: View?)` 参数用了 `View?`，但 `msgsnapReport()` 里调用点 `listKindOf(node)`（`:831`）传的是 `node: View?`（while 条件已确保非 null 才进循环体，但类型上仍是 `View?`），`listKindOf(chatList)`（`:848`）传 `View?`。函数签名 `View?` 接收 `View?` 无类型错误。`chatList.javaClass`（`:868`）在 `when` 的 `listKind == "unknown"` 分支里——此时 `chatList` 已由外层 `if (chatList != null && v != null)`（`:862`）保证非 null，但**类型上** `chatList` 是 `var chatList: View? = null`，Kotlin 智能转换对 `var` 跨闭包/分支不生效，`:868` 直接 `chatList.javaClass.name` 若 `chatList` 为 null 会 NPE。

   **但**：`effKind` 的 when（`:865`）只在 `if (chatList != null && v != null)` 块内求值（`:862-871`），运行时 `chatList` 非 null。这是**运行时安全、编译期依赖智能转换失效的隐患**——若未来有人把 `effKind` 移出 `if` 块，会 NPE。当前代码结构下不触发。**判 P2 级（静态脆弱，运行时安全）**，建议把 `chatList` 在进入 `if` 后提一个 `val cl = chatList` 局部非空变量，或改用 `chatList!!` 语义明确——但这是风格建议，不是阻断。

   > 补充核对：team-lead 已报 `compileDebugKotlin EXIT=0`、`testDebugUnitTest tests=606 failures=0`，与本静态审查一致（无编译错误）。

**结论**：新增代码无编译期错误、无运行时 NPE/死循环/越界。唯一可挑剔点是 `:868` 对 `var chatList: View?` 的 `javaClass` 解引用依赖外层 `if` 的运行时保证、而非 Kotlin 智能转换（P2 静态脆弱，运行时安全），不阻断。**判 PASS**。

### 6.7 最终判据 PASS/FAIL

**PASS。**

| 问题 | 结论 |
|---|---|
| 1. `listKindOf` 覆盖三个运行时类 | ✅ 继承链判定无跳层/无提前 break，`view.l` 隔两层命中（第 3 轮） |
| 2. `unknown` 降级方向 | ✅ 假 pos 被 `diagnose()` 的 `list=unknown` 早退兜住，不会静默误判 |
| 3. `getListView()` 兜底 | ✅ `u3` 实现类仅 3 个且全为 `View` 子类，返回就是列表本身，无误报路径 |
| 4. 候选链 [F0,G0,...] 顺序 | ✅ 先 F0（adapter position，`getItem` 需要的下标）正确；`posVia` 能区分最终胜出者。仅「F0 返回 -1 vs 抛异常」不可分（P2） |
| 5. 链尾原名 | ✅ 保留，作未来未混淆版本哨兵，无实质成本 |
| 6. 静态审查 | ✅ 无 `!!`、无 NPE/死循环/越界；`:868` 依赖运行时非空保证（P2 静态脆弱，运行时安全） |

**阻断项**：无。

**P2 级（单独列出，不阻塞上机）**：
1. 候选链降级「F0 返回 -1」与「F0 抛异常」在 `posVia`/`posFail` 里不可区分（§6.4）。
2. `:868` 对 `var chatList: View?` 的 `javaClass` 解引用依赖外层 `if` 的运行时非空保证，非 Kotlin 智能转换（§6.6）。

**需真机确认（静态无法判定的，不硬下结论）**：
1. 聊天页实际走 listview 还是 recycler（AB 开关 `adapter/x;->c1` 默认 4）。
2. item 根 View 实际层级深度（12 层封顶是否够）。
3. `getListView()` 兜底在真机上是否真的会被触发（parent 链 12 层通常已能直接筛到列表，兜底是二重保险）。
4. `F0` 在真机列表静止时的实际返回值。

**最终结论**：两个 P0（chatList 查找条件、反射方法名）都已真修好，无新 P0/P1，仅余 2 个 P2（不阻塞）。**PASS，可上真机。**

---

## 6.8 增量：两级 chatList 优先级 + posFail 修复的复核

> 背景：§6.0-§6.7 审的是「加两级优先级之前」的代码（行号 :831/:840-847/:887-905/:868 已偏移）。team-lead 在上条消息后又改 3 处：① `chatList` 拆两级（`chatList` 严格 + `chatListLoose` 宽松）；② `posFail` 包进 `if (pos < 0)`；③ listview 分支补 `posFail` 赋值。本节基于**新代码**（`ForceForward.kt:817-982`）重审，只答 team-lead 的三条（A/B/C）。

### 6.8-A 两级 `when` 是否漏返回值（问题 A）

**实现原文**（`ForceForward.kt:838-843`）：

```kotlin
if (chatList == null) {
    when (listKindOf(node)) {
        "recycler", "listview" -> chatList = node
        "unknown" -> if (chatListLoose == null) chatListLoose = node
    }
}
```

**`listKindOf` 的完整返回集**（`ForceForward.kt:1054-1071`，本复核复读确认）：只有 4 个——`"recycler"` / `"listview"` / `"unknown"` / `"notList"`。

**Kotlin `when` 对 String 不穷尽时的行为**：这里的 `when` 是**语句**（statement），不是表达式——没有 `else` 分支、返回值未被赋值/使用。Kotlin 对「作语句用的 `when`」**不要求穷尽**，未匹配的分支（`"notList"`）**静默跳过、不编译报错**。（只有把 `when` 当**表达式**用、值被消费时，编译器才强制要求 `else` 或穷尽。）

**逐返回值核对**：

| `listKindOf` 返回 | when 里的分支 | 落到哪 | 是否符合意图 |
|---|---|---|---|
| `recycler` | 命中 `"recycler"` | `chatList = node`（严格） | ✅ 确定是列表，硬命中 |
| `listview` | 命中 `"listview"` | `chatList = node`（严格） | ✅ 确定是列表，硬命中 |
| `unknown` | 命中 `"unknown"` | `chatListLoose = node`（宽松，仅首次） | ✅ 只记着，不抢占 |
| `notList` | **无分支** | 静默跳过（两者都不占） | ✅ 正确：不是列表，不该占任何槽 |

**结论**：`when` **没有漏掉导致 `chatList` 永远拿不到的返回值**。`recycler`/`listview` 都精确命中严格槽；`notList` 静默跳过是**期望行为**（非列表不占位）；Kotlin 作语句的 `when` 对 String 不穷尽**不报错**（静默跳过）。team-lead 的担心「漏返回值导致 chatList 拿不到」不成立——反而正是因为 `notList` 不在任何分支里，才避免了「非列表抢占」的旧缺陷。

> 唯一风格备注（P2，非必需）：作语句的 `when` 省略 `else` 虽合法，但读者需知道 `notList` 被静默跳过。可加一行 `else -> { /* notList：不占位 */ }` 让意图显式化。现状不改无碍。

### 6.8-B `getListView()` 排在 loose 前是否会浪费/有害（问题 B）

**实现原文**（`ForceForward.kt:852-862`）：

```kotlin
// 兜底路径 1：parent 链没筛到时，用 MMChattingListView.getListView()
if (chatList == null && outer != null) {
    chatList = runCatching {
        outer.javaClass.getMethod("getListView").invoke(outer) as? View
    }.getOrNull()
}
// 兜底路径 2：只有前两条都失败，才用「类名像列表」的那个
if (chatList == null) chatList = chatListLoose
val listKind = if (chatList == null) "none" else listKindOf(chatList)
```

**team-lead 的两个子问**：

**（1）`getListView()` 排在 loose 之前，会不会让 loose 永远用不上？**

`chatListLoose` 被用上的**充要条件**是：`getListView()` 兜底失败（`chatList` 仍为 null）。`getListView()` 失败的情形（本复核 §6.3 已坐实，复读确认）：

- `outer == null`：parent 链 12 层里没遇到 `MMChattingListView`（不在聊天页，或层级 >12）。此时 `outer=null`，兜底 1 直接跳过，`chatList` 仍 null ⇒ **落到 loose**。
- `getMethod("getListView")` 抛异常：`outer` 不是 `MMChattingListView`（`contains("MMChattingListView")` 误命中未来子类，但 team-lead 已实测无子类，且 :844-848 有继承链再确认注释）。极端情况下可能。
- `T` 字段为 null（`l()` 未执行）：`invoke` 返回 null ⇒ `as? View` 得 null ⇒ `getOrNull()` 得 null ⇒ `chatList` 仍 null ⇒ **落到 loose**。

所以 loose **不是永远用不上**——在「不在聊天页/列表未初始化」时，`getListView()` 拿不到列表，`chatListLoose`（若 parent 链里碰巧有个类名像列表的内层容器）会被采用。此时 `listKind=unknown`，`diagnose()` 报「类名不认识」——**语义正确**（确实没找到真正的聊天列表，只有一个像列表的东西）。

**（2）「严格没命中但 getListView() 成功」时，loose 里那个可能更近的对象被浪费，有没有害处？**

**没有害处，且这是正确选择。** 理由：

- `chatListLoose` 记的是「继承链不认识、只靠类名含 `Recycler`/`ListView` 猜」的对象——它**极可能不是**聊天列表（内嵌横向 Recycler 之类）。`getListView()` 返回的是 `T` 字段（`l(Context)` 里赋值的 `ScrollControlRecyclerView` / `view.l` 二选一，本复核 §6.3 已反查 `u3` 仅 3 个实现类），**确定是**当前聊天列表。
- 「更近」≠「更对」。parent 链从内往外走，内层容器虽然先遇到（物理上更近），但语义上**不是**目标列表。用 `getListView()` 拿到的「确定正确的列表」替换「可能错误的内层容器」，正是缺陷 1（`chatList` 抢占）要防的事。
- 唯一理论损失：若 `chatListLoose` 其实是真正的列表、而 `getListView()` 恰好返回了另一个列表（team-lead 问的「缓存了另一个列表」场景），则用了错的 `getListView()`。但本复核 §6.3 + `docs/_r16b_selfaudit.md:258-305` 已双重坐实：`getListView()` 是纯 `iget-object T`（无缓存/无重建），`T` 唯一写点在 `l()` 末尾、构造期只跑一次，**不会指向旧列表**。故「缓存另一个列表」场景不存在。

**结论**：`getListView()` 排在 loose 之前**正确且无浪费之害**。loose 在 `getListView()` 失败（不在聊天页/列表未初始化）时仍会启用，兜住了「不在聊天页」的退化场景。判 PASS，无需改动。

### 6.8-C `posFail` 组合是否还有矛盾/空洞（问题 C）

**三处修改后的 `pos`×`posVia`×`posFail` 全组合走查**（基于 `ForceForward.kt:873-982`）：

先列出三个关键赋值点（原文）：

```kotlin
// :912 候选链成功
if (p >= 0) { pos = p; posVia = name; break }

// :923-926 候选链结束，只有最终没拿到 pos 才记失败
if (pos < 0) {
    if (allMissing) posFail = "noMethod"
    else if (sawClassCast) posFail = "classCast"
}

// :935 A0scan 入口（仅 noMethod 时）
if (itemRoot != null && pos < 0 && posFail == "noMethod") { ... }

// :963 A0scan 成功
if (scanHit >= 0) { pos = scanHit; posVia = "A0scan"; posFail = "none" }

// :966 候选链有方法但都返 -1，或 A0 也没命中
if (pos < 0 && posFail == "none") posFail = "returnedMinusOne"

// :974-980 listview 分支
if (m != null) {
    pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
    posVia = "getPositionForView"
    if (pos < 0) posFail = "returnedMinusOne"
} else {
    posFail = "noMethod"
}
```

**全组合真值表**（recycler 分支 + listview 分支 + 边界）：

| # | 场景 | pos | posVia | posFail | 自洽？ |
|---|---|---|---|---|---|
| 1 | 候选链 F0 命中 | ≥0 | `F0` | `none`（:912 设 pos，:923 的 `if(pos<0)` 不执行，:966 的 `if(pos<0)` 不执行） | ✅ |
| 2 | F0 返 -1，G0 命中 | ≥0 | `G0` | `none` | ✅ |
| 3 | F0 抛 CC，G0 命中 | ≥0 | `G0` | `none`（:912 break，:923 `if(pos<0)` 不执行 ⇒ `sawClassCast` 不写 posFail） | ✅（缺陷 2 已修） |
| 4 | 四候选全 noMethod，A0scan 命中 | ≥0 | `A0scan` | `none`（:963 显式复位） | ✅ |
| 5 | 四候选全 noMethod，A0scan 未命中 | -1 | `none` | `noMethod`（:924 设，:966 的 `if(posFail=="none")` 不执行） | ✅ |
| 6 | 有方法但都返 -1（无 CC） | -1 | `none` | `returnedMinusOne`（:923 `if(pos<0)` 不写，:966 命中 `posFail=="none"`） | ✅ |
| 7 | 有方法、部分返 -1 部分抛 CC | -1 | `none` | `classCast`（:925 `sawClassCast` 命中） | ✅ |
| 8 | itemRoot 没找到 | -1 | `none` | `itemRootNotFound`（:928） | ✅ |
| 9 | listview `getPositionForView` 命中且 ≥0 | ≥0 | `getPositionForView` | `none`（:977 `if(pos<0)` 不执行） | ✅ |
| 10 | listview 命中但返 -1 | -1 | `getPositionForView` | `returnedMinusOne`（:977） | ✅ |
| 11 | listview `getPositionForView` noMethod | -1 | `none` | `noMethod`（:979） | ✅ |
| 12 | chatList=null 或 v=null（不进 if 块） | -1 | `none` | `none` | ⚠️ 见下 |

**第 12 行的「空洞」分析**：当 `chatList == null` 或 `v == null` 时，整个 `if (chatList != null && v != null)` 块（:876）不进入，`pos=-1 posVia=none posFail=none`。

这是否算「空洞」？**不算可观测性空洞**，因为：
- `chatList == null` ⇒ `listKind = "none"`（:862），`diagnose()` 的 `list=none` 分支（`_r16_msgsnap.py:212-217`）**先于** pos 判读触发，报「向上 12 层没找到聊天列表」——不依赖 posFail。
- `v == null` ⇒ `view=none`，`diagnose()` 的 `view=none` 分支（`_r16_msgsnap.py:205-207`）**最早**触发。
- 即第 12 行的「三字段全 none」总伴随 `list=none` 或 `view=none`，`diagnose()` 有更早的判读兜住，不会出现「三字段都说没事但结果错却无人解释」的真空洞。

**`pos>=0 但 posFail != none` 的矛盾组合是否存在？** 逐一排除：
- 候选链成功（:912 break）后，:923 `if(pos<0)` 挡住、:966 `if(pos<0)` 挡住 ⇒ posFail 保持初始 `none`。
- A0scan 成功 :963 显式 `posFail="none"`。
- listview 成功（pos≥0）时 :977 `if(pos<0)` 不执行 ⇒ 保持 `none`。
- **无路径能让 pos>=0 时 posFail 仍非 none**。✅ 矛盾组合已消除。

**`pos=-1 且 posVia=none 且 posFail=none` 的真空洞是否存在？** 只有第 12 行（`chatList==null` 或 `v==null`），但该行总伴随 `list=none`/`view=none` 由 `diagnose()` 早退兜住，不是「无人解释」的状态。其余所有 pos=-1 场景，`posFail` 都落在 `noMethod`/`classCast`/`returnedMinusOne`/`itemRootNotFound` 之一。✅ 真空洞已消除。

**结论**：三处修改后，`pos`×`posVia`×`posFail` 组合**无自相矛盾**（pos≥0 必 posFail=none）、**无可观测性空洞**（pos=-1 时除 `list=none`/`view=none` 早退场景外，posFail 必非 none）。team-lead 在 `docs/_r16b_selfaudit.md:218-227` 的真值表 8 行**没有遗漏第 9 行**——我补的边界第 12 行（`chatList==null`/`v==null`）不是「矛盾/空洞」禁形，因为被 `diagnose()` 更早的 `view=none`/`list=none` 分支兜住，属于「成功早退」而非「无声失败」。

> 附：team-lead 要的 `0..-1` Kotlin 语义独立确认——`IntRange(0, -1).isEmpty()` 为 `true`（`first > last`），`for` 展开为 `while (i <= last)` 首次即假、**不执行循环体、不抛异常、不倒序**。`:956` 的 `for (i in from..to)` 在 `count==0` 时 `from=0, to=-1` 是安全空循环。**确认非 bug**，与 team-lead 判断一致。
