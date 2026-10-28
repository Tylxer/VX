## 模型路由：自定义模型 id 与内置同名冲突（R23 定案，实测+代码双证）

**结论一句话**：`model` 参数按「内置优先」解析，**内置表里有同名 id 时永远命中内置**，自定义模型
只有加 `custom-local:` 前缀才可选。UI 里改不了这件事——UI 那个字段不是 WorkBuddy 的 id。

### 自定义模型存在哪（三处，只有一处是源）
| 文件 | 角色 | 备注 |
|---|---|---|
| `~/.workbuddy/models.json` | ✅ **唯一真源** | 数组，每项 `id` 就是用户在 UI 填的值 |
| `~/.workbuddy/storage/user-*/…/scoped/<hash>/wb-product-config.json` | 合并快照 | `product-config-snapshot.data.models`，64 条（58 内置 + 4 自定义 + 2 其他） |
| `~/.workbuddy/cache/acc-product-config-v3.json` | 传给 CLI 的缓存 | 58 条，**不含自定义**；每次刷新都可能变 |

⚠️ **只看 `cache/acc-product-config-v3.json` 会得出「没有自定义模型」的错误结论**——那是只含内置的子集。

### 前缀是代码加的，不是用户填的
`cli/dist/codebuddy-lite-wb.mjs`：
```js
CUSTOM_MODEL_ID_PREFIX       = "custom:"
CUSTOM_LOCAL_MODEL_PREFIX    = "custom-local:"

decorateCustomModels(t, isLocal) {          // models.json 载入时调用
  if (isLocal && !id.startsWith("custom-local:")) {
     aliases.push(id);                     // 原值留作别名
     id = "custom-local:" + id;            // ← id 在这一步被改写
  }
  tags.push("custom"); if (url) t[OVERRIDE_MODE] = "replace";
}
```
⇒ `models.json` 里写 `"id": "deepseek-v4-pro"`，运行时变成 `custom-local:deepseek-v4-pro`。

### 解析顺序（问题所在）
```js
resolveModel(t)        → findModelByIdOrName(t)
findModelByIdOrName(t) {
  currentModelMap.get(t)                    // ① 精确 id —— 内置命中就返回，不往下走
  ?? find(m => m.name === t)                // ② 按 name
  ?? find(m => m.aliases?.includes(t))      // ③ 别名（自定义的原值在这里）
}
```
内置 `deepseek-v4-pro` 在 ① 就命中 ⇒ 先补前缀再查的那条路**根本走不到**。

### 实测铁证（两个受控实验，19:06 同一 turn）
| 传入 `model` | `requestModelId` | `requestModelName` | 结果 |
|---|---|---|---|
| `custom-local:deepseek-v4-pro` | `custom-local:deepseek-v4-pro` | **DeepSeek-V4 Pro** | ✅ 自定义 |
| `deepseek-v4-pro` | `deepseek-v4-pro` | **Deepseek-V4-Pro**（大写 V4 是内置名） | ❌ 内置 |

跨天同类对照：`deepseek-v4-flash-202605` / `deepseek-v4-pro-202606`（内置表**无**同名）→ 永远正确路由到自定义。

### 传入上游的值 = 去掉前缀后的原 id
`prepareCompatibleModelRequest()`：`t.body.model = stripHostModelPrefix(t.rawWireModelId)`
⇒ `custom-local:deepseek-v4-pro` 发给 `api.deepseek.com` 的是 `deepseek-v4-pro`。**转发行一直是对的。**

### 冲突清单（当前 models.json）
| 自定义 id | 内置同名？ | 后果 |
|---|---|---|
| `deepseek-v4-pro` | ⚠️ 有（`vendor:"f"`, credits x0.51） | **档位拿不到自定义** |
| `deepseek-v4.1-flash` | ⚠️ 有 | 潜在同类问题 |
| `deepseek-v4-pro-202606` | 无 | ✅ 正常 |
| `deepseek-v4-flash-202605` | 无 | ✅ 正常 |

### 为什么 UI 改不了 id
`ModelsSettingsPanel` 的表单项 i18n key 是 `settings.models.fields.modelId`，中文文案
**「模型名称」**，placeholder **「输入模型参数值，例如 gpt-4o 或 openai/gpt-4o」**。
⇒ 这个框填的是**转发给上游 API 的模型名**，改了 API 就会 404。
WorkBuddy 内部 id 由 `decorateCustomModels()` 在 `models.json` 里原值前自动拼前缀，无 UI 入口。

### 可行做法
1. **派子代理时写全前缀**（已实测通过，零风险）：
   `Agent(model="custom-local:deepseek-v4-pro")`
2. **改 `models.json`**（需退出 WorkBuddy，改完重启）：
   `"id": "deepseek-v4-pro"` → `"id": "ds-v4-pro-official"`
   ⚠️ 此时转发名也变成 `ds-v4-pro-official` → 必须同时改 `url` 指向能认这个名字的端点，
   或改用 `api.deepseek.com/v1` + 正确模型名。**实际是「改 id」与「改上游模型名」两件事冲突**，
   所以对 `deepseek-v4-pro` 这条最省事的就是方案 1。
3. 在 UI **另建一条** id 不冲突的自定义模型（如 `deepseek-v4-pro-api`，url 用官方 v1），
   与内置并存，档位写 `custom-local:deepseek-v4-pro-api`。

---

## ⛔ 实证：显式传 `deepseek-v4-pro` 未必按 Pro 发出（2026-10-29 真值回填发现）

**现象**：R33 派的两个 Pro 类 lane，台账记的 spawn 串是 `deepseek-v4-pro`，
但读 `subagents/*.jsonl` 的 `providerData.requestModelId` 得：

| lane | agent id | spawn 传的串 | **真值 `requestModelId`** |
|---|---|---|---|
| `rev-sns`（复核 Sns 注入候选） | `agent-c57d0517` | `deepseek-v4-pro` | **`deepseek-v4.1-flash`** |
| `rev-hd`（复核 AutoHdImg 递归） | `agent-b17187d5` | `deepseek-v4-pro` | **`deepseek-v4.1-flash`** |
| `review-r31h`（对照，规划就是 flash） | `agent-32189435` | `deepseek-v4.1-flash` | `deepseek-v4.1-flash` ✅ 相符 |

**结论**：对 `deepseek-v4-pro` 这个 id，L1 请求意图层**可能出现「写了 Pro、实际发 Flash」**，
与 §四②「裸写 ID 即命中」的假设冲突。对照 `Hy4 preview`（R34 五个 lane 全部真值 `hy4-preview`）
则**完全相符** ⇒ 问题出在 `deepseek-v4-pro` 这一条，不是所有 id。

**推论（谨慎）**：`rev-hd` 两次 400 / 零产出可能与档位回落同源，但**没有证据**，
不能当成因果。只记事实。

### 硬核验的正确读法（别再写成「读不到」）

`requestModelId` 在 **`providerData` 里**，不是顶层：

```python
o = json.loads(line)
mid = (o.get("providerData") or {}).get("requestModelId")   # ✅
mid = o.get("requestModelId")                                # ❌ 恒 None
```

R33/R34 两次自审都漏了真值回填，第二次差点因为顶层读法为 `None`
就写成「沙箱读不到、事后无补救」—— 那正是 §四② 明令禁止的错误写法。

### 行动项
- 凡 spawn 串写 `deepseek-v4-pro` 的 lane，**事后必须回填真值**再判「档位合规」。
- 若后续再见到 pro→flash 回落，优先试 `custom-local:deepseek-v4-pro`（上文「可行做法 1」）。
