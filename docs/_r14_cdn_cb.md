# R14 CDN 回调取证（只读）

## Q1: `ok0.x0#onC2CDownloadCompleted` 真实签名

✅ **确证（DEX class_defs 直读，非 grep）**：2 个参数，无重载。

```
类：Lok0/x0;   DEFINED in _cache/dex/classes13.dex  class_defs#4301  super=Ljava/lang/Object;  access=0x0001
方法：onC2CDownloadCompleted (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V   acc=0x0001
     → arg[0] = String fileid
     → arg[1] = C2CDownloadResult   ★「结果对象」就在第 2 位（args[1]），位置没错
返回：void
```

复现命令（⚠️ 第三条 yrgs 后面别加名字子串，shell `;` 会被当命令分隔符）：

```bash
PY=/c/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python
cd /c/Users/lx/WorkBuddy/2026-09-29-20-23-21
"$PY" _cache/_r14_fields.py methods 'Lok0/x0;'       # 打印 67 个方法的完整描述符
```

⇒ **结论：探针里 `param.args.getOrNull(1)` 取结果对象是【对的】**，`pic=none` 不是参数位错。
（同类的 `onDownloadProgressChanged` 实测 `(Ljava/lang/String;JJZ)V`，与接口声明一致 ⇒ 解析器可信。）

### ⚠️ 顺带修掉的一个工具 bug（会影响此前所有跟_params_有关的结论）

`tools/_r13_findclass.py` 的 `Dex._proto()` 用 **uleb128** 解析 proto 的参数列表，但 DEX 的
`type_list` 实际是 `{ u4 size; u2 items[]; }`。后果：**所有形参都被解析成 type_idx=0**
（本批 dex 里 type 0 恰好是 `B`）。症状就是每个方法都显示成 `(BB)V` / `(BBB)V`：

```
   onC2CDownloadCompleted    (BB)V        ← 修之前看到的样子（假）
   onC2CDownloadCompleted    (Ljava/lang/String;Lcom/tencent/mars/cdn/CdnLogic$C2CDownloadResult;)V  ← 修之后
```

我在临时脚本 `_cache/_r14_fields.py` 里重写了 `_proto()`（size=`u4`，元素=`u2`），**未改 tools 下的正式脚本**。
⇒ 之前任何「靠 `methods_of()` 判参数个数/类型」的结论都建议用修好的版本重跑一遍（参数**个数**不受影响，
因为 size 的第一个字节碰巧读对了；只有**类型**是假的）。


## Q2: 结果类真实类名 + 字段清单（重点是落盘路径字段）

### 2.1 真实类全名 / 所在 dex
<!-- S1 -->

### 2.2 实例字段清单（字段名 + 类型）
<!-- S2 -->

### 2.3 落盘路径字段判定
<!-- S3 -->

### 2.4 若无路径字段 → 下一步去哪找
<!-- S4 -->

<!-- [DONE] -->
