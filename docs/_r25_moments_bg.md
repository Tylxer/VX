# R25 · 朋友圈后台化 —— 定时刷新 / 自动加载不依赖 UI，新动态自动导出

> 需求原话：
> ①「定时刷新朋友圈和自动加载朋友圈动态在后台定时执行，不需要我进入朋友圈或者进入模块功能栏。」
> ②「自动导出朋友圈动态是我设置导出名单后该好友的新动态被捕捉到都会保存。」

## 1. 旧实现的病根

`MomentsAuto.doRefresh` 原本是这样刷新的：

```kotlin
val i = Intent().setClassName(ctx, cls.name)   // cls = ImproveSnsTimelineUI
i.addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT)
ctx.startActivity(i)
```

三个副作用，条条都撞用户诉求：

| # | 现象 | 根因 |
|---|---|---|
| 1 | **必须先手动进一次朋友圈**，否则永远不刷新 | `timelineCls` 只能靠 `ActivityLifecycleCallbacks` 在 `onActivityResumed` 里捕获；没进过就是 null，`doRefresh` 直接 return |
| 2 | **一到点就把朋友圈顶到前台** | `startActivity` + `NEW_TASK` 的本质就是拉前台，无论用户在干什么 |
| 3 | 失败一次就整体 `launchDisabled` | 起 Activity 是"系统看不懂意图"的操作，只能一刀切禁掉，诊断入口也一起被挡 |

第 2 条尤其致命：用户要的是「后台定时」，得到的却是「定时抢屏幕」。

而且这两条凑在一起，让"设了自动导出却总是不导出"从**逻辑 bug** 变成**看起来像 bug 的正常行为** ——
数据压根没拉回来，导出自然没新东西可写。

## 2. 解法：绕开 UI，直接投递微信自己的同步 NetScene

新增 `core/MomentsSyncTrigger.kt`。核心是三行：

```kotlin
val scene = x2ctor.newInstance(256)              // NetSceneSnsSync(scene=256)
val w = y70.k1.n()                                // 静态方法，返回 y70/w
val queued = getObjectField(w, "b")               // modelbase/p1
    .let { fMethod.invoke(it, scene) as Boolean } // p1.f(scene)
```

### 取证链（classes3.dex，纯静态取证，无一个是猜的）

```
Lcom/tencent/mm/plugin/sns/model/x2;
  super        = com.tencent.mm.modelbase.k1      ← NetScene 基类
  interfaces   = com.tencent.mm.network.j0
  <init>(I)V   ← 唯一构造，单 int 参数（语义 = scene）
  getType()    = 214
  CGI          = "/cgi-bin/micromsg-bin/mmsnssync"（<init> 内 const-string 写死）
```

唯一生产调用点 —— **微信自己的事件监听器，不是 UI**：

```
l93/q4.callback:(Lcom/tencent/mm/sdk/event/IEvent;)Z   super = com.tencent.mm.sdk.event.n
  002e: iget-object v9, v9, SnsSyncEvent.g:Lgj/ru;
  0030: iget         v9, v9, Lgj/ru;.a:I
  0032: const/4  v2, #int 2
  0034: const/16 v7, #int 256                 ★ scene = 256
  0074: new-instance v2, x2
  0076: invoke-direct {v2, v7}, x2.<init>:(I)V
  0079: invoke-virtual {v9, v2}, modelbase/p1.f:(modelbase/k1;)Z
```

**为什么敢认定 scene=256**：这个方法里 `v7` 只有 `const/16 v7,#256` 一处赋值，
而 `event.g.a == 2` 与否的两条分支走到 `new x2(v7)` 时 `v7` 恒为 256。
另外全 APK 逐个 dexdump 确认：**只有 classes3.dex 有 `x2.<init>` 调用**，
其余 dex 零命中 ⇒ 这就是微信标准的「朋友圈同步」入口，不是冷门旁路。

投递链路（同一份字节码）：`y70.k1.i()` → `y70.k1.n()` → `y70/w.b` → `p1.f(k1)Z`

⛔ **两个易错点（都从字节码直接可证）**
- `n()` 是 **static**（`invoke-static {}` 的寄存器列表为空）。按实例方法调会 `NoSuchMethodError`。
- 是 **`f()` 不是 `g()`**。`FileSender` 里那条 `modelbase/p1.g` 用在发送消息场景，别照抄到这里。

## 3. 两个开关的接线调整

`MomentsAuto.onTick` 的守卫从 `if (!c.autoRefresh) return` 放宽到：

```kotlin
if (!c.autoRefresh && !c.autoLoad) return
```

理由：用户视角里「定时刷新」和「自动加载」都是「模块帮我把朋友圈拉进来」，
看不出也不该关心底下分了两个开关。既然拉新已后台化，两个开关任一开启都该跑。

⚠️ 两者**共享同一个 `lastRefreshAt` 节拍**，autoLoad 在这里不额外多跑一次，
它只是放宽了「什么情况下允许跑」。autoLoad 真正独立的部分仍在原分支：
用户打开朋友圈时帮他继续往下滚。

## 4. 「新动态被捕捉到就保存」——导出侧联动

导出本来就有 5 分钟巡检 + 10 分钟最小间隔 + 按文件名里的 snsId 去重，
**缺的不是去重，是时机**：最坏情况新动态要等 10 分钟才落盘。

加 `MomentsExport.pokeExport()`：后台同步刚投递成功时把它唤醒，
`POKE_MIN_INTERVAL_MS = 60s` 节流，把等待从"最多 10 分钟"压到"分钟级"。

三条安全边界，缺一条就会变成刷机器的电风扇：
1. **必须 `autoExport` 开着** —— 用户没开自动导出就绝不替他导出；
2. **60s 节流** —— 同步会反复触发，每次都唤醒等于把间隔塌到零；
3. **不替代定时 tick** —— 两条路径互为兜底，tick 里原有的 `exportDue` 判据没动。

重复导出本身是幂等的（`scanExportedIds` 去重），节流纯粹为了省电省 IO。

## 5. 真机验收（2026-10-25，设备 467cf5bc）

**整个过程中一次都没打开过朋友圈。**

```
op=msync sub=stat
  MSYNC ready=true ok=1 failed=0 rcv=1 watching=false disabled=-

op=msync sub=watch        → MSYNC watch on window=60000ms method=onGYNetEnd/6
op=moments sub=refresh    → MSYNC OK scene=256 type=214 cgi=mmsnssync
                            MOMENT refresh bg-sent MSYNC OK scene=256 ...
                            MOMENT poke reason=bg-sync
                            MSYNC onGYNetEnd errType=0 errCode=0 errMsg=''
                            MOMENT export done rows=7 written=0 skipped=7 listMiss=0 err=0
```

**判据解读**

| 日志 | 证明什么 |
|---|---|
| `ready=true disabled=-` | 反射链在真机微信上确实存在，静态取证正确 |
| `MSYNC OK scene=256` | `p1.f()` 返回 true，NetScene 入队成功 |
| **`onGYNetEnd errType=0 errCode=0`** | ⭐ **请求真的走了网络并被服务端正常处理** —— 这是唯一能证明"不是只入了个队"的判据 |
| `poke reason=bg-sync` | 导出被同步事件唤醒 |
| `rows=7 written=0 skipped=7` | 导出跑完，幂等去重生效（没重复写文件） |

⚠️ `written=0` 不是失败：此时朋友圈确实没有名单内的新动态，
7 行全部命中"已导出过"被跳过。**要看这条链路是否真的会产生新文件，
需要一位好友新发一条朋友圈后再观察** —— 这一步留给用户在日常使用中确认。

## 6. 诊断探针

```bash
# 句柄与计数（ready= 必须为 true，disabled= 必须为 -）
adb shell am broadcast -a com.lx.wxgroup.action.PROBE -p com.tencent.mm --es op msync --es sub stat
# 立刻投一次（force，绕过 disabled 闸门）
... --es op msync --es sub sync
# 挂临时钩子监听回包，60s 后自动摘 —— 验证「是否真走了网络」的唯一手段
... --es op msync --es sub watch
... --es op msync --es sub unwatch
```

⚠️ **`f()=true` ≠ 数据已同步**。队列满/离线/被去重都会让它返回 false，
而返回 true 之后也可能中途丢弃。要确认真的拉到了，看 `onGYNetEnd errType/errCode`，
再看导出目录有没有新文件。

## 7. 遗留

1. **`x2(256)` 抓的是最新一批，不是翻历史**。`doScene` 里请求体取自全局单例
   `hl0/x1.a.a()`，想让「自动加载」在后台翻更多**历史**动态，得再取证那条分页链路。
   目前进朋友圈时的自动滚动仍然有效，两条互补。
2. **失败后的自动退化策略已定：不退**。反射链一旦失效，自动轮安静停住
   （`disabled` 记录原因），**不回退成「拉 Activity」** —— 抢前台的代价远大于这一轮没刷成。
3. `load=true` 单独开启现在也能触发后台同步，但 UI 滚动部分仍需用户打开朋友圈。
