# R86c Q4 真机只读取证 · 事实落盘（2026-11-24 01:20~01:25）

> 本文只记录**肉眼判据 + 日志事实**，不做根因推理、不下定案。
> 根因裁决与修复路线 go/no-go 由 Pro 子代理在同任务链下游产出。
> 原始日志：`_r86c_q4_stepA_raw.txt`、`_r86c_q4_stepAB_raw.txt`（均本轮 pull_dbg 落盘）。

## 一、肉眼判据（用户可见事实，总判据）

- **同一张图**（用户 01:24 明确确认 A/B 用的是同一张）。
- **A = 微信原生「转发」→ 文件传输助手：清晰大图。**
- **B = 模块「转发（模块）」→ 文件传输助手：模糊图。**
- ⛔ 判据纪律：命中/hit/exist/UploadHitCacheType 都是内部计数器，**不能替代肉眼清晰/模糊**。本轮结论以肉眼为终审。

## 二、探针状态（只读，01:20:19 挂载）

双探针 SCD + B6X 全开，11 个 hook 全 OK（SCD-B6 / m1e / CDNcb / s0b / b1j6 / j2R / f2C2 / B8 / x0cb / c2c / g9c）。APK 未改动（3349574B，00:36:51），**本轮零落码、零装机**。

## 三、A 段（stock 原生转发，01:21:27~28）—— 清晰

上传 task（`SCD2 c2c in @m1.e`）:
- `fullpathLen=124` `pathTail=c09.jpg_hevc` （带原图字节）
- `fidLen=194` `fidPre=305f0201` （带 fileId，mid URL 语义）
- `enableHit=true` `only=false` `fakeSigEmpty=true` `md5Len=0`

回调（`SCD2 x0cb in @onC2CUploadCompleted`）:
- `hit=1 exist=true md5=cff4f5a52813d02be816367d4c58a8be`（32 位）
- `fidLen=194 fidPre=305f0201`

消息重建出口（`SCD f2C2 out`，HotPool#1 01:21:28.09）:
- `field_exist_whencheck=true` `field_UploadHitCacheType=1`
- `field_filemd5=cff4f5a52813d02be816367d4c58a8be`（= 回调命中 md5）
- `field_fileId=305f020100044b30490201000204**0f836cfd**02032f56c10204d1e5e77302046b001026042432623037633030362d373462392d346639312d61393431`
- `field_thumbfilemd5=f6ab3ac5c8e5ed4888458b49a0492e77`
- `field_aesKey=60af7983188a7701aa5c491195a1cbea` `field_toUser=filehelper`

## 四、B 段（模块转发，01:22:53~54）—— 模糊

一次操作内出现**两条上传 task + 两次 f2C2 回调**：

### B-1 模块自建 checkExist task（wxg-forward 线程，01:22:53.433）
- `fullpathLen=0` `pathTail=`（**无原图字节**）
- `fidLen=194` `fidPre=305f0201`（有 fileId）
- `md5Pre=ca47f83e` `md5Len=32`（源图 md5）
- `enableHit=true` `only=false` `fakeSigEmpty=true`
- 回调：`SCD2 x0cb hit=0 exist=false md5Len=0 fidLen=0`；B6X C2 `exist=false md5Len=0` → **未命中**

### B-2 微信侧兜底 task（[D]#worker 线程，01:22:53.610，晚 177ms）
- `fullpathLen=124` `pathTail=f3a.jpg_hevc`（带字节）
- `fidLen=0` `fidPre=`（**无 fileId 锚点**）
- 回调：`SCD2 x0cb hit=3 exist=true md5=0ef205c165e99eb0f4a1c0084e65f1aa`（32 位）
- 消息重建出口（HotPool#2 01:22:54.34）:
  - `field_exist_whencheck=true` `field_UploadHitCacheType=3`
  - `field_filemd5=0ef205c165e99eb0f4a1c0084e65f1aa`（= 该 task 命中 md5，**≠ 源图 md5**）
  - `field_fileId=305f020100044b30490201000204**4954a2f9**02032f56c10204**55e5e773**02046b04696b042435323030333032612d633362642d343233382d62636365`
  - `field_thumbfilemd5=f6ab3ac5c8e5ed4888458b49a0492e77`（**与 A 完全相同**）
  - `field_toUser=filehelper`（`field_aesKey` 未在该次 dump 中出现）

## 五、事实层对照表（不下推理，仅供 Pro 读）

| 维度 | A stock（清晰） | B-1 模块自建（未命中） | B-2 微信兜底（命中→仍模糊） |
|---|---|---|---|
| fullpath | 124 (c09.jpg_hevc) | **0** | 124 (f3a.jpg_hevc) |
| fileId | 305f0201…(len194) | 305f0201…(len194) | **空 (len0)** |
| 传入 md5 | — | ca47f83e (源图) | — |
| 回调 hit/exist | 1 / true | 0 / false | 3 / true |
| 回调 md5 | cff4f5a5 | — | 0ef205c1 |
| out filemd5 | cff4f5a5 | — | 0ef205c1 |
| out UploadHitCacheType | 1 | — | 3 |
| out thumbfilemd5 | f6ab3ac5 | — | f6ab3ac5 |
| out fileId 内嵌差异 | `…02040f836cfd…d1e5e773…` | — | `…02044954a2f9…55e5e773…` |
| **肉眼** | **清晰** | — | **模糊** |

## 六、待 Pro 回答（根因推理，主会话不自做）

1. 三条 task 的 fullpath × fileId 组合，与「清晰/未命中/命中但模糊」的因果机制是什么？
2. `UploadHitCacheType=1` vs `=3` 的语义差异，是否解释了「同样 exist=true，A 清晰 B 模糊」？
3. A 的 out filemd5=cff4f5a5（=源图）vs B-2 的 0ef205c1（≠源图），是否就是「原图字节 vs 转码字节」的直接证据？
4. 修复路线：在模块自建 checkExist task 里补 `field_fullpath`（指向 R83 已能拼出的源图路径 `image2/<2>/<2>/<md5>.jpg`），能否让它像 A 一样命中 cff4f5a5、产出清晰图？给出 dex 级 go/no-go + 落码规格。
5. B-2 `field_fileId` 内嵌值（4954a2f9 / 55e5e773）与 A（0f836cfd / d1e5e773）差异，是否说明兜底路径用了不同的秒传锚点/参数？
