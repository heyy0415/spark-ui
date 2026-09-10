---
name: deploy-verify
description: 阶段 7 — 部署 / 预览验证。触发场景："部署验证"、"preview"、"构建检查"、"端到端跑一条"。校验前端产物可预览且 console.error 为 0，后端可启动且 health UP，并端到端跑通一条示例 Run。
---

# Skill: deploy-verify

## 何时触发
阶段 6 CI 通过后，进入阶段 7。

## 输入
- 前端 `spark-ui/apps/chat/dist/` 与 `spark-ui/packages/core/dist/`，后端 `spark-rooter/app/target/*.jar`
- `deployment/` 目录（落产出）

## 步骤

全部步骤已脚本化：`pnpm -C .harness run deploy-verify`（= `scripts/deploy-verify.sh`，一次性生成并冻结 `deployment/` 全部产物；要求 8080 / 4173 未被其他进程占用，否则退出码 2）。`deployment/` 由 `scripts/lib/change-dir` 定位：默认取唯一非 DONE / DELIVERED 的 change，**并行多个 change 时必须显式 `SPARK_CHANGE=<change-id>`**。以下为脚本做的事：

```bash
# 1. 后端启动与健康
SPARK_LLM_BASE_URL=... SPARK_LLM_API_KEY=... java -jar spark-rooter/app/target/app.jar &
BE=$!
for i in $(seq 1 30); do curl -sf http://localhost:8080/actuator/health && break; sleep 1; done

# 2. 前端预览
pnpm -C spark-ui run preview &   # = apps/chat 的 vite preview :4173
FE=$!
sleep 3
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:4173/

# 3. 端到端一条 Run（用 examples 里的 intent-request）
curl -N -s -X POST http://localhost:8080/agent/runs \
  -H 'Content-Type: application/json' -H 'X-Tenant-Id: tenant_001' -H 'X-User-Id: user_001' \
  --data @.harness/contracts/examples/intent-request.example.json > deployment/run_events.log
grep -c "event: " deployment/run_events.log     # 事件条数
grep -q "run.completed\|confirmation.required" deployment/run_events.log

# 4. 关停
kill $FE $BE

# 5. 体积报告
ls -la spark-ui/apps/chat/dist/assets/*.js | awk '{print $5, $9}' > deployment/bundle_size.txt
find spark-ui/packages/core/dist -name '*.js' -exec ls -la {} + | awk '{print $5, $9}' >> deployment/bundle_size.txt
ls -la spark-rooter/app/target/*.jar   | awk '{print $5, $9}' >> deployment/bundle_size.txt
```

## 产出
- `deployment/preview_report.md`：前端 bundle 与 baseline 对比、关键页面截图与 console.error 数、后端 health 结果、示例 Run 的 SSE 事件序列（脱敏）。

## 失败回退
- console.error > 0 或 health 非 UP → 回阶段 3
- 示例 Run 未到 `confirmation.required` / `run.completed` → 回阶段 3
- bundle 单 chunk > 250KB gzip 且未记录 → 回阶段 3

## Checklist
- [ ] `pnpm -C spark-ui run build` 与 `node .harness/scripts/mvn.mjs package` 退出码 0
- [ ] `/actuator/health` 返回 `"status":"UP"`
- [ ] 预览首页 console.error == 0
- [ ] 示例 Run SSE 事件序列含 `run.started` 与终态事件
- [ ] bundle 增长 ≤ 10%
