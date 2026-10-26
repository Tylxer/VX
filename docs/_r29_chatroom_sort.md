# R29-4 群聊列表：无名群沉底

## 规则（一句话）

群「有没有名字」：`remark` 非空白 → 有名；`remark` 空白但 `nickname` 非空白 → 有名；
两者皆空**或全是空白字符**（`"   "`/`"\t"`/`"\n"`）→ 无名。排序时**有名的在前、无名的在后，
两组内部各自保持原有相对顺序**（稳定排序）。

## 不是缺陷，只是观感

这些群在微信里本来就没名字，Tab 里显示数字群号是**正确兜底**，
所以 ⛔ **没有改任何显示逻辑**（`Friend.displayName` 原样不动），只改了排序。

## 改了哪一处

`app/src/main/java/com/lx/wxgroup/data/GroupDb.kt`

- `allChatrooms()`（原 1191–1194 行 → 现 1196–1203 行）：从「直接 `return queryFriends(...)`」
  改成「`ChatroomSortLogic.sortNamedFirst(queryFriends(...), remarkOf = { it.remark }, nicknameOf = { it.nickname })`」。
  其余一行未动。
- ⛔ **没动** `queryFriends` 里的共享 `ORDER BY wxid_type, remark, nickname` ——
  它被 `allFriends` / `allRealFriends` / `findByDisplayName` / `searchFriends` / `findFriend` 共用，
  改它会把好友列表一起带偏。排序只发生在 `allChatrooms` 这一条返回路径上。

新增文件：

- `app/src/main/java/com/lx/wxgroup/data/ChatroomSortLogic.kt` —— 纯逻辑，零 Android 依赖
  （连 `Friend` 都不 import：`Friend.kt` 的默认参数引用 `GroupDb.WxidType`，会拖进 Android 依赖，
  所以这里是泛型 + 取字段 lambda）。三个函数：`hasName(remark, nickname)` / `sortKey(...)` /
  `sortNamedFirst(items, remarkOf, nicknameOf)`。
- `app/src/test/java/com/lx/wxgroup/data/ChatroomSortLogicTest.kt` —— 12 条单测。

## 为什么用 Kotlin 侧排序而不是加 SQL

SQL 里要表达「remark/nickname 都 trim 后为空则排后」，得写成
`ORDER BY (CASE WHEN TRIM(IFNULL(remark,''))='' AND TRIM(IFNULL(nickname,''))='' THEN 1 ELSE 0 END)`，
塞进 `queryFriends` 的共享 ORDER BY 会波及好友列表；另开一条 SQL 则复制整段查询。
`allChatrooms()` 的量级是几百~上千个群，内存里一次稳定排序代价可忽略。

## 单测：12 条，全绿

判据（6 条）：`remarkOnlyIsNamed`、`nicknameOnlyIsNamed`、`blankBothIsUnnamed`、
`whitespaceOnlyCountsAsUnnamed`、`surroundingSpacesAndCaseStillNamed`、`remarkBlankFallsBackToNickname`。

排序（4 条）：`mixedNamedGoesFirst`（有名/无名混排）、`allUnnamedKeepsInputOrder`（全无名）、
`allNamedKeepsInputOrder`（全有名）、`emptyListAndSingleItem`。

稳定性（2 条）：`sortIsStableForEqualKeys`（交错输入，两组组内顺序与输入一致）、
`sortDoesNotMutateInput`（返回新列表、不改原列表）。

覆盖点对照：混排 ✅ / 全无名 ✅ / 全有名 ✅ / remark 有值但 nickname 空 ✅ /
nickname 全是空格 ✅ / 大小写与前后的空格 ✅ / 稳定性（相同比较键保持输入顺序）✅。

## 变异验证：3 个变异全部变红，还原后复绿

因为约束「不许跑 gradle」，改用本地 `kotlin-compiler-embeddable 2.0.21`（gradle 缓存里的 jar）
直接编译 `ChatroomSortLogic.kt` + `ChatroomSortLogicTest.kt`，再用 JUnit 4.13.2 的
`JUnitCore` 跑；**没有执行任何 gradle 任务、没有碰 adb**。

| 变异 | 改坏方式 | 结果 |
|---|---|---|
| M1 | `isNotBlank()` → `isNotEmpty()`（等于去掉 trim 语义） | **红：12 条挂 3 条**（`whitespaceOnlyCountsAsUnnamed`、`mixedNamedGoesFirst`、`allUnnamedKeepsInputOrder`） |
| M2 | 有名/无名顺序反过来（`GROUP_NAMED`/`GROUP_UNNAMED` 对调） | **红：12 条挂 8 条**（含 `surroundingSpacesAndCaseStillNamed`、`remarkBlankFallsBackToNickname`、`sortIsStableForEqualKeys`） |
| M3 | 组内用 `asReversed()` 拼接（等价于不稳定排序） | **红：12 条挂 4 条**（`allNamedKeepsInputOrder`、`allUnnamedKeepsInputOrder`、`sortIsStableForEqualKeys` 等） |
| 还原 | 全部改回 | **绿：OK (12 tests)** |

结论：判据与「稳定性」两条要求都被测试真正锁住了（M3 证明稳定性用例不是摆设 —— 只改组内顺序也会被抓到）。

## 风险 / 未验证项

1. **未编译**：`GroupDb.kt` 带 Android 依赖，无法离线手编。`allChatrooms()` 的改动只有
   「包一层 `ChatroomSortLogic.sortNamedFirst(...)`」+ 两个 lambda，签名是
   `List<T> (List<T>, (T)->String, (T)->String)`，`Friend.remark`/`Friend.nickname` 都是 `String` 非空，
   类型上应该直接过；仍需主会话统一 `assembleDebug` 确认。
2. **探针输出会变**：`ChatroomSource.sampleText()` 取 `allChatrooms().take(n)` 的样本，
   现在前 8 条会变成「有名群」而不是「无名群」。这只是探针，不改逻辑；对「核对群名取值」反而更直观。
3. **组内有名的排序仍是 SQL 的 `remark, nickname` 升序**：本轮的判据只分两组、组内不动，
   不引入新的组内排序规则（避免和好友列表的观感不一致）。
4. 无名群之间的相对顺序 = 数据库顺序（`remark, nickname` 全空时实际由 SQLite 返回序决定），
   仍然是**确定性**的，不会每次进 Tab 抖动。

[DONE]
