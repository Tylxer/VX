# R86c「B1~B4 只读取证」可执行性核验 = 已执行过 · 无新信息 · 升级判据存在物理死结

> 性质 = 事实核验（读已有产物 + 源码比对）+ 汇总，Lite 类，主会话执行。
> 触发：用户 2026-11-26 对「是否走 B6 方向 B1~B4 只读取证」回「继续」。执行前例行核实，发现该批步骤已在 B5 轮做过。
> 纪律：不改码、不碰设备、不发图、不装机。只引用已归档结论，不重写。

## 核验结论（一句话）

Lite 产物 `docs/_r86c_origbytes_feasibility.md:138` 把「B1~B4 只读取证 + B5 干预实验」列为待执行最小下一步，但**这五步在 R86c B5 轮已全部执行并归档**（`docs/_r86c_b5_armresult_verdict.md`，四发真机 on1/on2/off1/stock），结论 = C2 命中但肉眼模糊 ⇒ B6 方向 NO-GO。且升级判据要求「肉眼 A→B→A 翻转」，而 B5 探针 C2 只读、不调 j6、不发消息，肉眼在只读阶段**物理不可能翻转** ⇒ 该方向在「不落码」前提下已无可执行的新取证动作。

**根因（为何 Lite 产物会误列）**：派发 Lite 子代理时给的背景锚点含 direction / generalize / stability / q4b，但**未列 `b5_armresult_verdict.md` 作为「B5 已执行且 NO-GO」的既成事实**；fresh-eyes 子代理据旧文档 `direction_verdict.md:160-168` 的规划把 B1~B5 当成待办。属主会话派发锚点不全，非子代理推理错误；其核心裁决（根子不可靠可控 / 泛化 NO-GO / G1 证伪）经主会话逐条亲核仍成立，仅「最小下一步」一节需作废。

## 逐条核验：B1~B4 已执行证据

| 步 | Lite 建议的动作 | 已执行事实（产物 + 源码锚点） | 裁定 |
|---|---|---|---|
| B1 | 读自建 checkExist task 六字段（fullpath/enable_hitcheck/fake_sig/fileId/onlycheckexist/fileType） | 六字段在 `B6CheckExistProbe.kt:270-280` 全部 `put`；B5 轮 taskFields 双 talker 已实测（`_r86c_b5_armresult_verdict.md` F2/F3） | 已做 |
| B2 | Proxy C2 加打 args[3] 类名 + Ltj/f 全 String 字段 | `logC2Shape`（`B6CheckExistProbe.kt:411-423`）已实现；B5 轮 F4/F5 实测 `cls=tj.f` 非空壳、`md5Len=32` | 已做 |
| B3 | 同时刻 stock 原生对照（肉眼+日志） | B5 轮 stock 发（F8：c2c `fbytes=45522 fmd5pre=cff4f5a5` → x0cb `hit=1 exist=true` → 肉眼清晰）；b3 `:152` 同向，服务端过期已排除 | 已做 |
| B4 | 源 xml cdnbigimgurl 核对 | `D3:220-222` 已答（坏图 attrs 无 `cdnbigimgurl`、只有 mid）；SCD 探针 `StockCdnProbe.kt:120-123` `hasCdnBigImgUrl` 已打 | 已做 |
| B5 | 补齐三字段后自建 checkExist 下发 + Proxy C2 只读命中 | B5 轮四发真机已做：on1/on2 真实回包 `exist=true md5Len=32`（F4/F5），但 F9 出口 `9554/0ef205c1`、肉眼模糊 ⇒ NO-GO | 已做且否 |

## 升级判据的物理死结（为什么再跑也无用）

- Lite 产物 Q5 / 三件套②（`_r86c_origbytes_feasibility.md:113/:134`）要求升级三条件同时：① B5 C2 命中；② 出口 filemd5 翻源图身份；③ **用户肉眼 A→B→A 翻转**。
- 但 ① 在 B5 轮已达成（F4/F5 `exist=true md5Len=32`），②③ 未达成且**在只读探针阶段不可达成**：B5 探针 C2 只反射读字段 + `return 0`，不调 `b1.j6` / 不调 `FileSender.send` / 不写宿主字段（`B6CheckExistProbe.kt:18-19`、`_r86c_b5_fix_note.md:162`）。自建 checkExist 与真实转发消息零耦合 ⇒ **肉眼不会因探针发生翻转**（产物原文）。
- 要让肉眼翻转必须落码 B6 全量功能（写宿主字段 + 真实下发 + 改 m.a），而这正是 Lite 产物 Q5 / 三件套③ 明确「不 GO 到落码」的部分；且泛化判据 NO-GO(0.75)（`_r86c_generalize_verdict.md:185-192`）、根子模块不可靠可控。
- ⇒ **在「不落码」约束下，②③ 永远不可达，升级判据无法满足**；重复跑 B1~B4 只回读已归档事实，不产生新信息。符合 D3「判据连塌即停，回头问是不是问题选错了」。

## 裁决与主会话自纠

- **裁决**：B1~B4「只读取证」= 已执行、无新信息，不重复执行；Lite 产物「最小下一步」一节作废。R86c 治本方向在「不落码」前提下无可执行动作，实质到头。
- **主会话自纠**：派 Lite 子代理时背景锚点漏喂 `b5_armresult_verdict.md`（B5 已执行 NO-GO），导致其把已办当待办；后续派发涉及「是否待执行」类问题时，锚点必须包含最新一轮的产物状态。
- **未动**：本轮零改码 / 未装机 / 未碰设备 / 未发图 / 未改开关；G1 仍 false、四探针仍 off（上一轮已复位）。
- **待用户定夺**：见对话中的方向选择；本核验不自行开工。
