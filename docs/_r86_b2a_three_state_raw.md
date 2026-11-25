# R86 B2-a · 三态真机取证原始记录（2026-11-23 22:33~22:38）

> 纯数据归档。本文件**只记客观日志行 + 用户肉眼结果**，不写根因结论、不写 B2 实现方案。
> 判定与下一步属于 Pro 类（根因推理 + Hook 链路），另开 lane，不在此文件里下结论。

## 0. 前置校验（取证有效性）

- 冷启日志：`SCD STATUS enabled=true installed=true ok=11 fail=0`，11 hook 全 OK（含新增 `SCD-x0cb`/`SCD-c2c`/`SCD-g9c`）。
- 写 hook `ForwardCdnFieldHook.install` 已摘（态②不会被 `CDN_FIELD injected` 污染）。
- 探针默认关，取证期间 `op=stockcdn sub=on` 手动开；每态之间 `logclear` 清日志。
- 用户确认手上有「stock 转发清晰、模块转发模糊」的同类坏图。

## 1. 态① stock 原生转发 → 用户肉眼：**清晰**

> 操作：长按坏图 → 微信原生「转发」→ 发文件传输助手。
> 用户：「清晰，看不了字节数」（字节数不可得，不影响肉眼判据）。
> ⚠️ 态①日志在切态②前被 `logclear` 覆盖，以下为当时终端打印输出逐行转录（非 pull 文件）。

关键行（时间戳为准）：

```
22:34:18.005 SCD2 c2c in fullpathLen=124 pathTail=be6.jpg_hevc fidLen=194 fidPre=305f0201 md5Pre= md5Len=0 enableHit=true only=false fakeSigEmpty=true msig=public boolean ok0.m1.e(tj.k)
22:34:18.745 SCD2 x0cb in md5=ca47f83e md5Len=32 hit=1 exist=true fidLen=194 fidPre=305f0201 msig=CUploadCompleted(java.lang.String,com.tencent.mars.cdn.CdnLogic$C2CUploadResult)
22:34:18.754 SCD f2C2 in fIntFields={... field_UploadHitCacheType=1 ...}
22:34:18.756 SCD f2C2 in f field_exist_whencheck=true field_UploadHitCacheType=1
22:34:18.647 SCD f2C2 in msig=blic int com.tencent.mm.modelimage.f2.C2(...) s0=aupimg_efab514877cf4e87_1795444457199 i1=0 b4=false
22:34:30.478 SCD s0b ret path=5e17eaa4ab8e6cecfa0bb087a6f96be6.jpg
22:34:30.641 SCD2 g9c in hasBig=true bigLen=194 hasAes=true hasMd5=true md5Pre=ca47f83e has216b=false msig=nt.mm.sdk.platformtools.g9.c(java.lang.String,java.lang.String,java.lang.String)
22:34:18.631 SCD2 g9c in hasBig=false bigLen=-1 hasAes=true hasMd5=true md5Pre=880a3f93 has216b=false ...
```

## 2. 态② 模块转发 → 用户肉眼：**模糊**

> 操作：长按同一张坏图 → 「转发（模块）」→ 发文件传输助手。
> 原始日志：`docs/_r86_b2a_state2_raw.txt`（461 行，含全部上下文）。

关键行（从归档 raw 文件摘）：

```
22:37:56.516 SCD2 c2c in fullpathLen=124 pathTail=f2f.jpg_hevc fidLen=0 fidPre= md5Pre= md5Len=0 enableHit=true only=false fakeSigEmpty=true msig=public boolean ok0.m1.e(tj.k)
22:37:57.046 SCD2 x0cb in md5=0ef205c1 md5Len=32 hit=3 exist=true fidLen=194 fidPre=305f0201 msig=CUploadCompleted(...)
22:37:57.055 SCD f2C2 in fIntFields={... field_UploadHitCacheType=3 ...}
22:37:57.057 SCD f2C2 in f field_exist_whencheck=true field_UploadHitCacheType=3
22:37:57.056 SCD f2C2 in fStrFields={... field_fileId=305f020100044b3049...}
22:37:44.691 SCD2 g9c in hasBig=true bigLen=194 hasAes=true hasMd5=true md5Pre=9afc9919 has216b=false ...
22:38:01.293 SCD2 g9c in hasBig=true bigLen=194 ... md5Pre=f6ab3ac5 ...
22:38:02.367 SCD2 g9c in hasBig=true bigLen=194 ... md5Pre=ca47f83e ...
```

## 2.5 取证有效性校验（方案 §5.3）

- 态② raw 中 `CDN_FIELD injected` = **0**、`ForwardCdnFieldHook` = **0** ⇒ 写 hook 未复活，样本未被污染，处置(a)达标。
- 态② `SCD m1e task fileId= md5= onlycheckexist=false` — 模块转发上传任务 fileId 确实为空。
- 态② `SCD m1e ... field_fullpath=.../image2/aa/01/aa01148928db5b23bca2af35c7ef2f2f.jpg_` — fullpath 指向 image2 本地字节（非 CDN 占位），与态② `c2c pathTail=f2f.jpg_hevc` 同 md5 前缀 f2f，指向同一本地文件。
- `SCD m1e out addSendTask ret=true` — 任务确实下发（非空转）。

## 3. 客观差异表（仅陈述日志值，不解释因果）

| 观测点 | 字段 | 态① 清晰 | 态② 模糊 | 差异 |
|---|---|---|---|---|
| `SCD-c2c`（上传任务构造 before） | `fidLen` | 194 | **0** | ✅ 不同 |
| | `fidPre` | 305f0201 | 空 | ✅ 不同 |
| | `pathTail` | be6.jpg_hevc | f2f.jpg_hevc | 不同（两名均 `_hevc`） |
| | `fullpathLen` | 124 | 124 | 同 |
| | `md5Pre`/`md5Len` | 空 / 0 | 空 / 0 | 同 |
| | `enableHit`/`only`/`fakeSigEmpty` | true/false/true | true/false/true | 同 |
| `SCD-x0cb`（上传完成回包 after） | `md5` | ca47f83e | 0ef205c1 | 不同 |
| | **`hit` (hitCache)** | **1** | **3** | ✅ 不同 |
| | `exist` (existOnSvr) | true | true | 同 |
| | `fidLen`/`fidPre` | 194 / 305f0201 | 194 / 305f0201 | 同 |
| `SCD-f2C2`（重建消息，after） | `field_UploadHitCacheType` | 1 | 3 | ✅ 不同 |
| | `field_exist_whencheck` | true | true | 同 |
| `SCD-g9c`（g9.c 入参 xml） | `hasBig`/`bigLen` | true / 194 | true / 194 | 同（需按 md5 对齐再判） |

### 需要特别注意的两点（防误读）

1. **两态 `pathTail` 与上传完成 `md5` 都不同**（be6/ca47f83e vs f2f/0ef205c1）。虽用户称「同一张坏图」，但真机两态落的是不同本地文件名/不同回包 md5；**不能预设是同一字节源**。是否同图需另行比对（本地大图字节 / 源 xml md5），不得凭用户口述当作已证事实。
2. **`hitCache` 取值语义（1 vs 3）尚未查 dex 确认**。禁止把 1 直接写成「命中原图秒传」、3 写成「命中缩略图」——这是 R86 §2.2 已标注的推断，不是本轮直接观测。交给 Pro 判定。

## 4. 交给 Pro 判定的问题清单（本文件不下结论）

- Q1：`hitCache=1` vs `=3` 的准确语义（查 dex `C2CUploadResult` / `UploadHitCacheType` 常量），以及它是否就是决定对端清晰度的变量。
- Q2：态② `c2c` 的 `field_fileId=0`（空）而态①=194 —— 这一「上传任务构造时入参」差异，是根因还是伴生现象？结合 R86 §2.2「决定量是 fullpath 字节触发 hitCheck 命中原图/缩略图」判断。
- Q3：两态 md5/pathTail 不同，是否影响 A/B 唯一自变量成立？若影响，需补哪一态取证。
- Q4：若 B2 命题需继续推进，基于本轮真机数据的最小改法落点是哪里（Hook 链路设计）。

---
状态：三态取证数据落盘完成；态①（清晰）/态②（模糊）肉眼结果齐；未下根因结论；未提交。
