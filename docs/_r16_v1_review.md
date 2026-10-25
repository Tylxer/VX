# R16 · V1 复核报告

> 复核人：general-purpose-1（V1 复核 lane）
> 基线：`git HEAD` = `38ef1b3`
> 复核范围：R15+R16 两轮全部未提交代码改动（7 个主代码文件 + 2 个新测试文件 + 1 个改动测试文件）
> 时间：2026-10-24

## 结论：FAIL（2 个 P0）

**一句话理由**：① `MomentsLogic.fliterPre` 实现与 DEX 真实语义相反（从下标 2 开始 vs 从下标 0 开始），方案文档已定案要求改但代码/KDoc/测试三处均未同步，取图候选链「靠双候选兜底蒙对、而非算对」；② `ForceForward.msgsnapReport` 在 RecyclerView 形态下 `getPositionForView` 恒返回 -1，探针取不到 position，全部字段落 `none` 并被 `diagnose()` 误读成「adapter 链不通」，会带反取证结论。

## P0（必须修，否则不许上机）

### P0-1 · `fliterPre` 起点错误：`var i = 2` → `var i = 0`

- **位置**：`MomentsLogic.kt:429`（KDoc :424-427 同错）。
- **DEX 铁证**：`Lbf3/t0;->s(String)`（`SnsUtil`，acc=0x0009 static）反汇编 `[19] const/4 v3 #0` + `[22] charAt(I)` + `[26] const/16 #48('0')` + `[28] if-eq` + `[31] add-int/lit8` + `[34] substring(I)` —— 循环从 0 开始跳前导 '0'，**不是砍前 2 位**。
- **方案文档已定案**：`docs/_r15_sns_cache_plan.md` §122-177「⛔⛔ 重大订正」§167 明确要求 `var i = 2 → var i = 0` + KDoc 同步，但代码未改。
- **为什么现在不必然漏图（不能当作「不用修」）**：`snsNameCandidates` 是「`fliterPre(mediaId)` + `mediaId` 原值」双候选。当 `vm3.d` 首位非 0（方案 §157 实测文件名侧 100% 首位 '1'）时，`mediaId 原值` 兜底候选仍命中，属「靠兜底蒙对」（方案 §175-176 原话）。
- **稳定漏图的场景（我实际跑过）**：当 `vm3.d` 带 **1 个前导 0**（如 `"015038644089917813396"`）时，正确文件名 `snsb_15038644089917813396` 在 BROKEN 候选里**命中 False**（BROKEN 会砍前 2 位 "01" 得到 `5038644089917813396`，且 `mediaId` 原值带 0 前缀也不等于文件名），FIXED 才命中。协议对 `vm3.d` 是否带前导 0 无保证 ⇒ 属必须修，不能靠兜底。
- **修法（最小改动）**：`var i = 2` 改为 `var i = 0`，KDoc「从下标 2 开始」改为「从下标 0 开始跳过连续 `'0'`」。
- **同步测试改动（3 处断言 + 若干硬编码值）**：
  1. `SnsCacheMatchTest.fliterPreDropsFirstTwoCharsThenLeadingZeros`：`fliterPre("15038644089917813396")` 预期 `"38644089917813396"` → `"15038644089917813396"`（并改测试名，如 `fliterPreSkipsLeadingZerosOnly`）。
  2. `SnsCacheMatchTest.fliterPreKeepsLeadingNonZero`：`fliterPre("12345")` 预期 `"345"` → `"12345"`；`"12a0b"` 预期 `"12a0b"`（原断言已对，保留）。
  3. `SnsProtoTest.mediaIdIsReturnedVerbatim`：`assertTrue(got != fliterPre(raw))` 在 fliterPre 修对后变红（`fliterPre("150...")` == 原值），需换成带前导 0 的反证或删除。
  4. 硬编码 `"38644089917813396"` 的若干断言（`SnsCacheMatchTest.candidatesTierOrderIsBigThenMidThenThumb`、`matchExactName`、`doesNotMatchLongerNumber`、`endToEnd...`、`SnsProtoTest.endToEndCandidatesFromParsedId` 等）在修对后候选顺序/值会变，需一并重算。

### P0-2 · RecyclerView 形态下 `getPositionForView` 恒 -1 ⇒ `msgsnap` 探针失效，且伪装成「adapter 链不通」

- **现象**：`ChattingRecyclerView.getPositionForView(View)I` 反汇编为 `const/4 v1, #-1; return v1` —— 是**恒返回 -1 的空实现**（`_r12_dexclass.py` 实测该类 32 个 virtual 方法里含 `getPositionForView`，access=0x1 public；`_r14_walk.py dis` 输出 `const/4 v1 #-1; return v1`）。
- **后果**：`msgsnapReport()` 里 `pos` 经 `chatList.javaClass.getMethod("getPositionForView", View::class.java)` 反射拿到。RecyclerView 形态下 `pos` **恒 -1** ⇒ 下游 `if (adapter != null && pos >= 0)` 整块跳过 ⇒ `h9 / msgId / type / isSend / imgPath` 全部落 `none`。更糟的是，`diagnose()` 的判读顺序把 `pos=-1` 读成「adapter 链不通」，于是**把「RecyclerView 形态探针本来就是坏的」误报成「链路故障」**——取证结论被带反。
- **不可规避**：`getListView()` 返回的 `T:u3` 字段对象本身就是那个实现（`T` 在 `l(Context)` 里 `[155] iput-object v1, v10, T:u3`，两个分支 `[56] check-cast ScrollControlRecyclerView`（extends ChattingRecyclerView）与 `[150] new-instance .../view/l;`（l extends ScrollControlListView））。所以「改成走 u3 接口反射」也打到同一个恒 -1 的实现，规避不掉。
- **触发条件**：聊天页 ListView vs RecyclerView 形态由运行时 AB 开关 `adapter/x;->c1` 决定（默认 4，`dh4/e0;->e(dh4/g;->v;I)I`），两种形态真机都可能出现。一旦命中 RecyclerView 形态，探针对「链路取证」部分即失效。
- **修法建议（只写建议，不改代码）**：按 `list=` 输出区分形态后分派：
  1. RecyclerView 形态：改用 `RecyclerView.getChildAdapterPosition(itemRootView)` 取 position。注意必须传**条目的根 View**，而非被长按的深层子 View —— 需沿 `parent` 链向上走到「RecyclerView 直接子节点」的根 View 再传。
  2. 在 `diagnose()` 里把 `list=recycler && pos=-1` 标注为「RecyclerView 形态下 `getPositionForView` 恒 -1，属预期」，不要读成链路故障。
  3. 若 `getChildAdapterPosition` 在该版本不可用，退化方案：遍历 `0..getCount()-1` 逐个 `A0(i)`，比对 ViewHolder 的 `d` 字段是否等于目标 View。

## P1（建议修）

### P1-1 · `ForceForward.msgsnapReport` 的 `getItem(int)` 反射可能因协变+桥接双方法而抛 `NoSuchMethodException`

- 三个消息 adapter（`adapter/h`、`adapter/i0`、`adapter/x`）同时声明 `getItem(I)h9`（协变）与 `getItem(I)Object`（桥接），`adapter.javaClass.getMethod("getItem", Int)` 对「同名同参仅返回类型不同」的双候选行为依赖 JDK 实现，可能抛异常或返回其一。
- `runCatching` 兜底不崩，但真机 dump 若 `adapter` 有了而 `h9=none`，优先怀疑这里。建议改遍历 `methods` 找「返回类型为 h9 的那个」再 invoke。

### P1-2 · `fliterPre` 空串 + `snsNameCandidates` 空键候选（修对后仍存在的既有小坑，非本次引入）

`fliterPre` 返回空（如全 0 串）时，`snsNameCandidates` 生成 `snsb_/snsu_/snst_`（前缀+空键）三项，注释已声明「必然匹配不到真机文件」且被 `matchSnsFileName` 边界拒掉，无害。仅提示 `probeSnsCache` 日志里 `cand0=` 会显示 `snsb_`（带尾下划线），真机对账时别误读成异常。

## P2（可留待后续）

### P2-1 · `SNS_EXCLUDE_PREFIXES` 三项冗余

`snstblur_`/`wxid_`/`sight_` 均不以 `snsb_`/`snsu_`/`snst_` 开头，白名单 `startsWith` 已天然排除（`snstblur_` 第 5 字符 `b` ≠ `_`），防御性冗余，无害（沿用 R15 V1 P1-1 附注）。

## 逐文件复核

### Keys.kt            [DONE]

改动内容：`Prefs.ALL` 里 `SNS_CDN_IMG` / `SNS_CDN_REWRITE_PATH` 两项从普通 SWITCH 改为 `hidden = true`，并加了大段注释说明两个 key「无效」的原因不同。

复核结论：**通过**。

1. **`hidden` 机制本身正确**：`PrefSpec.hidden` 默认 false（Keys.kt:50），`visible()` 用 `byDomain(domain).filter { !it.hidden }` 过滤（Keys.kt:359），而 `byDomain()`（351）、`Prefs.ALL`、`spec()`、`defaultOf()`、`Kv.exportAll()`（Kv.kt:72 遍历 `Prefs.ALL`）、`BackupFlow.knownKeys`（BackupFlow.kt:69 `Prefs.ALL.map { it.key }`）全部照旧包含。即「不渲染但定义/备份/探针都在」的承诺成立。
2. **`sns.cdn_img` 消费点**：确认 SnsCdnImage.kt:102 `Kv.getBool(Keys.SNS_CDN_IMG)` 是唯一消费点，注释「有消费点但整条路已判死」与代码一致（SnsCdnImage.kt 顶部注释 + 取证结论 ⑫.9 引用）。
3. **`sns.cdn_rewrite_path` 零消费点**：全仓 grep 确认只有 Keys.kt 定义、SnsCdnImage.kt:85 Cfg 字段声明、:102 写入三处，**无任何读取点**，与注释「全类零读取点」一致。
4. **为什么 hidden 而不是删定义**：理由成立 —— 旧备份文件可能带这两个 key，删定义会让 BackupMerge.pickSettings 按 knownKeys 过滤时当未知 key 丢弃（BackupMerge.kt:172）。

### ForceForward.kt    [DONE]

改动内容：`probe()` 新增 `sub=msgsnap` 分支 + `msgsnapReport()` + `staleViewForProbe()`，`else` 分支的 subs 清单同步加上 `msgsnap`。

复核结论：**FAIL（1 条 P0：P0-2）+ 1 条 P1（getItem 协变/桥接）**。

逐项核对（全部用 DEX 反汇编/类文件字节实测，非照抄注释）：

1. **`getPositionForView` 在 RecyclerView 形态恒 -1 —— P0-2（阻断）**。
   - `ChattingRecyclerView` 自己声明 `getPositionForView(View)I`（`_r12_dexclass.py` 实测 32 virtual 方法里含此名，access=0x1 public），反汇编 `const/4 v1, #-1; return v1` —— **恒返回 -1 的空实现**，不是「能反射到但无害」。
   - `ChattingListView` **没有**声明（`_r14_walk.py dis` 报「未找到」；`_r12_dexclass.py` 20 virtual 方法里也无此名），它靠继承 `ListView → AbsListView → AdapterView` 命中 `getPositionForView`（声明点在 `android.widget.ListView`，public）—— 这条路径能返回真实 position。
   - 实现（:824-831）用 `chatList.javaClass.getMethod("getPositionForView", View::class.java)` 按**具体类**反射：ListView 形态命中继承方法返回真 position；**RecyclerView 形态命中自己的恒 -1 空实现，`pos` 恒 -1**。
   - `getMethod` 沿继承链找 public 方法，`ChattingRecyclerView` 自声明的 public 方法**优先命中**，所以「能反射到」是真，但「能拿到正确 position」是假——这两个概念不能混。`pos=-1` ⇒ 下游 `if (adapter != null && pos >= 0)` 整块跳过 ⇒ `h9/msgId/type/isSend/imgPath` 全部 `none`，且被 `diagnose()` 误读成「adapter 链不通」，取证结论被带反。
   - 「改成走 `u3` 接口反射」**规避不掉**：`getListView()` 返回的 `T:u3` 字段对象本身就是那个实现（`l(Context)` `[155] iput-object`，两个分支 `[56] check-cast ScrollControlRecyclerView` / `[150] new-instance .../view/l;`），接口反射打到同一个恒 -1 实现。
   - **触发条件**：ListView vs RecyclerView 形态由运行时 AB 开关 `adapter/x;->c1` 决定（默认 4），两形态真机都可能出现。
   - ⇒ 结论：**实现没按注释说的走 u3 是次要的，真正的阻断是 RecyclerView 形态下 position 恒 -1 导致探针失效。** 修法建议见 P0-2。

2. **adapter 链按名字反射，无 check-cast 到 h9**：
   - `MMChattingListView.getBaseAdapter()Lt3;` 反汇编坐实（`iget U; return`，U 字段类型 t3），未混淆名。
   - `ChattingListView.m()` / `ChattingRecyclerView.m()` 反汇编坐实：返回类型 `Lcom/tencent/mm/pluginsdk/ui/tools/t3;`，是 `getAdapter()` 包装 + `instance-of t3` + `check-cast t3`。退回路径 `m()` 反射正确。
   - `t3` 接口 `getItem` 返回 Object（`t3` 4 方法接口，`_r12_dexclass.py` 实测）。三个消息 adapter（`adapter/h`、`adapter/i0`、`adapter/x`）均有协变 `getItem(I)h9` + 桥接 `getItem(I)Object`（反汇编坐实，`iget List + List.get + check-cast h9`）。
   - 实现（:858-861）用 `adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)` 拿 Object，**没有 `as? h9`**。✓

3. **h9 字段/getter 按名字反射，正确**：
   - `jj/m7.getMsgId()J` / `getType()I` 反汇编坐实（`iget field_msgId; return` / `iget field_type; return`），public。
   - `field_isSend` / `field_imgPath` 是 `jj/m7` 的 PRIVATE 字段（`_r12_dexclass.py` 实测类型 I / String），实现用 `XposedHelpers.getObjectField` 沿类链找，正确。**注意**：`got.javaClass` 是 h9，字段声明在父类 jj/m7，`XposedHelpers.getObjectField` 沿 super 链找（工具语义），能命中。

4. **`getItem` 反射的一个边界细节（P1，非阻断）**：实现用 `adapter.javaClass.getMethod("getItem", Int::class.javaPrimitiveType)`。但三个消息 adapter **同时**声明了 `getItem(I)h9` 和 `getItem(I)Object` 两个方法（协变+桥接）。Java 反射 `getMethod(name, int)` 按「名称+参数类型」匹配，此时**存在两个同名同参、仅返回类型不同的方法** —— 虽然 JVM 字节码允许（桥接），但 `getMethod` 对这种情况的行为取决于 JDK 实现（`getMethod` 文档要求唯一匹配，返回类型不在匹配里，遇到两个候选可能抛 `NoSuchMethodException` 或返回其中一个）。此处 `runCatching` 兜底不会崩，但**可能拿不到 adapter 链最后一段**。建议真机 dump 时重点看 `h9=` 字段；若 `adapter` 有了但 `h9=none`，优先怀疑这里。**不影响上机**（探针是取证用途，失败只报 none 不崩）。

5. **`ageMs` / `attached` 能区分「View 摘窗」vs「链路不通」—— 确认成立**：
   - `staleViewForProbe()`（:895-897）跳过 TTL，只 `burstDeepest?.get()`。`ageMs`（:789-791）取 `now - burstStartAt`，>10000 属正常（真机 adb 慢）。
   - `attached`（:796-798）`v.isAttachedToWindow`（API≥19），把「陈旧 View 已摘窗（attached=false ⇒ pos 必然 -1）」与「View 还在但链路不通（attached=true 且 pos=-1）」分开。两个字段独立输出，判读顺序脚本 `_r16_msgsnap.py:diagnose()` 也是先 `view=none` → `attached=false` → `list=none` → `pos=-1` → `adapter=none` → `msgId=none`，先排除假失败再报真失败，顺序正确。
   - **确认**：`ageMs` 标陈旧程度，`attached` 标是否仍挂窗，二者合起来足以区分两类失败，不会假失败。

6. **并发 / null 安全**：
   - `pressLock` 是 `synchronized` 锁，`burstStartAt`/`burstDeepest` 的读写都在锁内（installLongPressTrap :162-167、staleViewForProbe :895、ageMs :789），无竞态。
   - `burstDeepest` 是 `WeakReference<View>`，`staleViewForProbe` 返回 `burstDeepest?.get()`，View 被 GC 后返回 null，`viewName` 落 "none"，不 NPE。✓
   - 新增代码**无 ConcurrentHashMap**、**无 `apply { this.adapter = ... }` 静默失效写法**（全文件 grep 确认）。

7. **边界条件**：
   - `burstStartAt == 0L` 时 ageMs=-1（从没长按过），输出 -1 可辨识。✓
   - 向上 12 层 `guard++ < 12` 封顶，防死循环；`node = node.parent as? View` 父链走到非 View（如 Activity 的 DecorView 之上）时 `as?` 返回 null 自然终止。✓
   - `pos`/`adapter` 各步失败都有可见标记（list=none / pos=-1 / posVia=none / adapter=none / adapterVia=none / h9=none），不静默。✓

P1（建议修，非阻断）：第 4 条 —— `getItem(int)` 存在协变+桥接双方法，`getMethod` 可能抛异常，建议真机 dump 时若 `h9=none` 优先查这里；必要时改用遍历 `methods` 找「返回类型为 h9 的那个」再 invoke。

### MomentsExport.kt   [DONE]

改动内容：新增 SNS 磁盘缓存直读（`buildSnsIndex` / `copyOneFromCache` / `copyImagesForRow` / `countImg` / `probeSnsCache` / `mediaIdsOf`），四态计数，`COPY_IMAGES` 注释更新，probe 新增 `snsprobe` / `snsidx` 分支。

复核结论：**结构正确，但取图链路依赖 `MomentsLogic.fliterPre`，而该函数有 P0 级错误（见 MomentsLogic.kt 节）⇒ 本文件连带 FAIL（上机前必须修 fliterPre）**。

逐项核对：

1. **`COPY_IMAGES = false`** ✓（MomentsExport.kt:67）。确认仍是 false，未被人改 true。
2. **索引直扫（非分桶公式）** ✓：`buildSnsIndex` 遍历 `<ext>/cache/<hash32>/sns/<hex1>/<hex2>/` 三级目录，`listFiles` 建 `Map<文件名,File>`。与任务「禁止分桶公式、必须索引直扫」一致。`isHex32` 校验 hash 目录名（32 位小写 hex）。
3. **`listFiles` 判空 + 打日志** ✓：四层 listFiles 都显式 `== null` 判空并 `LogX.w`（R15 V1 P2-2 已修）。
4. **`fliterPre` 相关注释多处声称「从下标 2 起跳过连续 0」—— 与 DEX 真实语义不符（P0，见 MomentsLogic 节）**。
5. **四态互斥穷尽** ✓：`ImgOutcome` 枚举 ROOT_MISS/FILE_MISS/READ_FAIL/COPIED，`countImg` 的 `when` 无 else 静默出口，每态计数 + 日志。
6. **`imgNoMedia` 独立计数** ✓：`copyImagesForRow` 里 `ids.isEmpty()` 走独立 `imgNoMedia`，不再混进 `imgFileMiss`（R15 V1 P2-1 已修）。
7. **回滚路径** ✓：`copyOneFromCache` 先写 `.tmp` 再 `renameTo`；rename 失败 / 拷贝超 20MB / 异常 catch 三处都 `runCatching { tmp.delete() }`，tmp 不残留。`dst.exists()` 先 `delete()`（真机踩过 renameTo 不覆盖）。
8. **20MB 上限双保险** ✓：拷贝前 `s.length() > MAX_MEDIA_BYTES` + 拷贝中累计 `n > MAX_MEDIA_BYTES`。
9. **失败隔离** ✓：每 slot 独立 `runCatching`，不 break 整轮导出。
10. **`mediaIdsOf` 已接通** ✓：委托 `MomentsLogic.mediaIdsFromContent(row.content)`，不再是 `return emptyList()`（R15 V1 P0-1 已修）。
11. **probe 分支** ✓：`snsprobe`（只读链路探针）、`snsidx`（索引诊断）都接线，subs 清单已更新（:1156）。`snsprobe` 只读不拷贝。
12. **并发** ✓：新增代码无 ConcurrentHashMap（`HashMap` 单线程 buildSnsIndex 调用）；四态用 `AtomicLong`。

唯一阻断点：**取图命中依赖 `fliterPre` 正确生成候选文件名，而 fliterPre 实现有 P0 错误**（见下节），真机 `imgCopied` 大概率恒 0。修好 fliterPre 后本文件结构无需再改。

### MomentsLogic.kt    [DONE]

改动内容：新增 `fliterPre` / `snsNameCandidates` / `isSnsMediaName` / `matchSnsFileName` 四个纯函数 + protobuf 线格式解析（`readVarint` / `protoSegments` / `protoString` / `mediaIdsFromContent`）+ 旧 md5 路线的注释订正。

复核结论：**发现 1 个 P0 语义错误（`fliterPre` 起点错误），其余全部正确。**

#### ⛔ P0 · `fliterPre` 从下标 2 开始 —— 与 DEX 真实语义相反

- **位置**：`MomentsLogic.kt:429` `var i = 2`，KDoc（:424-427）写「从下标 2 开始，跳过所有连续 `'0'`」。
- **DEX 铁证**（我独立反汇编，非照抄文档）：
  ```
  Lbf3/t0;->s(Ljava/lang/String;)Ljava/lang/String;   // acc=0x0009 static
    [  15] invoke-virtual  String.length()
    [  19] const/4        v3  #0      ← ⭐ 循环变量 i 从 0 开始
    [  20] if-ge          v3, v2      ← i >= length 就退出
    [  22] invoke-virtual String.charAt(I)
    [  26] const/16       v5  #48     ← '0'
    [  28] if-eq          v4, v5 -> 31  ← 是 '0' 就 i++
    [  31] add-int/lit8   v3, #1
    [  33] goto -> 20
    [  34] invoke-virtual String.substring(I)
  ```
  我另用手工字节解码 `0x216004` 处的 84 字节核对过，`[19] const/4 v3 #0` 的立即数确为 0（`0x12 0x03` = const/4 v3,#0）。
- **真实语义**：`fliterPre(s)` = **从下标 0 开始跳过连续 `'0'`，返回从第一个非 `'0'` 处到结尾的子串**。不是「先砍前 2 位」。
- **代码错误**：`var i = 2` 是「先砍前 2 位、再从第 2 位起跳前导 0」，与 DEX 不符。
- **对账（我实际跑过）**：
  - 真机 mediaId 若为 `"15038644089917813396"`（20 位、首位 '1'）：
    - DEX 正确语义 → `fliterPre` = 原串（首位非 0 不跳）
    - 代码错误语义 → `fliterPre` = `"38644089917813396"`（砍掉前 2 位 "15"）
  - 两者给同一 mediaId 不同的候选名 ⇒ 直接对不上。
- **方案文档已定案但代码未同步**：`docs/_r15_sns_cache_plan.md` §122-177 有「⛔⛔ 重大订正」（标注「R16-V1 复核触发」），§167 明确要求 `MomentsLogic.kt:429 var i = 2 → var i = 0`，§167 还要求同步改 KDoc。**但代码、KDoc、测试三处均未改。**
- **为什么它目前不必然漏图（诚实说明）**：`snsNameCandidates` 是「`fliterPre(mediaId)` + `mediaId 原值`」双候选。真机文件名 `snsb_<20位数字>` 的数字段（§157 实测 100% 首位 '1'、100% 长度 20）就等于 `vm3.d` 原值本身 ⇒ `mediaId 原值` 这条兜底候选仍能命中，属「靠兜底蒙对」，不是「算对」（方案 §175-176 原话）。
- **为什么仍判 P0 而非 P1**：
  1. 这是**确凿的语义错误**（DEX 铁证 + 方案文档明确要求改），不是猜测、不是边界风险。
  2. **测试把错误行为固化成正确**：`SnsCacheMatchTest.fliterPreDropsFirstTwoCharsThenLeadingZeros` 断言 `fliterPre("15038644089917813396") == "38644089917813396"`，这是**错误语义的产物**。改对 `var i = 0` 后此测试会红，必须同步改测试，否则「改对代码 vs 改错测试」会打架。
  3. 方案文档措辞是「必须改，不能留着靠兜底」（§177）。既然本 lane 的职责是上机闸门，一个「已定案要改、代码/文档/测试三处都还错着」的项，不放行。

- **为什么「1029/1029 自洽」没能暴露它（方法论教训，来自 team-lead 已写进 §157 的实测）**：真机 1029 个图片文件名里，1001 个是纯数字、长度 100% = 20、首位 100% = '1'、527 个唯一数字段在两种实现下两两不同。因为**真实文件名侧从不带前导 0**，所以「从 0 跳前导 0」和「从 2 砍起」在**可见文件名这一侧永远打平**——两边都能「自洽」却给出同一 mediaId 的不同候选名。**这是通用教训：两侧同源时，「自洽率 100%」不构成验证，它只是把两边都调到同一个错答案上。**
- **§4.3 表格需重算**：`docs/_r15_sns_cache_plan.md` §4.3 里 `MD5(fliterPre(mediaId))[0]/[1] = 4/1029 (0.39%)` 这一行用的是**错误实现**的 fliterPre，改对后需用 `var i = 0` 语义重算这一行的命中率（另两行 98.45%/100% 与「索引直扫」本身不受影响）。

#### 其余部分复核（全部通过）

1. **`protoSegments` 四种 wiretype 跳字节正确**：我独立重写并反向对照（wt0 varint / wt1 fixed64 / wt2 length-delimited / wt5 fixed32 混合噪音字段后仍能解析出字段 6），通过。结构断裂（字段号 0 / varint 越界 / 长度越界 / group wt3）返回空列表，不抛异常。
2. **`mediaIdsFromContent` 正确**：字段 6 → ContentObj → 字段 8(repeated) → 元素字段 1 = `d`。独立验证：多媒体保持字节序、null/空/截断/group 均安全返回空、**返回 `vm3.d` 原值不加工**（契约正确，双候选靠它兜底）。
3. **`snsNameCandidates` / `isSnsMediaName` / `matchSnsFileName` 正确**：反向对照验证通过（见「我独立验证过的东西」）。
4. **`readVarint` 边界**：`shift < 64` 封顶防溢出，`x & 0x80 == 0` 判终止，越界返回 null。正确。

#### 连带结论

`fliterPre` 的 P0 连带影响 `MomentsExport.kt`（`copyOneFromCache` / `copyImagesForRow` / `probeSnsCache` 都经 `snsNameCandidates` → `fliterPre`）。修好 `fliterPre`（`var i = 0`）+ KDoc + 测试三处后，其余无需再改。

### MuteAtLogic.kt     [DONE]

改动内容：**纯注释改动**（+7/-6），类文件头 KDoc 更新「群名匹配已接线」，删掉旧的「未接线（待取证）」描述。

复核结论：**通过**。

1. **逻辑零变更**：`git diff` 确认只有类头 KDoc 注释改动，无任何 `fun`/字段/语句变化。
2. **注释与代码一致**：
   - 注释声称「`MuteAt.kt:65` 已把 `GroupNameSource.nameOf(talker)` 传进 `groupInScope` 的 groupName 参数」—— 我核实 `MuteAt.kt:65` 确有 `talker?.let { GroupNameSource.nameOf(it) }`，属实。
   - 注释声称 `GroupNameSource` 走懒加载、`nameOf` 内部 `warmUp()`、`msgDb` 为 null 时不起线程 —— 核实 `GroupNameSource.kt:31/84/117` 确有 `warmUp()` / `nameOf()`，`loads` 是 AtomicLong（:75），注释与实现一致。
   - 「冷启动首条通知退回 talker 匹配属预期降级」「`GROUPNAME loads=0` 只说明还没触发装载」与 `MuteAt.kt:34-35` 的注释一致。

无问题。

### SnsCdnImage.kt     [DONE]

改动内容：**+4 行注释**（Cfg data class 前），标注 `rewritePath` 字段零消费点 + 批量下载路线判死。

复核结论：**通过**。

1. **纯注释零逻辑**：`git diff` 确认只加了 4 行注释，无代码变更。
2. **注释准确**：
   - `rewritePath` 零消费点：全仓 grep 确认 `SNS_CDN_REWRITE_PATH` 只在 `refreshCfg()`（SnsCdnImage.kt:102）被写入 `Cfg.rewritePath`，全文件无读取点。
   - 判死理由引用 `docs/conclusions/moments_cdn.md`：`picCachePath` 字段恒空（hitPicE=7/hitNoField=0）、批量请求 `ok0.x0#n` 未触发（hitN=0），与 Keys.kt 注释一致。

无问题。

### 测试文件            [DONE]

改动内容：`PrefsVisibilityTest.kt`（+10/-4）+ 新增 `SnsCacheMatchTest.kt`（约 220 行）+ 新增 `SnsProtoTest.kt`（约 270 行）。

复核结论：**测试质量整体高，但 `SnsCacheMatchTest.fliterPreDropsFirstTwoCharsThenLeadingZeros` 把 P0 错误语义固化成正确（见 MomentsLogic 节）⇒ 该断言错误，改 fliterPre 时必须同步改。**

#### PrefsVisibilityTest.kt —— 通过

1. `expectedHidden` 加入 `SNS_CDN_IMG` / `SNS_CDN_REWRITE_PATH`，与 Keys.kt 的 hidden 改动配套。✓
2. `byDomainKeepsHiddenButVisibleDropsIt` 的修复正确：旧写法固定用 `REDPACKET_ALLOW` 的域，会漏测「朋友圈」域的 hidden 项；新写法 `for (k in expectedHidden)` 逐个取 `Prefs.spec(k)!!.domain`，跨域正确。`assertTrue(all.containsAll(shown))` 移进循环内，覆盖每个域。✓
3. `noUnexpectedHiddenPrefs` 用 `assertEquals(expectedHidden, actual)` 锁死白名单 —— 反向守卫，防 hidden 被滥用。✓
4. `keyPrefixMatchesDomainGrouping` 的 `"sns" to "朋友圈"` 映射是本次必需配套（SNS_CDN_IMG 的 key 前缀是 `sns.` 但归朋友圈域）。✓
5. 反向对照：我确认「摘掉一个 hidden ⇒ 4 个测试全红」的机制存在 —— `expectedHidden` 是白名单，`noUnexpectedHiddenPrefs` 用 `assertEquals` 双向锁定，Keys.kt 少标一个 hidden 或测试漏加一个 key，都会红。

#### SnsCacheMatchTest.kt —— 1 条断言错误（P0 连带）

- **错误断言**：`fliterPreDropsFirstTwoCharsThenLeadingZeros`（`fliterPre("15038644089917813396") == "38644089917813396"`）和 `fliterPreKeepsLeadingNonZero`（`fliterPre("12345") == "345"`）—— 这两个断言把「从下标 2 开始砍」的错误语义写成预期。
- DEX 真语义是「从下标 0 跳前导 0」：`fliterPre("15038644089917813396")` 应为 `"15038644089917813396"`（首位 '1' 不跳），`fliterPre("12345")` 应为 `"12345"`。
- 其余测试（候选顺序、matchSnsFileName 边界、排除前缀、端到端）正确，我已独立复刻逻辑验证。

#### SnsProtoTest.kt —— 通过

1. 手工构造 protobuf 字节测「字段号 + 线格式」不变契约，思路正确（不依赖真机 BLOB fixture）。
2. `mediaIdIsReturnedVerbatim` 的反证断言（`got != fliterPre(raw)`）**方向正确**：它锁的是「mediaIdsFromContent 返回原值不加工」。但**当前 fliterPre 是错的**，所以这个断言现在碰巧通过；一旦 fliterPre 改对，`fliterPre("15038644089917813396")` 会等于原值，这条 `assertTrue(got != fliterPre(raw))` 会**变红**（因为此时两者相等）。⇒ 这是 fliterPre 修对后必须同步调整的第二处测试（把 `!=` 改成针对「带前导 0 的 mediaId」的反证，或直接删）。
3. 截断/非法/group/干扰字段/端到端用例，我已独立复刻解析逻辑全部跑通。

#### 测试文件连带结论

改 `fliterPre` 时需同步改 **3 处测试断言**：
1. `SnsCacheMatchTest.fliterPreDropsFirstTwoCharsThenLeadingZeros`（改断言为「首位非 0 原样返回」）
2. `SnsCacheMatchTest.fliterPreKeepsLeadingNonZero`（`"12345"` → `"12345"`，`"12a0b"` 的预期不变）
3. `SnsProtoTest.mediaIdIsReturnedVerbatim`（`got != fliterPre(raw)` 需换掉，否则改对后变红）

另注意：`SnsProtoTest.endToEndCandidatesFromParsedId` / `endToEndAlreadyTrimmedIdStillHits` / `SnsCacheMatchTest.candidatesTierOrderIsBigThenMidThenThumb` 等**多处断言里硬编码了 `"38644089917813396"`**，这些是 `fliterPre` 错误语义的产物，改对后**候选顺序和值都会变**，需一并重算（不是只改 3 处）。我会在下节「修法」里把影响面列全。

## 我独立验证过的东西

以下全部是我**实际执行**过的取证/脚本命令与输出，非照抄注释。所有命令在 `c:\Users\lx\WorkBuddy\2026-09-29-20-23-21` 下用 `python`（3.13）运行，只读，未改任何 `.kt`/`.py`/配置。

### 1. DEX 反汇编（关键锚点，逐个实跑）

| 命令 | 关键输出 |
|---|---|
| `python _cache/_r14_walk.py dis classes4.dex "Lcom/tencent/mm/pluginsdk/ui/tools/ChattingListView;" getPositionForView` | `未找到 …ChattingListView;->getPositionForView`（证实该类未声明） |
| `python _cache/_r14_walk.py dis classes4.dex "Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;" getPositionForView` | 命中，`acc=0x0001`，反汇编 `const/4 v1 #-1; return v1` |
| `python tools/_r12_dexclass.py --class 'Lcom/tencent/mm/pluginsdk/ui/tools/u3;'` | `virtual_methods=55`，含 `getPositionForView (NO-CODE)` 接口方法 |
| `python tools/_r12_dexclass.py --class 'Lcom/tencent/mm/pluginsdk/ui/tools/t3;'` | 4 方法接口：`E`/`getCount`/`getItem`/`getView` |
| `python _cache/_r14_walk.py dis classes4.dex "Lcom/tencent/mm/ui/chatting/view/MMChattingListView;" getBaseAdapter` | `iget-object U … return`（未混淆名，返回 t3） |
| `python _cache/_r14_walk.py dis classes4.dex "Lcom/tencent/mm/ui/chatting/view/MMChattingListView;" getListView` | `iget-object T … return`（返回 u3） |
| `python tools/_r12_dexclass.py --class 'Ljj/m7;'` | `field_msgId:J` / `field_type:I` / `field_isSend:I` / `field_imgPath:String` / `field_content:String` |
| `python _cache/_r14_walk.py dis classes4.dex "Ljj/m7;" getMsgId` | `iget-wide field_msgId; return`（public） |
| `python _cache/_r14_walk.py dis classes4.dex "Ljj/m7;" getType` | `iget field_type; return`（public） |
| `python _cache/_r14_walk.py dis classes7.dex "Lcom/tencent/mm/ui/chatting/adapter/h;" getItem` | 两个方法：`getItem(I)h9`（协变，`iget List; List.get; check-cast h9`）+ `getItem(I)Object`（桥接，acc=0x1041） |
| 同上 `adapter/i0` / `adapter/x` | 均有协变 `(I)h9` + 桥接 `(I)Object` 两个方法 |

### 2. 类接口/继承链（class_defs interfaces 段 + android.jar 字节）

- 我写脚本读 DEX `class_defs` 的 `interfaces_off` 段，输出：
  - `ChattingListView; super=android/widget/ListView; interfaces(1)=[u3]`
  - `ChattingRecyclerView; super=androidx/recyclerview/widget/RecyclerView; interfaces(1)=[u3]`
- 我用 android-34 `android.jar` 的 classfile 字节解析 `ListView.getPositionForView`（**声明点在 `android.widget.ListView`**，`access_flags=0x0001` = public），并验证 `ListView.super=AbsListView`、`AbsListView.super=AdapterView` ⇒ `ChattingListView` 经继承能命中 `getPositionForView`。

### 3. `fliterPre`（SnsUtil.s）DEX 反汇编 + 手工字节解码

- `python _cache/_r14_walk.py dis classes3.dex "Lbf3/t0;" s "(Ljava/lang/String;)Ljava/lang/String;"` 输出（关键行）：
  ```
  [   0] const-string 'fliterPre'
  [   7] if-nez v6 -> abs 15
  [  12] const-string v6 ''
  [  15] String.length()
  [  19] const/4 v3 #0          ← i 从 0 开始（P0 铁证）
  [  20] if-ge v3,v2 -> 34
  [  22] String.charAt(I)
  [  26] const/16 v5 #48        ← '0'
  [  28] if-eq v4,v5 -> 31
  [  31] add-int/lit8 v3,#1     ← i++
  [  33] goto -> 20
  [  34] String.substring(I)
  ```
- 我另用 `struct` 手工解码 `0x216004` 处的 84 字节指令流，确认 `[19]` 是 `0x12 0x03` = `const/4 v3, #0`，立即数确为 0。
- 结论：真实语义「从 0 跳前导 0」，代码 `var i = 2` 是错的。

### 4. 反向对照（4 组，均故意改错验证该被发现的错误）

- **反向对照 1（fliterPre 错误实现）**：把 fliterPre 改成「只砍前 2 位不跳前导 0」，输出：
  - 正确 `fliterPre("15038644089917813396") = 38644089917813396`（按 BROKEN 语义）
  - 错误变体 `= 038644089917813396`，真机文件名 `snsb_038644089917813396` **不在**正确候选里 ⇒ 错误实现会漏。
- **反向对照 2（matchSnsFileName 数字边界）**：去掉「下一字符非数字」守卫后，`match(snst_150)` 从 `None` 变成命中 `snst_15034444444444444444`（误配到另一条动态的图）。守卫有效。
- **反向对照 3（protoSegments wiretype 跳字节）**：构造 `wt0 varint + wt2 string + wt1 fixed64 + wt5 fixed32 + field6` 噪音链，正确解析出 `['15038644089917813396']`；确认 wt0/wt1/wt5 跳字节正确。
- **反向对照 4（fliterPre 修复前后命中差异）**：`vm3.d` 三种形态（20 位首位 1 / `00` 前缀 / **`0` 前缀**）下对比 BROKEN vs FIXED 候选链：
  - `"015038644089917813396"`（1 个前导 0）：真机文件名 `snsb_15038644089917813396` 在 **BROKEN 命中 False、FIXED 命中 True** —— 这是「必须修、不能靠兜底」的实证场景。

### 5. 全仓消费点 grep

- `grep -rn "rewritePath\|SNS_CDN_REWRITE_PATH" .../main/`：只有 Keys.kt 定义、SnsCdnImage.kt:85 声明、:102 写入，**无读取点**（坐实「零消费点」）。
- `grep -rn "SNS_CDN_IMG" .../main/`：唯一消费点 SnsCdnImage.kt:102。
- `grep -rn "Prefs.visible" .../main/`：消费点 MainPage.kt:74 / MassSendPage.kt:107 / SettingPage.kt:53（渲染用 visible）；`BackupFlow.kt:69` 用 `Prefs.ALL`（备份不含隐藏过滤），`Kv.kt:72` 遍历 `Prefs.ALL`。
- `grep -n "ConcurrentHashMap\|apply {" ForceForward.kt`：无 ConcurrentHashMap、无 `apply { this.adapter = ... }` 静默失效写法。

### 6. `COPY_IMAGES` 确认

- `grep -n "const val COPY_IMAGES" MomentsExport.kt` → `:67  const val COPY_IMAGES = false`（仍是 false，未被人改 true）。

## 我没能验证的

以下是我**没能**实际验证的，明确标出，不假装验过：

1. **真机行为**（硬边界要求不碰 adb、不装包、不跑 gradle）：`imgCopied>0`、`ageMs`/`attached` 在真机上的实际取值、`h9` 反射在真机运行时是否命中、`GROUPNAME loads` 实际值 —— 全部未跑真机。
2. **JVM `getMethod` 对协变+桥接双方法的确切行为**（ForceForward P1-1）：我确认了两个方法在 DEX 里共存，但「`getMethod("getItem", int)` 到底抛异常还是返回其一」取决于真机运行的 ART/JDK 实现，**未能在真机验证**（只能列为 P1 建议）。
3. **`vm3.d` 是否真的带前导 0**：我证明了「若带 1 个前导 0，BROKEN 会稳定漏图」，但「真机 `vm3.d` 是否带前导 0」本身需真机 `sub=snsprobe` 的 `raw=` 字段确认（方案 §157 只观测了**文件名侧**首位恒 '1'，未观测 protobuf 侧 `vm3.d` 原值）。
4. **编译/测试是否通过**：硬边界禁止跑 gradle，未执行 `compileDebugKotlin` / `test`。测试正确性靠我读源码 + 独立复刻逻辑跑 Python 脚本验证，未跑 JVM 单测。
5. **`staleViewForProbe` 在真机 View 被 GC 后是否返回 null**：`WeakReference.get()` 语义正确，但真机 GC 时机未验证。
