# R42 · 开源参考实现对照：WechatMagician 的朋友圈防删除

> 来源：GitHub `InvisibleMoon/WechatMagician`（作者 gh0u1l5，**GPL-3.0**，Kotlin，最后更新 2017-09）
> 文件：`src/main/kotlin/com/gh0u1l5/wechatmagician/xposed/WechatRevokeHook.kt`
> 目标微信版本：6.5.13（2017 年）；本记录只取**机制**，不取代码
> 采集时间：2026-11-05 21:20

---

## §1 它的实现（原文 10 行）

hook `com.tencent.wcdb.database.SQLiteDatabase#updateWithOnConflict`（`beforeHookedMethod`）：

```kotlin
if (p1 == "SnsInfo") {
    p2?.apply {
        if (!containsKey("sourceType") || this["sourceType"] != 0) return
        // ① 给 content 加一句「此动态已删除」的提示
        put("content", MessageUtil.notifyInfoDelete(label_deleted, getAsByteArray("content")))
        // ② 把 sourceType 从待写字段里摘掉 ⇒ 清位永不落地
        remove("sourceType")
    }
}
```

同文件里朋友圈相关的还有两段（与本议题无关，仅备查）：
- `SnsComment` + `commentflag == 1` / `type != 1` ⇒ 摘掉 `commentflag`，并把 `curActionBuf` 改写为「评论已删除」（评论防删）。
- `hookSns()` 里挂 `com.tencent.mm.plugin.sns.ui.at#bho`，把整屏 SNS 对象 dump 到 `/sdcard/wechatsns_<yyyyMMdd>.log`（这是它的导出路子，不是防删）。

---

## §2 与我们现实现的对照

| 维度 | WechatMagician | 我们的现状（`SnsGuard.snsUpdateGuard`） |
|---|---|---|
| 钩子点 | 同一个：`updateWithOnConflict(String, ContentValues, String, Array, int)` | 同一个 |
| 时机 | **before**：把 `sourceType` 键摘掉 | **after**：看到 `sourceType=0` 后等 350ms，批量 `\| FIX_BITS` 补回（`SnsGuard.kt:249-250`、`SnsTimelineSourceFix.kt:530`） |
| 竞态 | **无**。字段不下去，清位从未发生 | **有**。350ms 静默窗内，行处于「已清位」状态；同批后续写入可能覆盖（R27 实测：一轮清 3 条只补回 1 条） |
| 对「微信自愈」的依赖 | 不依赖 | 依赖「补位不再被下一轮清掉」，需要持续跟跑 |
| 用户可见效果 | 本地行保持 `&2!=0` ⇒ **翻页能翻到**；`content` 里多一句「此动态已删除」 | 同上（补回后 `&2!=0` ⇒ 翻页可见），但**没有**文案 |
| 首屏 | **解决不了**（首屏由网络响应重建） | **解决不了**（同） |
| 数据库副作用 | 该行 `sourceType` 永不更新，与服务器端状态不一致 | 写入后被改回，同样不一致，但有日志可追溯 |

⇒ **结论：它的方案是「我们的方案去掉了竞态」**。思路同源，实现更省。

---

## §3 对我们 R41 判据的直接影响（重要，必须与 B+ 一起决策）

R41 的 B+ 判据是：`成员（曾见过）∧ 当前 &2==0 && &4==0 ⇒ 疑似删除`。

**如果直接照搬「写时拦截」，`&2` 永远不变 0 ⇒ B+ 失去信号源，判据永远不触发。**
这两条路线是互斥的，必须二选一：

### 路线甲：**拦截派**（照搬 WechatMagician）
- 清位永不落地 ⇒ 被删动态在翻页里一直可见 ⇒ **不需要注入、不需要判据**。
- 灰标来源：在拦截那一刻就知道「微信要清这条」⇒ **这是最干净的删除事件信号**（带时间戳、带 id、带 ContentValues）。
- 代价：① `sourceType` 与服务器永久不一致，微信若在别处（非 update 路径）重算会覆盖我们的拦截，需要兜底；
  ② 依赖「清位只走 `updateWithOnConflict`」这一条通道（R27 已证 2017 年成立，2026 年我们实测也成立）；
  ③ 用户看到的是「没删过」的假象，而不是「已删但被保留」的诚实提示——除非像它那样改写 `content`。
- **未验证的关键点**：微信是否会因为本地 `sourceType` 与响应不一致而**重新下发整行覆盖**（R39 实测微信会自修行值 `0→2` / `8→10`，说明它有校正机制）。若会，拦截只能撑到下一次校正。

### 路线乙：**记录派**（当前 B+）
- 不拦写入，只把「`sourceType=0` 的写入」当**事件**记录（快照 + 灰标 + 时间戳）。
- 代价：翻页会看不见（已实测），必须靠第 3 期注入把它拉回首屏。
- 好处：不改微信的库状态，行为更「诚实」；判据有明确事件源，不依赖「状态是否被自愈」。

### 建议：**甲做「保翻页」，乙做「打灰标」，两者可以并存**
- 拦截时**先记录事件**（快照 + 灰标 + 时间戳），**再决定是否摘掉 `sourceType`**。
- 这样：灰标有真事件源（不依赖 `&2==0` 状态），翻页也能看见，且不需要等第 3 期注入。
- 唯一要验的是「微信会不会把拦截后的行重新校正回去」——这是下一个探针要回答的问题。

---

## §4 待验证清单（按价值排序）

| # | 问题 | 探针 | 若成立的后果 |
|---|---|---|---|
| W1 | 摘掉 `sourceType` 键后，微信是否会重新下发整行把它覆盖回去？多久？ | 实现拦截 + 真实删除，连续 30 分钟读 `sourceType` 与 `serverExtFlag` | 若 30 分钟内被覆盖 ⇒ 甲只能当「临时保全」，最终仍需注入 |
| W2 | `content` 改写是否会影响渲染（微信是否按 content 的 protobuf 严格解析）？ | 拦截时改写 `content`，看朋友圈是否显示异常/崩溃 | 若会崩 ⇒ 只能加「附带标记」不能改 content |
| W3 | 清位是否**只**走 `updateWithOnConflict`？有没有 `execSQL` 分支（旧模板 `sourceType & -3`）？ | 保留 `noteUnmatchedSourceWrite` 观察一个月 | 若有第二条通道 ⇒ 拦截必须补挂，否则漏保 |

---

## §5 许可提示

WechatMagician 是 **GPL-3.0**。本记录只取「机制/思路」，未复制代码。
若后续决定照搬其实现，需注意 GPL 传染性（我们的模块当前无 license 声明）。
最稳妥的做法是：按机制自行实现（3 行：`containsKey("sourceType") && ==0` → `remove("sourceType")`），不引入其源码文件。
