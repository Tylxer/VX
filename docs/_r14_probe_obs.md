# R14 SnsCdnProbe 可观测性改造（进展）

文件：`wxgroup/app/src/main/java/com/lx/wxgroup/probe/SnsCdnProbe.kt`（唯一改动的源文件）

改动点 1 `reqHook()`（`ok0.x0#n`）：进钩子即 `hitN +1`；把原来的两个静默 `?: return` 拆成六类可见原因
`no-arg0` / `l1-nofield` / `l1-null` / `l1-not-array cls=<实际类名>` / `l1-empty` / `ex:<异常名>`，
打 `CDN reqerr reason=<why> n=<累计次数>`；节流策略 = 同类原因打前 3 次，之后按计数为 2 的幂（4,8,16…）稀疏补打。**已改**
改动点 2 `doStat()`：`CDN stat hooked=` 行首原样不动，行尾追加
`hitN= hitPicE= hitPicNE= hitDir= hitNoField=`（`tools/_r9_cdn.py` 的 `last_cdn(lines,"stat hooked=")` 及
`proc=/hooked=/hitK=/hitCB=` 正则均不受影响）。**已改**
改动点 3 `cbHook()`：新增 `ReadState`(OK/NO_FIELD/OTHER) + `readField()`（沿 cause 链找 `NoSuchFieldError`），
替代会把两者混为一谈的 `readString()`；回调里 `picCachePath` 分别计 `hitNoField`（字段不存在）/ `hitPicE`（null 或空串）/ `hitPicNE`（非空）。**已改**
改动点 4 回调非空 `picCachePath`：保持写 `lastPicParent`，同时 `observedDirs.add(parent)`（供给 `sub=paths` 的 `CDN path <dir>` 行）。**已保持**

新增计数（全为 `AtomicInteger`）：`hitN`、`hitPicE`、`hitPicNE`、`hitNoField`；`doReset()` 里一并清零并 `reqErrCount.clear()`。
未触碰：`tools/` 下任何脚本、任何 dex 常量（`ok0.x0` / `tj/g` / `l1` / `imageCachePath` / `picCachePath` 等）、adb / 装包。
编译：`C:/Users/lx/.workbuddy/binaries/python/versions/3.13.12/python.exe tools/gradle_run.py assembleDebug` ⇒ **EXIT=0 BUILD SUCCESSFUL**（跑两次，第二次 9s UP-TO-DATE 确认最终源码已编入）。未 commit。

[DONE]
