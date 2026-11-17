# R57：从聊天返回落主列表 —— 离线取证（排除法）

日期：2026-11-17 · 设备不在线，纯静态取证（`_cache/mm.apk` + `_cache/dex/classes4.dex`）

## 现象

点分组行 → 进入聊天页（`ChattingUI`）→ 按返回，落在**主会话列表（LauncherUI）**，
而不是「折叠的群聊」页（`ConvBoxServiceConversationUI`）。此前记为「未与原生对照」。

## 取证方法与结果

### 1. `ChattingUI` 的清单声明（`aapt2 dump xmltree --file AndroidManifest.xml mm.apk`）

```
E: activity
  name = "com.tencent.mm.ui.chatting.ChattingUI"
  configChanges = 0x000004a0
  windowSoftInputMode = 0x00000030
```

⇒ **没有 `launchMode`、没有 `taskAffinity`、没有 `excludeFromRecents`**，
即 `launchMode="standard"`，taskAffinity 继承 application 默认（= 包名）。

### 2. `ConvBoxServiceConversationUI` 的清单声明

```
E: activity
  name = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
  configChanges = 0x000004a0
  windowSoftInputMode = 0x00000030
```

⇒ 同样是 standard，**没有 `noHistory` / `finishOnTaskLaunch` / `clearTaskOnLaunch`**。
（这条很关键：如果是 `noHistory`，「一失焦就 finish」就能一句话解释现象。）

### 3. `ConvBoxServiceConversationUI.finish()` 的字节码（`dexdump -d classes4.dex`）

```
invoke-super MMFragmentActivity.finish()          // 只有这一句是真的 finish
const-class  Lcom/tencent/mm/plugin/appbrand/service/t4
invoke-static d94/n0.c(Class) → check-cast t4 → check-cast zc4/c
invoke-virtual zc4/c.V8(false)                    // 小程序服务置状态，与 Activity 栈无关
```

⇒ **`finish()` 里没有 `startActivity(LauncherUI)` 之类的跳转**，
它只是顺手把 appbrand（小程序）服务标记为非活跃。

### 4. 我方启动路径（`feature/GconvPage.kt:634-638`）

```kotlin
putExtra("Chat_User", talker)
putExtra("Chat_Mode", 1)
if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)   // ctx 是列表 View 的 context ⇒ 不加
ctx.startActivity(i)
```

⇒ 与「微信自己点折叠群聊里的会话」同形：**同 task 内 push，不加 NEW_TASK**。

## 结论

| 假设 | 结论 |
|---|---|
| ChattingUI 是 singleTask / 新 task | ❌ 排除（standard，无 taskAffinity） |
| ConvBox 页 `noHistory`，失焦即销毁 | ❌ 排除（清单里没有） |
| ConvBox 页 `finish()` 主动跳回主列表 | ❌ 排除（字节码里只有 super.finish + appbrand 清理） |
| 我方 intent 带 NEW_TASK | ❌ 排除（ctx 是 Activity 时明确不加） |

⇒ 剩下两个候选，都需要真机才能定：

1. **宿主在 `ChattingUI` 启动时把 ConvBox 页 finish 掉**（`BaseConversationUI` 层）；
2. **返回键走宿主的 back 处理**（`MMActivity` / `LauncherUI` 的返回栈归位逻辑）。

## 下一步（真机，两步就能定生死）

**先做对照，再决定要不要修**：

1. 真机打开微信原生「折叠的群聊」页（设置 → 通用 → 折叠的群聊），点里面一个会话 → 返回。
   - 若**原生也落主列表** ⇒ 这是微信自身行为，我们的路线 E 与原生一致，**本条关闭，不算 bug**；
   - 若原生回到折叠页 ⇒ 才需要继续查「谁 finish 了 ConvBox」。
2. 若需要查：Hook `Activity.finish`（或 `ActivityManager` 的 `finishActivity`）打一条带栈的日志，
   看返回前后 ConvBox 实例是被**谁**结束的。

⛔ 不要在拿到第 1 步结论前改启动 flag —— 现有 flag 已与原生同形，改它等于引入新变量。
