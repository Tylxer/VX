# R21 · 群发「选择发送目标」搜索框不弹输入法

状态：**代码已按判据改完，待真机验证**（本文件不声称已修复）
改动范围：`ui/Panel.kt`、`ui/UiKit.kt`、`feature/PickerPage.kt`、`feature/BatchPicker.kt`、
`feature/ForwardPicker.kt`、`feature/GroupDetailPage.kt`

---

## §1 根因：Dialog 窗口带 `FLAG_ALT_FOCUSABLE_IM`

### 1.1 实测证据链（全部来自 `dumpsys`，可复现）

| 步骤 | 结果 |
|---|---|
| `uiautomator dump` 找 EditText | `<node class="android.widget.EditText" focusable="true" focused="false" bounds="[150,734][1290,804]" hint="搜索" />` |
| `input tap 720 769` 点它 | **`focused="true"`** ← 焦点**已经拿到了** |
| `dumpsys input_method` | **`mInputShown=false`**、`mIsInputViewShown=true` |
| 微信进程（`mUid=10363`）的 ClientState | **`sessionRequested=false`、`curSession=null`** ← 系统从未收到 IME 会话请求 |
| `dumpsys window windows` 取 `Window{d2b97fb u0 群发}` | **`fl=DIM_BEHIND ALT_FOCUSABLE_IM SPLIT_TOUCH HARDWARE_ACCELERATED`** |
| 同窗口 `mAttrs` | `sim={adjust=resize}` ← **已经生效** |

### 1.2 结论

**`FLAG_ALT_FOCUSABLE_IM`**。AOSP 语义：窗口带这个 flag ⇒ 进入「替代焦点模式」(alternative
focus mode)，**IME 不会被自动拉起**（框架认为该窗口不需要文字输入，典型是模态确认框）。
`AlertDialog` 骨架默认就带它，而 `Panel` 的搜索框长在这个 Dialog 里 ⇒ 恒不弹。

即便控件 `focused=true`，`InputMethodManager.showSoftInput()` 在替代焦点模式下也不会
建立起 `ClientState.sessionRequested`，所以 `dumpsys input_method` 里永远看不到会话请求。

### 1.3 已排除的方向（不要再试）

| 方向 | 为什么不成立 |
|---|---|
| 补 `requestFocus()` | 实测 `focused="true"` 早就成立，焦点从来没丢 |
| 改 `UiKit.widen` 的 `SOFT_INPUT_ADJUST_RESIZE` | `mAttrs` 里 `sim={adjust=resize}` 已生效，`UiKit.widen` 起作用了 |
| 怀疑输入法坏了 | `mIsInputViewShown=true`、`Window{59c4c00 u0 InputMethod}` 存在 |
| 怀疑 ListView 抢焦点 | `mServedView` 是微信自己的 `MMEditText`，那是宿主另一个输入框，与本 Dialog 无关 |
| 改 `Panel` 的返回键逻辑 | 与 IME 无关 |

---

## §2 改动清单

### 2.1 `ui/Panel.kt` — 清掉 `FLAG_ALT_FOCUSABLE_IM`（核心修复）

**行 10**：新增 import

```diff
 import android.view.KeyEvent
 import android.view.View
 import android.view.ViewGroup
+import android.view.WindowManager
```

**行 176-184**（`openInternal()` 内，`create()` 之后、`show()` 之前）

```diff
         dialog = d
+        // ⛔ R21：AlertDialog 骨架默认带 FLAG_ALT_FOCUSABLE_IM（= 替代焦点模式），
+        // AOSP 语义下该模式下 IME 不会被自动拉起 —— 搜索框能拿到焦点（实测 focused=true）
+        // 但输入法永远不弹（实测 mInputShown=false / sessionRequested=false）。
+        // 这个 flag 一清，**所有** Panel 页面都受影响，不只是搜索框 —— 这是有意的。
+        runCatching {
+            d.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
+        }.onFailure { LogX.w("Panel clearFlags ALT_FOCUSABLE_IM failed: ${it.javaClass.simpleName}") }
         d.show()
         UiKit.widen(d, c)
```

放在 `show()` 之前是**必须的**：`Dialog.getWindow()` 在 `create()` 之后即非空，
`clearFlags` 改的是 `mWindowAttributes`，窗口在 `show()` 时才真正 addView 到 WMS，
这样下发上去的 `fl=` 从一开始就不含 `ALT_FOCUSABLE_IM`，不会有一次「先带 flag 再加窗」的竞态。

---

### 2.2 `ui/UiKit.kt` — 新增 `bindSoftInput` 助手

**行 15**：新增 import

```diff
 import android.view.WindowManager
+import android.view.inputmethod.InputMethodManager
 import android.widget.EditText
```

**行 149-177**（插在 `toast()` 之后、`runOnMain()` 之前）

```kotlin
fun bindSoftInput(edit: EditText): EditText {
    val kick = { v: EditText ->
        runCatching {
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(v, 0)
        }.onFailure { LogX.w("UiKit showSoftInput failed: ${it.javaClass.simpleName}") }
    }
    edit.setOnClickListener { v -> kick(v as EditText) }
    edit.setOnFocusChangeListener { v, hasFocus -> if (hasFocus) kick(v as EditText) }
    return edit
}
```

设计要点：

- **不改任何原有属性**：只挂 `setOnClickListener` / `setOnFocusChangeListener`，
  四个调用点原来的 hint / inputType / maxLines / textSize / setText / setSelection /
  setTextColor / setHintTextColor / setPadding / background / layoutParams 全部原样保留。
- **不调 `requestFocus()`**：根因已证明焦点本来就拿到了（`focused=true`），
  再 requestFocus 既无益也会扰动宿主原有焦点顺序。这里只补 `showSoftInput`。
- 标志位用 `0` 不用 `SHOW_IMPLICIT`：`SHOW_IMPLICIT` 只是「非用户直接发起」的语义提示，
  对微信这种系统已认定在前台的宿主没有实际差别；`0` 兼容面最广，且不必为 API 级别加分支。
- 返回 `edit`，便于包在构造外面做链式，不必改成在 `.apply{}` 里再加一行（少一处漏改风险）。
- 全部 `runCatching` + `LogX.w` 留痕，与同文件既有风格一致。

### 2.3 四个调用点（只包一层，不改任何属性）

统一形态：`val x = UiKit.bindSoftInput(EditText(ctx).apply { ...原样... })`

| 文件 | 改动前行号 | 改动后行号 | 用途 |
|---|---|---|---|
| `feature/PickerPage.kt` | 68 `val search = EditText(ctx).apply {` | **70** `val search = UiKit.bindSoftInput(EditText(ctx).apply {` | 群发「选择发送目标」，本次报障入口 |
| `feature/BatchPicker.kt` | 79 `val search = EditText(ctx).apply {` | **80** 同上 | 批量加群成员 |
| `feature/ForwardPicker.kt` | 111 `searchBox = EditText(ctx).apply {` | **112** 同上 | 转发目标选择 |
| `feature/GroupDetailPage.kt` | 100 `val search = EditText(ctx).apply {` | **101** 同上 | 群成员搜索 |

四处 `apply {}` 块内部逐一字段核对过，**一行未动**；仅把构造包进 `bindSoftInput(...)`
并把右括号从 `}` 改成 `})`，另各加一行 R21 注释。

### 2.4 自查结果

```
$ grep -c "FLAG_ALT_FOCUSABLE_IM" ui/Panel.kt
2          # 注释 + clearFlags 调用，命中 ✓

$ grep -rho "bindSoftInput" . | wc -l
5          # 1 定义（UiKit）+ 4 调用 ✓

$ grep -rc "bindSoftInput" . | grep -v ":0"
ui/UiKit.kt:1  feature/PickerPage.kt:1  feature/BatchPicker.kt:1
feature/ForwardPicker.kt:1  feature/GroupDetailPage.kt:1        ✓
```

**未跑 gradle**（构建由主会话串行执行），**未碰 adb / 任何设备命令**。
改动文件全部落在允许的 6 个之内，未触 `core/` `data/` `entry/` `probe/` `tools/` 与
`SnsGuard/Moments*/MassSend*`。

---

## §3 为什么不能用两种常见替代方案

- **补 `requestFocus()`**：实测点完搜索框节点已是 `focused="true"`，焦点从头到尾没丢；
  再 requestFocus 只会在宿主焦点链里多一次无意义抢占，不能把 `sessionRequested`
  变 true，也解释不了 `mInputShown=false`。
- **改 `UiKit.widen` 的 `SOFT_INPUT_ADJUST_RESIZE`**：`dumpsys window` 里本窗口
  `mAttrs` 已是 `sim={adjust=resize}`，说明 `widen` 早就生效；`adjust*` 只决定键盘
  弹出后窗口**怎么让位**，不负责**决定弹不弹**，弹不弹由 `FLAG_ALT_FOCUSABLE_IM`
  的替代焦点模式规则控制。

---

## §4 回归影响面

### 4.1 清 flag 是全局的（有意为之）

`Panel` 是模块**唯一**的弹窗容器（K1 设计），所有 `PanelPage` 共用同一个
`AlertDialog`。所以这处 `clearFlags` 影响**全部**页面，不只是本次报障的搜索框。

会变的行为：**任何** Panel 页面里用户点到可输入 `EditText`，系统都会正常建立 IME
会话并弹键盘（改前是全部一律不弹）。

### 4.2 逐项核对

| 页面 | 含 EditText | 预期变化 | 怎么核 |
|---|---|---|---|
| 主面板 / 各开关页 | 否 | 无变化（替代焦点模式只影响 IME） | 开关各点一遍，行为与改前一致 |
| `PickerPage`（选择发送目标） | 是 | 点搜索框弹键盘 | 本次报障场景 |
| `BatchPicker`（批量加成员） | 是 | 同上 | 进页面点搜索框 |
| `ForwardPicker`（转发选择） | 是 | 同上 | 进页面点搜索框 |
| `GroupDetailPage`（群成员搜索） | 是 | 同上 | 进页面点搜索框 |
| `UiKit.input()` 单行输入 | 是 | **同样受益**：那也是 AlertDialog 骨架，改前新建分组时键盘同样不弹 | 新建分组 → 点输入框 |
| `UiKit.confirm()` 纯确认 | 否 | 无变化 | 弹一次，看布局没被顶乱 |

### 4.3 潜在风险点（本 lane 未处理，仅供主会话留意）

1. **键盘顶起布局**：`widen()` 已设 `SOFT_INPUT_ADJUST_RESIZE`，Panel 内容区按
   `screenHeight * 0.62` 定高，键盘弹出后可能被压缩。这是预期行为（内容可滚动），
   若真机上搜索框被顶出可视区，属独立问题。
2. **关面板时 IME 是否残留**：`closeInternal()` 只 `dismiss()`，未显式
   `hideSoftInput`。正常 Dialog dismiss 会随窗口销毁收键盘；若验收发现「关面板后
   键盘不走」，是独立问题，需另开 lane。
3. **`setOnFocusChangeListener` 覆盖**：四个调用点改动前都没设过焦点监听
   （已 `grep OnFocusChange` 确认），不存在覆盖宿主既有回调的风险。

---

## §5 真机验收判据

⛔ **「截图看着弹了」不算判据。**
⛔ **`focused=true` 也不算** —— 改之前就是 true，无法区分修好与否。

必须同时满足：

```bash
# 1) 群发 → 选择发送目标 → 点搜索框，然后：
adb shell dumpsys input_method | grep -E "sessionRequested|mInputShown"
# 期望：sessionRequested=true（改前 false）、mInputShown=true（改前 false）
# 微信进程（mUid=10363）的 ClientState 里 curSession 非 null（改前 null）
```

```bash
# 2) 窗口 flag 断言：
adb shell dumpsys window windows | grep -A2 "群发"
# 期望：fl= 中不再出现 ALT_FOCUSABLE_IM；DIM_BEHIND 仍在属正常
```

补充判据（建议一并核）：`PickerPage` / `BatchPicker` / `ForwardPicker` /
`GroupDetailPage` 四处搜索框各点一次，`sessionRequested` 都应为 true；
`UiKit.input()`（新建分组）也应能弹键盘。

---

**本轮只做代码改动，未做任何真机验证。措辞：代码已按判据改完，待真机验证。**

[DONE]
