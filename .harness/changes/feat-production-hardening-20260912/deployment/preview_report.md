# 部署验证报告 — feat-production-hardening-20260912

**规划器**：`planner=fake-e2e`（无 `SPARK_LLM_API_KEY`，启用 e2e profile 的假规划器）。验的是校验边界 / 编排 / 网关 / 领域 / 前端渲染 / 多副本一致性；模型理解质量不在覆盖范围。

## 五套脚本 + 一套新脚本

| 脚本 | 结果 | 变化 |
|---|---|---|
| `deploy-verify` | **14 passed, 0 failed** | +2：readiness 组 200、`sparkRooter=UP` |
| `e2e-backend` | **162 passed, 0 failed** | +1：客户端断开后 Run 到终态不悬挂 |
| `e2e-provider` | **15 passed, 0 failed** | 不变 |
| `e2e-frontend` | **7 passed** | 不变（前端零改动） |
| `e2e-multi-instance` | **21 passed, 0 failed** | **新**：两 hub 共享 Redis |
| 前端单测 | **107** | 不变 |
| 后端单测 | **275 / 0** | +30（Redis 18、Run 3、编排 8、健康 3、内存 TTL 5，减去合并）|
| `pnpm -C .harness run ci` | 见 `ci_result/ci_summary.md` | 9 步 |

## 多副本链路（e2e-multi-instance 逐步）

```
0. 两 hub（8095 / 8096）profile=e2e,redis，同一 Redis db 15；两边都 "spark storage: redis"，无回落 WARN
1. A 发起「订单 10002 退款」→ confirmation.required；Redis 有 spark:run:{id} 与 spark:token:{t}
2. B GET 同 runId → WAITING_CONFIRMATION，currentUi 含 confirm-refund 动作（屏来自 Redis，不是 A 的内存）
3. B 确认 → recheck + refund.create → run.completed；B 审计有 refund.create，A 没有；令牌已从 Redis 消失
4. A GET → COMPLETED（看到 B 写回的终态）
5. 同令牌回 A 重放 → CONFIRMATION_REJECTED，Run 仍 COMPLETED
6. 对 A 直调 gateway 同 idempotencyKey → replayed，refund.create 执行数不变
7. A 写会话记忆（列表）→ B 上「第二个的物流」解析为 order.logistics.get
8. Redis 全部值里 0 处用户原话
```

## 健康探针实测

| 场景 | 根 `/actuator/health` | `/readiness` | `sparkRooter` detail |
|---|---|---|---|
| 无模型、无 profile | DOWN | **503** | `planner=unavailable, circuit=closed` |
| fake planner | UP | 200 | `planner=fake-e2e` |
| Redis 模块在 classpath 但 `storage.type=memory` | 不受 Redis 影响（`management.health.redis.enabled=false`） | — | — |

Dockerfile HEALTHCHECK 已改打 `/actuator/health/liveness`。

## 压测（`deployment/load_test.md`）

独立会话 8 / 16 / 32 并发 0 拒绝，p99 10 / 51 / 55 ms；64 并发从第 8 个请求开始拒绝，128 并发 rps 3400+（快速失败）；`client_timeout=0`、`ERROR 0`。同会话 4 并发 0 拒绝，8 并发出现 `session busy`。三个默认值不改。

## 自发包路径实测

`mvn -P '!examples' install` → `~/.m2/com/sparkrooter` 10 目录（9 artifact + parent）；`-P release` → sources / javadoc jar；默认 install 15 目录（含 5 个示例）。

## 一次假阳性

`e2e-backend`（8091）与 `deploy-verify`（8092）并行跑，共用同一 change 的 `deployment/backend.log`，后者 `>` 截断了前者的启动段，15 条 grep 启动日志的断言红。单独串行重跑全绿。已写进 `deploy-verify` skill 注意事项。

## 投产前需人工确认（HITL ④）

1. `spark.storage.type=redis` 时 `spring.data.redis.*` 指向生产 Redis；`claim-ttl` ≥ 最大工具 `timeoutMs`
2. K8s readinessProbe 打 `/actuator/health/readiness`，livenessProbe 打 `/actuator/health/liveness`——**别打根端点**
3. `server.shutdown=graceful` + `timeout-per-shutdown-phase` 与 Pod 的 `terminationGracePeriodSeconds` 对齐（后者应更大）
4. 真模型下单副本约 40 并发对话；按实际规划耗时复核 `run-queue`
5. CI 工作流首次在 GitHub 上跑后看一眼（本地无法验证 Actions）
