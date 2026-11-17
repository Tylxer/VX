# R59 分组行头像定案：从 2×2 拼图改为「最后发消息人的头像」（2026-11-17 22:1x）

> 用户在工作途中明确改向：**不要拼图，显示最新消息那个人的头像**。
> R57 的拼图整套（四格骨架 / `AvatarGridLogic` / 7 条单测）已**物理删除**，
> 不是「留着不调用」。全量单测从 46 类/932 条降为 **45 类/925 条，0 失败**。

## 一、两条渲染路径，必须分开看

| 路径 | 分组行由谁渲染 | 头像来源 |
|---|---|---|
| `gconv=0`（回滚/自绘） | `GroupRowView`（模块自绘） | **本来就是** `lastSenderWxid` 的单头像，R59 只是把 R57 的拼图分支删掉，恢复原状 |
| `gconv=1`（用户主路径） | 微信原生渲染 `wxgrp_<gid>` 伪会话行 | 原来显示微信默认群头像（灰人形）⇒ **R59 新增 `NativeConvAvatar`** |

## 二、`NativeConvAvatar`（新文件）的取证与实现

### 2.1 头像取图链（dexdump 字面证据，8.0.48）

`_cache/_s9_classes7_dis.txt`：

```
69a798: invoke-static {}, Lcom/tencent/mm/modelavatar/a1;.z8:()Lcom/tencent/mm/modelavatar/x;
69a7a0: invoke-virtual {v0, v2}, Lcom/tencent/mm/modelavatar/x;.i:(Ljava/lang/String;)Landroid/graphics/Bitmap;
```

链：`p3.getView`（classes4 会话 adapter）→ `setImageDrawable(n3.g, …)` → `g41/e.draw()` →
`f41/l.H8(username)` → **`com.tencent.mm.modelavatar.x.i(String):Bitmap`**（AvatarStorage 内存查表）。

hook 这**一个点**：`beforeHookedMethod` 里 username 以 `wxgrp_` 开头 ⇒ 把 `param.result` 换成
lastSender 的头像 Bitmap；非伪会话一律放行，真实联系人头像零影响。

### 2.2 数据只能运行时现查（方向 2 不成立）

`ConvFilter.rebuild()` 在 `gconv=1` 时走 `if (groups.isEmpty() || gconvEnabled)` 分支，
`models = emptyMap()`，`buildModel` / `rebuildModels` **一次都不调用**
（ConvFilter.kt:1332）⇒「在 rebuild 时顺手记 gid→lastSender」的 Map 恒空。

所以 `NativeConvAvatar` 自己按 username 反解 gid（`GroupConvPlan.groupIdOf`），
再 `GroupDb.memberWxids(gid)` ∩ `rconversation.conversationTime` 取最大者。
口径与 `GroupConvStore.sync` / `ConvFilter.buildModel` 一致。

**性能**：主线程 hook 里只做 `ConcurrentHashMap.get` + `AvatarLoader.getCached`（纯内存）；
反解 gid / 查成员 / 读 `rconversation` 全表 / 头像解码全在单线程 `gconv-ava` 后台；
`rconversation` 快照与 lastSender 都 30s 节流复核。

### 2.3 兜底与开关

- 解不出 lastSender、或头像取不到 ⇒ **不设 `param.result`，放行微信原方法** ⇒ 保持默认灰头像。
  绝不出现白洞或色块。
- 新开关 `Keys.GCONV_AVATAR = "chat.gconv_avatar"`，`PrefKind.SWITCH` 默认 `"true"`。
  运行时 `enabled = 本开关 ∧ GCONV_MODE`，装机时读一次；`gconv=0` 时 hook 零生效。
- 探针 `op=gconv sub=ava`：只读对账，输出每组 lastSender 的时间戳、缓存命中、
  以及每个成员的 `memAvatar` / `diskAvatar`（用来区分「没解析出来」和「本人没头像文件」）。

## 三、真机验证

1. **hook 已加载**：`GCONV_AVA hooked com.tencent.mm.modelavatar.x.i enabled=true`。
2. **解析对账正确**：
   `group gid=153 … members=5 lastSender=wxid_hh2q6oufeltu22 time=1794920515663`
   与独立抓到的原生 `Chat_User=wxid_hh2q6oufeltu22` 完全一致。
3. **整条链打通**（临时把解析结果指向同组一个有磁盘头像的成员 `wxid_ompaxnosnti421`）：
   分组行头像**从灰色人形变成那只小狗的真照片**（截图 `av2crop.png`）。
   证明 hook 命中 → Bitmap 注入 → 列表刷新全部正常。验证完**已把临时写死值改回**，
   `grep ompaxnosnti421` 在源码里零残留。
4. **当前数据下的实际效果**：真实 lastSender `wxid_hh2q6oufeltu22` 自己
   `memAvatar=false diskAvatar=false`（微信内存/磁盘都没有它的头像文件）⇒
   按兜底链保持默认灰图，**这是数据问题，不是代码问题**。让该成员在微信里正常加载过头像
   （或换一个已有头像的成员成为最新发信人）即可看到效果。

## 四、顺带修的

- `ConvLayout.build` 的 `owner == null → 原样保留` 会把 `wxgrp_` 伪会话当普通会话输出，
  导致 `gconv=0` 时同一个组显示两次（自绘行 + 灰色人形行）。已在循环最前面加
  `GroupConvPlan.isOurs(wxid) -> Unit`。`gconv=1` 走 passthrough，不受影响。
- `AvatarLoader` 新增 `prefetch` / `publish`（写微信头像内存缓存 + 通知刷新），
  `q` 方法反射不到时静默退化，不改既有 `bind` 路径。

## 五、协作记录（必须如实写）

实现「gconv=1 单人头像」的 `general-purpose-1` 子代理以 **`429 额度已用尽`** 失败结束
（运行 29m15s），**没有给出回报**。所幸代码已提前落盘，主会话逐项复核：
`NativeConvAvatar.kt` + `HookEntry` / `AvatarLoader` / `Keys` / `EntryReceiver` / `GroupConvProbe`
的 diff 全部检查过，并亲自核对了它声称的 dex 地址 `69a7a0`（签名 `x.i(String):Bitmap` 一致），
随后编译、装包、真机验证。其「取证结论」一栏是主会话从源码 + dex 重建的，**不是它的回报**。

用户临时规则（本次会话，不写入文件）：**暂停模型分流，子代理统一使用父会话模型**。
此后的派发不再指定 `model` 参数。
