# R42 · 路线甲（写时拦截）实测方案

> 目的：验证 WechatMagician 那套「把 `sourceType` 从 ContentValues 摘掉」在本机微信 8.0.48 / 2588 上是否成立，并采集旁证（是否被校正、是否还有别的清位通道）。
> 对应实现：`docs/_r42_block_impl.md`｜机制对照：`docs/_r42_wechatmagician.md`
> 设备 `467cf5bc`｜模块需装含拦截的新版（构建由 lane 完成）

---

## §0 基线（2026-11-05 21:29，实验前）

`localFlag=2 AND (sourceType & 2)=0` 共 **41,281** 条：

| sourceType | 条数 |
|---|---|
| `8`（相册位，好友位已清） | 25,053 |
| `0` | 16,043 |
| `20`（=16+4） | 122 |
| `84`（=64+16+4） | 50 |
| `4`（私密） | 10 |
| `68`（=64+4） | 3 |

开关状态：`antirevoke.sns = false`（补位关，保持隔离，本次实验不改）。

**实验后必须重跑此查询对照**：若 `8`/`0` 的数量几乎不变 ⇒ 拦截确实生效；若某条新增了 `&2==0`，说明有通道绕过拦截。

---

## §1 前置：装包与开关

```bash
cd wxgroup && sh dev.sh install
# 冷启两次（装包后常不重载）
python tools/dev.py restart   # 等 12s
python tools/dev.py restart   # 等 14s
# 确认钩子
adb shell "grep -E 'BLOCK|SNS installed' /sdcard/Android/data/com.tencent.mm/cache/wxgroup_dbg.txt" | tail -5
# 开拦截并回读
adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub block --es sub2 on -p com.tencent.mm"
adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub blockstat -p com.tencent.mm"
```
**通过判据**：`blockstat` 显示 `block=true`；`BLOCK switch on` 出现在日志。

---

## §2 主实验步骤

1. **让 verity 发一条纯文字动态**（不带图，便于辨认）。
2. 你下拉刷新朋友圈，确认看见它 → 告诉我「看到了」。
3. 我查库定位：`select snsId,sourceType,createTime from SnsInfo where createTime > <现在-10分钟>`，记录 **snsId + 初始 src**（应为 `2`）。
4. 我启动追踪：`snsg sub=trace --el id <snsId>`（每 30 秒一行，最多 30 分钟）。
5. **让 verity 删掉它。**
6. 你**先别刷新**，等我查一次库（间隔 ≥15 秒，让微信写库）：
   - 若 `src` 仍是 `2` ⇒ **拦截生效**（这是甲的立身之本）
   - 若 `src` 变 `0`/`8` ⇒ 拦截没拦住（可能走了别的通道，看 `BLOCK stat` 的 `nonZero`/`hit`）
7. 你**下拉刷新一次**，再查一次库（观察「刷新」这个动作会不会触发微信校正）。
8. 之后每 5 分钟自动采样一次（`trace` 在跑），中途你可以正常用微信。
9. 30 分钟后，我导出 `trace` 全量 + `blockstat`，判定 W1。

---

## §3 三组判定

| # | 现象 | 结论 | 后续 |
|---|---|---|---|
| **W1-a** | 拦截后 `src` 始终保持 `2`/`10` 达 30 分钟，且刷新/冷启都不改 | **拦截长久有效** | 路线甲成立：不需要注入即可保「翻页可见」，灰标改由「拦截事件」驱动，第 3 期可砍掉一半 |
| **W1-b** | 拦截后数分钟内被改回 `0`/`8` | **拦截只是临时保全** | 需查改回发生在哪一步（刷新？冷启？后台同步？），据此决定是否仍值得保留拦截 |
| **W1-c** | 拦截后 `src` 保持，但**界面刷新后仍看不到** | 拦截保住了库，救不了首屏 | 与 R38 的「改库≠改界面」一致，第 3 期注入仍不可砍 |
| **W2** | `blockstat` 里 `nonZero` 很多但 `blocked=0` | 微信写的不是 0 而是别的值（如 `8`） | 拦截条件要放宽到「新值 & 2 == 0 且原值 & 2 != 0」 |
| **W2'** | `execSQL` 计数 > 0 且含 `SnsInfo` | 存在第二条通道 | 必须补挂，否则拦截有漏 |

---

## §4 必须同时看的旁证（采集清单）

| 数据 | 从哪看 | 用来回答 |
|---|---|---|
| `BLOCK hit/blocked/nonZero` | `snsg sub=blockstat` | 拦截命中率、微信写 0 的频率 |
| `values` 的 key 数量 | `BLOCK hit ... keys=<n>` | W3：整行 upsert 还是部分列（key 数接近全表列数 ⇒ 整行） |
| 该 id 的 `src/lf/sef` 时间线 | `TRACE` 行 | W1：是否被校正、何时 |
| 全库 `&2==0` 分组计数 | §0 的 SQL 重跑 | 整体有没有被绕过的行新增 |
| 界面可见性 | `uiautomator dump` + `wxgroup/_vis.py` | 甲的承诺（翻页可见 / 首屏不可见） |

---

## §5 收尾（必做，成对命令）

```bash
# 关拦截，避免影响后续判据实验
adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub block --es sub2 off -p com.tencent.mm"
# 回读
adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub blockstat -p com.tencent.mm"
# 停 trace
adb shell "am broadcast -a com.lx.wxgroup.action.PROBE --es op snsg --es sub trace --el id 0 -p com.tencent.mm"
```
⛔ 教训（R40 首轮 `rounds=0`）：**关掉的开关必须回读确认，且实验后恢复原状**，否则下一轮验证从错误的初态开始。

---

## §6 已知会干扰结果的三个因素

1. **`antirevoke.sns=false` 是好也是坏**：好——补位不参与，能干净看拦截；坏——我们的 after 钩子仍会采样（`onUpsert`），但不会改库，不干扰。
2. **微信有校正能力**（实测 `0→2` 7 分钟、`8→10` 11 分钟，但那是人工 `setsrc` 的非法值）。真实删除清位后的 `0` 是微信自己写的，是否也会被改回**未知**——这正是 W1 要回答的。
3. **拦截只作用于 `updateWithOnConflict`**：若微信在某些路径用 `update` 重载或 `execSQL`，会漏。`blockstat` 的通道计数就是为此设的。
