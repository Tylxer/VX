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

