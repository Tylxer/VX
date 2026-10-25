# R14 真机段 · 第 2 批（2026-10-24 02:15~02:20，主会话跑）

> 与 CDN 批量下载（子代理 `cdn-trigger` 静态查 `ok0.x0#n`）**并行**跑的「其他真机验证」。
> 设备 `467cf5bc` PJD110 / 微信 2588-8.0.48 / 模块为 00:46 装的新包（含 L3 探针改动）。

## A · 朋友圈域端到端（`tools/_momentsv.py`）—— PASS

| 项 | 实测 | 判定 |
|---|---|---|
| 进朋友圈 | `act: com.tencent.mm/.plugin.sns.ui.improve.ImproveSnsTimelineUI` | ✅ |
| `MOMENT stat` | `refresh=true load=true interval=3h … timeline=ImproveSnsTimelineUI active=true` | ✅ |
| **钳位（img 探针）** | 请求 `n=999999` → `MOMENT img probe rows=7 n=50` | ✅ 钳到 50，未崩 |
| **钳位（归档 ls）** | `SNSLS archived=7 limit=50` | ✅ |
| **rv 捕获** | `MOMENT load trigger posted rv=ImproveRecyclerView` | ✅ `rv=` 字段拿得到（不再是 null） |
| **写盘通路** | `MOMENT export done rows=7 written=0 skipped=7 listMiss=0 err=0`<br>`root=/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments` | ✅ 通路无异常（`err=0`）。`written=0` 是因为 7 条**全部**被 `scanExportedIds` 判为已导出（既有文件命中），不是失败 |

⚠️ **图片侧又一个独立佐证**（与 CDN 侧 I-2 互相印证）：

```
MOMENT img #1 snsId=… user=… nick=… md5=5 picked=0 roots=5 {md5=… cand=10 picked=NONE} …
…（#1~#7 全部 cand=10 picked=NONE）
```

⇒ 每条动态**有 10 个候选路径**但一个都选不中，落盘路径解析全空。这与 CDN 侧
「`picCachePath` 恒空串、`hitPicE=7/hitPicNE=0`」是同一现象的两个观测面。

## B · 免打扰域（待办 #6）—— 部分 PASS，判据有缺口

`tools/_r5_quiet.py --nowait` + `--dnd-now` + 手工补探针。

| 项 | 实测 | 判定 |
|---|---|---|
| 注册/数据源 | `QUIET registered=true enabled=true matched=0 bridge=installed=true item=true at=true dnd=true muted=0 autoDnd=0` | ✅ 钩子装上了、`at`/`dnd` 两个数据源都在 |
| **时段判定（真判据）** | `QUIET start=02:12 end=04:17 now=139 active=true`（139 分 = 02:19，落在窗口内） | ✅ **PASS** `QuietLogic.inDndWindow` 正确 |
| 新加群 | `QUIET installed=true enabled=true baselineReady=true baseline=734 handled=5 joined=0` | ✅ 钩子存活 + 基线就绪（734 个会话）。`joined=0` 因为没有真的新加群事件 |
| 被@静音 | `matched=0`，`allow=[] exclude=[]` | ⚠️ **只到「注册就绪」**：真判据 `matched>0` 需人工构造 @ 场景（脚本 `--wait=90` 分支） |
| 群名数据源 | `QUIET GROUPNAME stat ready=false loading=false count=0 loads=0 loadFail=0 lastError=-` | ⚠️ `loads=0` ⇒ **从未尝试加载**（不是失败），属懒加载；群名相关匹配未被真机验证过 |

### 两条工具/可观测性缺口（记下来，别再踩）

1. ⚠️ **脚本判据缺失**：`tools/_r5_quiet.py` 只发 `sub=dnd sub2=status`，**没发 `sub2=window`**。
   而 `DndWindow.kt:73` 的 `status` 只打 `registered/enabled/muted/callsPassed`，**不含 `active`**；
   `active` 只在 `sub2=window`（`DndWindow.kt:80`）里打 ⇒ 光看 status **判不出「在不在时段内」**。
   本轮是手工补发 `sub2=window` 才拿到判据。⇒ 建议给脚本补 `sub2=window`，并给 `status` 行追加 `active=`。
2. ⚠️ **脚本会改用户配置**：`_r5_quiet.py` 无条件把 `chat.mute_at_enabled` / `chat.auto_dnd_new_group`
   （`--dnd-now` 时还有 `chat.dnd_enabled`）设为 `true`，**跑完不会复位**。
   ⇒ 本轮已手工复位三者为 `false`；`chat.dnd_start/end` 仍留 `02:12~04:17`（`enabled=false` 时不生效，无害）。
   **若你原本开着这些功能，需要重新打开。**

## C · 装包后冒烟（`tools/_smoke.py`，02:23）

| 项 | 实测 | 判定 |
|---|---|---|
| 6.1 静音四项 | `QUIET installed=true hooks=1 blocked=0 enabled=false dbltap=false dumps=0/50` | ✅ **`enabled=false` 是上面复位生效的反向对照**（之前是 true） |
| 免打扰/新加群 | `enabled=false`（同上），`baselineReady=true baseline=734` | ✅ |
| 评论防撤回 / 评论标签 | `SNSCG SNSC stat enabled=true seen=0 dao=0 bulk=0` / `SNSC_TAG stat enabled=true&&true binds=0` | ⚠️ 评论域**仍然开着** ⇒ C1「摘掉评论域 hook 注册（`HookEntry.kt:269/274`）」还没做 |
| 红包 | `REDPACKET stat enabled=false switch=false hooks[cb=true resume=1 click=true] resumes=10 taps=15` | ✅ 关着、钩子在、未误触发（`rp=0 dispatched=0`） |
| 异常段 | 空 | ✅ 无 exception，微信没崩 |

⚠️ **脚本缺陷（非功能问题）**：UI 断言打出 `未找到 '微信'` / `未找到 '通讯录'`。
原因是脚本**没先回主页** —— 上一步停在朋友圈页（`ImproveSnsTimelineUI`），主页文本当然找不到。
⇒ 建议 `_smoke.py` 在 UI 断言前先按一次返回键回 `LauncherUI`，否则每次非主页跑都会假报。

## D · 结论与下一步

- 朋友圈域**导出通路**（钳位 / rv 捕获 / 写盘 / 归档 ls）端到端 PASS，**卡点只在图片落盘路径**，
  与 CDN 批量下载是同一根因的两个面 ⇒ 等 `cdn-trigger` 的静态结论决定下一步锚点。
- 免打扰域：时段判定 PASS，其余两项（被@静音、新加群）**需要人工构造事件**才能收口，
  已记入待办；可观测性缺口两条建议已列在上面。
