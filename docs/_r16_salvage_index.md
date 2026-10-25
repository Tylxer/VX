# R16 批次 1 · P1/P2 失败抢救索引（交接用）

> 生成时间：2026-10-24 16:32
> 起因：P1（`d3a69640`）与 P2（`03ede3b9`）双双撞 **429 TPM 超限**（上限 1,000,000 tokens/min）。
> 二者分别跑了 18m36s / 58 次调用、约 30min / 61 次调用，**成果全在上下文里、未落盘**。
> 主会话已从子代理 transcript 逐条提取**全部已返回的真实工具输出**，落盘原始证据：
> **`docs/_r16_p1p2_salvage_raw.md`（269KB，P1 段 114KB / P2 段 135KB，119 个 `=====` 小节）**

---

## 使用方式

```bash
# P1 的某次调用（编号见下表）
grep -n "^===== \[P1#24\]" docs/_r16_p1p2_salvage_raw.md
# 直接看某段
sed -n '/^===== \[P1#24\]/,/^===== \[P1#25\]/p' docs/_r16_p1p2_salvage_raw.md
```

⛔ **不要整读 269KB**。先查下表定位编号，再 `sed -n` 切一段。

---

## P1（G1 原生转发锚点）已有成果 — 可直接复用，不必重跑

| 编号 | 内容 | 状态 |
|---|---|---|
| #12 | `MsgRetransmitUI` 类定义：`classes7.dex`，super=`MMBaseActivity`，access=0x0001，**30 方法**（`g6 h6 v6 A6 B6 C6 D6 finish i6 j6 k6 l6 m6 n6 o6 onActivityResult onCreate onDestroy onSceneEnd p6 q6 r6 s6`） | ✅ 完整 |
| #24 | **`g6(Lhl0/l1;)V` 完整字节码**（code_off=0x62d018）。核心判据：先读 `l1.a`(→`modelbase/k1`)，`getType()==522` 且 `instance-of hl0/q0` ⇒ 取 `q0.f:J` 传给 `h6(Long)`；随后 `getType()==110` 分支 | ✅ 完整 |
| #26 | `h6(Ljava/lang/Long;)V` 完整字节码（code_off=0x62d0c0）：new `UpdateForwardMsgIdEvent` → `g:Lgj/hx;->a:J`=msgId、`->b:I`=3 → `IEvent.publish` | ✅ 完整 |
| #27 | `onCreate` 字节码（code_off=0x62fea8）：`HellActivity.onCreate` 父调用 + `ko/i0` 取实例 + `rf4/u` 引用 | ⚠️ 只取到前 ~20 行（`head -200` 截断风险） |
| #40 | **`MsgRetransmitUI.g6` 的 8 处调用点**：`by3/g.invoke()` · 同类 `k6`/`l6`/`s6`/`u6` · **`qf4/o4.F8`** · **`qf4/o4.O8`** · **`qf4/o4.W8`** | ✅ 完整，**这是 G1 的主线索** |
| #39 | `hl0/q1.f(Lhl0/m;)Lhl0/q1;` 的 9 处调用点：同类 `k6`/`l6`/`s6`/`u6` · `qf4/o4.O8`/`Q8`/`W8` · `modelimage/f2.C2` · `qf4/j0.C2` | ✅ 完整 |
| #54 | `qf4/o4.W8(String,String,I,I,J)V` 的 3 处调用点：`plugin/fav/ui/p5.run()` · **`ui/chatting/m4.L(...)`** · `qf4/o4.V8` | ✅ 完整 |
| #55 | `qf4/o4.F8(String,String,ZLq54/si;)V` 调用点（**跨 classes3/classes4 两个 dex**）：`ExdeviceRankInfoUI.onActivityResult` · `chatting/component/c0.a(Z,String,I)` · `f71/p1.a` · `jz2/i.mmOnActivityResult` · `profile/ui/x4` · `profile/ui/z2` · `EnterpriseConversationFmUI` · `fj3/c` · `h54/g.a` 等 | ⚠️ 输出被 `head -50` 截断（显示"合计"后被切） |
| #57 | `ui/chatting/m4.L(Context,String,storage/h9,Z)V` 反汇编（code_off=0x5899fc）：`m4.N(ctx,talker,h9,'text')` 门控 → `jj/m7.getContent()`/`p0()` → `m4.c(String,I,Z)` 出目标串 | ✅ 完整 |
| #41 | `MsgRetransmitUI.k6(String,I,String,String,modelbase/t0,J)V` 反汇编（code_off=0x62d368）：`this.g`(String) 判空 → `msg_forward_sns_obj_id` const-string → `oy/l` 类引用 | ⚠️ 只取到前 ~37 行 |
| #50–#53, #56 | `qf4/o4.V8` · `qf4/o4.W8` 细节 · `msg` 相关辅助 | ✅ 已抓，未细看 |
| #29–#38, #48–#49 | `hl0/m` / `hl0/q1` / `t81/g` / `hl0/l1` / `qf4/o4` 的类定义与字段引用（`fieldref`） | ✅ 已抓 |

**尚未做的（P1 的缺口）**：
1. `onCreate` 的 **Intent extra 键名清单**（骨架 `[DONE]` 声称解出 `Retr_Msg_*` 系列，但**原始输出没在 transcript 里找到** —— #27 只取到前 20 行且未见 `Retr_` 字样）。⚠️ **这条要在重派时重新取证**，别信骨架。
2. `k6`/`l6`/`s6`/`u6` 四个方法的完整字节码（只有 `k6` 前 37 行）。
3. `qf4/o4.O8` 的 8 参数签名逐个映射（哪个是 talker、哪个是 msgId、哪个是 `hl0/m`）。
4. **`nativeForward` 的可实现签名表**（骨架 §2 仍 `[TODO]`）。
5. 未做 `_r13_sigmap.py` 参数映射 ⇒ 按 `CLAUDE.md` 的 DEX 参数铁律，**手算会整体偏 1 位且不报错**，必须用工具。

---

## P2（D3 视频发送链 `modelimage/o3.j()`）已有成果

| 编号 | 内容 | 状态 |
|---|---|---|
| #14/#15/#16 | `com/tencent/mm/modelimage/o3` 类定义与全部方法列表 | ✅ 已抓 |
| #19/#20 | **`o3.j()` 反汇编**（`_cache/_r11_dex7_dis.txt` 行 2127900–2127990） | ✅ 已抓，需核对区域是否完整 |
| #21/#22 | `o3` 类头（access/super/字段） | ✅ 完整 |
| #26 | `hl0/o1.<clinit>` 常量表（行 1306986–1307050） | ✅ 完整 |
| #30/#31 | `hl0/q1.<init>` 与 `q1.a()` 反汇编（行 1309154–1309300） | ✅ 完整 |
| #35/#37/#38 | `bm0/p1.<init>` + **`p1.doScene` 前后半**（行 76125–77630，含 `021f` 分支） | ✅ 完整 |
| #43 | `bm0/b3.i` 反汇编（行 68350–68400） | ✅ 完整 |
| #49/#50 | **`bm0/u0.N` 反汇编**（行 82751–83040，**含 `o3.j` 调用点与 `o3` 赋值段**） | ✅ 完整 —— **这是 D3 的主线索** |
| #57 | `u2.f`（行 86527–86580）与 `m2.O8`（行 73076–73100） | ✅ 完整 |
| #60 | `o1.doScene` 反汇编（行 2089820–2089900，判断字段读取） | ✅ 完整 |
| #23/#36/#42/#46/#55 | 自写的 DEX 结构解析（uleb/类型表）小脚本输出 | ✅ 已抓 |

**尚未做的（P2 的缺口）**：
1. `o3.j()` **完整参数签名映射**（同样未用 `_r13_sigmap.py`）。
2. `bm0/u0.N` 里调 `o3.j` 时**传的是哪几个字段**（#49/#50 有原始字节码，但未做字段语义归纳）。
3. `_r16_video_send.md` **整篇未写**（结论/签名表/边界/真机方案全缺）。
4. 未与 `FileSender.kt` 现有 `getType()==6` 路径做对照（`FileSender` 已读过：#4）。

---

## 重派时的硬要求（本次失败的根因修复）

⛔ **prompt 必须含「分步落盘」，否则同样会全丢**（`CLAUDE.md` §五 / 用户级记忆 0.5）：

1. **第一步就建产物文件**（写空骨架 + `[DONE]` 标记位），不要等分析完才写。
2. **每完成一个子步立刻 `Edit` 追加**，格式：`## [DONE] <子步名>` + 证据。
3. 产出文件：
   - P1 → `docs/_r16_g1_native_forward.md`（覆盖现有 938B 骨架）
   - P2 → `docs/_r16_video_send.md`（新建）
4. 起步先读 `docs/_r16_salvage_index.md` 与 `docs/_r16_p1p2_salvage_raw.md` 的**对应小节**（用 `sed -n` 切，别整读）。
5. ✅ **可以并发派 Pro lane** —— 原「不许两条 Pro lane 并发」的说法**已被推翻**（用户扩容 TPM 后明确要求恢复并发，实测 P1r/P2r 并发下产物正常分步落盘）。
   ⛔ 真正必须守的是：prompt 强制「第一步建产物文件 + 每子步 `Edit` 追加 `[DONE]`」，否则一次打断全蒸发。

---

## 429 的可复现证据

| lane | model | 结束时刻 | 工具调用数 | 产物 |
|---|---|---|---|---|
| P1 | `custom-local:deepseek-v4-pro-202606` | 16:28 左右 | 58 | 938B 骨架 |
| P2 | `custom-local:deepseek-v4-pro-202606` | 16:28:24 | 61 | **无文件** |
| P3 | `custom-local:deepseek-v4-pro-202606` | 仍在跑 | — | 1435B 骨架 |

两条 Pro lane 各扛 60 次左右的重型反汇编调用（每次输出 1k–3k tokens），叠加即超 1M/min。
**P3 单独跑时压力已落单，故未打断它** —— 打断会导致它重读上下文、二次燃烧。
