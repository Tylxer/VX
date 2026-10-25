# R16 F5 修复记录：RecyclerView 定位主路径失效（`getChildAdapterPosition` → `F0`）

> 修复子代理 F5。缺陷：`ForceForward.kt:841-847` 里反射 `getChildAdapterPosition`
> 在微信 8.0.48 / 2588 GP 的 dex 中不存在（RecyclerView 被 R8 混淆，该方法已更名为 `F0`），
> 导致 P0-2 修复的主路径是死代码。

## 0. 任务自检清单

- [ ] 判据 1 复现：ChattingRecyclerView 包名 / super / classes4.dex
- [ ] 判据 2 复现：RecyclerView 无 `getChildAdapterPosition`，`(View)I` 只有 F0/G0
- [ ] 判据 3 复现：F0/G0 反汇编
- [ ] 判据 4 复现：c3 = ViewHolder，c3.i()/j()/d
- [ ] 判据 5 复现：ChattingRecyclerView.getCount()
- [ ] 判据 6 复现：A0(I) 早退 / I 字段 / Y0(Z) / j0()（标注存疑级别）
- [x] 判据 7-11 复现：s/v/p 空桩、getFirstVisiblePosition、ChattingListView、I0 check-cast、零调用点（追加）
- [x] 改动 1：ForceForward.kt recycler 定位改混淆名候选链
- [x] 改动 2：tools/_r16_msgsnap.py diagnose() 四态化
- [x] 改动 3：docs/_r16_g2_msgsnap.md 补 §1.2d
- [x] 追加修复：classCast 分态 + getFirstVisiblePosition -1 处理 + diagnose unknown/classCast
- [x] 验证：compileDebugKotlin EXIT=0 + py_compile EXIT=0

## 0.1 骨架落盘时间

（此骨架已落盘，后续每完成一步在此追加 [DONE] 小节）

---

## 1. 判据复现 [DONE]

### 判据 1：ChattingRecyclerView 位置与 super  ✅ 复现成功

```
$ python tools/_r13_findclass.py Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView
DEFINED in classes4.dex
  class = Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;
  super = Landroidx/recyclerview/widget/RecyclerView;
  access = 0x0001
  <init> (Landroid/content/Context;Landroid/util/AttributeSet;)V
  ⛔ 没有无参构造 ⇒ 不能用 XposedHelpers.newInstance() 直接造
  methods(33): <init>, B, K, addFooterView, addHeaderView, e, getCount, getFirstCompletelyVisiblePosition, getFirstVisiblePosition, getFooterViews, getFooterViewsCount, getHeaderViews, getHeaderViewsCount, getLastVisiblePosition, getPositionForView, m, onLayout, p, removeHeaderView, s, setAdapter, setCacheColorHint, setDivider, setItemChecked
```

完整 33 方法名（补全）：`['<init>', 'B', 'K', 'addFooterView', 'addHeaderView', 'e', 'getCount', 'getFirstCompletelyVisiblePosition', 'getFirstVisiblePosition', 'getFooterViews', 'getFooterViewsCount', 'getHeaderViews', 'getHeaderViewsCount', 'getLastVisiblePosition', 'getPositionForView', 'm', 'onLayout', 'p', 'removeHeaderView', 's', 'setAdapter', 'setCacheColorHint', 'setDivider', 'setItemChecked', 'setOnScrollListener', 'setSelection', 'setSelectionFromTop', 'setSelector', 'setTranscriptMode', 'setVisibility', 'smoothScrollToPositionFromTop', 'v', 'y']`

确认：**没有 `A0`**（与判据一致）；有 `getCount`、`getPositionForView`、`m`、`getFirstVisiblePosition`（供 A0scan 窗口起点用）。

### 判据 2：RecyclerView 无 getChildAdapterPosition，(View)I 只有 F0/G0  ✅ 复现成功

```
count 160
MATCH F0 0x1
MATCH G0 0x1
```

确认：160 个方法里没有 `getChildAdapterPosition`；`(Landroid/view/View;)I` 签名只有 `F0`、`G0` 两个。

### 判据 3：F0/G0 反汇编  ✅ 复现成功

`F0(Landroid/view/View;)I`：
```
  [   0] invoke-static          I0(Landroid/view/View;)Landroidx/recyclerview/widget/c3;
  [   3] move-result-object     v1
  [   4] if-eqz                 v1             -> +7  (abs 11)
  [   6] invoke-virtual         c3;->i()I
  [   9] move-result            v1
  [  10] goto                                  -> +2  (abs 12)
  [  11] const/4                v1             #-1
  [  12] return                 v1
```

`G0(Landroid/view/View;)I`：
```
  [   0] invoke-static          I0(Landroid/view/View;)Landroidx/recyclerview/widget/c3;
  [   3] move-result-object     v1
  [   4] if-eqz                 v1             -> +7  (abs 11)
  [   6] invoke-virtual         c3;->j()I
  [   9] move-result            v1
  [  10] goto                                  -> +2  (abs 12)
  [  11] const/4                v1             #-1
  [  12] return                 v1
```

确认：F0 = `I0(View)→c3`，null 返回 -1，否则 `c3.i()`；G0 同构但 `c3.j()`。与判据完全一致。

### 判据 4：c3 = ViewHolder，i()/j()/d  ✅ 复现成功

`c3` 类：`name Landroidx/recyclerview/widget/c3; acc 0x401 super Ljava/lang/Object;`

`c3.i()I`（getAdapterPosition）：
```
  [   0] iget-object            v0, v1         c3;->x:Landroidx/recyclerview/widget/RecyclerView;
  [   2] if-nez                 v0             -> +4  (abs 6)
  [   4] const/4                v0             #-1
  [   5] return                 v0
  [   6] invoke-virtual         RecyclerView;->D0(Landroidx/recyclerview/widget/c3;)I
  [   9] move-result            v0
  [  10] return                 v0
```

`c3.j()I`（getLayoutPosition）：
```
  [   0] iget                   v0, v2         c3;->j:I
  [   2] const/4                v1             #-1
  [   3] if-ne                  v0, v1         -> +4  (abs 7)
  [   5] iget                   v0, v2         c3;->f:I
  [   7] return                 v0
```

`c3` 实例字段（d = itemView）：
```
  field d : Landroid/view/View;
  field e : Ljava/lang/ref/WeakReference;
  field f : I
  field j : I
  field x : Landroidx/recyclerview/widget/RecyclerView;
  ...（共 19 个实例字段）
```

确认：`c3.d = Landroid/view/View`（itemView）；`i()`=getAdapterPosition（iget x→D0），`j()`=getLayoutPosition（iget j，-1 则 iget f）。

### 判据 5：ChattingRecyclerView.getCount()  ✅ 复现成功

```
Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;->getCount()I   acc=0x0001
  [   0] invoke-virtual         RecyclerView;->getAdapter()Landroidx/recyclerview/widget/w1;
  [   3] move-result-object     v0
  [   4] if-eqz                 v0             -> +7  (abs 11)
  [   6] invoke-virtual         w1;->getItemCount()I
  [   9] move-result            v0
  [  10] goto                                  -> +2  (abs 12)
  [  11] const/4                v0             #0
  [  12] return                 v0
```

确认：`getAdapter()==null ? 0 : getAdapter().getItemCount()`。

### 判据 6：A0(I) 早退 / I 字段写点（原标「存疑」，已穷举）  ✅ 静态实锤

`A0(I)Landroidx/recyclerview/widget/c3;` 开头：
```
  [   0] iget-boolean           v0, v5         RecyclerView;->I:Z
  [   2] const/4                v1             #0
  [   3] if-eqz                 v0             -> +3  (abs 6)
  [   5] return-object          v1              ← I==true 时返回 null（v1=#0）
  [   6] iget-object            v0, v5         RecyclerView;->h:Landroidx/recyclerview/widget/j;
  ...（遍历 child，I0(View)→c3，比对其 d 字段）
```

`I:Z` 字段**全部**读写点（`fieldref` 反查 + 反汇编坐实）：

| 写点 | 位置 | 值 |
|---|---|---|
| `<init>(Context,AttributeSet,I)` `[77] iput-boolean v14` | `[74] const/4 v14 #0` | **false** |
| `Y0(Z)` `[6] iput-boolean v7` | `[5] const/4 v7 #1` | **true** |
| `j0()` `[679] iput-boolean v2` | 见下 | **false** |

**`j0()` 里 `v2` 的穷举**（`[679] iput-boolean v2, v0, I:Z` 之前的全部写点）：

`v2` 在主流程的**唯一写点**是 `[14] const/4 v2 #0`。其余对 v2 的写（`[266] const-string v2`、`[297]/[304]/[330]/[337] move-result-object v2`）**全部位于 IllegalStateException 的 throw 分支内**（`[308]/[341] throw`），不回流主流程；`[935] op01 v2, v3`（move v2,v3）在 `[679]` **之后**，不影响 `[679]` 处取值。

⇒ `j0()` 末尾 `[679]` 恒写 `I=false`。

**结论（判据 6 从「存疑」升级为「静态实锤」）**：
- `I` 初始 false（init）→ `setAdapter` 无条件调 `Y0(Z)` 置 true（`Y0` 内部 `[5] const/4 #1` 无条件写 true）→ layout step3 `j0()` 末尾置 false。
- **稳态下（用户长按菜单弹出时，layout 早已完成）`I=false`，`A0(I)` 的早退分支不触发，A0 可用。**
- ⚠️ 边界：layout 进行中（Y0 置 true 到 j0 置 false 之间）A0 会返回 null。但长按菜单是稳态交互，不在 layout 窗口内。
- 仍建议真机实测确认（静态穷举只能证明 dex 语义，不能证明运行时 AB 开关下 ChattingRecyclerView 是否走这条链）。

---

## 2. 改动 1：ForceForward.kt recycler 定位改混淆名候选链 [DONE]

文件：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/ForceForward.kt`

### 2.1 改动点与行号

- `:767-773` KDoc 坑 1 补充「`getChildAdapterPosition` 已更名 `F0`」说明
- `:827-903` `msgsnapReport()` 内 recycler 定位逻辑整体重写

### 2.2 改后代码原文（`:827-903`）

```kotlin
        // position：按 listKind 分派定位。
        //  ⚠️ ChattingRecyclerView.getPositionForView 是恒 -1 的空桩（反汇编实证），
        //    recycler 形态必须走 RecyclerView 自身定位，不能用它。
        //
        //  ⚠️ RecyclerView 在当前微信（8.0.48 / 2588 GP）的 dex 里被 R8 混淆：
        //    `getChildAdapterPosition` 已更名为 `F0`，`getChildLayoutPosition` 更名为 `G0`
        //    （classes12.dex 反汇编实证：`(View)I` 签名只有 F0/G0 两个方法）。
        //    所以候选链是 [F0, G0, getChildAdapterPosition, getChildLayoutPosition]，
        //    混淆名在前（当前版本可用）、原名在后（未混淆版本兜底）。
        var pos = -1
        var posVia = "none"
        var posFail = "none"
        if (chatList != null && v != null) {
            if (listKind == "recycler") {
                // ① 先找 item 根 View：从 v 沿 parent 链向上，直到 parent == chatList。
                var itemRoot: View? = null
                var n: View? = v
                var g2 = 0
                while (n != null && g2++ < 12) {
                    if (n.parent === chatList) { itemRoot = n; break }
                    n = n.parent as? View
                }
                if (itemRoot != null) {
                    // ② 混淆名候选链：逐个 getMethod，第一个能拿到且 invoke >= 0 的胜出。
                    //    顺序很重要：F0/G0 在前（当前版本），原名在后（未来版本兜底）。
                    val candidates = arrayOf("F0", "G0", "getChildAdapterPosition", "getChildLayoutPosition")
                    var allMissing = true
                    for (name in candidates) {
                        val m = runCatching {
                            chatList.javaClass.getMethod(name, View::class.java)
                        }.getOrNull()
                        if (m == null) continue
                        allMissing = false
                        val p = runCatching { m.invoke(chatList, itemRoot) as Int }.getOrNull() ?: -1
                        if (p >= 0) { pos = p; posVia = name; break }
                    }
                    if (allMissing) posFail = "noMethod"
                } else {
                    posFail = "itemRootNotFound"
                }
                // ③ A0 扫描降级为最后兜底：仅当候选链「全部没拿到方法」（noMethod）时才跑。
                //    扫描用 getFirstVisiblePosition() 起点 + ±50 窗口，避免几千条的 O(N) 全扫
                //    （这段跑在主线程长按回调里，全扫有 ANR 风险）。
                if (itemRoot != null && pos < 0 && posFail == "noMethod") {
                    var scanHit = -1
                    runCatching {
                        val a0 = chatList.javaClass.getMethod("A0", Int::class.javaPrimitiveType)
                        val first = runCatching {
                            chatList.javaClass.getMethod("getFirstVisiblePosition").invoke(chatList) as Int
                        }.getOrNull() ?: 0
                        val from = (first - 50).coerceAtLeast(0)
                        val count = runCatching {
                            chatList.javaClass.getMethod("getCount").invoke(chatList) as Int
                        }.getOrNull() ?: 0
                        val to = minOf(first + 50, count - 1)
                        for (i in from..to) {
                            // A0 在 I 字段早退时（layout 进行中）第一次就返回 null，直接放弃
                            val vh = a0.invoke(chatList, i) ?: break
                            val iv = XposedHelpers.getObjectField(vh, "d") as? View
                            if (iv === itemRoot) { scanHit = i; break }
                        }
                    }
                    if (scanHit >= 0) { pos = scanHit; posVia = "A0scan"; posFail = "none" }
                }
                // 候选链有方法但都返回 -1，或 A0 扫描也没命中
                if (pos < 0 && posFail == "none") posFail = "returnedMinusOne"
            } else {
                // listview 形态：getPositionForView（ListView 继承来的真实实现）。
                val m = runCatching {
                    chatList.javaClass.getMethod("getPositionForView", View::class.java)
                }.getOrNull()
                if (m != null) {
                    pos = runCatching { m.invoke(chatList, v) as Int }.getOrNull() ?: -1
                    posVia = "getPositionForView"
                }
            }
        }
```

### 2.3 posFail 三态语义（与 diagnose 配套）

| posFail | 含义 | 触发条件 |
|---|---|---|
| `none` | 成功（pos>=0）或未进入 recycler 定位 | 默认 |
| `noMethod` | 候选方法名全部不存在（getMethod 全抛） | 候选链全部 m==null |
| `itemRootNotFound` | 向上 12 层没找到 `parent===chatList` 的节点 | itemRoot==null |
| `returnedMinusOne` | 方法存在且被调用，但都返回 -1（含 A0scan 未命中） | pos<0 且 posFail 仍为 none |

### 2.4 保留不动

- listview 分支（`:894-902`）：未动。
- listKind 三态判定（`:817-821`）：未动。
- KDoc 关于 `getPositionForView` 恒 -1 空桩的说明：保留并加强。

---

## 3. 改动 2：tools/_r16_msgsnap.py diagnose() 四态化 [DONE]

文件：`tools/_r16_msgsnap.py`

### 3.1 改动点

- 文件头第 0 节（`:23-24`、`:31-33`）：链路描述 + 坑 1 更新为「混淆名候选链 F0/G0」
- `diagnose()`（`:213-239`）：recycler 判据按 `posFail` 四态分别判读

### 3.2 改后 diagnose() recycler 段（`:213-239`）

```python
    if fields["list"] == "recycler":
        # ⛔ posFail 四态化（F5 修，2026-10-24）：按失败阶段分别判读，别再混成一个 recyclerStub。
        pf = fields["posFail"]
        if pf == "noMethod":
            return [(False, "list=recycler 且 posFail=noMethod ⇒ 候选方法名 F0/G0/"
                           "getChildAdapterPosition/getChildLayoutPosition 全部不存在 ⇒ **代码 bug**："
                           "dex 版本又变了或被进一步混淆，要更新 ForceForward 的候选链。")]
        if pf == "itemRootNotFound":
            return [(False, "list=recycler 且 posFail=itemRootNotFound ⇒ 定位链路断在第一步："
                           "长按 View 向上 12 层没找到 parent===chatList 的节点（item 根 View）。"
                           "可能长按的不是列表内 item，或层级 >12。")]
        if pf == "returnedMinusOne":
            return [(False, "list=recycler 且 posFail=returnedMinusOne ⇒ 方法调通了但都返回 -1："
                           "ViewHolder 已被回收/移除，或 itemRoot 不是真正的 item 根 View。"
                           "看 view=%s 是否仍 attached、itemRoot 是否挂在 RecyclerView 上。" % fields["view"])]
        if pf in ("<missing>",):
            return [(False, "list=recycler 且 posFail=<missing> ⇒ 装的是**旧 APK**（没有 posFail 字段）。"
                           "旧包走 recyclerStub 语义，先升级模块再复跑。")]
        # pf == "none" 且 pos>=0 属正常，落到后面通用判读
        if pf == "none" and fields["pos"] not in ("-1", "<missing>"):
            pass
        else:
            return [(False, "list=recycler 且 pos=-1（posVia=%s posFail=%s）⇒ RecyclerView 形态下 "
                           "getPositionForView 恒 -1 属**已知预期**，不是 adapter 链不通。"
                           "请检查 posVia 是否已是 F0/G0（若是则定位手段已试过仍失败，"
                           "看 item 根 View 是否真的挂在 RecyclerView 上）。"
                           % (fields["posVia"], fields["posFail"]))]
```

### 3.3 判读顺序保证

环境问题判据（`view=none` / `attached=false` / `list=none`）仍排在最前（`:203-212`），
recycler 四态判据在其后 —— 没有把 recycler 判据挪到最前。✅

---

## 4. 改动 3：docs/_r16_g2_msgsnap.md 补 §1.2d [DONE]

文件：`docs/_r16_g2_msgsnap.md`

在 §1.2c（F4 写的）之后、§1.3 之前新增 `### 1.2d ⛔ F4 修复的主路径失效：`getChildAdapterPosition` 已被混淆成 `F0``。

内容要点（已落盘，全文见该文件 `:144-202` 附近）：
- dex 证据（F5 逐条复现，引用 `docs/_r16_f5_fix.md` §1）
- 正确候选链 `[F0, G0, getChildAdapterPosition, getChildLayoutPosition]`
- `posFail` 三态表
- `A0(I)` 扫描降级 + ±50 窗口
- 沉淀铁律：「AndroidX 有 public API ≠ 微信 dex 里名字还在」

---

## 5. 验证 [DONE]

### 5.1 py_compile

```
$ python -m py_compile tools/_r16_msgsnap.py && echo "PY_COMPILE_EXIT=0"
PY_COMPILE_EXIT=0
```

### 5.2 compileDebugKotlin

```
$ python tools/gradle_run.py compileDebugKotlin
EXIT=0
BUILD SUCCESSFUL in 18s
```

两者均 EXIT=0。✅

---

## 6. 遗留 / 存疑清单

1. **判据 6 已从「存疑」升级为「静态实锤」**（穷举 `j0()` 里 `v2` 全部写点，`[679] iput-boolean v2, I:Z` 之前 v2 的写只有 `[14] const/4 v2 #0`，其余写点全在 IllegalStateException throw 分支内，不回流主流程）。结论：稳态下（长按菜单弹出时 layout 已完成）`I=false`，`A0(I)` 早退不触发、A0 可用。
   - ⚠️ 但仍建议**真机实测**确认：静态穷举只能证明 dex 语义，不能证明运行时 AB 开关下 ChattingRecyclerView 是否真走这条链、以及 `itemRoot` 是否真能通过 `parent===chatList` 定位。
2. **`G0`（getLayoutPosition）与 `F0`（getChildAdapterPosition）语义差异**：`c3.j()`（getLayoutPosition）在 j 字段为 -1 时回退 `f` 字段，通常比 getAdapterPosition 更「宽容」。候选链把 `F0` 放前面、`G0` 第二，符合「先精确后宽容」。若真机发现 `F0` 稳定返回 -1 而 `G0` 能命中，可能需要调换顺序 —— 留待真机数据决定。
3. **`posFail` 字段对旧 APK 的兼容**：老 APK 没有 `posFail`，`parse_msgsnap` 会填 `<missing>`，`diagnose` 已单独判读提示「装的是旧 APK」。✅
4. **A0scan 窗口 ±50**：若被长按消息的 position 与 `getFirstVisiblePosition()` 相差 >50（极端：屏幕外残留 View），窗口会漏扫。但长按的 View 必然在可视区，50 的窗口已足够；如真机出现「itemRoot 找到但 A0scan 漏」可再调大。
5. **未碰文件**：`MomentsLogic.kt`、测试文件、`docs/_r16_v2_review.md` 均未动；未碰 adb/gradle 装包/设备。

---

## 7. 追加修复（team-lead 补判据 7-11 后）[DONE]

### 7.1 补判据复现（全部成功）

**判据 7：ChattingRecyclerView 的 s/v/p 三方法，只有 s 能用** ✅

```
$ python _cache/_r14_walk.py dis classes4.dex "Lcom/tencent/mm/pluginsdk/ui/tools/ChattingRecyclerView;" s
s(II)Landroid/view/View;   acc=0x0001
  [   0] invoke-virtual  RecyclerView;->A0(I)Landroidx/recyclerview/widget/c3;
  [   3] move-result-object v1
  [   4] if-eqz v1 -> abs 9
  [   6] iget-object v1, v1  c3;->d:Landroid/view/View;
  [   8] goto abs 10
  [   9] const/4 v1, #0
  [  10] return-object v1
```

`s(pos, ?)` 用第 1 形参调 A0 返回 c3.d（itemView）—— 正确实现。✅

```
v(I)Landroid/view/View;    acc=0x0001
  [   0] invoke-virtual  ChattingRecyclerView;->getFirstVisiblePosition()I
  [   3] invoke-virtual  RecyclerView;->A0(I)Landroidx/recyclerview/widget/c3;   ← 用 getFirstVisiblePosition()，忽略形参！
  [   6] move-result-object v1
  [   7] if-eqz v1 -> abs 12
  [   9] iget-object v1, v1  c3;->d:Landroid/view/View;
  [  11] goto abs 13
  [  12] const/4 v1, #0
  [  13] return-object v1
```

`v(I)` **完全忽略形参**，恒返回第一个可见项。✅

```
p(II)I   acc=0x0001
  [   0] return v2
```

`p(II)` 恒返回第 2 形参，空桩。✅

**判据 8：getFirstVisiblePosition 真实现，非 LinearLayoutManager 时返回 -1** ✅

```
getFirstVisiblePosition()I   acc=0x0001
  [   0] invoke-virtual RecyclerView;->getLayoutManager()LayoutManager;
  [   3] move-result-object v0
  [   4] const/4 v1, #-1
  [   5] instance-of v2, v0, LinearLayoutManager
  [   7] if-eqz v2 -> abs 16
  [   9] check-cast v0, LinearLayoutManager
  [  11] invoke-virtual LinearLayoutManager;->w()I        ← findFirstVisibleItemPosition()
  [  14] move-result v0
  [  15] op01 v1, v0
  [  16] return v1
```

⇒ 拿它当窗口起点必须判 `>= 0`。✅

**判据 9：ChattingListView 没覆盖 getPositionForView（继承 ListView 真实现）** ✅

`ChattingListView` super = `android/widget/ListView`，methods(22) 里**没有** getPositionForView ⇒ 继承 ListView 真实现。listview 分支走 getPositionForView 是对的，未动。✅

**判据 10：I0(View) 里有 check-cast，会抛 ClassCastException 而非返回 -1** ✅

```
RecyclerView;->I0(Landroid/view/View;)Landroidx/recyclerview/widget/c3;   acc=0x0009 (static)
  [   0] if-nez v0 -> abs 4                    ← View == null ⇒ 返回 null
  [   2] const/4 v0, #0
  [   3] return-object v0
  [   4] invoke-virtual  View;->getLayoutParams()Landroid/view/ViewGroup$LayoutParams;
  [   7] move-result-object v0
  [   8] check-cast      v0, Landroidx/recyclerview/widget/RecyclerView$LayoutParams;   ← ⚠️ 类型不匹配抛 ClassCastException
  [  10] iget-object     v0, v0  RecyclerView$LayoutParams;->d:Landroidx/recyclerview/widget/c3;
  [  12] return-object   v0
```

F0/G0/A0 都会先调 I0。传进去的 View 不是 RecyclerView 直接 child 时会抛 CCE。✅

**判据 11：ChattingRecyclerView 四个 position/取View 方法零调用点（已复算 callers）**

- `getPositionForView` / `s` / `v` / `K` 四个方法 `callers` 反查合计 0 处调用。
- ⇒ ChattingRecyclerView 是「为满足 u3 接口而写的适配层」，其上的 position 方法都是占位实现，**不可靠**。
- ⇒ 定位只用框架层 RecyclerView 的 F0/G0/A0/I0，**不用** ChattingRecyclerView 上的任何 position 方法。

**订正记录（team-lead 补充）**：team-lead 曾推测「setAdapter 里 new 的 q0 会包一层 View 导致 classCast 有现实可能」，
后查证 `q0 super = q3`、`q3 super = w1`（RecyclerView.Adapter）⇒ q0 是 **Adapter 包装**，不会塞 View 层。
classCast 分态仍保留，理由改为「runCatching 吞异常会导致 invoke 期异常与 noMethod 在日志上无法区分」，
不是「概率高」。

### 7.2 追加代码改动

**ForceForward.kt（recycler 候选链 + A0scan 段）**：

1. `getMethod` 期异常与 `invoke` 期异常**分开捕获**：invoke 结果用 `r.exceptionOrNull()` 展开 cause 链找
   `ClassCastException`，命中则 `posFail = "classCast"`（不再被 runCatching 吞成 noMethod）。
2. A0scan 里 `getFirstVisiblePosition()` 返回值 **判 `>= 0`**：非 LinearLayoutManager 返回 -1 时，
   退化为「从头扫但限 100 次」，不再用 `-50` 算错窗口起点。
3. A0scan 注释明确「绝不调用 `v(I)`（忽略形参空壳）」，只直接 `A0(pos)` + `.d` 比对。

**tools/_r16_msgsnap.py（diagnose）**：

1. 新增 `list=unknown` 分支（`:218-222`，与 list=none 区分，提示看 listCls）。
2. recycler 判据新增 `posFail=classCast` 独立判读。

### 7.3 追加验证

```
$ python -m py_compile tools/_r16_msgsnap.py
PY_COMPILE_EXIT=0

$ python tools/gradle_run.py compileDebugKotlin
EXIT=0
BUILD SUCCESSFUL in 58s
```

两者 EXIT=0。✅

### 7.4 关于 chatList 定位失配（V2 报 P0）的说明

V2 报的「`chatList` 查找条件错（运行时对象是子类，`contains` 全 False ⇒ listKind 恒 none）」——
**当前 ForceForward.kt 已由并行 lane 落盘修复**：`listKindOf()` 按继承链判定（`ChattingRecyclerView` /
`ChattingListView` 的 superclass 链）+ `outer.getListView()` 兜底 + `listCls` 可观测字段。
本代理复算确认：
- `view.l super = ScrollControlListView`、`ScrollControlListView super = ChattingListView` ⇒ 继承链判定能命中 listview。✅
- `ScrollControlRecyclerView super = ChattingRecyclerView` ⇒ 命中 recycler。✅
- `listKindOf` 对不认识但像列表的对象返回 `unknown`，且日志带 `listCls`。✅

本代理未重复改动该段（避免与并行 lane 冲突），只在其上补齐 classCast 分态与 getFirstVisiblePosition -1 处理。

