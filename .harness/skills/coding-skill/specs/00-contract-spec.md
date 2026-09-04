# Spec: .harness/contracts/ 层

## 职责
- 跨端数据结构的唯一真源。JSON Schema 2020-12。
- 只有 `*.schema.json` 与 `examples/*.json`；不含可执行代码。

## 文件模板

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://strato.local/contracts/v1/{name}.schema.json",
  "title": "{Name}",
  "description": "一句话说明用途与方向（谁 → 谁）",
  "type": "object",
  "additionalProperties": false,
  "required": ["..."],
  "properties": {}
}
```

## 必备
- 每个 Schema 至少 1 个 `examples/{name}.example.json`，`pnpm -C .harness run check-contracts` 用它做正例校验。
- ID / 金额 / Token 为 `string`；金额加 `pattern`；时间加 `format: date-time`。
- 对外结构 `additionalProperties: false`；自由 JSON 字段显式 `type: object`。
- 复用结构用 `$defs` + `$ref`，不复制粘贴。
- 枚举用 `enum`，并在 `description` 里逐项解释。

## 变更
- 兼容性变更（新增可选字段）：改原文件，`examples` 同步。
- 破坏性变更：新建 `{name}.v2.schema.json`，旧文件保留并在 `description` 标注 deprecated。

## 反模式
- ❌ 在 Schema 里放 URL、HTML、脚本字段。
- ❌ 用 `type: ["string","number"]` 之类的联合类型表示 ID 或金额。
- ❌ 前端 Zod 与 Schema 字段名不一致（评审逐字段比对）。

## Ajv strict 模式注意（check-contracts 使用 `strict: true`）
- `if` / `then` / `else` 子 Schema 必须显式写 `"type": "object"`。
- `then` 中 `required` 的属性必须同时出现在该子 Schema 的 `properties`（值可为 `true`）。
- 不要用 `"type": ["string", "number"]` 联合类型，改用 `anyOf`。
- 被 `additionalProperties: false` 约束的对象，`required` 的每个键都要在 `properties` 中声明。
