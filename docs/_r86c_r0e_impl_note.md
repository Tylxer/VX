# R86c · j2.R after → 写 r0.e/f 指向 45522B hevc 字节 · 实现说明与三闸结果

> lane：`r86c-r0e-impl`（实现 lane）。按已裁决规格落码，不做根因推理、不改裁决。
> 工作根 = `wxgroup`。允许写：J2R0eOverrideHook.kt / J2R0eOverrideHookTest.kt / HookEntry.kt / EntryReceiver.kt / Keys.kt / 本文档。
> 禁改：ForceForward.kt / FileSender.kt / ForwardCdnFieldHook.kt / StockCdnProbe.kt / 其它文件 / 任何裁决文档。

## 落盘进度

- [DONE 1 hook] `app/src/main/java/com/lx/wxgroup/core/J2R0eOverrideHook.kt` 已落盘（j2.R after e/f 双写 + 动态字节定位 + .part→rename + TTL Map + onGYNetEnd 0/0 cleanup + 独立开关默认关）。
- [DONE 2 接线] `Keys.kt` 加 `FORWARD_R0E_HEVC`（默认 false）；`HookEntry.kt` install 注册；`EntryReceiver.kt` 加 `op=r0e sub=on|off|stat`。
- [DONE 3 单测] `app/src/test/java/com/lx/wxgroup/core/J2R0eOverrideHookTest.kt` 已落盘（纯 JVM 源码文本断言 + 4 项真实源码变异：e/f 双写改单写、localId a→i、内容 MD5 改值、删掉字节定位失败的 skip return）。
- [DONE 4 三闸] 三闸全绿（见下）。
- 真 `[DONE ALL]`（真完成）

## 实现说明（逐条对应规格）

1. **hook 点**：`j2.R()` after，精确 sig `("R", emptyList(), "com.tencent.mm.modelimage.r0")`，复用 `hookRobust`（精确命中 hookMethod，否则 hookAllMethods 兜底）+ 独立 callback + 独立 guard `j2R`/`j2NetEnd` + 独立开关。完全不碰 SCD-j2R 只读探针及其 hookStatus。
2. **写字段**：after 里 `param.result` = r0；只 `XposedHelpers.setObjectField(r0, "e", tempPath)` + `setObjectField(r0, "f", tempPath)`。**绝不 `r0.l()`/`r0.n()`**（实际写 j / 写 f 且置脏）、绝不调任何宿主业务方法（b1.R5/j6/o2/u8、mq.k.B8、ok0/m1.e）。
3. **时机 + V2 收紧（team-lead 二次校准）**：`_r86c_v2_dbg.txt` 铁证 `j2.R()` 每次转发被多次、跨多线程调用且各帧 e 不同（23:44:47~48 `[D]#worker`/`ColdPool#10`/`Thread-380`/`ColdPool##2/16` 上 `e=bd64a9fd…jpg` 缩略图帧；23:44:54 才在 `wxg-forward` 线程 `e=de291b56…jpg`，紧接 z3 in→out=image2/de/29/de291b56.jpg）。⇒ **以「本帧能定位到 cff4f5a5 hevc 字节且内容 MD5 校验通过」为天然闸门**：定位失败一律 `skip`、绝不碰原 e/f，避免污染 bd64a9fd 那类无关帧；不缓存「已写过」标志，命中帧每次都重写（应对 e 被重建）。skip 日志统一 `R0E after skip reason=... thread=<线程> e=<old>`，写帧日志带 `thread=<线程> md5=cff4f5a5 localId=<id>`，便于真机核对到底哪一帧被改。
4. **字节定位**：`resolveOriginLocal(r0)` 复用 `ForwardCdnFieldHook.resolveOriginFullpath` 判据——从 r0 的 String 字段（e/f/g + 反射遍历）取 localPath → 抠 `MicroMsg/<32hex>` 数据根 → 从全部 String 字段抠 32hex 候选 md5 → 拼 `image2/<2>/<2>/<md5>.jpg_hevc` → 内容 MD5 前 4 字节 `cff4f5a5` 校验 → 兜底有限枚举 `*.jpg_hevc` 且命中数 `== 1`（歧义不采信）。取不到降级 null、不写 r0.e。**不写死任何文件名 md5**（09ec74ee 仅作历史参考，不进代码）。
5. **临时文件**：`HostContext.app()?.cacheDir/_<uuid>.jpg_hevc`；写 `<uuid>.part` → `flush()` + close → `renameTo`；复制后复核 `length > 15*1024` 与内容 MD5 前缀；取不到 cacheDir 降级 null，**无硬编码 `/data/data`**。不经 P3AdmitGate、不改 FLOOR、不调 b1.u8、不动 q1.j。
6. **交接**：按 r0 localId（long 字段 `a`，R78 订正）为 key 的短 TTL(120s) `ConcurrentHashMap`。j2.R after 先查 Map 命中复用，查不到才当次动态定位并登记；cleanup 按 localId 命中删。ThreadLocal 方案因禁改 FileSender.kt 未采用，线程亲和 UNKNOWN 如实记录、不参与成功判据。
7. **cleanup**：hook `j2.onGYNetEnd`（6 参、前 3 int），只 `errType==0 && errCode==0` 删；关联字段主取 `j2.q → r0` 的 long a/b，best-effort 兜底 j2 自身 a/b/i/h/j/Q（口径 UNKNOWN 双覆盖）。V2 多线程帧可能共享 localId ⇒ **只有「字节命中且被本 hook 写过 e/f 的那一帧」才在 TTL Map 登记 tempPath**（缩略图帧根本不登记），0/0 到达时只删该条登记的文件，不碰别的帧；一 localId 在 TTL 内只保留一份 tempPath（复用）。删除不早于读字节；删不到靠 cacheDir 回收，成功判据不依赖清理日志。
8. **开关**：`Keys.FORWARD_R0E_HEVC = "chat.forward_r0e_hevc"` 默认 false；install 时读一次启动值（冷启静默）；`op=r0e sub=on|off|stat` 运行时翻转内存 `@Volatile enabled`（不写库、不写宿主）；off 时两回调首行 `if (!enabled) return`（零反射、零拼接）。日志统一前缀 `R0E`，路径 `take(120)`。

## 三闸结果（从 `C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\wxgroup` 跑；runner 在父目录 `../tools`）

| 闸 | 命令 | 结果 |
|---|---|---|
| 1 定向 | `bash ../tools/_junit.sh com.lx.wxgroup.core.J2R0eOverrideHookTest` | **绿** — `OK (13 tests)` |
| 2 全量 | `bash ../tools/_junit.sh` | **绿** — `OK (1203 tests)` |
| 3 编译 | `python ../tools/gradle_run.py assembleDebug` | **绿** — `EXIT=0 BUILD SUCCESSFUL in 12s` |

> 前置步骤（非闸）：`python ../tools/gradle_run.py compileDebugUnitTestKotlin` → `EXIT=0 BUILD SUCCESSFUL in 19s`。
> 说明：任务给的 `tools/_junit.sh` / `tools/gradle_run.py` 实际位于父工作区 `C:\Users\lx\WorkBuddy\2026-09-29-20-23-21\tools`，故从 `wxgroup` 用 `../tools/...` 调用。`_junit.sh` 接收的是 JUnit 全限定类名，定向闸用 `com.lx.wxgroup.core.J2R0eOverrideHookTest`。
> 变异验证：4 项变异断言随定向闸 13 例一起通过（把 f 双写改成 g、localId a 改成 i、cff4f5a5 改成 deadbeef、删掉字节定位失败的 skip return，对应判据均被判红），证明非假绿。

## 4 项 Hook 链路 UNKNOWN 处置

1. **ThreadLocal 线程亲和（`R()` 与 `q1.a()` 是否同线程）**：规格方案 B 前提未真机确认，且本任务禁改 FileSender.kt ⇒ 改用 TTL Map（localId 为 key）兜底，不依赖线程亲和；不强依赖，不进成功判据。
2. **`z3(r0.e)` 对 `.jpg_hevc` 后缀是否改写路径**：本实现只负责把绝对路径写进 e/f，后缀解析由宿主完成；是否改写属宿主侧 UNKNOWN，落码不改判据，交真机 z3 out / 对端字节闭合。
3. **对端 hevc 解码兼容**：未验证对端能否显示 hevc；本实现不改变字节格式（照搬用户已上传成功的 cff4f5a5 hevc），该项留真机肉眼/对端保存字节判据。
4. **cleanup 关联字段口径（a/b vs i/h/j/Q）**：R78 订正 localId/msgId 在 r0 a/b，而 ForceForward 旧 cleanup 用 j2 的 i/h/j/Q；本实现 dual-path（主取 `j2.q→r0` a/b，兜底 j2 自身 a/b/i/h/j/Q）双覆盖，且删不到靠系统回收，**成功判据不依赖清理日志**。

> 另记（非 Hook 链路 UNKNOWN，属裁决已标注风险）：B2/R79「服务端秒传才是清晰度决定量」竞争理论与 `z3(r0.e)` 本地字节决定量存在张力；按裁决仅记录、不改判据，由落码后肉眼+字节闭环证伪/证实。微X私有字节来源仍【UNKNOWN】。

## 阻塞

无。三闸全绿，未碰 adb / 真机，未改任何禁改文件。
