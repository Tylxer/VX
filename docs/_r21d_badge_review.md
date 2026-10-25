# R21d 角标实现·交叉复核报告

> 复核对象：`wxgroup/app/src/main/java/com/lx/wxgroup/feature/HintBadge.kt`
> 纯逻辑：`data/HintBadgeLogic.kt`、`data/HintTextLogic.kt`
> 参考范式：`feature/SnsTag.kt`（同类 ViewHolder 注入）
> 链路文档：`docs/_r21d_badge_chain.md`
> 基线：`compileDebugKotlin` EXIT=0；`testDebugUnitTest` files=30 tests=579 failures=0 errors=0（复核时实测，与文档 §9 未回填的基线一致，且 HookEntry.kt:29 的 import 已补齐）

---

## 0. 总结论

**PASS —— 无阻塞性缺陷，可上真机。** 实现严格沿用了 SnsTag 已真机验证的 ViewHolder 注入范式，八条判据全部成立或成立但有轻微注记（0 条 FAIL）。最要紧的一件事：**pos 坐标系的唯一正解 `adapter.getItem(pos)` 已正确使用、全链无一条直接 `t3.getItem(pos)` 的路径**，且 `getMsgId()J→Long` / `getType()I→Int` 的返回类型假设与既有真机反汇编文档（`_r16_v1_review.md` §`jj/m7.getMsgId()J`）一致，不是猜的。真正的风险不在逻辑，而在**需真机才能确认的运行时形状**（holder 运行时类名、header 是否真的存在、restore_view 切换瞬间的 sweep 不一致），这些已在下文 §3 列出判别方法。

需留意的三类轻微问题（非阻塞）：① 开关关掉最多延迟 30 秒才摘角标（CFG_TTL_MS 缓存）；② `apply()` 的 `host.post` 重试闭包不复核开关/命中状态；③ 2 参 + 3 参 onBindViewHolder 都挂，若 q3 的 3 参内部回调 2 参，计数器会 2×（幂等无害，但诊断要看比例不看绝对值）。

---

## 1. 八条判据逐条结论

| # | 判据 | 结论 | 依据（文件:行号） | 风险 |
|---|---|---|---|---|
| 1 | pos 坐标系 | **PASS** | `HintBadge.kt:277-278`（`getItem(adapter,pos)`）、`:401-414`（反射 `q0.getItem`）、`:396-399`（KDoc 明示禁 `t3.getItem`）。全文件 grep 无 `t3.getItem` 调用 | 无。q0.getItem 内部已做 `pos-headerCount` 换算，header/footer 返回 null（链文档 §2.2 已字节码坐实） |
| 2 | holder→itemView | **PASS**（轻微注记） | `HintBadge.kt:349-385`。逐层反射 View 字段、`isRv` 排除 RecyclerView(:387)、优先 `isRvChild`(:389-390)、面积最大兜底(:370) | 面积兜底仅当「无任一 View 字段的 parent 含 RecyclerView」时触发。bind 期 itemView 已 attach 于 RecyclerView，正常不触发；极端情况（首个 holder 实例未测量 w/h=0）可能选到内部大 ViewGroup。与 SnsTag 同款、已真机验证，风险低 |
| 3 | 复用池清理 | **PASS** | 五条失败路径均 `bound.remove` + `ensure(itemView,false,"")`：header/footer(:279-283)、nomsgid(:286-291)、未命中(:298-302)、typeOk 抑制(:308-313)、文本对账失败(:318-323)；开关关断路径 `ensure(false)`(:266) | 开关关断路径不 `bound.remove`，但 `refresh()` 会 `ensureSweep()`(:223)，sweep 用空清单全摘，且保留 bound 是为了「关掉再打开自贴回」，行为正确 |
| 4 | typeOk 判据 | **PASS** | `HintBadgeLogic.kt:35-36`（`and 0xFFFF` 低 16 位）。测试 `HintBadgeLogicTest` typeOk×4 覆盖高位标志、10000/10002 抑制、origType 自带高位标志 | 「低 16 位恰好相同」的误命中不成立：msgId 是主键已唯一定位消息，typeOk 只判断「当前是否被伪装」，不参与识别消息 |
| 5 | contentOk 判据 | **PASS**（方向正确） | `HintBadgeLogic.kt:46-52`（折平→trim→前 12 字 probe→任一 item 文字 contains）；`HintBadge.kt:316-325` 仅对 `TYPE_TEXT` 调用 | @提及（DB 存 wxid、气泡显示昵称）、emoji、`take(12)` 切到代理对中间 → 假阴性漏画；方向「宁可漏画」正确。非文本消息无内容对账，仅靠 msgId 唯一 + getItem 换算（链文档 §2.4 已声明为可接受最优解） |
| 6 | 异步/线程安全 | **PASS** | `hitMap`/`setAt`/`forceSet` 均 `@Volatile` 且**整张替换不原地改**(:88-89, :447)；后台线程只写 `hitMap`/`setAt`(:444-453)；`rescued()` 只在主线程被调(onBind/sweep/fake/dump/stat)；`bound` 用 `synchronizedMap(WeakHashMap)` + `snapshot()` 显式 `synchronized(bound)`(:513-522) | **无 ConcurrentHashMap**（用的是 `synchronizedMap(HashMap<String, Field?/Method?>)`，HashMap 允许 null value），不存在 CHM 存 null 抛 NPE 的问题。WeakHashMap 已同步 |
| 7 | 配置开关 | **PASS**（轻微注记） | `HintBadge.kt:216-224` 读 `Keys.ANTIREVOKE_MSG` + `Keys.ANTIREVOKE_SHOW_HINT`（同一个 key `antirevoke.show_hint`）。AntiRevoke.kt 注释确认语义已改为角标、`insertHint` 已废 | 关掉不立刻生效：`on()` 每 30s 才 `refresh()`(:226-229, CFG_TTL_MS=30000:79)；sweep 虽每 2s 跑但 `on()` 也走同一 30s 门。**最多延迟 30s 摘角标**。与 SnsTag 同款，可接受但需知情 |
| 8 | 静默失效 | **PASS** | `install()` 类找不到→`LogX.w`(:138)、`hooked==0`→`LogX.w`(:143-144)、setAdapter 找不到→`LogX.w`(:177, :195)。`stat()`(:717-721) 覆盖 §7 全部 7 步计数器（binds/chat/resolved/hits/added/masked/verifyFail + lastMiss）。op 分发已接线 `EntryReceiver.kt:582-590` 且主线程执行（dump/fake 需主线程，注释明确不许包 io） | 无静默盲区。唯一细点：`hookAdapterClass` 里 `cands` 为空时自身不打日志，但会被 `install()` 的 `hooked==0` 兜住 |

---

## 2. 必须修的问题（按严重度排序）

**无阻塞级必须修项。** 下列三条为轻微/健壮性问题，非本轮上机前必修，但建议记入 backlog：

### F1. `apply()` 的 `host.post` 重试闭包不复核开关/命中状态（低）
- 位置：`HintBadge.kt:533-537`
- 现象：`ensure(host, true, text)` 返回 false（仅当 host 未 attach / 非 ViewGroup / 是 RV）时，post 一个闭包重试；闭包里只重放 `ensure(host, true, text)`，**不复核 `on()` 是否还开着、这个 msgId 是否还在 rescued/forceSet**。若极窄窗口内开关被关，post 会把角标重新贴上。
- 根因：post 闭包没有携带「该不该显示」的判定上下文，把「贴角标」当成无条件动作。
- 修法（可选，二选一）：
  ```kotlin
  // A. post 里重新走一次完整判定（最稳，但要在 onBind 里把 h9/msgId 传给 apply）
  host.post {
      val r = rescued()[msgId]
      if (on() && (msgId in forceSet || r != null)) {
          ensure(host, true, HintTextLogic.badgeText(r?.origType ?: 0, r?.origContent))
      }
  }
  // B. 最小改动：post 里至少查一次 on()，关着就不贴
  host.post { runCatching { if (on()) ensure(host, true, text) } }
  ```
  实际上 bind 期 itemView 恒 attach，此路径近乎死代码，优先级可排最后。

### F2. sweep 不重跑 typeOk/contentOk，restore_view 切换瞬间可能短暂不一致（低）
- 位置：`HintBadge.kt:495-511`（sweep 只判 `hit != null`，不重跑闸门）
- 现象：角标已贴、bound 已登记后，若消息被「恢复默认显示」伪装成 type=10000，sweep 仍按台账 `hit != null` 重新贴角标，理论上角标会出现在「撤回了一条消息」灰色条上。fresh bind 会因 typeOk 抑制，但 sweep 不抑制。
- 根因：`onBind` 里 typeOk/contentOk 通过才登记 bound（:328-330 注释已说明这是刻意的「sweep 不重复走闸门」），但未考虑 bound 存续期间消息显示态被 restore_view 改写。
- 修法：不必改——切 restore_view 必须离开会话（去设置页），回到会话必然 rebind，typeOk 重新生效，窗口极小且自愈。仅记录为已知不一致。

### F3. 2 参 + 3 参 onBindViewHolder 双挂，计数器可能 2×（观察项，非 bug）
- 位置：`HintBadge.kt:158-166`
- 现象：若 q3 的 3 参实现内部回调 2 参（标准 RecyclerView.Adapter 默认链就是 `bindViewHolder→onBind(3参)→onBind(2参)`），两个 afterHookedMethod 都会触发，`binds/chatBinds/resolved/hits` 绝对值翻倍。`added` 因 `ensure` 幂等不翻倍。
- 根因：这是 SnsTag 同款设计（注释「两边都触发时 ensure 幂等，只是计数翻倍，无害」），为的是防御「只覆写 3 参不回调 2 参」的静默失效。
- 修法：不改。诊断口诀按比例看（`resolved≈chat`、`hits>0`），不看绝对值；若真要精确，可在 `bindHook` 里按 `param.method.parameterTypes.size` 只记 3 参一次，但收益低、风险高，不建议。

---

## 3. 存疑 / 需真机验证的点

每条都给出「怎么判」：

1. **holder 运行时类名是否为 `c3` 或子类**。判法：装真机后滚动会话，看 `LogX` 里 `itemViewOf` 首类 holder 的 `pick=… cands=[…]` 明细（`HintBadge.kt:377-382`）。若 pick 是 RecyclerView 或某个子 TextView/ImageView，说明字段选择链失效（会表现为 `bound=1 resolved=14` 或角标全挤在一处）。
2. **header 是否真的存在**。判法：`op=hintbadge sub=dump`，若 `getItem(pos)` 与 pos 恒等（headerCount=0），§2.2 换算退化为恒等；若 headerCount>0，q0.getItem 已做减法，dump 的 `u=` 应与 `op=forward sub=msgsnap` 的 msgId 一致。
3. **`h9.getMsgId()J` / `getType()I` 返回类型是否如反汇编**。判法：`stat()` 里 `resolved≈chat` 且 `hits>0` 即证明 `as? Long`/`as? Int` 没踩坑；若 `resolved=0` 而 `chat>0`、`lastMiss=nomsgid(h9)`，说明返回类型不是 long/int（需改成 `Number` 归一）。现有证据（`_r16_v1_review.md:93` `getMsgId()J`、ForceForward.kt:1027 同款 `getMethod("getMsgId").invoke`）支持 long，属低风险。
4. **restore_view 交互**。判法：开 restore_view → rescued 消息显示成撤回条 → 角标应消失（typeOk 抑制，`masked` 上升）；关 restore_view → 角标回来。同时验证 F2 的 sweep 不一致是否真的可见（大概率不可见，因为切换要离开会话）。
5. **非文本消息（语音/图片/视频）无内容对账**。判法：fake 一条语音 msgId，dump 看角标是否落在「语音气泡」那条 item 上（靠 msgId 唯一 + getItem 换算）。这是全链唯一没有运行时自证的分支，务必用 fake 单独验证一次。
6. **`getItem` 返回非 h9 对象（日期分隔条/系统提示）是否安全**。判法：滚动一个含「日期分隔」的会话，确认无 `missed` 异常暴涨（分隔条若返回无 getMsgId 的对象会 `missed++`/`lastMiss=nomsgid(...)`，这是安全的预期行为，不是 bug）；若分隔条本身是 h9（有 getMsgId 且 msgId>0），会被查 rescued 集但未命中→摘角标→return，同样安全。

---

## 4. 反向对照建议

「故意做错一次看会不会出问题」的实验，验证判据真的有效（都在本机可做，不上真机风险）：

1. **摘掉 isRv 排除**（临时把 `HintBadge.kt:363` 的 `&& !isRv(v)` 删掉）→ 预期：`itemViewOf` 面积兜底选中 RecyclerView 自身，角标全加到列表容器，`dump` 里 `bound.size=1` 而所有 item 的 `txt` 都指向同一个 host。恢复后正常。**证明「排除 RecyclerView」这条防线是真的在拦**。
2. **故意用 `t3.getItem(pos)` 替代 `adapter.getItem(pos)`**（临时在 `getItem` 里改调 t3）→ 有 header 时预期角标错位到邻居，`dump` 的 `txt` 与 fake 的 msgId 内容对不上。恢复。**证明 pos 换算是唯一的正确路径**（仅当 headerCount>0 时此对照才有区分度，headerCount=0 时两者等价，正好印证「无 header 时恒等」）。
3. **临时把 contentOk 短路成恒 true**（`HintBadgeLogic.contentOk` 返回 true）→ 对文本消息，若 pos 错位，角标会打到邻居且无内容校验兜底。恢复。**证明 contentOk 是防错位的最后一道闸**。
4. **fake 一条当前屏幕可见的真实文本消息**，不滚屏直接 `dump` → 角标应出现在「txt 含该消息内容」的那条 item 上，且 `u=` 与 msgsnap 一致。这是对整条「getItem→msgId→rescued/forceSet→apply」链路的端到端正验（链文档 §2.5 已设计，建议作为上机第一步）。
5. **开关 30s 缓存对照**：开角标→关 `antirevoke.show_hint`→立即 `stat()` 看 `enabled`（应仍是 true 直到 30s 内某次 `on()` 触发 refresh）→等 35s 再 `stat()`（应 false）。**量化验证开关延迟，确认用户可感知的响应时间**。

---

## 5. 单测覆盖缺口

| 缺口 | 现状 | 建议 |
|---|---|---|
| `typeOk` 的 `h9Type = -1`（typeOf 反射失败降级） | 未测 | 补 `typeOk(-1, 1)` 应为 false（反射拿不到 type 时抑制而非误贴） |
| `contentOk` 的 `take(12)` 切到代理对中间 | 未测 | 补一例：origContent 首字符是 emoji（如 `😀😀😀你好世界...`），断言当前行为（false 漏画）可接受，锁定「宁可漏画」语义 |
| `contentOk` 的 `@` 提及假阴性 | 未测 | 补一例：origContent=`@wxid_abc 你好`、texts 里是 `@张三 你好`，断言 false（锁「宁可漏画」，防止未来有人改成过度匹配） |
| `contentOk` 的 `texts.any` 多 TextView 命中 | 已有（`contentOkBubbleHasSurroundingText`） | 覆盖到位 |
| `typeOk` 的 origType=10000/10002（台账被污染） | 未测 | 补 `typeOk(10000, 10000)` true / `typeOk(1, 10000)` false，锁定「台账 origType 若是系统态也不误贴」 |
| `badgeText`/`stripOuterBrackets`/`BADGE_PREFIX` | 已充分覆盖（`HintTextLogicTest` 角标段 8 条 + `badgeNeverTrailingSeparator` 穷举） | 无需补 |
| `HintBadge.kt` 自身的 onBind/sweep/ensure 状态机 | 无单测（Android 依赖，无法 JVM） | 靠 §4 的反向对照 + 真机 dump 覆盖，不建议为它引入 Robolectric 重活 |
