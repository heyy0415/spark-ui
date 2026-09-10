---
name: code-review
description: 阶段 4 机器化检查。触发场景："代码检查"、"lint"、"typecheck"、"红线检查"、"契约校验"。在 expert-reviewer 之前跑一遍全部可命令化的检查，把机器能发现的问题清零。
---

# Skill: code-review

## 与 expert-reviewer 的分工
- **code-review（本 Skill）**：机器能发现的问题。契约示例校验、typecheck、lint、format、FSD 依赖方向、Maven 编译 / 格式、模块依赖红线。
- **expert-reviewer**：机器发现不了的问题。语义错误、契约与实现不一致、安全边界被绕过、过度抽象。

## 输入
- 当前 git diff
- L1 Rules

## 步骤（按顺序，遇到失败立即返回）

```bash
# 0. 契约（真源）
pnpm -C .harness run check-contracts

# 1. 前端
pnpm -C spark-ui run build:core   # typecheck 不依赖 dist，但 style.css 与 verify-pack 需要
pnpm -C spark-ui run typecheck
pnpm -C spark-ui run lint         # oxlint --deny-warnings + check-deps + check-registry
pnpm -C spark-ui run format:check
pnpm -C spark-ui run verify-examples
pnpm -C spark-ui run verify-pack

# 2. 后端
node .harness/scripts/mvn.mjs -q -B spotless:check
node .harness/scripts/mvn.mjs -q -B verify

# 3. 模块依赖红线（脚本读取各 pom 的 <dependency>）
pnpm -C .harness run check-module-deps

# 4. 全量门禁（等价于以上全部）
pnpm -C .harness run ci
```

## 红线检查清单

前端：
- [ ] `pages/` 之外无路由声明
- [ ] 无 FSD 反向依赖、无穿透 `index.ts`
- [ ] 无 `eval` / `new Function` / `dangerouslySetInnerHTML` / 任意路径动态 `import()`
- [ ] 可被 UI Schema 引用的组件只在 `spark-ui/packages/core/src/registry/componentRegistry.ts` 注册；`apps/chat` 无 antd / antd-mobile / `@spark-ui/core/src/*` import

后端：
- [ ] `agent-runtime`、`tool-registry` 的 pom 不依赖 `domains/*`
- [ ] `domain/` 包无 `org.springframework` / `com.fasterxml` import
- [ ] 无 `System.out`、无空 catch
- [ ] 金额 / ID 字段类型为 `String`

契约：
- [ ] 每个 Schema 有示例且校验通过
- [ ] 新增跨端结构有 Schema

## 输出
- `coding/review/code_review_v{n}.md`，列出每条命令的退出码、输出摘要、违规位置。

## Checklist
- [ ] 全部命令退出码 0
- [ ] 无 lint warning
- [ ] 红线清单全部勾选
