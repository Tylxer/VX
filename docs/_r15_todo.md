# 未完成任务总表（全模块 · 2026-10-24 R16 盘点）

> 权威口径：本表**只列「还没做完」的**。已完成项在这里查不到就是已收口。
> 每项标了**档位**（按 `CLAUDE.md` §四②）与**是否要真机**、**是否需要人工配合**。
> ⛔ 模型分流已于 02:41 恢复。R16 的 lane 切分见 `docs/并行开发轮次计划.md` §R16。

**盘点方法**（可复现）：`docs/需求功能清单.md` 六段逐条 + `grep -rn 'TODO|FIXME|尚未|未取证|待取证|暂不可用'` 全 `.kt`
+ `grep 恒 false|未接` + 逐项反查调用点（不只看注释，**注释经常比代码旧**）。

---

## A · 朋友圈图片（主线，已改道 SNS 缓存直读）

| # | 任务 | 状态 | 档位 | 要真机 | 备注 |
|---|---|---|---|---|---|
| A1 | SNS 缓存取图**方案设计** | ✅ **已完成**（`_r15_sns_cache_plan.md`） | Pro | 否 | Q2 分桶公式有误，已订正为 **Q2b 索引直扫** |
| A2 | 按方案**实现**（`MomentsLogic` 纯逻辑 + `MomentsExport` 接线 + 单测） | ✅ **已完成**（`i1-sns-cache-impl`，`tests=588`） | Flash | 否 | — |
| A3 | **复核** A2 | ✅ **完成**（`_r15_v1_review.md`，**PASS WITH CONDITIONS/禁上真机**） | Pro | 否 | P0 = protobuf 未接；否证了我的「二次截断」判断 |
| A3b | **接 protobuf**（`mediaIdsOf`） | ✅ **已完成**（`i2-proto-impl`，`tests=606`） | Flash | 否 | 无反射无三方库，手走线格式 |
| A3c | **只读探针 `sub=snsprobe`** | ✅ **已完成**（主会话直接改） | Flash | 否 | 解决「验的人被被验的开关锁死」 |
| A4 | 真机验收：装包 → `sub=snsprobe` → 看 `hit=` → 再开 `COPY_IMAGES` | ⛔ **待用户通知**（我不自行上机） | — | **是** | 三步判据见 `_r15_i1_impl.md` 第三轮 |
| A5 | `取证结论.md §6.4b` 措辞订正 | ✅ **已完成** | Flash | 否 | — |
| A6 | `需求功能清单.md` 6.4 三行合并订正 | ✅ **已完成** | Flash | 否 | — |
| A7 | **分桶公式订正**（`MD5(mid)[2]/[8]` 只中 0.68%） | ✅ **已完成** | Flash | 是（只读） | 见 `tools/_r15_bucket*.py` |
| **A8** | **旧 md5 路线与 R15 新链路并存**：`MomentsExport.logImgProbe()`(:434) 与 `tryCopyImages()`(:786) 仍走 `mediaKeys → md5sOf → imageCandidates` | 🔴 **待收敛** | Flash | 否 | 真机 `sub=img` 会打一堆 `picked=NONE`，**看起来像新链路也失败**，实际那是旧路线。必须①改成委托 `buildSnsIndex/lookup`，或②删掉并只留 `snsprobe`。**⛔ 严格排在 A4 之后**（否则污染 A4 判据） |
| **A9** | `MomentsLogic.imageCandidates():310` / `mediaKeys` / `pickExisting` 的 KDoc 仍写「本轮诚实边界：尚未取证」，**已被 R15 证伪** | ✅ **已完成**（R16-F1，纯注释） | Flash | 否 | 3 处 KDoc 改标「旧 md5 路线遗留，仅服务 `sub=img`/`sub=imgtry` 探针对账」，并写入 5 条 R15 真机事实（`picked=NONE` 是**必然**不是 bug）。未动逻辑 ⇒ 不污染 A4 |
| **A10** | `MomentsExport` 头注释(:32)「图片（原图）导出：命名规则**未取证**，本版不做」已过时 | ✅ **已完成**（R16-F1，纯注释） | Flash | 否 | 改标题为「命名规则已取证（R15），但**旧 md5 路线仍在**」，正文写入目录/命名规则/0.68% 命中率三条，并点名 `logImgProbe`/`tryCopyImages` 属遗留探针、新链路是 `buildSnsIndex`/`copyImagesForRow`/`probeSnsCache` |

## B · CDN 批量下载路线（已判定为弯路）

| # | 任务 | 状态 | 说明 |
|---|---|---|---|
| B1 | 静态取证 `ok0.x0#n` 触发条件 | ✅ **已完成**（`_r14_ok0_n.md`） | — |
| B2 | 真机验 Q4 五条判据 | ⛔ **不做了** | SNS 直读已可行 |
| B3 | I-3（改写 `imageCachePath`）/ I-4（判明文） | ✅ I-4 已定案（明文 JPEG）；I-3 **取消** | — |
| **B4** | **两个死开关仍在设置页对用户可见**：`sns.cdn_img`（批量下载图片）/ `sns.cdn_rewrite_path`（改写落盘路径） | 🔴 **待处置** | ⑫.9 已定案：`picCachePath` 字段存在但**恒空**（`hitPicE=7/hitNoField=0`）、`ok0.x0#n` **从未触发**（`hitN=0`）⇒ 这条路对用户「改了也没用」。且 `rewritePath` 在 `SnsCdnImage` 里**零消费点**（只有 `data class Cfg` 一个字段，从未被读）。按 `Keys.kt:47` 自己定的规则（「做不成的用 hidden」）⇒ `hidden = true`。<br>✅ **R16-F2 已完成**：`Keys.kt` 两开关加 `hidden = true`，`PrefsVisibilityTest` 的 `expectedHidden` 扩到 4 项；主会话做反向对照（摘掉一个 `hidden` ⇒ 4 个测试立即 FAIL）证明断言有判别力 |

> ⚠️ B 段唯一保留价值：`_r14_ok0_n.md` Q5 的 `ok0.x0#o` 入参含 `.p0`=落盘路径、`.f1`=snsCipherKey，
> 若 A4 的 mediaId 路线全部走不通，这是备用方案。

## C · 旧功能补验收（代码就位、缺真机）

| # | 任务 | 状态 | 要真机 | 需人工 | 备注 |
|---|---|---|---|---|---|
| C1 | 摘掉评论域 hook 注册 | ✅ **早已完成**（`HookEntry.kt:271-284` 已注释） | 否 | — | 上一轮「仍开着」是**误判**：`SNSC stat enabled=true` 读的是**配置开关** |
| C2 | **被@静音**验收 | 🟡 只到「注册就绪」（`matched=0`） | **是** | **是（另一账号群里 @ 你）** | 脚本 `_r5_quiet.py --wait=90` |
| C3 | **免打扰时段**验收 | ✅ **PASS**（`now=139 active=true`） | 是 | 否 | `_r14_device_pass2.md §B` |
| C4 | **新加群自动免打扰**验收 | 🟡 只到「钩子存活」（`joined=0`） | **是** | **是（真拉新群）** | ⭐ 也可**不用人工**：`op=quiet sub=grp sub2=force --es val <chatroomId>` 直接走 `applyOne` 全流程（含真开关 CGI）。**先用 force 验链路，再等真新群验事件** |
| C5 | 群名数据源（`GROUPNAME ready=false loads=0`） | 🟡 **懒加载，从未触发** | 是 | 否 | `loads=0` = 从未尝试（**不是失败**）。`warmUp()` 在 `AntiRevoke` 拿到主库时调；可 `op=quiet sub=at sub2=grpname` 强制刷一次 |
| C6 | **K9 微信来电放行**验证 | ❌ 未做 | **是** | **是（真来电/语音）** | — |
| C7 | 强制转发 `resendFile` 真机验收 | ⛔ 阻塞 | **是** | 否 | 硬前置 = **D1**（G2 消息种类识别） |
| C8 | 从归档恢复真机验 | 🟡 探针已齐，只差真机 | **是** | 否 | 先 `sub=plan`（dry），再 `sub=apply`。代码 + 探针都已存在（`EntryReceiver.kt:320-357`） |
| **C9** | **群发「开始群发」UI 按钮路径**未手验 | 🟡 广播路径已 PASS | 是 | 否 | 与广播共用 `launchRun`，风险极低，可顺带上机点一次 |

## D · 未取证锚点（卡功能）—— **全部 Pro**

| # | 任务 | 状态 | 档位 | 能否纯离线 | 备注 |
|---|---|---|---|---|---|
| D1 | **G2 消息种类识别**（`ForceForward.snapshotNow():459` 恒 `kind=UNKNOWN`） | 🟡 **离线半已完成**（R16-P3 + 主会话复核接线）；**终判待真机 dump** | **Pro** | 🟡 **一半可离线** | 结论 = adapter 链（`u3.getPositionForView` → `t3.getItem(pos)` → `h9`）。`docs/_r16_g2_msgsnap.md` + `tools/_r16_msgsnap.py`；`ForceForward.kt` 已加 `sub=msgsnap` 探针，`compileDebugKotlin` EXIT=0、`tests=606` 全绿。**真机跑 `op=forward sub=msgsnap` 即可终判**。它是 C7 的硬前置 |
| D2 | **G1 原生转发锚点**（`nativeForward():633` 恒 false） | ✅ **离线取证完成**（R16-P1r 交付 22KB / **10 个 `[DONE]` / 零 `[TODO]`**）；**终判待真机** | **Pro** | ✅ **可纯离线**（全 15 dex 静态） | 产物 `docs/_r16_g1_native_forward.md`。原 P1 因 429 失败（18m36s/58 调用），证据已抢救（`_r16_p1p2_salvage_raw.md` + `_r16_salvage_index.md`）。<br>**主会话独立复算并订正 4 处**：① `Retr_Msg_Type` 默认值 `3` → **`-1`**（两处，`const/4` 解码 bug 所致）；② `Select_Conv_User` 从「⚠️强推测」升级为 **✅实证**（三处：§0 / §2.3 / §3）。<br>**结论**：原生转发入口 = `MsgRetransmitUI` + `Select_Conv_User`；`onCreate[1426→1457]` → `z6(String,List)` → `[44] hl0/r1.b(talker)` → `[48] q1.e(内容)`，**可跳过选择器直达发送**。<br>⛔ 附带订正：`qf4/o4.O8` 是**发图**链（写 `q1.r` + `q1.h=13` + 日志 `'sendImg'`），与 `FileSender`（写 `q1.j`）是两条 constructor 分派，**字段不能混用** |
| D3 | **视频降级发送**（`ForwardLogic.canResendFile` 对 VIDEO 判 false） | ✅ **已完成（结论：不可行，维持 false）** | **Pro** | ✅ 纯离线 | **⛔ 最终判定：视频降级重发在本链路下没有任何可行路径，`canResendFile` 保持 false（有字节码支撑，非保守）**。<br>取证 `docs/_r16_video_send.md`（30.6KB，19 个 `[DONE]`、零 `[TODO]`）。<br>关键证据（主会话逐条复算）：**① `path` 不在消息库** ⇒ `b3.i` 返回 null ⇒ `u0.N()` 在 `[4]` 无判空直接 `t2.h()` **NPE**；或走 `p1.doScene` 在 `[11]` 判 null ⇒ 日志 `'Get info Failed file:'` + 回调 `a(3,-1)` + `return -1`。**② `path` 在库** ⇒ 能发，但 talker 取自 `t2.q` = **原会话**（`t2.a(Cursor)` 的 `getString(16)`；`b3.s` 插入时第 3 参落 `t2.q`；`u0.N` 日志称其为 `user`）⇒ **发错人**。<br>正解方向：`WXVideoObject` + `sendAppMsg`（同普通文件链），不碰 `o3`/`t2` |
| **D4** | **微信标签数据源 `LabelSource` 从未真机验证装载** | 🟡 静态就位、真机零记录 | **Pro**（取数链推导） | ❌ 要真机探针 | 分隔符已定案（英文逗号，`2026-10-18` 记忆）；`readLabels` 走 `rawQuery` 反射。**全项目搜不到一条 `LABEL loaded` 真机日志** ⇒ 「K3 标签段 / ForwardPicker Tab3」是**没验过**的，不是「没做」。判据 `op=label sub=stat` + `sub=of` |

> ⚠️ **D3 的旧描述「微信标签 Tab（G5，选择器只有两段）」已订正**：
> `ForwardPicker.kt:211-226` **有** Tab3（`TAB_LABEL`），`MainPage.onEnterSpec` 的群发/导出名单也**有**标签段。
> 真实缺口是 D4（数据源没验过），不是「Tab 没写」。旧描述的「G5」编号还与该文件里 CDN 的 G5 撞号，一并废弃。

## E · 工具缺陷 —— **已全部修完**（2026-10-24 03:3x 复核）

| # | 工具 | 状态 |
|---|---|---|
| E1 | `tools/_r5_quiet.py` 判据缺失 + 不复位配置 | ✅ 已修（+76/-36，实跑 PASS，`finally` 复位） |
| E2 | `tools/_smoke.py` UI 断言前没回主页 | ✅ 已修（+9） |
| E3 | `dev.sh` / `_check.py` / `_fileb.py` / `_r9_cdn.py` / `_r13_findclass.py` 共 6 处 | ✅ 已修（含 `type_list` uleb128 误解析根因） |

## F · 阻塞项 / 待用户决策

| # | 事项 | 说明 |
|---|---|---|
| F1 | 设备在线 | ✅ `467cf5bc` PJD110 正常，微信 2588-8.0.48 |
| F2 | **需人工配合的真机项** | C2（@我）/ C4（拉新群）/ C6（来电）—— 建议**一次性凑齐** |
| F3 | 上一轮真机跑动改了你的开关 | `chat.mute_at_enabled` / `chat.auto_dnd_new_group` / `chat.dnd_enabled` 已被脚本置 true，**已复位 false**；`dnd_start/end` 留 `02:12~04:17`（enabled=false 不生效） |
| **F4** | **需要你拍板：朋友圈导出「失败只弹窗」** | `需求功能清单.md` 6.4 写「失败只弹窗」，代码实为「静默跳过 + 一行日志」（`MomentsExport` 头注释:38 说明理由：后台线程弹 Dialog 要把微信撩到前台，比不弹更糟）。**两边不一致，等你定**：改需求文档（推荐）还是改代码 |

## N · R16 盘点新发现的缺口（本轮新增，原清单里没有）

| # | 缺口 | 位置 | 档位 | 要真机 | 说明 |
|---|---|---|---|---|---|
| N1 | **`MuteAtLogic` 的「群名匹配未接线」注释已过时** | ✅ **已完成**（R16-F1） | Flash | 否 | KDoc 改为「群名匹配**已接线**（`MuteAt.kt:65`）」+ 懒加载降级说明（冷启动首条通知可能退回纯 talker 匹配，属预期）。真缺口只剩 C5（数据源没装载） |
| N2 | **`RedPacket` 4 个候选 Activity 未在 8.0.48 取证** | `RedPacket.kt:62-75` | Pro | 是 | 但有兜底：真机「开」是靠 `text/contentDescription` 文案命中，**功能已双向验收 PASS** ⇒ 低优先，可先维持「找不到就静默跳过」 |
| N3 | **`ForwardPicker.PENDING_MAX_TARGETS = 0`**（一次批量转发最大目标数） | `ForwardPicker.kt:533` | Pro | 是 | 微信原生 UI 限 9 个，但模块是「逐目标分别调」的批量链路，是否继承上限**未取证**。当前 0 = 不拦 |
| N4 | **`AutoDndGroup` 的群主（owner）字段无锚点** | `AutoDndGroup.kt:46-47` / `:353` | Pro | 是 | ⇒「自建群不设」**不生效**（需求 6.1 第四条未实现）。`applyOne(ownerWxid=null)` 参数已预留 |
| N5 | **`MomentsAuto` 待取证 1/2**：显式 Intent 重新拉起时间线是否**真触发服务端拉取** | `MomentsAuto.kt:50-56` | Pro | 是 | 第 2 条（滚到底是否稳定触发加载更多）**已实证**（`grow 0→10 / 10→20`），只剩这条 |
| N6 | **备份 6.7 的 SAF 路径仍是唯一通路**，`tools/_backup.py` 走的是「目录兜底」绕开 SAF | `需求功能清单.md` 附录 C | Flash | 是 | 附录 C 那条「Android/data 用户能否在文件管理器看到」**至今没验**（手机早已连上）。取不到 ⇒ 要启用 6.4 预留的 SAF 授权入口 |
| N7 | **`SnsCommentGuard` / `SnsCommentTag` / `SnsCommentLogic` 冻结代码**（约 2000 行）物理归属未定 | `HookEntry.kt:271-284` 已注释不装 | — | 否 | 2026-10-20 决定「不做了」。**用户当时说「是否物理删除待定」，至今未定** |
| N8 | **`SnsCdnImage.rewritePath` 是死字段** | ✅ **已完成**（R16-F1+F2） | Flash | 否 | `SnsCdnImage.kt` 加 4 行注释标明零消费点 + 判死依据；`Keys.kt` 对应开关 `hidden = true`。主会话独立 grep 复核：「全仓零读取」为真（仅 3 处命中：注释 ×2 + 声明 ×1） |
| **N9** | **`msgsnapReport` 探针在 RecyclerView 形态下恒失效** | ❌ **未修（P0，V1 复核实锤）** | **Pro**（链路判据重设计） | 是（最终仍要真机 dump） | `ChattingRecyclerView.getPositionForView(View)I` 反汇编仅 `const/4 v1,#-1; return v1` ⇒ **恒返回 -1**。而该形态由运行时 AB 开关 `adapter/x;->c1` 决定（值 = `dh4/e0;->e(dh4/g;->v;I)`，默认 4），`l(Context)` 里 `[43] if-eqz` 两分支分别构造 `ScrollControlRecyclerView`（`extends ChattingRecyclerView`）/ `ui/chatting/view/l`（`extends ScrollControlListView`）。<br>⛔ 「改走 `u3` 接口」**无效** —— `getListView()` 返回的就是 `T:u3` 字段那个对象，接口反射与具体类反射调到同一实现。<br>后果：RecyclerView 形态下 `pos` 恒 -1 ⇒ `h9/msgId/type/isSend/imgPath` 全 `none`，**探针失效且伪装成「adapter 链不通」**，会直接误导 D1 终判。<br>修法方向：RecyclerView 形态改用 `RecyclerView.getChildAdapterPosition(itemRootView)`；并对 `list=recycler && pos=-1` 单独标注为「预期行为」而非故障。<br>证据 `docs/_r16_g2_msgsnap.md` §1.2b |
| **N10** | **`fliterPre` 实现起点错（`i=2` 应为 `i=0`）** | ❌ **未修（P0，21:3x 反汇编实锤）** | **Pro**（改判据+改代码+补单测） | 否（可纯离线） | `Lbf3/t0#s` 反汇编 `[19] const/4 v3,#0` ⇒ **从下标 0 起跳前导 '0'**，而 `MomentsLogic.kt:429` 写 `var i = 2`，KDoc 也写「从下标 2 开始」。<br>⛔ **假自洽警告**：先前记的「真机 `fliterPre` 自洽 1029/1029」**验证不了这条**。真机 1029 个图片文件名里纯数字段 **100% 是 20 位、100% 首位为 `1`**（从无前导 0），所以两实现在「可见文件名」侧永远同分，实则对同一 mediaId 给出**不同**候选名（去重 527 个样本，两实现 527/527 全不同）。<br>影响：`snsNameCandidates()` 有「`mediaId` 原值」兜底，`vm3.d` 本身是 20 位数字时大概率仍能命中 ⇒ 不必然漏图；但若 `vm3.d` 真带 `"00"` 前缀则**稳定漏掉全部原图**。<br>连带重算：`MD5(fliterPre(mediaId))[0]/[1] = 4/1029(0.39%)` 是用错实现算的，方向可能反过来（`MD5(mediaId)[0]/[1]=98.45%` 与 `[:20]` 100% 两行不受影响，索引直扫方案不受影响）。<br>订正已落 `docs/_r15_sns_cache_plan.md` 新增「重大订正」节 |

---

## 汇总（按「能不能现在干」分三堆）

| 堆 | 项 | 数量 |
|---|---|---|
| 🟢 **现在就能干（离线、不碰设备）** | ~~A9 A10 B4 N1 N8~~（R16 已完成）· ~~D1 离线半~~（R16-P3 已完成）· ~~D3~~（R16-P2r 已完成，结论=不可行）· ~~D2~~（R16-P1r 已完成） | 7 → **0（全部离线项已清空）** |
| 🟡 **要真机但不需要人工** | A4 C5 C8 C9 N6 + D4（探针一跑就有结论） | 6 |
| 🔴 **要人工配合或等前置** | C2 C4 C6（人工）· C7（等 D1）· D1（等真机 dump）· N2 N3 N4 N5（要真机取证） | 9 |
| ⚪ **等你拍板** | F4（弹窗口径）· N7（冻结代码删不删） | 2 |
| ⛔ **不做了** | B2 · I-3 · 评论域全部 | — |
