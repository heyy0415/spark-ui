# Change Summary: refactor-llm-planner-domain-free-20260910

| 字段 | 值 |
|---|---|
| Change ID | refactor-llm-planner-domain-free-20260910 |
| 类型 | refactor |
| 状态 | CODING |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts / spark-rooter / spark-ui（按需删减） |
| 起止时间 | 2026-09-10 ~ 2026-09-11 |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Coding Report | [coding/coding_report_v1.md](coding/coding_report_v1.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE | — | spec.md | 2026-09-10 |
| 2 | 需求评审 | SKIP | 0/3 | 用户口头确认架构方向 | 2026-09-10 |
| 3 | 编码实现 | DONE | — | coding_report_v1.md | 2026-09-11 |
| 4 | 编码评审 | SKIP | 0/2 | 用户要求直接提交 | — |
| 5 | 代码推送 | TODO | — | — | — |
| 6 | CI 验证 | TODO | — | — | — |
| 7 | 部署验证 | TODO | — | — | — |
| 8 | 用户确认 | TODO | — | — | — |

## 契约变更
- `tool-search.schema.json`：domain 字段从 required 移除，缺省返回全部可发现工具

## 编码摘要
- **删除 16 个类**（领域路由、规则规划器、意图分类、规则抽取），**新增 5 个类**（LlmPlanner、UnavailablePlanner、PlanDraft、PlanValidator、SparkLoggingDefaults）
- **EntityType 枚举 → String**：entity / clarifiesEntity / label 全改宿主定义字符串
- **模型主导规划 + 通用校验边界**：PlanValidator 5 条规则，无领域知识
- **日志泄露封堵**：SparkLoggingDefaults 压制 Spring AI logger，redact 去模型名/密钥/URL
- **check-module-deps 新增 DOMAIN_WORDS 红线**：7 个平台模块禁止出现领域词汇
- **offline 门禁全绿**：check-rename / check-contracts / check-module-deps / check-seed / spark-ui / spark-rooter / host-demo / harness-doctor 均 exit 0
- **live 验证 5/7 场景通过**：「看看我的订单」「10030查看物流」「第二个的物流」「有什么商品」「今天天气怎么样」✓；「申请售后」「删除订单 10010」因上游 503 未验证

## 遗留债务
1. e2e-backend 规则模式断言已失效（用户明确要求跳过 e2e，待修改为纯 HTTP 断言）
2. deploy-verify 期望 selfcheck count=9，实际 8（DomainResolver 已删除）

## 经验沉淀
- check-module-deps self-test 正则与实际检查规则不一致 → 已修为引用同一常量（DOMAIN_WORDS）
- Spring AI OpenAiChatModel logger 泄露用户原话 + 模型名 → SparkLoggingDefaults 压到 ERROR
- 上游错误响应体回显模型名 → redact 新增 `"model":"***"` 替换
