# CI 验证 — feat-runtime-limits-and-metrics-20260912

**本仓未配置 GitHub Actions**（用户决策：单人仓库）。阶段 6 记为 **SKIP**，以本地实跑的全量门禁替代。

## 门禁：`pnpm -C .harness run ci`

**在已提交状态下**重跑，退出码 **0**（九步全过）——确认提交的不是坏的，而非只在工作区绿。

新增门禁一条：**Micrometer 不得成为硬依赖**（`check-module-deps`）。守两件事：

1. spi / contracts / runtime / registry / gateway / web-mvc / provider-starter 的 pom **不含** micrometer；
2. hub starter 可以含，但**必须带 `<optional>true</optional>`** —— 只查存在性不够，不带 optional 就会传递给所有宿主。

三条双向自证：给 runtime 加依赖 → 红；去掉 starter 的 optional → 红；给 provider 加依赖 → 红；恢复 → 绿。

## 传递依赖实测

不止查 pom 声明，实测依赖树：

| 工程 | `micrometer-core` 数 | 含义 |
|---|---|---|
| `provider-demo` | **0** | optional 真的阻断了传递，provider 不会装配 `MetricsBeans` |
| `host-demo` | **1** | 经自身 actuator 引入，hub 侧指标可用 |

provider-demo 树里另有 `micrometer-observation` / `micrometer-commons` 两项，来自 **`spring-web`**（Spring Framework 自己的 observation API），不是本 starter 泄漏的。

## 测试

| 套件 | 结果 |
|---|---|
| 后端单测（`mvnw install` 内） | 全过 |
| 前端单测 | **107 passed**（106 → 107，新增 `ErrorResponse.code` 接受 `RATE_LIMITED` 且仍拒未知值） |
| `e2e-backend` | **161 passed, 0 failed** |
| `e2e-provider` | **15 passed, 0 failed** |
| `e2e-frontend` | **7 passed** |
| `deploy-verify` | **12 passed, 0 failed** |

## 指标实测（单测覆盖不到的部分）

起真实进程查 `/actuator/metrics`，确认 4 个指标真实出现且标签低基数：

```
spark.tool.invocations   availableTags: toolId, status   ← 只有这两个，无任何 ID
spark.tool.duration      availableTags: toolId
spark.run.outcomes       outcome=completed, count=1
spark.run.duration       availableTags: outcome
```

`spark.llm.*` 未出现是**预期**：e2e profile 用 `FakeLlmPlanner`，不经 `LlmPlanner`（LLM 埋点在那里）。**带真 key 的 LLM 指标本轮未验证**，已记入遗留。

这一步查出了 `@ConditionalOnBean` 在 `@Import` 类上不生效的真 bug —— 单测全绿、启动正常、指标一个都没有。只有跑真实进程才能发现。
