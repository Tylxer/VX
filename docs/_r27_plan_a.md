# R27 方案 A（Response-Merge）实现记录

> 上游结论见 `docs/_r27_clear_api_forensics.md`（方案 B v2 已闭环）。
> 本文只记 A 的实现、判据分层和真机校验清单。

## 1. 为什么必须做 A

方案 B 修好之后，同一个库、同一时刻：

| 场景 | 界面 |
|---|---|
| 断网冷启进朋友圈 | verity「10」「9」「8」全部可见 ✅ |
| 联网进朋友圈 / 下拉刷新 | 三条**都不见** ❌ |

⇒ 联网时间线用**响应内存对象**渲染，`src=2` 只救读库路径。
⇒ B 的价值边界 = 断网 / 冷启 / 离线浏览。联网刷新可见只能靠 A。

## 2. 链路（静态 dex 取证）

```
c3.N(IILjava/lang/String;Lq54/zb5;)V        ← NetSceneSnsTimeLine（getType=211）
  ├─ 遍历 zb5.f（LinkedList<SnsObject>）
  ├─ 逐条 l5.o(SnsInfo, SnsObject, …) 写库
  ├─ 收集 Id → SnsTimelineRespListNotifyEvent
  └─ IEvent.d() 发布 → UI 渲染
```

- 在 `N` 执行**前**把对象 `add` 进 `zb5.f`，它就会随「写库 + 事件 + 渲染」全链路走完。
- 个人相册走 `h3`(212) / `k3`(5203)，**不经过 `c3.N`** ⇒ 天然只命中时间线。

字段核实（`_r12_dexclass.py`）：

| 声明 | 实测 |
|---|---|
| `q54.zb5.f` | `LinkedList`（0x11 = PUBLIC\|FINAL）✅ |
| `SnsObject.Id` | `J`（long）✅ |
| `SnsObject.CreateTime` | `I`（int）✅ |
| `SnsObject.LocalFlag` | **不存在**（52 个实例字段里没有）⚠️ |

## 3. ⭐ 对象不用手拼：`attrBuf` 就是 SnsObject 的序列化

- `l5.o`（写）：`SnsObject.toByteArray()` → `SnsInfo.setAttrBuf([B)`
- `l5.f` / `l5.e`（读）：`new SnsObject().parseFrom(attrBuf)`

⇒ 我们只需 `new SnsObject().parseFrom(attrBuf)` 一条，**不必碰那 53 个字段**。
`parseFrom` 定义在 protobuf 基类 `com.tencent.mm.protobuf.a`，返回基类类型（实际是同一实例）。

好友删除动态**不删行**，`attrBuf` 保留的是删除前最后一次同步的内容 ⇒ 内容完整可还原。

## 4. 实现

### 4.1 文件分工（沿用 B 的分层惯例）

| 文件 | 职责 |
|---|---|
| `feature/SnsTimelineMergeLogic.kt` | 纯判据，零 Xposed 依赖，JVM 可测 |
| `feature/SnsTimelineMerge.kt` | 钩子安装 + 反射取列表/读 BLOB/构造对象/注入 |
| `feature/SnsGuard.kt` | `install()` 末尾调用 `SnsTimelineMerge.install(lpparam)` |
| `entry/EntryReceiver.kt` | 探针 `op=snsg sub=merge` → 打印统计 |

⚠️ 必须拆两个文件：`SnsTimelineMerge` 里挂着 `XC_MethodHook` 字段，
JVM 单测一加载该 object 就会因缺 Xposed 运行时**整个测试进程崩掉**（exit 1，不是断言失败）。
这与 `SnsTimelineSrcLogic` / `SnsTimelineSourceFix` 是同一套做法。

### 4.2 注入流程（`onBeforeResp`）

```
① enabled()（读 Keys.ANTIREVOKE_SNS）
② args.size == 4，取 args[3] = zb5
③ 反射 getObjectField(resp, "f") as? MutableList<Any>   ← 失败打日志
④ 遍历列表收 respIds + times（按 list 顺序一一对应）
⑤ 候选 = GroupDb.listSrcFixedIds(200) 减去 respIds，取前 MAX_PER_ROUND(12)
⑥ db = SnsGuard.snsDbHandle()  ← null 打日志（冷启首次可能还没捕获到）
⑦ 逐条：readAttrBuf → parse → Id 兜底 → 算下标 → list.add(idx, o)
```

### 4.3 判据分层（本项目栽过三次，必须分层）

| 层 | 判据 | 观测手段 |
|---|---|---|
| **L1** | `list.add` 成功 | 日志 `SNS-INJECT snsId=… parse=ok buf=N ct=… idx=… size=…` |
| **L2** | 该行落库后 `sourceType=2` | `snsg sub=dump` |
| **L3** | 界面真的渲染出来 | `uiautomator dump` 抽文本 / 截图 |

**L1 ∧ L2 ∧ L3 全中才算成功**。只看 L1 会重演「以为注入了其实没显示」。

### 4.4 反静默失效（硬性）

- 类找不到：`SNS-INJECT install: class missing …(merge disabled)`
- 钩不上：`SNS-INJECT hook c3.N failed`
- 列表字段拿不到：`SNS-INJECT no list field f on <类名>`
- BLOB 空 / 解析异常 / add 失败：各自一条 `SNS-INJECT-FAIL snsId=… reason=…`
- **注入日志不去重**（历史事故：`n==1` 去重把第 2 条起吞掉，结论完全反掉）

## 5. 已知风险与对策

| # | 风险 | 现状 |
|---|---|---|
| 1 | 重复注入（同 snsId 多轮 add） | 每轮 `zb5` 是新对象；同轮内 `respIds` 去重 + 纯逻辑 `pickMissing` 双保险 |
| 2 | 排序错乱 | 按 `CreateTime` 降序算插入位（`insertIndexDesc`），相等插到同值之后 |
| 3 | `isDieItem()` 过滤（`localFlag<=0`） | **未定**：`SnsObject` 没有 LocalFlag 字段，`oh3/p` 另有 `F0()→SnsInfo`。真机 L3 直接回答 |
| 4 | `stringSeq` 分页游标 | 注入只影响首屏 `zb5.f`，不参与 `d2.t5` 游标；翻页由 B 兜底 |
| 5 | 性能 | 上限 12 条/轮，`parseFrom` 是轻量字节解析 |
| 6 | 方法名漂移 | install 失败/类缺失都有日志；未做 `w5.onSceneEnd` fallback |
| 7 | `zb5.f` 被下游 clear | 每轮日志带 `size=` 前后值 |

## 6. 真机校验清单（待执行）

1. 冷启微信 → `snsg sub=merge` 应打出 `install` 后首次统计
2. 进朋友圈 → 期望 `SNS-INJECT snsId=-3405721048722501111 parse=ok …`（verity「10」）
3. `snsg sub=dump` → 三条 `src=2`
4. **L3**：界面滚到 45~60 分钟前区间，看「10 / 9 / 8」是否出现
5. 反例对照：同一轮里未被删的正常动态仍正常显示（不能把别人挤掉/搞乱序）

**若 L3 不中**：说明 `isDieItem()` 把注入对象判死了。下一步取证 `oh3/p.isDieItem()`
读的是哪份数据（`SnsObject.DeleteFlag` 还是 `SnsInfo.localFlag`），再决定补哪个字段。
