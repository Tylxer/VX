# R20b · 先例核查：`listCurrentSns` 的反射写法是「抄成熟通路」还是「新发明」

> 主会话自查记录（2026-10-25 02:2x）。
> 目的：在 `sns-live-review`（Pro 只读复核）出结论之前，先用**仓内先例**给它的两个
> 最可疑项（第 1、2 条）做一次**独立的事实准备**。先例若在真机上已跑通，
> 那「理论上不成立」的推论就必须让步于「实测确实通了」。
>
> ⛔ 本文件**不下最终结论**（那是复核 lane 的产出），只列可自证的事实与行号。

---

## 1. 结论先行（两句话）

`SnsGuard.listCurrentSns` 的反射写法**不是新发明的**，它与 `GroupNameSource.kt`（C5 群名源，
R20 真机 **PASS**：`loaded groups=660`）**逐字同构**。

⇒ 复核第 1、2 条怀疑的「静默恒 0 行」，**在这台设备的这个微信版本上已被反证**。
但复核仍有价值：要点从「能不能跑通」转为「**为什么**能跑通，以及边界在哪」。

---

## 2. 事实 A：`rawQueryMethod` 与 C5 完全同构（对应复核第 1 条）

| | `GroupNameSource.kt`（C5，真机 PASS） | `SnsGuard.kt`（R20 新增） |
|---|---|---|
| 方法名 | `rawQuery` | `rawQuery` |
| 形参个数 | 2 | 2 |
| 第 1 形参 | `String::class.java` | `String::class.java` |
| 第 2 形参 | `Array<String>` **或** `Array<Any>` | `Array<String>` **或** `Array<Any>` |
| 失败处理 | `LogX.w("rawQuery lookup failed")` | 返回 null（调用方降级） |

`GroupNameSource.kt:251-258` 原文：
```kotlin
private fun rawQueryMethod(db: Any): Method? = runCatching {
    db.javaClass.methods.firstOrNull {
        it.name == "rawQuery" &&
            it.parameterTypes.size == 2 &&
            it.parameterTypes[0] == String::class.java &&
            (it.parameterTypes[1] == Array<String>::class.java ||
                it.parameterTypes[1] == Array<Any>::class.java)
    }
}
```

**关于「`Object[]` 变体从哪来」**（复核第 1 条的疑问，此处给出机制解释）：
`parameterTypes[1] == Array<Any>::class.java` 在 Java 侧就是 `Object[].class`。
`String[]` 与 `Object[]` 是**两个不同的 Class 对象**（数组的协变只在「赋值」层面，
`Class` 相等性上 `String[].class != Object[].class`）。

`SQLiteDatabase` 的公开重载确实只有 `rawQuery(String, String[])`。
但**混淆后的微信类**常重写/遮蔽成 `rawQuery(String, Object[])`（微信有多套 DB 封装），
而这个分支正是为那种情况准备的。**两种签名都试，命中哪个用哪个** ——
所以这不是缺陷，是**防御性兼容**。

⚠️ 但这留下一个真问题（复核应继续追）：**`Array<Any>` 分支在真机上到底走到过没有？**
若从未走到，它就是**未被覆盖的代码**（死分支），一旦微信升级成 `Object[]` 签名，
`args` 传的是 `arrayOf<Any>(字符串…)`，SQLite 的 JNI 层能不能接受 `Object[]` 里装 String
是需要另查的（大概率可以，因为 `Object[]` 里元素就是 String）。

## 3. 事实 B：`callMethod(cur, "getLong", 0)` 的装箱问题已被真机反证（对应复核第 2 条）

复核的核心疑虑：`i: Int` 装箱成 `Integer`，`XposedHelpers.callMethod` 用
`parameterTypes` 精确比较 ⇒ `Integer.class != int.class` ⇒ 匹配失败 ⇒ 恒返回 null。

**反证**：`AntiRevoke.kt:730-778` 用的就是**完全相同的写法**，且该功能真机已在用：

```kotlin
// AntiRevoke.kt:730-731
val isSend = XposedHelpers.callMethod(cur, "getInt", 0) as? Int
val talker = XposedHelpers.callMethod(cur, "getString", 1) as? String

// AntiRevoke.kt:774-778
val msgId  = XposedHelpers.callMethod(cur, "getLong", 0)
val isSend = XposedHelpers.callMethod(cur, "getInt", 1)
val type   = XposedHelpers.callMethod(cur, "getInt", 2)
val talker = XposedHelpers.callMethod(cur, "getString", 3) as? String
val content= XposedHelpers.callMethod(cur, "getString", 4) as? String
```

`GroupNameSource.kt:269-271` / `LabelSource.kt:289`（C5、D4 两条真机 PASS 的通路）同样如此。

⇒ **`XposedHelpers.callMethod` 内部走的是 `findMethodBestMatch`，它对基本类型与包装类型
做了兼容处理**（源码里 `getParameterTypes` 比较失败后会退到「按可赋值性/装箱兼容」再找）。
这是 XposedBridge 的既有行为，不是本项目踩的坑。

**旁证（规模）**：全仓 `XposedHelpers.callMethod` 共 **164 处**。若装箱匹配真有系统性缺陷，
这 164 处会在整个模块里集体失效 —— 而模块的导出/防撤回/群名/标签等多个功能真机都正常。

## 4. 仍需复核 lane 回答的（真正的开放项）

先例只否证了「恒 0 行」，**没能否证下面这些**：

| # | 开放项 | 为什么先例帮不上 |
|---|---|---|
| a | `VISIBLE_BIT = 16` 是否真的等价于「时间线可见」 | 先例（C5/D4）读的是 `rcontact` / `ContactLabel`，**没有 localFlag 这个概念** |
| b | `Array<Any>` 分支在真机上**走到过没有** | 先例也分不清；需要打点或读日志确认实际命中的是哪支 |
| c | `limit` 钳位 `coerceIn(1, 200)` 与 `guard < 5_000` 的关系 | 先例都用 `null` 参数 + 自己数行，无 `LIMIT` |
| d | 首轮 `Long.MAX_VALUE` 会漏掉 `snsId == Long.MAX_VALUE` 的行 | 先例无复合游标 |
| e | `runCatching{}.getOrNull()` 把「降级 null」与「反射抛异常」混为一谈 | 先例的失败路径只是打日志，不参与判定 |

---

## 5. 给主会话的即时结论

1. **不必因为「装箱可能不匹配」而推迟真机验收** —— 该疑虑已被 164 处既有用法 + C5/D4 真机 PASS 反证。
2. **真机验收的判据仍然有效**：`rows>0` 才是硬证据。若 `rows=0` 且 `curNoDb>0` ⇒ 是**句柄未捕获**的预期降级（不是反射问题），下一步应看 `snsReady()`。
3. **复核的价值集中在 (a)(e) 两项** —— 它们决定「拿到的行对不对」和「失败了看不看得出」，而非「能不能拿到」。
