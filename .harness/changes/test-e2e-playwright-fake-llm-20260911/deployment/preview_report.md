# Preview Report — test-e2e-playwright-fake-llm-20260911

- **阶段**: 7 部署验证
- **命令**: `SPARK_PORT=8091 SPARK_CHANGE=test-e2e-playwright-fake-llm-20260911 bash .harness/scripts/deploy-verify.sh`
- **结果**: **12 passed, 0 failed**，退出码 0
- **规划器**: `planner=fake-e2e`（本机未设 `SPARK_LLM_API_KEY`，脚本自动启用 e2e profile 的假规划器）

> 规划器口径说明（`deploy-verify/SKILL.md` 本 change 新增的要求）：这 12 条是在**假规划器**下通过的。假规划器只替代「理解」环节，产出的计划仍经 `PlanValidator.decide` 的全部校验，因此编排、令牌、网关、领域实现、前端渲染都是真实链路。但**模型的意图理解质量未被本次验证覆盖** —— 那需要设 `SPARK_LLM_API_KEY` 后重跑（届时脚本会打印 `planner=llm`）。
>
> 对比：本 change 之前，无模型机器上这 12 条只能过 8 条（Run 段 4 条因 `UnavailablePlanner` 让所有请求直接失败而必失）。

## 1. 后端

| 项 | 结果 |
|---|---|
| `/actuator/health` | `{"status":"UP"}` |
| 启动自检 | 9 项全 OK |
| 工具注册 | 14 tools from 6 beans |
| 假规划器装配 WARN | 1 条（`e2e 假规划器已装配（profile=e2e/e2e-ttl），仅供测试`） |

## 2. 前端预览（vite preview :4173，代理到 8091）

| 项 | 结果 |
|---|---|
| `/` | 200 |
| `/actuator/health` 代理 | 200 |
| `preview-chat` console.error | 0 |
| `preview-notfound` console.error | 0 |
| 首屏渲染 `#agent-input` | `agent-input=1` |

console 检查已由 `preview-console.mjs` 的 Playwright 实现完成（本 change 从 puppeteer + 本机 Chrome 迁移），stdout 格式与退出码语义保持不变。

## 3. 端到端一条 Run

消息：`帮我把订单 10001 退款`（取自 `.harness/contracts/examples/intent-request.example.json`）

```
run.started
tool.selected tool.started tool.completed     ← refund.eligibility.check
tool.selected tool.started tool.completed     ← refund.preview
ui.replace
confirmation.required
```

两个只读前置步骤对应 `RefundTools` 的 `@SparkPrerequisite({"refund.eligibility.check","refund.preview"})`；`refund.create` 在确认后才执行。

确认动作后：`run.completed`，`GET /agent/runs/{runId}` 返回 `state=COMPLETED`。

**安全断言**：`backend.log` 中用户原话 `帮我把订单 10001 退款` 出现 0 次（假规划器不打原话）。

## 4. 体积

| 产物 | 大小 |
|---|---|
| `apps/chat` 最大 chunk `index-*.js` | 353 KB（未 gzip） |
| `apps/chat` 次大 `useSize-*.js` | 252 KB |
| `packages/core/dist/index.js` | 13.9 KB |
| `host-demo.jar` | 38.5 MB |

`verify-pack` 的 core dist 基线检查：47 KB ≤ baseline 47 KB × 1.1，通过。本 change 未引入运行时依赖（Playwright 是 devDependency，不进产物），体积无变化。

## 5. 前端 e2e 报告

`deployment/e2e-frontend/` 下有 Playwright 的 HTML 报告（`index.html`）与 `test-results/`。7 个用例全过，无失败 trace。

## 6. 门禁清单

- [x] `pnpm -C spark-ui run build` 与后端 package 退出码 0
- [x] `/actuator/health` 返回 `"status":"UP"`
- [x] 预览首页 console.error == 0
- [x] 示例 Run SSE 事件序列含 `run.started` 与终态事件
- [x] bundle 增长 ≤ 10%（无变化）
- [x] **实际规划器已记录**（`planner=fake-e2e`）
