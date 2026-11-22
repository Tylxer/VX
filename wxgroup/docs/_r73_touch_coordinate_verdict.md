# R73 ground truth 三方案裁决 —— 坐标法 / cell 绑定数据 / 微信菜单权威源

> 状态：[DONE]（含实施侧 r73-g0-impl 代码对齐补录；实施侧零真机数据）
> 只读取证 lane（模型 hy4-flash-free）
> 证据分级：【源码确定】/【真机日志确定】/【假设待验证】
> 边界：本 lane 全程只读，未改 `app/src/main`、未碰 adb/装机、未跑 gradle、未 commit、未实施探针；唯一产物为本文件。文中「实施侧状态」段落转述自 r73-g0-impl，非本 lane 操作。实施侧仅交付代码改动+单测+变异验证，全程未跑 adb/装机、无任何真机日志回传；凡涉及本轮 g0 实际身份的结论一律维持【假设待验证】，等主会话装机日志再定。

## 目录
1. 唯一裁决
2. 必答 1：x/y 是相对哪个 View 的局部坐标
3. 必答 2：findChildViewUnder(471,1690) 为何 null（含 scroll 判别）
4. 必答 3：g0 的 h9 是否独立源 / +1 是否 positionOf 链偏位
5. 必答 4：mutex——A0MAP pos 与 F0 pos 是否同一套编号，为何 F0=27 落文本
6. 必答 5：三方案逐项裁决与唯一推荐
7. 可落地锚点与最小改法
8. 证据索引 / 免责声明

---

## 1. 唯一裁决

**推荐方案 3：微信菜单权威源——用 `g0(r3,view,info,h9)` 第 4 参 h9（微信自己在 `onItemLongClick` 链里认定的被长按消息）作为第一 ground truth。放弃坐标法（方案 1）；cell 绑定数据（方案 2）仅作 g0 缺席时的二级降级。**（同源校验加固本轮暂缓，见 §7.2；本轮 g0 实际命中身份待装机日志验证。）

三条互斥理由：
1. **坐标法（方案 1）不是独立信源**。即便修好命中 cell，命中后仍走 `msgRefOf → positionOf(F0) → adapter.getItem(pos)`——这条链正是产出错误 pos27 文本的地方（H3/H4 均未排除）。真机又已 `noChild`，几何前提也不成立。双重失败。
2. **cell 绑定数据（方案 2）绕开了 `getItem(pos)`，但拿不到 cell**。它仍需先定位「被按住的那个 cell」，靠坐标则继承坐标法风险，靠回调的 v 则 v 已是 pos27 错行（H1/H4 未排除）。且 A0MAP 的 msgId/type 本就来自 `getItem(i)`，viewholder 是否直接持 h9 属【假设待验证】。不能主用。
3. **微信菜单权威源（方案 3）覆盖 H3+H4**。h9 来自微信 `onItemLongClick → new AdapterContextMenuInfo(view,pos,id) → g0(...,h9)` 这条**独立于 FF 自研 F0 链**的路径：pos 由系统 AdapterView 按 touch 算出，不是 FF 从深层 v 上溯 itemRoot 的 F0。它与 v / 坐标 / FF 的 positionOf 全无关，是「微信认为用户按了谁」的终局真相。实施侧 r73-g0-impl 已按本裁决把 g0 上位为第一取源（代码+单测+变异验证，零真机数据）；`FF ref src=cached msgId=? kind=?` 是主会话装机后的**验收判据**，非已观测结果，本轮 g0 实际身份仍为【假设待验证】。

---

## 2. 必答 1：x/y 是相对哪个 View 的局部坐标

【源码确定】AOSP `View.performLongClick(float x, float y)`：
```java
public boolean performLongClick(float x, float y) {
    mLongClickX = x; mLongClickY = y;
    final boolean handled = performLongClick();   // 内部再调无参重载
    mLongClickX = Float.NaN; mLongClickY = Float.NaN;
    return handled;
}
```
- x/y 来自系统 touch 流程：`onTouchEvent(ACTION_DOWN)` → `checkForLongClick(...,x,y)` → `CheckForLongPress.setAnchor(x,y)` → `run()` 调 `performLongClick(mX,mY)`；x/y = `MotionEvent.getX()/getY()`，**相对接收该 MotionEvent 的 View（= `this`）自身左上角**，即 view-local，不是父容器、不是屏幕坐标。
- 两条路径：系统流程走带参 `(float,float)`（本例 seq=1 `args=2 x=244.167 y=318.522`）；无参 `performLongClick()` 由带参内部调用（`args=0`，取证文档记 seq=4 的 x=y=-1）。
- 【真机日志确定】`v.getLocationOnScreen(vl)≈(227,1705)`：`global−vl=(244.167,318.522)` 恰等于触点局部值，反推自洽。
- **判定**：x/y 相对 **本次 `performLongClick` 的 thisObject = `FrameLayout#2131299633`**，非 itemView `xi`，非屏幕坐标。
- **关键限制**：v 是「burst 第一个回调的 View」（"最深处的先跑"）。真机确定它经 `msgRefOf` 稳定解析为 **pos27 文本**（freshMsgId=19733551），而用户触点物理落在 pos26 图片。故 **x/y 虽相对 v，但 v 本身是错行**；以它换算的 global=(471,2023) 未必落在用户真实按住的行。

---

## 3. 必答 2：findChildViewUnder(471,1690) 为何 null（含 scroll 判别）

【源码确定】AOSP `ViewGroup.findChildViewUnder(x,y)`：
```java
for (int i=mChildrenCount-1;i>=0;i--){
  child=mChildren[i];
  if (x>=child.getLeft()+child.getTranslationX() && x<=child.getRight()+tx
      && y>=child.getTop()+child.getTranslationY() && y<=child.getBottom()+ty) return child;
}
return null;
```
Javadoc：`x/y in this ViewGroup's coordinate system`。只比 child 的 getLeft/Top/Right/Bottom(+translation)，**不处理 scrollX/scrollY/padding/child matrix**。

【源码确定 → scroll 判别（直接回答 team-lead 的分支题）】
- RecyclerView 靠 **LayoutManager `offsetChildrenVertical()` 移动 child.getTop()** 实现滚动，RV 自身 `mScrollY` 恒 0（`scrollBy` 被 RV 重写为重新布局，不调 super 偏移自身）。
- ⇒ **排除「rv 靠 mScrollY 滚动」分支**：team-lead 提示的 `+rv.getScrollY()` 修法对 RV 是 no-op，当前代码「只传 local」本身不是 noChild 的原因。`ScrollControlRecyclerView` 是 RV 子类，除非它显式重写 scrollTo 并改用 mScrollY（【假设待验证】，无任何证据），否则同样恒 0。
- ⇒ noChild 必然落在另两个分支：**「触点 global 算错」或「rv 不是消息 item 的真正直接父容器」**。

【真机日志确定】rv 原点 (0,333)、屏幕 3168、rv 高约 2835；local y=1690 在 rv 纵向范围内，**非整体越界**；但无 child 命中。

**判定（唯一，二选一无法再互斥，但同归裁决）**：
- 【真机确定】触点 global=(471,2023) 是用**错行的 v**（pos27 文本行的深层 FrameLayout）的屏幕原点 + 相对它的 view-local 触点算出的；该点不在图片行 itemView 的屏幕矩形内，故 null。
- 【假设待验证】细机制：H-a 触点 global 算错（v 选错行 / Hook 时刻 `getLocationOnScreen` 与 child.getTop() 不同帧）；H-b rv 非真正直接父容器（`xi>*>list` 中 `*` 才是 direct child，或存在 header 包裹层）。两者都指向同一结论：**坐标法在本 Hook 点不可靠**。

---

## 4. 必答 3：g0 的 h9 是否独立源 / +1 是否 positionOf 链偏位

【源码确定：完整微信菜单构建链（反汇编 `_chatroom4_dis.txt` + 菜单文档 §2.1 逐条可复核）】
```
View.performLongClick
  └→ wg4/a.onItemLongClick (AdapterView.OnItemLongClickListener)
       ① r3.clear()
       ③ new AdapterView$AdapterContextMenuInfo(view, pos, id)   ← pos 由系统 AdapterView 按 touch 算
       ④ onCreateContextMenu(r3, view, info)
            └→ 消息策略 g0(r3, view, info, h9)                    ← 第4参 h9 = 微信认定的被长按消息
```
- `g0` 第 2 参 `view` 是 **AdapterView 的 itemView**（onItemLongClick 的 view 参数），第 3 参 `info` 带 `view/pos/id`，第 4 参 `h9` 是微信按该 pos 取出的消息对象。

**判定 g0 是否独立源（直接回答 team-lead）**：**是独立源，且不受 FF 那条 +1 链影响**。
- FF 的 `freshRef = msgRefOf(v)` 走的是「深层 v → 上溯 itemRoot（parent===list）→ F0(`getChildAdapterPosition`) → `getItem(pos)`」，+1 只能发生在这条链。
- g0 的 h9 来自微信 `onItemLongClick` 链：pos 由系统 AdapterView 基于 touch 位置算出（`AdapterContextMenuInfo(view,pos,id)`），不是 FF 从深层 v 上溯的 F0。**两条 pos 计算路径不同**，这正是 g0 可能给出正确 pos26 图片、而 FF 给 pos27 文本的机制差异。
- 所以「g0 缓存 h9 也来自 view」这个担忧不成立：g0 的 view 是 AdapterView itemView，不是 FF 的深层 v；h9 不是由 FF 的 positionOf 解析出来的。

【真机日志确定】`g0-ref mounted classes=134 hooked=133`；`FF hook s0.g0 failed: IllegalArgumentException` 仅基类 s0 一个失败；历史 `ref = msgRefOf(v) ?: cachedRef()` 曾靠 g0 成功取 ref（`_r73_wxid_in_text_forensics.md:110`）。

【装机验收判据 · 非真机日志】实施侧建议主会话装机后看 `FF ref src=cached msgId=? kind=?`：若得 `msgId=19733550 kind=IMAGE` 即证 g0 命中图片、裁决成立。**这只是期望判据，不是已观测结果**——实施侧全程未跑 adb/装机，无任何真机日志回传。故「本轮 g0 缓存 h9 身份（19733550 图片 vs 19733551 文本）」仍为【假设待验证】，不得升级为【真机日志确定】。机制上（§4）g0 独立于 FF 的 F0 链，是「理论上能绕开 +1 的独立源」，但其本轮实际命中须等装机日志验证。

【假设待验证 / 本轮已知且接受的缺陷】
- 现有 `installG0RefHooks` **只缓存 h9（args[3]），不缓存 g0 的 view（args[1]）与 info.position（`cachedG0View` 未实施）**。team-lead 已澄清 §6.2 改动 3（同源校验）本轮暂缓：串味无真机证据，且新反汇编确认 g0 的 view 是 AdapterView itemView、与 FF 深层 v 不同源，做同 itemRoot 比对极可能误伤。故 134 宿主一次 burst 多次 g0 覆盖的串味风险本轮**已知且接受**，不得写成「已加固/已实测」。

---

## 5. 必答 4：mutex——A0MAP pos 与 F0 pos 是否同一套编号，为何 F0=27 落文本

【真机日志确定】F0(xi)=27 → `getItem(27)=19733551`(文本, vt=0)；A0MAP：pos26=19733550(图片, vt=76)、pos27=19733551(文本, vt=0)。

**判定 1：是同一套 adapter position 编号，且二者自洽。** F0 = `getChildAdapterPosition`，A0MAP 的 `i` 也是 adapter position；F0=27 与 A0MAP pos27 都指向文本 19733551。**+1 不是两套编号错位造成的。**

**判定 2：F0=27 落文本、图片在 pos26 的成因——是「冻结用的 v 与用户按住行不一致」这一族，而非编号问题：**
- 微信自己在 `onItemLongClick` 用 `new AdapterContextMenuInfo(view,pos,id)` 拿 pos（基于 touch），走 g0 取 h9；
- FF 却从「burst 最深处的 performLongClick 触发 View」(深层 `FrameLayout#2131299633`) 上溯找 itemRoot 再 F0。深层嵌套下，"最深先跑" 的 v 可能属于相邻行，或上溯的 itemRoot 与 AdapterView 认定的 itemView 不一致 ⇒ 同一触摸，FF 得 pos27、微信得 pos26。
- 【假设待验证】H3（v 属图片行但 F0 偏位）vs H4（v 本就属文本行）现有字段无法互斥：`ivCls/ivId` 各行全同（`xi`/2131299732），vt 只挂 A0MAP 不挂 cb（取证文档 §6 定论）。但**两族都指向同一裁决**：不依赖 FF 的 F0/position/坐标的独立源（方案 3）才稳。

---

## 6. 必答 5：三方案逐项裁决与唯一推荐

| 方案 | 能否修 pos26 | 关键硬伤 | 裁决 |
|---|---|---|---|
| 1 坐标法（修 scroll/padding + findChildViewUnder） | 仅当 H4 成立且几何可靠 | 命中后仍走 `msgRefOf→F0→getItem`（H3 未排除则换谁都偏）；真机 noChild；RV scrollY=0 无需修 | **放弃** |
| 2 cell 绑定数据（读 xi tag / viewholder 的 h9） | 仅当 H3 成立时理论可修 | 仍需先定位被按住 cell（靠坐标或靠 v 都继承缺陷，H4 未排除）；vh 是否直持 h9 未证实（A0MAP 的 msgId 来自 getItem）；F0 仍可能偏 | **辅助降级，不主用** |
| 3 微信菜单权威源（g0 第4参 h9，或菜单构造入口） | 覆盖 H3+H4 | 需同源校验防串味；需真机确认本轮 h9 身份 | **唯一推荐** |

**唯一推荐：方案 3，落地形态 = 把现有 g0 钩子提升为第一取源（本轮已实施）；坐标法下线（本轮已实施）；方案 2 保留为 g0 缺席时的二级降级。g0 同源校验加固本轮暂缓，串味风险已知接受。互斥，不并列主修。**

---

## 7. 可落地锚点与最小改法

### 7.1 权威入口锚点（【源码确定】，反汇编已定位）
- **主锚（取 h9）**：`com.tencent.mm.ui.chatting.viewitems.*.g0(va4/r3, android.view.View, android.view.ContextMenu$ContextMenuInfo, com.tencent.mm.storage.h9)Z`，第 4 参即 h9。134 个宿主类（`G0_HOST_CLASSES`），签名统一；`installG0RefHooks()` 已延迟挂载 133 个。上游 `wg4/a.onItemLongClick`：`r3.clear()` → `new AdapterContextMenuInfo(view,pos,id)` → `onCreateContextMenu` → `g0`。
- **显示锚（不取 h9，仅参考）**：`wg4/q.p(II)Z`（deobf `showGridMenu`），`this.y`=r3 menu，已 hook（`installNativeMenuInject`）；此处只有 Menu 无 h9，**不能作 h9 源**，只证实菜单确已构建。
- 版本：微信 8.0.48；换版本 g0 宿主类清单与 `q.p` 需重新解析（菜单文档 §风险 6）。

### 7.2 改动点（实施侧 r73-g0-impl 本轮实际交付 2/3 处；改动 1 暂缓）
1. **g0 缓存加固（防串味）——本轮暂缓，未实施**：原方案为 `installG0RefHooks` 除缓存 `args[3]`(=h9) 外同时缓存 `args[1]`(=g0 的 view) 与 `args[2]`(`ContextMenuInfo.position`)，新增 `cachedG0View`/`cachedG0Pos` 并加同源校验。team-lead 已澄清本轮暂缓：串味无真机证据，且新反汇编确认 g0 的 view 是 AdapterView itemView、与 FF 深层 v 不同源，做同 itemRoot 比对极可能误伤。**串味风险（134 宿主一次 burst 多次 g0 覆盖）本轮已知且接受**，待装机日志再评估是否需补。
2. **提升 g0 为第一取源——已实施**：`resolveRefForBurst` 优先级由 `frozen > cached > view` 改为 **`rejected → cached(g0) → frozen → view`**。g0 是微信权威且绕过 F0，优先认领；`freshRef`(frozen) 降为 g0 未命中时的降级；rejected 硬停止保持最前。
3. **坐标法下线——已实施**：`installLongPressTrap` newBurst 内已删 `resolveRefByTouch(v, param.args)` 及 `chosen/contradictory/cellImg` 分支，`burstFrozenRef = freshRef`；`resolveRefByTouch`/`TouchCell` 保留但 KDoc 标注「R73-g0 起不再在生产路径调用，仅保留供取证」；`freshRef = msgRefOf(v)` 保留（探针与降级需要）。

### 7.3 红线保持（不可动）
- 门禁 `onShowGridMenu`(:697) 与点击快照 `snapshotOf`(:1185) **仍共用 `resolveRefForBurst`**，同源不拆。
- `cachedAt >= burstStartAt` 时效、新 burst 清 `cachedG0Ref/cachedG0At`、`BURST_GAP_MS`、`BURST_TTL_MS`+`staleViewForProbe` 不动。
- `allowsViewText` 门禁表、`kindOf` 全表、IMAGE+有效 path ⇒ RESEND_FILE 不动；不硬编码 +1。
- `planFor` / 原生开关 / 原生转发链路不动。

### 7.4 验收口径（主会话装机后执行；以下为期望判据，非已观测日志）
- 主路径：长按图片 → 期望出现 `FF ref src=cached msgId=19733550 kind=IMAGE`，frozen/snap/msgsnap 三源同号同种类，转发出图片。**这是验收判据，实施侧与主会话本轮均未装机，尚未观测到。**
- 降级路径：g0 未命中（`cachedRef()==null`）→ frozen(freshRef)。若此时 freshRef 与 g0 info.position 矛盾，走 rejected/SKIP，宁可不发也不错发文本。
- 回归：历史消息（View 链正常取 h9）不被 g0 串味；对齐 R73 既有单测断言。

---

## 8. 证据索引 / 免责声明

- 【源码确定】AOSP `View.java`（performLongClick 的 x/y → mLongClickX/Y，来自 MotionEvent）、`ViewGroup.java`（findChildViewUnder 只比 child 矩形+javadoc；RV 靠 offsetChildren 滚动、mScrollY=0）。
- 【源码确定】`ForceForward.kt`：:1230-1301 坐标法、:581-625 g0 钩子、:1403-1458 取源优先级、:649-738 原生菜单注入、:641-671 锚点 `wg4.q.p`；afb544e 提交（g0 装机）。
- 【源码确定】`docs/_r73_native_menu_inject.md` §2.1：`onItemLongClick → new AdapterContextMenuInfo(view,pos,id) → g0(r3,view,info,h9)` 完整链；方案 B「能直接拿 h9 第4参做快照」；锚点 `wg4/a.onItemLongClick`、`wg4/q.p(II)Z`。
- 【真机日志确定】cb(x=244.167/y=318.522/freshMsgId=19733551/pos27)、A0MAP(pos26=19733550 图片 vt76 / pos27=19733551 文本 vt0)、TOUCH noChild(global=471,2023 / local=471,1690)、g0 mount 133/134。
- **【纠正 23:25 主会话】** 旧版本此处曾把「实施侧 `FF ref src=cached msgId=19733550 kind=IMAGE`」标为【真机日志确定】——**错误**。该行是任务书给定的**期望验收判据**，实施 lane（r73-g0-impl）明确未跑 adb/未装机，主会话此刻也未装机，不存在这条实测日志。g0 在本图片场景究竟命中图片(19733550)还是文本(19733551)仍是【假设待验证】，须装机长按图片实证。已删除该误标。
- 【假设待验证】H3 vs H4；ScrollControlRV 是否特殊重写 scroll；**g0 本场景是否命中图片 ref（19733550 kind=IMAGE）——装机验收关键**；g0 的 view 与 burstDeepest 的 itemRoot 同源关系是否加固并实测（本轮改动3暂缓，未实施）。
- 【实施侧状态 · r73-g0-impl 同步】g0 已上位为第一取源，`resolveRefForBurst` 顺序改为 `rejected → cached(g0) → frozen → view`；`resolveRefByTouch`/`TouchCell` 保留但 KDoc 标注「R73-g0 起不再在生产路径调用，仅保留供取证」，newBurst 已删其调用与触点身份校验。⇒ 坐标法状态从「待验证」更新为 **已下线、仅取证保留**。实施侧未跑 gradle/adb/commit。
- 免责：本 lane 只读，未新增抓包/探针；H-a/H-b 与 H3/H4 不硬判某一个，但都指向同一裁决；**g0 在本场景是否给出图片尚未实测（无真机日志），须主会话装机长按图片按 §7.3 验收口径实证**；串味同源校验等机制的完备性仍须回归确认（本轮改动3暂缓）。本文件不代替主会话的最终验收。
