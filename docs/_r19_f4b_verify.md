# R19 · F4b 弹窗修复 —— 真机验证（2026-10-25 01:34–01:37）

## 为什么必须上真机

专审（`docs/_r18_f4b_review.md`）判 F1 为 P0 阻断，并明确指出：

> F1 的修正**无法由 JVM 单测证明** —— `MomentsNotify` 依赖 Android（Looper / Activity /
> AlertDialog），纯 JVM 环境够不着。建议在真机上确认一次「用户正在前台 + 导出失败 ⇒ 窗真的弹出来」。

修复 lane 也没有动 adb。所以这一步由主会话补上。

## 验证方法

造一个**持久性**故障（每轮都会复现，不是偶发），连跑 3 轮：

```
op=kvset --es key moments.export_path --es val /system/nope_cannot_write   # 必然不可写
op=moments sub=export         × 3 轮
每轮用 uiautomator dump 检查屏幕上有没有「朋友圈自动导出失败」
```

## 结果

| 轮次 | 日志（故障确实发生） | 屏幕 |
|---|---|---|
| 1 | `export skip: cannot create /system/nope_cannot_write` + `mkdirs ...` | ✅ **弹窗出现** |
| 2 | 同上 | 未弹 |
| 3 | 同上 | 未弹 |

第 1 轮的屏幕文本节点：
```
'朋友圈自动导出失败'
'无法创建导出目录，请检查路径是否可写。'
'取消'  '确定'
```

## 两条结论

1. **F1（P0）已修复。** 「用户正在前台 + 导出失败」这条**主路径**现在能弹出窗。
   修前的表现恰恰相反：唯一能弹出的是「微信在后台」那条异步分支，前台这条稳定失败
   （非主线程 `AlertDialog.Builder().show()` 必抛 → 被 `runCatching` 吞 → 仍 `return true`
   → 占住 latch → 永不再弹）。
2. **P0（每隔一轮弹一次）没有复活，F2 闭环。** 3 轮里故障每轮都发生、但只弹了 1 次。

### ⛔ 为什么必须跑满 3 轮

R19 计划里定的判据：**若只跑 2 轮就收工，P0 与正确实现表现完全一致**（都是弹 1 次）。
只有第 3 轮才能区分 —— P0 会在第 3 轮再弹一次。这次跑了 3 轮，第 3 轮未弹 ⇒ 确认修好。

> 📌 **判据沉淀**：凡「失败去重 + 成功复位」类机制，真机验收必须 **≥3 轮**并断言
> **总弹窗次数 == 1**，与单测的多轮驱动用例用同一判据。

## 收尾

- 已把 `moments.export_path` 恢复为默认值
  `/storage/emulated/0/Android/data/com.tencent.mm/files/WechatXposed/moments`。
- ⚠️ 中途踩了一次：`kvset(key, "")` 传空串时，adb 把空参数吞掉导致后续参数错位，
  配置被写成脏值 `-p`。**教训：给 `am broadcast --es` 传空串不安全，要传就传明确的默认值。**

## 验证锚点

```
tests=518 failures=0 errors=0        （含我补的 5 条 8>5>1 路径测试 + 子代理的 15 条）
assembleDebug EXIT=0
第1轮弹窗 / 第2、3轮不弹
```
