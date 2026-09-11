# Deploy Verify — test-backend-unit-tests-and-fixes-20260911

`SPARK_PORT=8091 bash .harness/scripts/deploy-verify.sh`（无 profile 启动，依赖 `spark.runtime.demo-session-resolver=true`），完整输出见 `deploy-verify.log`。

| 检查 | 结果 |
|---|---|
| 后端 `/actuator/health` | UP |
| 启动自检 | 9 项 OK（与改动前一致） |
| `SessionIdResolver 为 demo 实现` WARN | 出现 1 次（开关放行，符合 spec §2.2.4） |
| `BOOT FAILED` / `Application run failed` | 0 |
| vite preview `/`、代理 `/actuator/health` | 200 / 200 |
| 预览页面 console.error | 0；`#agent-input` 渲染 |
| 体积 | `bundle_size.txt`：core dist 13,952 B（基线 47 KB 内），chat 最大 chunk 353 KB，host-demo.jar 38.5 MB；未恶化 |
| 端到端一条 Run（事件序列 / 确认 / run-summary / 日志） | **4 项失败**：`run.started → run.failed`。原因：本机未配置 `SPARK_LLM_*`，`UnavailablePlanner` 对所有请求返回失败。与本 change 无关，改动前同环境结果相同（见 `ci_result/e2e_baseline.txt`，e2e 基线 60/101 亦因此）。 |

结论：本 change 涉及的部署面（启动、自检、fail-fast 开关、前端构建与预览）全部通过；模型依赖的主链路需在配置了 OpenAI 兼容端点的环境复验，属环境条件而非代码缺陷。
