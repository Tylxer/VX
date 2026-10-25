# R16-V2a 补充实证：V1 的 P1-1（`getItem` 协变/桥接双方法）**不成立**

**时间**：2026-10-24 22:2x
**执行**：主会话（JDK 21 实测 + dex 复核）
**目的**：在 V2 复核之外，独立清掉 V1 遗留的 P1-1（这是唯一一条**至今未修**的 P1）

---

## 1. 结论

**V1 报的 P1-1 是误报。`adapter.javaClass.getMethod("getItem", int.class)` 拿到的就是协变版
（返回 `Lcom/tencent/mm/storage/h9;`），桥接版不会被选中，`invoke` 正常返回 h9 对象。**

⇒ `ForceForward.kt:900-903` 那几行**不需要改**。P1-1 可以从待修清单里划掉。

---

## 2. dex 侧事实（V1 说得对的部分）

三个 adapter **确实**各有两个同名同形参的 `getItem`：

```
$ python -c "...遍历 class_defs 的 methods_of，筛 nm=='getItem'..."
== Lcom/tencent/mm/ui/chatting/adapter/h;  super= Landroid/widget/BaseAdapter;
   getItem (I)Lcom/tencent/mm/storage/h9;      acc 0x1
   getItem (I)Ljava/lang/Object;               acc 0x1041      ← 0x40 = ACC_BRIDGE
== Lcom/tencent/mm/ui/chatting/adapter/i0; super= Lju2/s0;
   getItem (I)Lcom/tencent/mm/storage/h9;      acc 0x1
   getItem (I)Ljava/lang/Object;               acc 0x1041
== Lcom/tencent/mm/ui/chatting/adapter/x;  super= Lki4/k0;
   getItem (I)Lcom/tencent/mm/storage/h9;      acc 0x1
   getItem (I)Ljava/lang/Object;               acc 0x1041
```

`0x1041` = `ACC_PUBLIC(0x1) | ACC_BRIDGE(0x40) | ACC_SYNTHETIC(0x1000)` ⇒ 确认第二条是**编译器生成的桥接方法**。

---

## 3. JVM 反射侧事实（V1 猜错的部分）

V1 的疑虑是：「`getMethod` 对双候选行为依赖 JDK，可能抛 `NoSuchMethodException` 或拿不到 h9」。

**实测（JDK 21，与本机工具链同版本）**：

```java
interface I1 { Object getItem(int i); }
class Base implements I1 {
    public String getItem(int i) { return "covariant-" + i; }   // javac 自动生成桥接
}
```

输出：
```
--- getDeclaredMethods 里所有 getItem ---
  public java.lang.String Base.getItem(int)  isBridge=false isSynthetic=false
  public java.lang.Object Base.getItem(int)  isBridge=true  isSynthetic=true
getMethod 拿到的: public java.lang.String Base.getItem(int) isBridge=false
invoke 结果 = covariant-7
```

⇒ **`getMethod` 返回的是协变版（`String` / 对应 dex 里的 `h9`），不是桥接版**；
`invoke` 正常，返回值类型正确。

### 为什么

`Class.getMethod(name, Class...)` 的匹配规则只看 **方法名 + 形参类型**，**返回类型不参与匹配**。
两条候选形参都是 `(int)` ⇒ 都符合。JDK 的选择策略是：
**优先返回非 bridge、非 synthetic 的那条**（`Class.getMethod` 内部走 `privateGetPublicMethods` →
`MethodArray.getMostSpecific`，桥接方法会被视为「不够具体」而排到后面）。

### 就算拿到桥接版会怎样？

**也不会错**。桥接方法的字节码就是
`invokevirtual 协变版(同名同参) → areturn` —— 它只是**转发**，返回的是同一个对象，
只是静态类型被擦成 `Object`。我们只要 `got.javaClass.name` 和后续 `getMethod`，
**根本不依赖静态返回类型**。

⇒ **两条路径都安全。P1-1 是双重误报。**

---

## 4. 顺带：V1 报的这条为什么会「看起来像真的」

`getDeclaredMethods()` 返回的数组顺序**是 JVM 实现相关的、不保证稳定**，
所以若有人按「数组第 0 个」取方法，确实会时好时坏。但代码用的是
`getMethod(name, paramTypes)`（按签名查，不是按下标取），**不受顺序影响**。

> **沉淀**：V1 的直觉（"两个同名方法，反射可能踩坑"）方向是对的，
> 但它**没有实测就下了结论**，把「可能存在风险」写成了「可能抛 `NoSuchMethodException`」。
> 这类「未实测的风险描述」在复核报告里应当标 **"待验证"**，不能当 P1 阻塞项。
> 这也是为什么我在 V2 的 prompt 里强制要求「每条结论附文件:行号原文，禁止只写结论」——
> 有些结论**根本不存在可附的原文**（因为它要跑起来才知道），这时候就该标"未验证"。
