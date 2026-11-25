# R86c Q4 补观测真机事实（2026-11-24 02:11~02:14）

> 目的：收口 `_r86c_fileid_vs_fullpath_verdict.md` 卡住的两个 UNKNOWN（A/B-2 fullpath 指向文件实际字节数 + 内容；可命中占位形态）。
> 本文档只记**事实**，不做根因定性/修复裁决（那是 Pro 类，另派）。

## 取证条件
- 探针：SCD（扩展版，含 `fexists`/`fbytes`/`fmd5pre`/`fidFull`）+ B6X 双开；APK 02:06 装机、双冷启、11 hook 全 OK。
- 目标：文件传输助手。用户肉眼终审确认 **同一张图**。
- 原始日志：`_r86c_q4b_stepA_raw.txt`（14 行）/ `_r86c_q4b_stepAB_raw.txt`（25 行）。

## 肉眼判据（终审，最高优先级）
- **A · 微信原生转发 → 文件传输助手 = 清晰大图**（用户 02:14 确认「1.清晰」）
- **B · 模块「转发（模块）」→ 文件传输助手 = 模糊图**（用户 02:14 确认「2.模糊」）

## 三态日志事实（fbytes = File(path).length() 真实字节；fmd5pre = 文件内容 MD5 前 8；fidFull = 完整 fileId）

| 时点 | 态 | fullpath 路径尾 | fbytes | fmd5pre | 入 task fileId | 回调 md5 / hit | 出口 filemd5 | 出口 fidBody | UploadHitCacheType | 肉眼 |
|---|---|---|---|---|---|---|---|---|---|---|
| 02:11:35.221 | A 原生 | `6ff.jpg_hevc` | **45522** | `cff4f5a5` | **有**（fidLen=194, 305f…0f836cfd…） | `cff4f5a5` / hit=1 | `cff4f5a5…` | `0f836cfd` | 1 | **清晰** |
| 02:12:46.500 | B 模块 | `a77.jpg_hevc` | **9554** | `0ef205c1` | **无**（fidLen=0, fidFull 空） | `0ef205c1` / hit=3 | `0ef205c1…` | `4954a2f9` | 3 | **模糊** |

### 完整 fileId 串（逐字，取证期临时打全，取证后须回归 len+prefix）
- A 入 task / 出口：`305f020100044b304902010002040f836cfd02032f56c10204d1e5e77302046b001026042432623037633030362d373462392d346639312d613934312d383463383633666335616531020405250a02020100040d004ca5e5000000000000000000`
  - body `0f836cfd`、uuid hex `323037633030362d373462392d346639312d61393431…`（= ASCII `206c006-74b9-4f91-a941…`，与 R81:58 记的 stock uuid 同源）
- B 入 task：**空**（fidLen=0）
- B 出口：`305f020100044b304902010002044954a2f902032f56c1020455e5e77302046b04696b042435323030333032612d633362642d343233382d62636365`
  - body `4954a2f9`、uuid `5200302a-c3bd-4238-bbce`（服务端新分配，≠源图 uuid）

### 关键伴生关系（事实，非定性）
1. **fmd5pre = 回调 md5 = 出口 filemd5 前 8**：A 全部 `cff4f5a5`；B 全部 `0ef205c1`。⇒ 出口引用哪张图，与 fullpath 指向文件的**字节内容 MD5** 一致。
2. **fullpath 真实字节数**：A=45522B、B=9554B。均非「小占位（36B）」、均非 `fullpathLen=124`（那是路径**字符串长度**，`StockCdnProbe.kt:437 path.length`）。
3. **fileId 有无**：A 入 task 有 305f 原图身份（body 0f836cfd）→ 出口 body 仍 0f836cfd；B 入 task 无（fidLen=0）→ 出口 body 变 4954a2f9（新分配）。
4. **thumbfilemd5 两态相同**（`f6ab3ac5…`）。
5. `enableHit=true` / `only=false` / `fakeSigEmpty=true` 两态相同，非区分量。

## 与历史证据的对齐（仅列对齐点，不下裁决）
- R81/R82 铁证：fileId 逐字相同仍一清晰一模糊 ⇒ fileId 非决定量；决定量 = fullpath 字节内容。本轮补观测**首次拿到 fullpath 真实字节数**（A=45522B 对应原图 md5 / B=9554B 对应另图 md5），与该方向一致，且消除了「未观测变量」质疑。
- 上一轮 `docs/_r86c_fileid_vs_fullpath_verdict.md` 的 no-go 是「因缺观测暂缓」；本轮观测已补齐。

## 交给 Pro 的裁决问题（另派，主会话不自裁）
1. 两轮证据是否彻底统一？决定「对端引用原图 cff4f5a5」的充分条件究竟是什么（fullpath 字节内容 / fileId / enableHit 各自角色）？
2. 本轮 A 清晰态 fullpath=45522B 原图字节、B 模糊态=9554B 另图字节——是否坐实「模块模糊根因 = 真实发送链喂了 9554B 另图字节（fullpath 指向错字节），而非缺 fileId」？
3. 修复路线唯一 go/no-go：若 go，落码规格必须同时满足 R81/R82 + 本轮补观测（不能退回「只换头」、不能赌未观测字节）；若 no-go，卡在哪个 UNKNOWN。

> 状态：探针仍开（SCD+B6X enabled=true）；待 Pro 裁决后关探针 + fileId 回归只打前缀。未改源码/未提交/未推送。
