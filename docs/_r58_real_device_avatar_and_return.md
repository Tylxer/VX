# R58 真机取证：分组行头像 + 从聊天返回落点（2026-11-17 21:0x）

> 设备 `467cf5bc`（PJD110），微信 8.0.48（2588 GP），模块 APK 21:07 装、双次冷启。
> `chat.gconv_enabled = 1`（本轮实测期间曾临时置 0 做对照，已还原为 1）。

## 一、R57 的 2×2 拼图头像：代码正确，但当前配置下永远不生效

### 1.1 真机现象

主会话列表分组行（`wxgrp_...0099`，昵称「123」）头像是**单张**（灰色默认人形 / 单个联系人头像），
9 个可见行逐个放大确认，**没有任何 2×2 拼接缝**。

### 1.2 根因（两条，必须都看）

**(a) 当前唯一的分组只有 1 个成员 —— 拼图判据本身就没满足。**

新增只读探针 `op=groups`（`EntryReceiver.kt`）实测：

```
GROUPS count=1
GROUPS id=153 name=123 members=1 first=[wxid_6wjz2ehi0i9x22]
```

`AvatarGridLogic.order()` 的契约是「成员 <4 ⇒ 返回空列表 ⇒ 回落单头像」，
所以真机显示首字「张」/ 单头像**完全符合设计**。R57 代码没有 bug，是**没有数据可拼**。

**(b) 更关键：`gconvEnabled=true` 时分组行根本不走 `GroupRowView`。**

`ConvFilter.kt` 约 1332 行：

```kotlin
if (groups.isEmpty() || gconvEnabled) {
    memberSets = emptyMap()
    models = emptyMap()
    lastSpecs = emptyList()
    ConvLayout.build(snaps, emptyList(), emptyMap(), enabled = false)   // passthrough
}
```

gconv 开着 ⇒ 走 passthrough，**不插自绘分组行**，由微信原生渲染库里的 `wxgrp_` 伪会话行
（R54 的数据层）。`GroupRowView.bind` / `bindAvatar` / `bindAvatarGrid` 在这条路径上
**一次都不会被调用** ⇒ 拼图不可能出现，与成员数无关。

> 关掉 `gconv=0` 做对照时，顶部确实出现自绘分组行（绿底「张」首字），
> 说明自绘路径本身工作正常 —— 它是首字兜底，因为该组只有 1 人。

### 1.3 结论 / 待决

- R57 的实现对「`gconv=0` 的自绘行路径」有效，但**用户当前配置（gconv=1）下看不到任何效果**。
- 要在 gconv 路径下也显示拼图，必须动**微信原生会话行的头像**（伪会话 `wxgrp_<gid>` 的头像来源），
  属另外一层工作（Pro 类，需先取证伪会话头像的读取链路）。
- 验收拼图本身需要一个 **≥4 人** 的分组；当前库里没有，需用户建一个或临时插成员（未做，避免污染数据）。

## 二、从聊天返回落主列表：确认是我们的 bug（原生对照已定案）

### 2.1 我方路径（复用 ConvBox）

| 时刻 | task 栈（`dumpsys activity activities`） |
|---|---|
| 进聊天后 | `Hist#2 ChattingUI` / `Hist#1 ConvBoxServiceConversationUI` / `Hist#0 LauncherUI`，sz=3 |
| 按一次 back | sz=**1**，只剩 `LauncherUI`；ChattingUI 记录变 `t-1`，**ConvBox 同时消失** |

### 2.2 原生对照（同机同版本，微信自带入口）

`通讯录 → 群聊（ChatroomContactUI）→ ChattingUI`：同样 3 层栈（sz=3）。

按一次 back ⇒ 落 **`ChatroomContactUI`**，sz=**2**，上一页保留。

### 2.3 结论

**不是微信 ChattingUI 的固有行为**（原生会回上一页），是我们这条链上多了一次
`startActivity(LauncherUI, FLAG_ACTIVITY_CLEAR_TOP)`。

**决定性证据（`logcat` 对比，同机同版本）**

我方路径（ConvBox → ChattingUI，back 后）：
```
I ActivityTaskManager: START u0 {flg=0x4000000 xflg=0x4 cmp=com.tencent.mm/.ui.LauncherUI
                                  (has extras) mCallingUid=10363} with LAUNCH_SINGLE_TOP
W ActivityTaskManager: Duplicate finish request for ActivityRecord{… ChattingUI t2854 f}
```
`flg=0x4000000` = **`FLAG_ACTIVITY_CLEAR_TOP`** ⇒ AMS 把 LauncherUI 之上所有 activity
（= ConvBox）一起清掉。**这条不走 Java 层 `Activity.finish()`**，所以钩子抓不到 —— 这也解释了
为什么 `GCONV_FIN` 只抓到 `self=ChattingUI`，一次 `self=ConvBoxServiceConversationUI` 都没有。

原生路径（ChatroomContactUI → ChattingUI，back 后）：
```
D ActivityClient: activity finished by caller: android.app.Activity.finish:7644 …
D ActivityThread: ComponentInfo{com.tencent.mm/…ChatroomContactUI} checkFinished=false 2
D Transition: finishTransition ar ActivityRecord{… ChatroomContactUI} state=RESUMED
```
**没有任何 `START u0 … LauncherUI`** —— 只有普通 `Activity.finish()`，上一页重新显示。

**已排除的假设（本轮实测，不是推断）**
- 不是 `noHistory`：清单里 `ConvBoxServiceConversationUI` 只有
  `name` / `configChanges=0x4a0` / `windowSoftInputMode=0x30` 三个属性。
- 不是 ConvBox 自己 finish：`ConvBox` 类只声明 `<init>/finish/onCreate`，没有 onStop/onPause；
  `ConvBoxServiceConversationFmUI.onPause` 只打日志 + `i1.N("@placeholder_foldgroup", …)` + `super.onPause()`。
- 不是「聊天期间自己消失」：进 ChattingUI 后**逐秒 dump 连续 8 次**，ConvBox 一直稳定在
  `Hist#1`、`state=STOPPED finishing=false`。
- 不是我方代码发的：全仓 `grep LauncherUI / CLEAR_TOP` 只有
  `ChatOpener`（打开聊天用，不跳主列表）和 `ConvFilter.hookBackExit`（只吃「筛选态」的返回键，
  在 `currentGroupId()==NONE` 或不在会话列表时直接放行）。
- 时间线：back 后 **0.2s** 时 ConvBox 已从 task 消失，而 ChattingUI 还在（退出动画中）
  ⇒ 是 CLEAR_TOP 的连带清理，不是两次独立 finish。

⇒ 下一步唯一未知：**谁发的这次 `startActivity(LauncherUI, CLEAR_TOP)`**，以及它凭什么条件判断。

### 2.4 元凶已抓到（`satrace` 钩子，21:46:26）

钩 `Activity.startActivity` / `startActivityForResult` 后按返回，抓到：

```
GCONV_SA hit m=startActivityForResult self=ChattingUI
  comp=com.tencent.mm.ui.LauncherUI flags=0x4000000 clearTop=true
  extras={ReportKey.CommonReportObjKey=[B@…,
          KEY_HOME_PAGE_CLS=com.tencent.mm.ui.chatting.ChattingUI,
          key_intent_action_uic_list=[…RevokeChattingLandingPageUIC]}
  栈: …<-VASLauncher.startActivityForResult<-ChattingUI.startActivityForResult
      <-a.b<-FragmentActivity.startActivityFromFragment
```

对照（我方打开 ConvBox 那次，21:45:52）：
```
self=LauncherUI comp=ConvBoxServiceConversationUI flags=0x0 clearTop=false
extras={…, KEY_HOME_PAGE_CLS=com.tencent.mm.ui.LauncherUI, Contact_User=gconv_view:0000000000000099}
```

**读法**：
- 发起者是 **`ChattingUI` 自己**（不是 ConvBox、不是我方代码），走
  `FragmentActivity.startActivityFromFragment` ← ChattingUI 的某个回调 `a.b`。
- `flags=0x4000000` = `CLEAR_TOP` ⇒ AMS 把 LauncherUI 上的 ConvBox 清掉。
- **`KEY_HOME_PAGE_CLS` 的值是 `ChattingUI` 自己**，而正常场景（LauncherUI 打开 ConvBox）
  这个键是 `LauncherUI`。**ChattingUI 把自己当成了「主页」**，因此返回时选择
  「CLEAR_TOP 回主页」而不是普通 finish。

**待证的最后一步**：我们启动 ChattingUI 时传的 extras 是否缺少
「让 ChattingUI 知道真正主页是 LauncherUI」的信息（`KEY_HOME_PAGE_CLS` 很可能就由
`VASLauncher` 在启动时写入，而我们是直接 `ctx.startActivity`）。

### 2.5 定案：缺的是 `finish_direct=true`（已修复并双向验证）

把 `satrace` 放宽到 `ChattingUI` 后对比**启动**那一刻的 extras（两边 `home` 都是 `<null>`，
所以问题不在 `KEY_HOME_PAGE_CLS`）：

| extra | 原生 `ChatroomContactUI` | 我方 `GconvPage.onRowClick` |
|---|---|---|
| `Chat_Mode` | 1 | 1 |
| `Chat_User` | `2218951006@chatroom` | `wxid_…` |
| `chat_from_scene_for_group_chats` | **4** | — |
| `specific_chat_from_scene` | **10** | — |
| `finish_direct` | **true** | — |

**只补 `finish_direct=true`**（`GconvPage.onRowClick` 的 intent），另外两个 scene 键先不动
（更可能是埋点/动效，不参与返回决策）。真机结果：

| | 返回落点 | `START … LauncherUI` 次数 |
|---|---|---|
| 补之前（21:46） | `LauncherUI`（ConvBox 被清） | 1（CLEAR_TOP） |
| 补之后（21:52，连续 2 轮） | **`ConvBoxServiceConversationUI`** | **0** |

第二轮栈：`Hist#1 ConvBox / Hist#0 LauncherUI`，ChattingUI 正常移除、ConvBox 保留
—— 与原生 `ChatroomContactUI` 的行为一致。

⇒ **根因**：`ChattingUI` 在 `finish_direct` 缺省时把自己当成「主页」，
返回时用 `CLEAR_TOP` 回 `LauncherUI`；带上 `finish_direct=true` 后走普通 finish，
上一页（ConvBox）自然保留。

### 2.6 副产品：`gconv=0` 时伪会话重复显示（已修）

`ConvLayout.build` 的 `owner == null -> 原样保留` 分支会把 `wxgrp_<gid>` 伪会话当普通会话输出，
列表里同一个组出现两次（顶部自绘「123」+ 下方灰色人形「123」）。已在循环最前面加
`GroupConvPlan.isOurs(wxid) -> Unit`。`gconv=1` 走的是 passthrough 分支，不受影响。




## 三、本轮新增的工具与探针

| 东西 | 用途 |
|---|---|
| `op=groups` | 只读列出所有分组：`id / name / members=<人数> / first=<前4个wxid>`。用来判断「拼图没出现」是数据不够还是代码没生效 |
| `tools/_ui.py` | `back / key / tap / swipe / shot`，走 `subprocess` 调 adb |
| `tools/_ui_dump.py` | 只列**有文字**的节点（`dev.py ui` 只能按文本过滤，看全貌不便） |

### ⛔ 两个工具坑（都在本轮踩到）

1. **Git Bash 会改写 adb 的 `/sdcard/...` 参数**：`adb pull /sdcard/ui.xml` 变成
   `failed to stat remote object 'F:/Program Files/WorkBuddy/.../sdcard/ui.xml'`。
   在 bash 里手工拼 adb 命令时，remote 路径必须避免被转换；**走 python `subprocess` 不受影响**
   （`tools/dev.py` / `tools/_ui.py` 都是 subprocess，所以它们能用，手工命令不能用）。
2. **`uiautomator dump` 失败时 adb 不报错**：`pull` 会静默拿到**上一次**的 XML，
   现象是「界面明明变了，dump 出来还是老内容」。`tools/_ui_dump.py` 已改为先 `rm -f` 远端文件、
   并校验输出含 `dumped to`，否则直接报 `dump 失败`（微信界面实测仍有约一半概率 dump 失败，
   这时改用截图）。
