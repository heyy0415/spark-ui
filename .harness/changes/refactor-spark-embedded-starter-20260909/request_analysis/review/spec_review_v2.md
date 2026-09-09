# Spec Review v2 — refactor-spark-embedded-starter-20260909

- mode: plan
- 评审对象：spec.md v2、tasks.md v2（对照 v1 评审的最小补丁清单逐项复核，机械 grep 见下）

## v1 条目复核

| # | 状态 | 证据 |
|---|---|---|
| M-1 代理 / 线程 | 已修复 | §2.4 新增「代理与线程」小节：final / 非 public / @Configuration 上 → 启动失败；`getMostSpecificMethod` + `selectInvocableMethod`；「宿主权限必须方法级」明写；`RunContextPropagator` 端口 + 默认 no-op WARN + 示例宿主实现；e2e ⑭' 反面路径、㉖ 传播断言、㉗ final 反例、`ProxyInvocationSelfCheck` |
| M-2 会话隔离 | 已修复 | §2.3 / §2.7 默认 `SessionIdResolver` 标 demo-only + WARN；`consume` 双校验 conversationId + sessionId；§7 如实写「默认无隔离」；README 生产必做 |
| M-3 粒度 | 已修复 | T07 → T07a（逻辑）/ T07b（纯移动）；T09 → T09a（内核）/ T09b（示例迁移）；依赖图更新；18 task |
| M-4 空洞验收 | 已修复 | §6.1 改机械 grep（含 `@ComponentScan`）；§6.3 写死 36/36 并列出增删条目；T15 改 grep 白名单 + 行数相等断言；`≥ 34 / 即证明 / 人工核对` 0 命中 |
| S-1 类型表 | 已修复 | 嵌套 record / Optional·@Nullable / Java enum / Instant·LocalDate / `NON_ABSENT`；T09a 增 `ManifestParitySelfCheck` 12 个 diff 为空 |
| S-2 lockfile | 已修复 | T02 验收 `pnpm install` 后 lockfile grep 0 |
| S-3 澄清屏 | 已修复 | Runtime `ClarificationScreen` 从原始输出投影并过 `toUi`；`@SparkTool(clarifiesEntity)` 替代硬表；T12 验收 runtime 源码无 `order.list.search` 字面量 |
| S-4 unit / limit | 已修复 | `@SparkParam.unit` 独立；参数名沿用 `limit` |
| S-5 ToolAccessPolicy 入参 | 已修复 | `(String toolId, String sessionId)`；线程上下文由 `RunContextPropagator` 解决 |
| S-6 自检数 | 已修复 | 统一 8（spec §6.2 / T10 / T14） |
| S-7 ㉓ | 已修复 | e2e-ttl profile 第二次启动单跑 |
| S-8 authorization | 已修复 | 推导为 `{}`，契约 `permission` 可选 |
| L-1 / L-2 / L-3 / L-4 / I-1 / I-2 | 已修复 | sse-timeout 90s；路由表不可配置列为已知限制；领域包名一次到位；host-demo 先在线再离线；i18n 非目标 |

## 新发现

| # | 位置 | 问题 | 建议 | 分级 |
|---|---|---|---|---|
| N-1 | §2.10 e2e 编号 | ⑯ 在 v1 列表中存在（change 4 的「删除订单」无号码用例）但 v2 §2.10 新增段从 ⑰ 起，⑯ 是否保留未明说 | §2.10 开头补一句「⑦–⑯ 沿用 change 4，⑭ 改造」 | LOW |
| N-2 | T10 `viewer` 字段 | 示例宿主给 `order.list.search` 输出加 `viewer`，但 T09a 的 `ManifestParitySelfCheck` 要求推导 schema 与旧 JSON diff 为空——`viewer` 会破坏 parity | parity 自检允许「示例宿主专有字段」白名单（`viewer`），或把 `viewer` 放到单独的演示工具 `demo.whoami` 上；建议后者，不污染 12 个基线工具 | SHOULD |
| N-3 | §2.6 记忆写入时机 | 「每个 Run 终态写入记忆」——`run.failed`（如 TOOL_SELECTION_INVALID）也写入会把失败意图的实体带进下一轮 | 只在 `run.completed` 且有成功步骤时写入 | LOW |

## verdict: APPROVED

1 SHOULD（N-2）+ 2 LOW，均可在编码阶段（T09a / T10 / T12）直接吸收，不需 v3。进入 HITL ②（用户已授权不阻断，视为通过）。
