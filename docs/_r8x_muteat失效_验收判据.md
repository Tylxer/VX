# R8x B′ 修复真机验收判据（主会话规划，B′ 落码后执行）

> 性质：验收清单，非结论。对着用户可见事实 / 系统通道字段写，不靠内部计数器（本次教训：`muted=2` 是假象）。
> 前置：Lite lane 落码 + 主会话亲核 diff + 三闸全过 + 装包前交叉复核后才装机。

## 0. 三闸（装机前，主会话）
1. `python tools/gradle_run.py compileDebugUnitTestKotlin` → EXIT=0
2. `bash tools/_junit.sh` → 全量 OK（基线 1217 + 本轮新增 MuteChannelIdTest 5 条 = 1222，以实际为准）
3. `python tools/gradle_run.py assembleDebug` → EXIT=0
4. ⛔ 装包前把 diff 交第二个视角复核（Lite 复核子代理或逐行对照本清单 §1 行级规格）；核 ①分包 ⑦Pro 类有无主会话自做。

## 1. 装包后静态验收（不依赖用户听感，可自证）
- **模块加载**：`op=logclear` → 冷启两次 → 日志有 `QUIET AT install registered=true`、`QUIET bridge installed itemHooked=true`；无 `DISABLED:` / `MuteAt install failed`。
- **pm path / lastUpdateTime** ≥ 本地 APK mtime（装包判据，防 STALE）。
- **开关/名单**：`probe quiet at status` → `registered=true enabled=true ... allOnly=true`；`probe quiet at scope` → allow 群在表、exclude 空。
- **新通道静音字段（核心）**：
  ```
  adb shell dumpsys notification --noredact | grep wxgroup_silent_v2
  ```
  判据：新通道 `mSound=null` 且 `mVibrationEnabled=false` 且 `mImportance=4` 且 mUserLockedFields **不含 SOUND 位（0x20）**。
  ⚠️ 只 grep 新 id；旧 `wxgroup_silent` 残留属预期（不动用户系统设置），不作判据。
- **通知落点**：`probe quiet at extras` → 快照 `channel=` 应显示新 id（若通知已被改写）；`AT muted` 日志前缀仍 `QUIET`。

## 2. 正向真机验收（需用户配合）
1. 请用户用**另一台设备/别人**在 allow 名单内的群（如「测试群聊」）发一条 **@全体成员**。
2. 用户确认：**未听到铃声、未感到震动**（这是最终判据，计数器不算）。
3. 同一时刻抓：`python tools/dev.py q 'QUIET|AT muted'` → 有 `QUIET AT muted talker=测试群聊`（证明判据仍通）。
4. 通知仍在通知栏（静音 ≠ 吞通知）。

## 3. 反向对照（关开关复现响铃，证明因果可逆）
1. `kvset chat.mute_at_enabled false` → 回读 `kvget` = false。
2. 同群再发一条 @全体成员 → 用户确认**响铃/震动恢复**。
3. 抓日志：此条**不应**有 `QUIET AT muted`（开关关 ⇒ shouldMute 第一行 return false），证明静音确由本功能落地。
4. 恢复：`kvset chat.mute_at_enabled true` → 回读 true。

## 4. 互斥与假象声明
- `AT muted`（判据层真）与「通道有声」（落地层失效）**曾同时为真** —— 本次根因。`AT muted` 单独出现**不能**判静音成功。
- 最终判据 = ①用户听感未响 **且** ②dumpsys 新通道无声无震 **且** ③反向关开关能复现响铃。三者缺一不可。
- 计数器 `matched/muted/bound` 一律不作最终判据。

## 5. 失败回退
- 若新通道仍被系统改回有声 ⇒ B′ 失败，按 D3「判据连塌即停」回头，不再换第三个 id；改走 B（IMPORTANCE_MIN）或上报用户决策。
- 若 `AT muted` 不再出现 ⇒ 判据链被动到（违反红线），立即回退 diff 重查。

---

## 4. 【装机后现场·重大反转 2026-11-28 01:40】B′ 静态验收 FAIL

> 装机后第一条 @全体成员（昵称「测试群聊」）现场，纯客观事实，等用户听感确认。

- **判据链仍全通**：`01:40:32.654 QUIET silent channel created`、`01:40:32.655 QUIET AT muted talker=测试群聊`；status `matched=1 muted=1`（新进程归零后首条）。
- **B′ 静态判据 FAIL**：新通道 `wxgroup_silent_v2` 首次创建后系统字段为
  ```
  mSound=content://settings/system/notification_sound   ← 有铃声（判据要求 null）
  mVibrationEnabled=true                                  ← 有震动（判据要求 false）
  mUserLockedFields=38                                    ← 含 SOUND 位 0x20（判据要求不含）
  mImportance=4, mAudioAttributes=null, mDeleted=false
  ```
  与旧通道 `wxgroup_silent`（已 mDeleted=true，01:36:49 冷启删除）**逐字段相同**。
- **`mAudioAttributes=null` 是新证据**：说明代码 `setSound(null,null)` 确实传了 null 给系统，但系统落库后 mSound 仍回退到系统默认铃声、并自动置 lock=38。⇒ 不是「代码没设静音」，是「系统在创建/落库覆盖了静音参数」。
- **排除「系统全局套 38」假设**：全机 1324 通道 `mUserLockedFields` 分布 `{0:756, 20:150, 38:40, 26:59, 22:69, ...}`，38 只占 40 个，非全局默认。但 wxgroup 新旧两个通道都精确是 38。
- **结论边界**：B′ 的「换 id 绕开 locked」前提被真机证伪——新 id 首次创建就被套成和旧 id 一样的有声有震+lock38。用户听感待确认（AskUserQuestion 答「再试一次」）。**in no case 把 `AT muted`/`muted=1` 当静音成功。**
- **待办**：用户第二次复现后若确认仍响 ⇒ B′ 失败，需重查「系统为何对新 id 也套声震+lock38」的最小因果链（Pro 类根因推理），路线重新设计（候选：C 清 Notification 字段 / B IMPORTANCE_MIN / hook 系统通道创建 / 绕过通道改用 DND 白名单）。**当前不改码、不回退。**

## 5. 【B′ 失败定案 2026-11-28 01:47】用户确认「有声音有震动」

- **第二条现场**：`01:47:02 QUIET AT muted talker=测试群聊`（判据链通）；新通道 `wxgroup_silent_v2` 字段复核实测仍是 `mSound=系统铃声 / mVibrationEnabled=true / mUserLockedFields=38 / mImportance=4 / mDeleted=false`。
- **用户听感（最终判据）**：明确「有声音有震动」⇒ B′ 的正向验收三条**全 FAIL**：①通道字段非静音 ②用户听到声震 ③（`AT muted` 照打但属假象，不算）。
- **定案**：B′「换 id 绕开 locked」被真机双向（两条复现 + 用户耳朵）证伪。新 id 首次创建即被系统套成与旧 id 完全相同的有声有震+lock38，且 `mAudioAttributes=null` 证明代码传了 null 仍被覆盖。
- **处置**：**不回退、不盲改**。B′ 代码留在树里（无害：新通道等效旧通道），但路线作废。下一步 = 重新查「系统为何连全新 id 也套声震+lock38」的最小因果链（Pro 类根因推理，Pro 402 则降档 Lite 查证 + 主会话亲核 + 台账三件套），候选路线：C 清 Notification 对象 sound/vibrate/defaults、B IMPORTANCE_MIN 通道、hook 通道创建/系统通知入队、DND 白名单。真机证据全部保留。

## 6. 【补充硬证据 2026-11-28 01:5x】NotificationRecord 实读（dumpsys --noredact）

对刚响的那条通知（`tickerText=路过人间: @所有人`、title=测试群聊）实读：
```
NotificationRecord(pkg=com.tencent.mm id=-1521557830 importance=4
  Notification(channel=wxgroup_silent_v2 shortcut=n ...))
flags=SHOW_LIGHTS|AUTO_CANCEL          ← 无 DEFAULT_SOUND / DEFAULT_VIBRATE 位
originalFlags=SHOW_LIGHTS|AUTO_CANCEL
```
- **通知落点确认**：`channel=wxgroup_silent_v2` —— hook 改写生效，通知确实落在新静音通道上，不是没改写。
- **路线 C 基本排除**：通知 flags 里本就没有 `DEFAULT_SOUND/DEFAULT_VIBRATE`，声震完全由**通道**（mSound + mVibrationEnabled + importance=4）决定。清 Notification 对象的 sound/vibrate/defaults 对这条 case 无意义（本来就是 null/未设）。
- **importance=4 双刃**：NotificationRecord importance=4 会触发 heads-up + 通道声震；即便通道声震被压，importance=4 的横幅行为仍在。路线 B（IMPORTANCE_MIN）能根治声震但牺牲横幅。
- **`mSoundNotificationKey` / `mVibrateNotificationKey` 均指向此通知**：系统声音/震动通道实际选中了它，与用户「有声音有震动」呼应，是响铃的直接系统证据。

## 7. 【决定性时间证据 2026-11-28 01:5x】新通道是真正首次创建

从 `NotificationRecord.effectiveNotificationChannel` 实读：
```
mCreationTimeMs=1795801232659  → 2026-11-28 01:40:32
mSound=content://settings/system/notification_sound
mVibration=Composed{...duration=200...}      ← 有震动段
mAttributes=AudioAttributes: usage=USAGE_NOTIFICATION ...   ← 非 null！
mIsInterruptive=true
effectiveNotificationChannel=wxgroup_silent_v2
```
- **真正首次创建**：`mCreationTimeMs=01:40:32` 精确等于第一条 @ 的日志时刻（`01:40:32 QUIET silent channel created`）。⇒ 排除「该 id 历史残留/非首次创建」假设（Lite 子节2 解释5）。新通道在**创建那一刻**就带 lock=38 + 默认铃声 + 有震动。
- **又一个矛盾**：通道对象里 `mAudioAttributes=null`，但 NotificationRecord 的 `mAttributes` 是**非 null 的 AudioAttributes**。系统发声用的是 Record 层的非 null 属性 + 通道的默认铃声。
- **收窄结论**：既然创建时间=首次、创建时参数 setSound(null) 正确，那么「lock=38 + 默认铃声」必是**创建瞬间某个外部力量写入**（OEM 通道创建钩子 / AOSP 联网所述路径），而非 app 参数错、非历史残留、非事后用户手改（几秒内无人操作）。

---

## 8. 【B 路线（降 importance）验收判据 2026-11-28 02:1x】用户选「保留通知栏、只不响」

> 前版 B′ 只换 id 仍 FAIL（importance=HIGH 是代码自己写的）。B 路线改为 **importance HIGH→LOW + 换全新 id `wxgroup_silent_low`**。判据对着**用户可见事实**（通知在栏 + 不响），不靠 `AT muted`/`muted` 计数器。

### 8.1 三闸（装机前，主会话，已定）
compileDebugUnitTestKotlin / `_junit.sh` 全量（基线 1222 + 新增 4 条 = 1226）/ assembleDebug；真跑变异 M1（importance→4 红）+ M2（VALUE→旧 v2 红）。

### 8.2 装包后静态判据（dumpsys，自证）
- **新通道 `wxgroup_silent_low` 首次创建后**：`mImportance=2`（LOW，核心判据）、`mSound=null`、`mVibrationEnabled=false`、`mUserLockedFields` 不含 SOUND 位(0x20)。
- 对照旧通道 `wxgroup_silent_v2`（HIGH，已 lock）：仍在但 mDeleted 或残留，**通知不得落它**。
- **NotificationRecord**：`channel=wxgroup_silent_low`、`importance=2`（不是 4）。
- 成立 = 以上全中；不成立 = 任一字段不符 ⇒ 停，不进入正向验收，先查 importance 是否被 OEM 提回 4。

### 8.3 正向真机验收（需用户配合）
- 别人在「测试群聊」（或 allow 名单群）发 **@全体成员**，用户回「发了」。
- 抓：`QUIET AT muted talker=测试群聊`（判据链）、通知快照 `ticker=...: @所有人`。
- **用户听感（最终判据）**：**通知栏能看到 + 不响铃 + 不震动**。三条同时成立才算通过。
- 互斥：`AT muted` 打了但用户听到声震 = FAIL（同 B′ 假象）。

### 8.4 反向对照（证明因果可逆，不可省）
- `kvset chat.mute_at_enabled=false` 回读确认 → 用户再发一条 @全体成员 → 确认**响铃恢复**。
- 再 `kvset chat.mute_at_enabled=true` 回读复位。
- 成立 = 关则响、开则不响；不成立 = 开关无关 ⇒ 因果未证。

### 8.5 失败回退
若静态 8.2 显示 importance 被 OEM 提回 4 ⇒ B 也证伪，路线转 G′（系统 NMS hook 吞通知，牺牲通知栏）或 D（hook 系统发声），不再换 id/改 importance。B 代码留树无害（LOW 等效降级），不回退。

---

## 9. 【B 路线正向验收现场 2026-11-28 02:24】重要性落地成功、静音成功、但通知不可见

- **判据链全通**：`02:24:09.959 QUIET silent channel created` → `02:24:09.961 QUIET AT muted talker=45424365325@chatroom`（昵称「测试群聊」）；status 新进程 matched=0→1。
- **核心判据 ① 命中**：新通道 `wxgroup_silent_low` 首次创建后 `mImportance=2`（LOW）——**没被 OEM 提回 4**。这跟 B′ 失败时（importance=4）形成关键对比，证明「降 importance」确实绕开了「HIGH 是代码自己写」这个根因。
- **sound/vib 字段仍被系统补默认**：`mSound=系统铃声 / mVibrationEnabled=true / mUserLockedFields=38 / mAudioAttributes=null`——与旧通道逐字段相同。但 importance=2 下系统不走响铃路径（见下）。
- **系统侧静音证据**：NotificationRecord `importance=2 / channel=wxgroup_silent_low / flags=SHOW_LIGHTS|AUTO_CANCEL`（无 DEFAULT_SOUND/VIBRATE）——**不再有 B′ 失败时的 `mIsInterruptive=true`/`mSoundNotificationKey`**。
- **通知未被吞**：该通知仍在系统通知队列（NotificationRecord 存在）、ticker=`路过人间: @所有人` 仍在 ⇒ 不是 `param.result=null` 那种吞通知。
- **用户听感（最终判据）**：答「**通知栏没有，不响不震**」⇒ 三条判据 **2 过 1 不过**：不响 ✅ / 不震 ✅ / 通知栏可见 ❌。
- **结论**：B 路线（LOW）在 ColorOS 16 上**静音成功但牺牲了通知可见性**——用户诉求「保留通知栏、只是不响」未完全满足。importance=2 在这台 ColorOS 上把通知状态栏图标/通知栏显示也压掉了（AOSP 语义 LOW 应保留通知栏显示，OEM 行为有差异）。
- **下一步待定**：需确认用户能否接受「通知在队列但不可见」，或改走折中方案（如 importance 保持能显示的最小值 + 另寻静音手段）；**当前不改码**，先向用户确认可见性诉求的优先级。

### 9.1 【用户二次澄清 → 正向验收最终 PASS 2026-11-28 02:2x】
- 用户澄清「通知栏没有」的确切含义：①下拉通知栏**能看到**（不是被吞）；②屏幕顶部**没弹横幅/悬浮提示**，只是下拉才看到。第二问「取舍」明确「必须能看到通知」⇒ 现已满足。
- **正向验收四条全 PASS**：不响铃 ✅ / 不震动 ✅ / 不弹横幅 ✅ / 下拉通知栏可见 ✅。
- 系统侧一致：`mImportance=2` 落地、无 `mIsInterruptive=true`、无 `mSoundNotificationKey`/`mVibrateNotificationKey`、通知 Record 在队列、ticker=`路过人间: @所有人` 在。⇒ B 路线（降 importance 到 LOW + 换 id `wxgroup_silent_low`）**正向验收成功**。
- **§9 初判「2 过 1 不过」作废**，以本节为准：初判把「通知栏没有」误读成完全不可见，实为「不弹横幅 + 下拉可见」，正是目标行为。

### 9.2 【反向对照 A/B 因果闭环 + 开关复位 2026-11-28 02:4x】
- **关开关**：`kvset chat.mute_at_enabled=false`，回读 `PROBE kv chat.mute_at_enabled = false`（02:38:13）。
- **用户再发 @全体成员**（02:42）：通知链正常到达 bridge —— `QUIET ADND observe 45424365325@chatroom -> HANDLED`（昵称「测试群聊」，talker 解析成功，说明确实是防@作用域内的群通知，而非没发出来）；此间**无新的 `QUIET AT muted`**。
- **运行时计数**：`QUIET registered=true enabled=false matched=4 bridge=installed=true item=true at=true dnd=true muted=4 autoDnd=0 allOnly=true`（02:44:27）——`enabled=false` 且 matched/muted 未因这条新增，证明防@分支确实被关掉、不是「打日志但没静音」的反向假象。
- **用户听感（最终判据）**：答「**响了（有声音或震动）**」⇒ 关则响成立。
- **开关复位**：`kvset chat.mute_at_enabled=true` → `now=true`（02:45:50）；回读 `PROBE kv chat.mute_at_enabled = true`（02:46:01）、运行时 `enabled=true`（02:46:05）。
- **§8.4 成立**：关则响 ✅ / 开则不响（§9.1）✅ ⇒ **A/B 因果闭环，R8x 验收结束**。

### 9.3 【本次验收未做变异/复核的边界说明】
- 正向/反向判据均为用户听感 + 真机通知链日志，不依赖 `AT muted` 计数器；三闸 + 真跑变异（M1/M2）已在装机前完成（见台账 02:2x 条），本轮只改通道 importance 常量，未动判据链。
- 反向对照唯一自变量 = `chat.mute_at_enabled`，正/反两次都是同一群「测试群聊」的 @全体成员，通道 `wxgroup_silent_low` 未被手工改动。
