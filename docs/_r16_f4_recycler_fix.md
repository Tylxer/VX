# f4-recyclerview 修复记录：msgsnapReport() RecyclerView 形态定位失效

任务：修复真机取证探针 `msgsnapReport()` 在 `ChattingRecyclerView` 形态下 `getPositionForView` 恒返回 -1 导致的定位失效，并让判读脚本区分「形态导致的预期 -1」与「真故障」。

## 骨架
（内容随进度逐步填充）

## [DONE] ForceForward.kt 定位分派改造

`msgsnapReport()` 约 824-831 行原来的「统一走 `getPositionForView`」改为按 `listKind` 分派：

- **listview 形态**：保持 `getPositionForView`（`ListView` 继承来的真实实现）。
- **recycler 形态**：不走 `getPositionForView`（恒 -1 空桩）。改为：
  1. 从被长按 `v` 沿 `parent` 链向上，找到 `parent === chatList` 的节点作为 item 根 View；
  2. 反射 `getChildAdapterPosition(View)`（`RecyclerView` public 方法，`getMethod` 含继承链）；
  3. 失败或 -1 时退化：遍历 `0 until getCount()`，用 `A0(I)` 取 ViewHolder，比对其 `d` 字段（itemView）与 item 根 View 是否同一对象。
- 新增 `posVia` 可能取值：`none` / `getPositionForView`（listview）/ `childAdapterPosition` / `A0scan`（recycler）。
- 新增输出字段 `posFail`：recycler 形态所有定位手段都失败时置 `recyclerStub`（不再让 `pos=-1` 孤零零混在输出里）。
- KDoc 第 1 条坑补了「RecyclerView 那个声明是恒 -1 空桩，recycler 形态必须走 `getChildAdapterPosition`」结论。

## [DONE] tools/_r16_msgsnap.py diagnose() 判读调整

- `parse_msgsnap()` / `_verdict_line()` 新增 `posFail` 字段（老 APK 缺字段显示 `<missing>`）。
- `diagnose()` 在 `list=none` 判据之后、`pos=-1` 通用判据**之前**插入一条 recycler 专用判据：
  `list=recycler` 且（`posFail=recyclerStub` 或 `pos=-1` 但 `posVia ∈ {childAdapterPosition, A0scan, none}`）
  ⇒ 判读为「RecyclerView 形态下 getPositionForView 恒 -1，属已知预期，不是 adapter 链不通」。
- 输出行 position 改为 `pos（via X / posFail Y）`。
- 文件头第 0 节链路说明与坑 1 同步更新为「按 listKind 分派」。

## [DONE] docs/_r16_g2_msgsnap.md §1.2c 修复记录

在 §1.2b 末尾新增 `### 1.2c 修复记录`：写明改了什么、`posVia` 四种取值对照表、`diagnose()` 判据变化。

## [DONE] 编译与语法验证

- `python tools/gradle_run.py compileDebugKotlin` ⇒ **EXIT=0** / `BUILD SUCCESSFUL in 18s`
- `python -m py_compile tools/_r16_msgsnap.py` ⇒ **EXIT=0**
