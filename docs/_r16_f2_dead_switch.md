# R16 / F2 lane —— 下架两个「改了也没用」的朋友圈 CDN 开关

日期：2026-10-24
lane：F2（本轮唯一实现 lane）

## 1. 改了什么

只动了两个文件：

| 文件 | 改动 |
|---|---|
| `wxgroup/app/src/main/java/com/lx/wxgroup/data/Keys.kt` | `SNS_CDN_IMG` / `SNS_CDN_REWRITE_PATH` 两条 `PrefSpec` 加 `hidden = true`，标题补「当前无消费点」字样，上方加注释说明判据 |
| `wxgroup/app/src/test/java/com/lx/wxgroup/data/PrefsVisibilityTest.kt` | `expectedHidden` 加入这两个 key；`byDomainKeepsHiddenButVisibleDropsIt` 改成逐 key 取自身 domain |

`git diff --stat`：

```
 .../app/src/main/java/com/lx/wxgroup/data/Keys.kt   | 21 +++++++++++++++++++--
 .../java/com/lx/wxgroup/data/PrefsVisibilityTest.kt | 14 ++++++++++----
 2 files changed, 29 insertions(+), 6 deletions(-)
```

`SnsCdnImage.kt`、`BackupFlow.kt`、任何 UI 文件**未改动**。

### 1.1 改后形态（Keys.kt）

```kotlin
// ⚠️ 下面两项是「用户改了也没用」的死开关，故 hidden = true（保留定义、不渲染）。
//    真机定案（docs/conclusions/moments_cdn.md，2026-10-24）两条判据：
//      ① picCachePath 字段**存在但恒为空**（hitPicE=7 / hitNoField=0）⇒ 拿不到落盘路径；
//      ② 批量下载请求 `ok0.x0#n` **一次都没被调用**（hitN=0）⇒ 批量通路根本没发生。
//    ⇒ CDN 批量下载这条路走不通，朋友圈图片已改道「SNS 缓存直读」。
//    另：sns.cdn_rewrite_path 在 SnsCdnImage.kt 里**零消费点** —— Cfg.rewritePath 只在
//    refreshCfg() 被写入，全类没有任何地方读它。
//    为什么是 hidden 而不是删定义：旧备份文件里可能带这两个 key，删定义会让
//    BackupFlow.knownKeys 当未知 key 丢弃，用户换机时静默丢配置且无任何症状。
PrefSpec(
    Keys.SNS_CDN_IMG, PrefKind.SWITCH, "false", "朋友圈",
    "批量下载图片（CDN 批量通路未打通，当前无消费点）",
    hidden = true
),
PrefSpec(
    Keys.SNS_CDN_REWRITE_PATH, PrefKind.SWITCH, "false", "朋友圈",
    "改写落盘路径（当前无消费点）",
    hidden = true
),
```

写法照抄同文件 `REDPACKET_ALLOW` / `REDPACKET_EXCLUDE`（`Keys.kt:282-291`）的
「多行 `PrefSpec(...)` + 末位具名 `hidden = true`」风格。

## 2. 为什么选 `hidden` 而不是删定义

这两条 key 属于「定义在、备份在、但不该渲染」的第三态。分开看：

- **不能画出来**：用户看到一个「批量下载图片」开关，打开后不会有任何效果 ——
  这正是 `PrefsVisibilityTest` 类注释里点名的错误做法 1（"说能做、实际不做"）。
- **不能连定义删**：`BackupFlow.kt:69` 的 `knownKeys = Prefs.ALL.map { it.key }.toSet()`，
  `Kv.exportAll()`（`Kv.kt:72`）也只遍历 `Prefs.ALL`。删掉定义 ⇒ 旧备份文件里带着的
  `sns.cdn_img` / `sns.cdn_rewrite_path` 会被 `BackupMerge` 当**未知 key 静默丢弃**。
  用户换机后配置消失，**没有任何提示、没有崩溃、没有日志** —— 只有用户自己发现"我明明开过"。

⇒ 唯一正确的处置：**保留在 `Prefs.ALL` 里，`hidden = true` 不渲染**。
备份/探针/统计口径（`byDomain`、`spec()`、`defaultOf()`、`exportAll()`）照常可见，
只有渲染入口 `Prefs.visible(domain)`（`Keys.kt:356`）会滤掉它们。

## 3. 为什么 `noUnexpectedHiddenPrefs` 必须两边同改

`PrefsVisibilityTest.kt:117` 是**双向**比对：

```kotlin
val actual = Prefs.ALL.filter { it.hidden }.map { it.key }.toSet()
assertEquals(expectedHidden, actual)
```

`assertEquals(set, set)` 走的是 `Set.equals`，即「互为子集」：

- 只改 `Keys.kt`（多出 2 个 hidden）→ `actual` 比 `expectedHidden` 大 → **红**；
- 只改测试（白名单多 2 个）→ `expectedHidden` 比 `actual` 大 → **红**。

这不是测试写麻烦，而是它的**设计意图**：注释原话是「新加 hidden **必须**同时改这里的白名单，
逼一次显式决策」。hidden 是个「眼不见」的旋钮，若可以单方面加，就变成
「功能没做完但先把开关藏起来」的合法借口 —— 比空壳更糟，因为连"设置页有这一项"的线索都没了。
两边同改 = 强制实现者留下一条可评审的 diff：`Keys.kt` 加 `hidden` 时，评审者一定会问
「它真的『不能删定义』吗？」本次答案是「是」，判据见 §1.1 与 §4。

## 4. 「`rewritePath` 零消费点」的核实证据

```console
$ grep -n "rewritePath" wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsCdnImage.kt
81:    private data class Cfg(val enabled: Boolean, val rewritePath: Boolean)

$ grep -n "REWRITE_PATH\|rewrite_path" wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsCdnImage.kt
98:        val next = Cfg(Kv.getBool(Keys.SNS_CDN_IMG), Kv.getBool(Keys.SNS_CDN_REWRITE_PATH))

$ grep -c "\.rewritePath" wxgroup/app/src/main/java/com/lx/wxgroup/feature/SnsCdnImage.kt
0
```

即：`rewritePath` 全类只出现 **1 次**——`Cfg` 的构造参数声明（`:81`）；
它被赋值的唯一途径是 `refreshCfg()` 里的 `Cfg(..., Kv.getBool(Keys.SNS_CDN_REWRITE_PATH))`（`:98`）。
**零处 `.rewritePath` 读取**。作为对照，同一 `Cfg` 里的 `enabled` 有两处真实读取
（`:100` 的边沿检测、`:126` 的 `if (!cfg().enabled) return false`）——
说明「零读取」不是 grep 写错，是这个字段**真的没被用过**。

全仓范围再确认（`grep -rn "rewrite_path"`）只有三处：`Keys.kt` 的定义、`Keys.kt` 的 `PrefSpec`、
`SnsCdnImage.kt:98` 的写入；无任何 feature/UI 读取。

配合 `⑫.9` 的两条真机判据（`docs/取证结论.md`）：

```
hitK=1 hitCB=7 hitN=0 hitPicE=7 hitPicNE=0 hitDir=0 hitNoField=0
```

- **结论 A**：`picCachePath` 字段存在，但值恒空（`hitPicE=7` / `hitNoField=0`）⇒ 拿不到落盘路径；
- **结论 B**：批量下载通路 `ok0.x0#n` 一次都没被调用（`hitN=0`）⇒ 批量通路根本没发生。

两条独立判据同时成立 ⇒ `sns.cdn_img` 打开也不会有任何图片被批量下载；
`sns.cdn_rewrite_path` 则连读取点都没有。两者都是死开关。

## 5. 一处必须说明的**测试内**附加改动（守规矩，先声明）

任务书里说「改 `expectedHidden` 就够」，但实际还多改了一处，**仍在允许改的同一个文件内**：

`byDomainKeepsHiddenButVisibleDropsIt`（原 `:71-80`）把域**写死**成了红包域：

```kotlin
val domain = Prefs.spec(Keys.REDPACKET_ALLOW)!!.domain   // 恒为 "红包"
val all = Prefs.byDomain(domain).map { it.key }
val shown = Prefs.visible(domain).map { it.key }
for (k in expectedHidden) {
    assertTrue("byDomain 口径是全集，应包含 $k", k in all)   // ← SNS key 不在红包域 ⇒ 假失败
    ...
}
```

`expectedHidden` 里有 4 个 key 后，遍历到 `SNS_CDN_IMG` 时 `k in all` 恒为 false，
这条测试必然红 —— 属于**测试自身的域硬编码缺陷**，不是实现问题。改法是「每个 key 取它自己
所属的域」「子集断言移到循环内」，语义**严格强于**原版（原来只校验 1 个域的
`visible ⊆ byDomain`，现在校验全部 hidden 项各自所属的域）。

## 6. 关于 `keyPrefixMatchesDomainGrouping()` —— 已核实，无需改

```kotlin
"sns" to "朋友圈",   // 朋友圈图片下载走 sns.* 前缀，但归朋友圈域   （:144）
```

两个 key 前缀均为 `sns.`、`spec.domain` 均为 `"朋友圈"`，映射已覆盖，**未改动映射表**。

## 7. 未做 / 留给主会话

- **未跑 gradle、未碰 adb**（铁律 1）。编译与 `PrefsVisibilityTest` 的执行由主会话统一跑。
- 建议主会话核对一次全集：改后 `Prefs.ALL` 里 `hidden = true` 的项应恰好 4 条
  （`redpacket.allow`、`redpacket.exclude`、`sns.cdn_img`、`sns.cdn_rewrite_path`），
  与 `expectedHidden` 完全一致。
- 未来若「SNS 缓存直读」那条 lane 收敛后决定给用户暴露同类开关，应当**新开一个 key**
  （真正有消费点的那个），而不是把这两个 `hidden` 摘掉 —— 它们的语义是「CDN 批量通路」，
  和「SNS 缓存直读」不是同一件事。
