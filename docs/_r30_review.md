# R30 代码复核报告

复核人：r30-reviewer（独立，只读，未改任何源码，未跑 gradle/adb）
范围：6.1（时间文案追加 `[删除已拦截]`）、6.2（相册页显示被删动态，**已停用**）、6.3（时间范围突破，随 6.2 一并停用）

---

## 1. 结论

**PASS WITH CONDITIONS —— 可以上真机。**

代码层面没有发现会导致微信崩溃的缺陷（R1 链路的反射失败/类型不符/字符串处理均安全降级）。唯一的"条件"是：**上真机后第一步必须用 `op=snstag --es sub stat` 看 `missedTime` 是否为 0** —— 它 >0 说明时间控件反射（`getPublishTimeText()`/字段 `o`）在当前微信 8.0.48/2588 上漂移，功能会**静默不生效**（但不崩）。这是取证结论能否落地的问题，不是代码缺陷。

---

## 2. 阻塞项

### P0（阻断上真机）

**无。**

### P1（上真机必须优先验证）

**无代码级 P1。** 唯一需真机首验的是功能生效性（见下方条件），不影响稳定性。

---

## 3. 非阻塞观察（P2）

按风险排序，均可上真机后再看：

1. **时间控件反射命中是"功能生效"的唯一前提，且当前无独立静态证据。**
   `SnsDeletedTag.apply` 全靠 `timeTextOf`（`SnsDeletedTag.kt:79-86`）反射 `getPublishTimeText()` / 字段 `o`。取不到 → 只 `++missedTime` 并 return，**不崩但整条 6.1 静默失效**。
   我作为复核者**未独立复核** `docs/_r30_lane1_time_label.md` 的取证结论，代码假设（父类公开 getter + 字段 `o`）依赖 lane1 结论成立。真机验证动作：`stat` 里 `missedTime=0` 且 `hits>0` 才算链路通。
   - 位置：`SnsDeletedTag.kt:50-86`、`SnsTag.kt:731-735`（stat 已暴露 `missedTime`）。

2. **`tv.text = want` 可能触发微信给该时间 TextView 设的 TextWatcher。**
   我们没 hook `setText`（全仓 grep 确认 SnsTag/SnsDeletedTag 只 hook `onBindViewHolder` + `setAdapter`），所以**无重入、无递归**。但若微信自己给这个 TextView 挂了 TextWatcher，赋值会回调它。风险极低（正常框架行为），真机看是否有异常即可。
   - 位置：`SnsDeletedTag.kt:69`。

3. **`snsTagIds()` 的 `WHERE fixed_orig IS NOT NULL` 无索引，全表扫 `sns_visible_src`。**
   台账（`sns_visible_src`）会随"见过多少条可见动态"线性增长（`listVisibleSrc` 默认 limit 5000）。主线程首屏同步扫一遍（`SnsTag.deleted()` 的 firstLoad 分支）目前毫秒级可接受；台账涨到几千行后可考虑加 `idx_src_fixed(fixed_orig)` 或改走 `listSrcFixedIds()`。
   - 位置：`GroupDb.kt:528-539`、`SnsTag.kt:600-605`。

4. **`snsTagIds()` 的语义边界：只含"补位未回滚"的行。**
   `fixed_orig IS NOT NULL` = 补过位且未回滚。若某动态被回滚（`clearSrcFixed` 清空 `fixed_orig`）后微信侧 `sourceType` 仍为 0，它会从清单消失而漏标。这是 `SnsTimelineSourceFix` 的既有语义，非本轮引入，但会直接影响 6.1 的覆盖完整度。
   - 位置：`GroupDb.kt:531`、`GroupDb.kt:663-667`（`clearSrcFixed`）。

5. **`SnsTag.MARK`（`"wxgroup.sns.deleted.tag"`）现在是"只读不写"的死代码残留。**
   旧绿色 `[已删除]` TextView 的 tag 不再被创建（`ensure()` 已删），但 `SnsTag.kt:65` 仍定义 MARK、`SnsTag.kt:460` 的 `textsOf` 仍 `if (MARK == v.tag) return`。无害，只是永不再命中，可后续清理。
   - 注意：它与 `SnsDeletedTagLogic.MARK`（`"[删除已拦截]"`）是**两个不同的常量**，作用域不同，无冲突。

6. **`HookEntry.kt:31` 未使用的 import `SnsAlbumMerge`。**
   `SnsAlbumMerge.install` 已被注释（`HookEntry.kt:259-261`），但 import 仍保留。项目**未开 `allWarningsAsErrors`**（`app/build.gradle.kts` 无此配置，`lint { abortOnError = false }` 见第 48-51 行），未使用 import 只是 warning，不会升级为 error，`assembleDebug` 已 EXIT=0 佐证。

7. **`SnsAlbumMerge.kt`（停用代码）里 `readAttrBuf` 的 SQL 是字符串拼接。**
   `"SELECT attrBuf FROM SnsInfo WHERE snsId=$snsId"`（`SnsAlbumMerge.kt:215`）。`snsId` 是 Long（台账 id，非用户输入），注入风险低；且该功能已停用。若未来启用，建议顺手改成参数绑定，与项目"所有 SQL 一律参数绑定"的约束（`GroupDb.kt:20`）保持一致。

---

## 4. 逐项复核结论（带证据）

### R1 `SnsTag.apply()` → `SnsDeletedTag.apply()` 会否炸微信 —— **不会**

- **反射失败 / 非 TextView / itemView 非预期类型 → 100% 安全退出。**
  `timeTextOf` 用 `runCatching` 包裹 `XposedHelpers.callMethod` 和 `getObjectField`，两者都 `as? TextView`（`SnsDeletedTag.kt:79-86`）。`runCatching` 捕获 `Throwable`（含 `NoSuchMethodError`），取不到即返回 null；`apply` 里 `tv == null` 只 `++missedTime` + 限流日志（`n<=5`）后 return（`SnsDeletedTag.kt:51-59`）。无任何向 View 写操作。

- **`strip()` + `appendTag()` 无死循环 / 无异常。**
  `strip` 的 `substring` 只在 `text.endsWith(" $mark")` 成立时执行（此时长度必然足够），无循环（`SnsDeletedTag.kt:103-110`）。`appendTag` 是 `trimEnd` + `contains` + 拼接（`SnsDeletedTagLogic.kt:66-72`），无循环。文案本身是微信时间戳，短文本，无超长风险。

- **无递归。**
  全仓 grep 确认：SnsTag 只 hook `onBindViewHolder`（`SnsTag.kt:145-167`）和 `setAdapter`（`SnsTag.kt:169-205`），**没有 hook `setText`**。`tv.text = want`（`SnsDeletedTag.kt:69`）不会触发我们自己的任何钩子。`bindHook` 还有 depth 守卫（`SnsTag.kt:238-255`，`d >= 4` 拦截）。`realTimeUpdate` 是微信侧定时写回，与我们的 `setText` 互不触发。

- **Sweep 不会对 detach 的 View 调 apply。**
  `sweep()` 循环内先判 `if (!v.isAttachedToWindow) { bound.remove(v); continue }`（`SnsTag.kt:571-577`），detach 的直接移除跳过，不调 `apply`。

### R2 被删的 130 行有无悬空引用 —— **无**

全仓 grep `ensure|LABEL|added|logged|hostWarned|lpWarned`（`app/src/main`），所有命中都属于**其他类自己的同名符号**：
- `HintBadge.kt` 有自己的 `ensure/added/logged/hostWarned/lpWarned`（第 97-111 行等）；
- `PlusMenu.kt:46`、`LabelSource.kt:26`、`LabelHuntProbe.kt:66` 各有自己的 `LABEL`；
- `ConvFilter.kt:530` 有自己的 `ensure`。

SnsTag 删掉的这些符号**没有任何跨文件引用**。

`stat()` 现输出把 `added=${added.get()}` 换成 `missedTime=${SnsDeletedTag.missedTimeCount()}`（`SnsTag.kt:731-735`）。`missedTimeCount()` 是 `internal`（`SnsDeletedTag.kt:36`），同模块可见，无编译后字段不存在/NPE 风险。`MARK` 常量仍保留且被 `textsOf` 引用（`SnsTag.kt:65`、`:460`），不悬空。

### R3 `snapshot()` / `sweep()` 空清单语义 —— **正确**

- **`bound` 是 `WeakHashMap`**：`SnsTag.kt:115-116` `Collections.synchronizedMap(WeakHashMap<View, Long>())`。`snapshot()` 用 `val k = e.key ?: continue` 显式滤掉 null key（`SnsTag.kt:581-590`），无 NPE。value 是 `Long`（不可变），不强引用 key，回收不受阻。

- **开关关闭空清单还原不会留永久标记：**
  - detach View 被 `isAttachedToWindow` 挡住（不进 apply）；
  - attached 但取不到时间控件的 View 会 return 残留，但「取不到 tv」= 反射漂移（missedTime 会计数暴露），且加标记本也要先取到 tv，同一 View 实例字段不变则取不到就加不上，不会出现"加上了却剥不掉"；
  - 关开关时双保险：`refresh()` 里 `if (!enabled) ensureSweep()`（`SnsTag.kt:225`）+ 后续每次 `onBind` 都会走 `SnsDeletedTag.apply(it, 0L, emptySet())` 剥（`SnsTag.kt:271-276`）。View 复用也会被 onBind 再剥。

### R4 `SnsAlbumMerge` 停用确认 —— **已确认停用，无构建风险**

- `HookEntry.kt:259-261` 明确注释掉 `SnsAlbumMerge.install(lpparam)`，且注释了停用原因（相册 UI 读库渲染，需真机先确认 sourceType=0 是否已被拦住）。
- 全仓 grep `SnsAlbumMerge`：仅 `HookEntry.kt:31`（import，未使用）+ `SnsAlbumMerge.kt` 自身 + `SnsAlbumMergeLogic.kt`。无任何 `install` 调用点。
- 未使用 import 不升级为 error：`app/build.gradle.kts` 无 `allWarningsAsErrors`，`lint.abortOnError=false`（第 48-51 行）。

### R5 `GroupDb.snsTagIds()` SQL —— **正确，无语义丢失**

- **表名/列名与 `createTable` 完全一致**：`T_SNS = "sns_archive"`、`T_SRC = "sns_visible_src"`（`GroupDb.kt:50-51`）；`sns_id` 列（`GroupDb.kt:215`、`:253`）；`fixed_orig` 列（`GroupDb.kt:257`）。SQL 里 `$T_SNS`/`$T_SRC`/`sns_id`/`fixed_orig` 与之一一对应（`GroupDb.kt:531`）。
- **与 `snsArchivedIds()` 的差异**：原 `snsArchivedIds` 只查 `sns_archive` 全表（`GroupDb.kt:501-513`）；新 `snsTagIds` 是 `sns_archive ∪ {fixed_orig IS NOT NULL 的 src}`，是**并集扩展**，不丢原集合。`UNION`（去重）与 `Set<Long>` 语义一致。
- **`snsArchivedIds()` 未被删除**，仍被 `SnsGuard.kt:1358`、`:1384` 使用。
- **TTL 缓存逻辑仍成立**：`snsTagIds()` 返回 `Set<Long>`，`SnsTag.deleted()` 的 `delSet` 也是 `Set<Long>`（`SnsTag.kt:85`、`:593-623`），类型一致，5s TTL + firstLoad 首屏同步/后台刷新逻辑不变。

---

## 5. 未能确认的点（诚实标注）

1. **`getPublishTimeText()` / 字段 `o` 在当前微信 8.0.48/2588 上是否真实存在并返回 TextView** —— 我未独立复核 `docs/_r30_lane1_time_label.md` 的取证结论。代码已做安全降级（取不到只计数不崩），但"功能是否真的生效"取决于该结论，须真机用 `missedTime` 验证。
2. **`SnsAlbumMerge` 的取证结论（`h3.e`、`jc5.f`、`attrBuf` 序列化、`d2.x` 拼接链）** —— 我未独立复核 `docs/_r30_lane2_album_page.md`；但该功能已停用，不阻塞上真机。
3. **`realTimeUpdate`（`ImproveTimelineItemMeasure.d()`）与 sweep 的竞争是否会肉眼可见闪烁** —— 代码层面两者幂等、无害（我们只追加/剥离，微信写回原文，sweep 2s 重判兜底），但"追加→覆盖→2s 后追加"的视觉体验需真机确认。
4. **微信是否给时间 TextView 设了 TextWatcher 且该监听对文案变更敏感** —— 需真机确认，静态无法判定。
