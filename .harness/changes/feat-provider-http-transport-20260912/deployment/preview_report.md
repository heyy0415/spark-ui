# 部署验证 — feat-provider-http-transport-20260912

单体形态 `deploy-verify.sh` **12 passed, 0 failed**；跨服务形态 `e2e-provider.sh` **15 passed, 0 failed**。

## 单体形态（deploy-verify，12 项）

| 门禁项 | 结果 |
|---|---|
| 后端 `/actuator/health` | `{"status":"UP"}` |
| 启动自检 | **9 项全 OK** |
| 预览页 `/` | 200 |
| 预览代理 `/actuator/health` | 200 |
| 一条 Run 的 SSE 事件序列 | `run.started → tool.selected → tool.started → tool.completed → tool.selected → tool.started → tool.completed → ui.replace → confirmation.required` |
| 确认后到达 `run.completed` | ✓ |
| run-summary 状态 | `COMPLETED` |
| 后端日志含本次 Run | ✓ |
| **日志不含用户原文** | ✓（0 命中） |
| 预览页 `console.error` | **0** |
| 聊天页渲染 `#agent-input` | ✓ |
| bundle 大小已记录 | ✓ |

上面这条事件序列走完了完整的高风险链路：两次工具调用（前置只读 → 目标工具）→ 换屏 → 要求确认 → 确认后完成。

## 跨服务形态（e2e-provider，15 项）

hub + provider 两个进程，**provider 以 JDK 17 编译**。

| 断言组 | 覆盖 |
|---|---|
| 装配（1） | hub 配了 provider 密钥才装配 HTTP 传输 |
| 认证（3） | 无令牌注册 401；错令牌注册 401；未认证直连 provider 执行端点 401 |
| 推送（3） | provider 扫到工具；全部 Manifest 推送成功；带 `protocol=http` |
| 注册（3） | hub 确实注册；`protocol=http`；`provider.serviceName` 正确 |
| 执行（2） | hub 经 HTTP 调通；provider 侧收到调用 |
| 幂等（1） | 同 `idempotencyKey` 重放，provider **不重复执行业务** |
| 审计（1） | 跨进程调用同样进 hub 审计 |
| 不回归（1） | 同一 hub 上的 in-process 工具照常可用 |

## bundle 大小

`bundle_size.txt` 已写入。最大三项（`apps/chat`）：

| 文件 | 大小 |
|---|---|
| `Table-CzYVpGid-*.js` | 219 KB |
| `tooltip-*.js` | 109 KB |
| `Form-BWKhP5gq-*.js` | 107 KB |

均为 antd 组件的懒加载分块，本 change 未触碰前端渲染层。`@spark-ui/core` dist 248 KB 由 `verify-pack` 断言 ≤ baseline × 1.1。

## 规划器口径

未设 `SPARK_LLM_*`，走示例宿主的假规划器（`planner=fake-e2e`）。其计划仍过 `PlanValidator` 全部校验。**模型的意图理解质量不在本轮覆盖范围**，需带真 key 单独验。

## HITL 确认点 ④：部署参数

本 change **不涉及生产部署参数**（环境、域名、灰度比例）——交付物是代码与示例，未部署到任何环境。

微服务形态投产时需人工确认的三项，已在文档写明而非留空：

| 项 | 说明 |
|---|---|
| `spark.providers.tokens.*` | hub 侧每个 provider 一条密钥。**不配 = 不接受远程工具**（非"不检查"） |
| `spark.provider.base-url` | provider 对 hub 可见的地址。用 `http://` 会启动 WARN，生产应 HTTPS 或 mTLS |
| 存储替换 | 全内存状态（Run / 令牌 / 幂等 / 记忆 / 注册表）在生产需替换为持久实现；provider 幂等多实例部署需换共享存储 |

三项均在 README「已知限制」与 `agent-safety.md` §8 有对应条目。
