# 压测报告（feat-production-hardening T07）

环境：本机 macOS（Apple Silicon），host-demo 单进程，fake planner（e2e profile，规划毫秒级），`--spark.selfcheck.enabled=false`，
默认配置：run-pool 8 / run-queue 32 / tool-pool 8 / tool-queue 64 / max-concurrent-per-session 4。
工具：`.harness/scripts/load-test.mjs`（Node 20 原生 fetch；每路并发独立会话；读 SSE 到终态；客户端 100s 硬超时）。

测的是**编排 + 网关 + 线程池**的容量，不含模型延迟。接真模型时单次规划 5–70s，瓶颈会移到模型侧，这里的数字只说明 hub 自身不是瓶颈。

## 整机容量：独立会话，各并发档 15s

```
total=29419  wall=15.0s  rps≈1959.6
ok=29419  rejected(overload)=0  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=4  p95=7  p99=10  max=76
first rejection at request #none
---
total=15875  wall=15.0s  rps≈1057.3
ok=15875  rejected(overload)=0  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=13  p95=24  p99=51  max=317
first rejection at request #none
---
total=14555  wall=15.0s  rps≈967.8
ok=14555  rejected(overload)=0  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=32  p95=43  p99=55  max=149
first rejection at request #none
---
total=50194  wall=15.1s  rps≈3329.2
ok=9945  rejected(overload)=40249  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=60  p95=102  p99=148  max=261
first rejection at request #8
---
total=51437  wall=15.1s  rps≈3415.0
ok=7721  rejected(overload)=43716  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=90  p95=137  p99=184  max=282
first rejection at request #1
---
```

## 单会话上限：全部并发打同一 conversationId（--same-session），各 10s

```
total=4637  wall=10.0s  rps≈463.3
ok=4637  rejected(overload)=0  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=8  p95=10  p99=14  max=44
first rejection at request #none
---
total=6203  wall=10.0s  rps≈619.7
ok=6197  rejected(overload)=6  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=12  p95=18  p99=23  max=67
first rejection at request #562
---
total=5834  wall=10.0s  rps≈582.1
ok=5813  rejected(overload)=21  failed=0  transport=0  client_timeout=0  other=0
latency(ok) ms: p50=26  p95=39  p99=59  max=113
first rejection at request #325
---
```

后端侧拒绝来源（本轮日志）：
```
编排池拒绝（run rejected: agent-run executor saturated）: 83965
会话超限（session busy）: 27
ERROR 行: 0
```

## 结论：三个默认值与实测吻合，不改

| 配置 | 默认 | 推算依据（改前 javadoc） | 实测 | 结论 |
|---|---|---|---|---|
| `run-pool` / `run-queue` | 8 / 32 | 排到第 32 位的等待已超 sseTimeout | 独立会话 32 并发 **0 拒绝**、p99 55ms；64 并发第 8 个请求即开始拒绝，拒绝率 80%——拒绝点正好落在 8+32=40 在飞附近 | 保持 |
| `tool-pool` / `tool-queue` | 8 / 64 | run-queue 的 2 倍 | 全程无 `TOOL_EXECUTION_FAILED`，拒绝全部来自编排池（83965 次）而非工具池——工具池不是瓶颈 | 保持 |
| `max-concurrent-per-session` | 4 | 与 toolQueue 挂钩 | 同会话 4 并发 **0 拒绝**；8 并发出现 `session busy`（6 次 / 10s）；16 并发 21 次 | 保持 |

两条边界外的观察：

- **过载时快速失败，不排队**：64 / 128 并发下 rps 反而升到 3300+，因为被拒请求毫秒内收到 `run.failed`，没有一条挂到 SSE 超时。`client_timeout=0`、`ERROR 行 0`。这正是有界线程池 + AbortPolicy 要的行为。
- **首版脚本的一个假象**：没有客户端超时时，一轮里出现过 1022s 的"最大延迟"——实际是本机短连接风暴耗尽临时端口后 `connect()` 卡住，服务端毫无痕迹。加了 100s 硬超时与 `wall` 实际耗时后不再出现（本轮 `client_timeout=0`、`wall=15.0s`）。量具自己先要可信。

这些数字是 fake planner 下 hub 的容量上限。接真模型后单次规划 5–70s，一个 run 线程会被占住几十秒，8 核心线程 + 32 队列意味着**约 40 个并发对话**是单副本的实际上限——要更多就水平扩（`spark.storage.type=redis`），而不是把队列调大（调大只是把拒绝延后成超时）。
