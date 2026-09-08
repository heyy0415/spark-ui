# Coding Report v1 — feat-intent-routing-20260908

日期：2026-09-08 · 基线：T01 前 e2e-backend 47/47（首期 + monorepo change 末态）

## 提交
| commit | task | 内容 |
|---|---|---|
| `85705f1` refactor(backed) | T01 | `ToolSearchPort` / `ToolInvokePort`；`ToolRegistryRepository.findAll`；`domains(principal)` 经 `DiscoveryPolicy`；runtime 适配器只依赖接口；check-module-deps 禁 import 对方 `application`；backend-standard §4 / pom 注释 |
| `6ee91a2` feat(runtime) | T02–T07 | 幂等 claim / complete / release + latch 自检 + `replayed` 审计；`IntentClassifier` + `DomainResolver` + `EntityRequirementCheck`；规则规划器实体二选一模板；种子 10004；e2e ①–⑥；doctor L1 检查；前端 T05 / T06 |
| `ccbd81e` docs(harness) | T08 | agent-safety §2、architecture、backend-standard §7、project-structure、三份 README |
| （本 commit） | T09 | selfcheck 补日志行；编码报告；机械评审 |

## 关键决策与偏差
1. **幂等 `replayed` 用 ThreadLocal 标记**：`pipeline` 与审计分处两层，重放 / 等待结果不改契约 `status`（仍 `succeeded`），只在审计行用 `replayed`。`finally` 清理。
2. **`Awaiting` 循环重 claim 用同一 deadline**（spec v2.1）：`ExecutionException` 表示执行者 release，重新 claim；`TimeoutException` → `TIMEOUT`。
3. **selfcheck 日志行**：e2e 的 selfcheck 断言过滤掉 `SelfCheckRunner` 的回显，只认各自检自己打的 `selfcheck: … OK`。首次跑 e2e 新自检未打该行 → 红 → 补一行。这不是行为缺陷，但说明 e2e 契约（每个自检自己打日志）没写进 `SelfCheck` 接口 Javadoc；已在 spec 偏差记录。
4. **`DomainResolver` 是 `@Component`，`RunOrchestrator` 注入它**；`DomainRouter` 仍纯函数留在 domain 包。
5. **`EntityRequirementCheck` 纯静态函数**（无状态），`ENTITY_ARGS` 首期只有 `orderId → order`。
6. 前端 `ActionBarProps` 放 `registry/types.ts`（评审 S5），`index.ts` 导出路径改；verify-pack 17 + 10 不变。

## 验收（真实输出）
```
node .harness/scripts/mvn.mjs -q -B verify                      exit 0
bash .harness/scripts/e2e-backend.sh（规则模式）                 62 passed / 0 failed；③ skipped (rule mode)
bash .harness/scripts/e2e-backend.sh（LIVE：gpt-5.6-sol）         62 passed / 0 failed；③a route by model 1；③b 事件序列 == 主链路；⑥ skipped (live mode)
  route 统计（LIVE 一次运行）：source=rule 4 / source=model 1 / source=none 2
bash .harness/scripts/deploy-verify.sh                           12 passed / 0 failed；selfcheck all OK 5
node .harness/scripts/e2e-frontend.mjs                           21 passed / 0 failed
rm -rf fronted/*/dist && pnpm -C fronted run ci                  exit 0（verify-pack 14 ✓，dist 39 KB）
pnpm -C .harness run ci                                          check-contracts 0 / check-module-deps 0 / fronted 0 / backed 0
pnpm -C .harness run doctor                                      0 errors（新增 L1 一致性检查）
grep 密钥 / 端点 / 模型名                                          全树 0 命中
```

### 新 e2e 用例（规则模式实测值）
| 用例 | 断言 | 结果 |
|---|---|---|
| ① 无 pageContext「退钱」 | 事件恰 3 件；text 含「选择一个订单」不含「退钱」；该 runId 审计 0；summary COMPLETED 无 failureCode | ✓ |
| ② entityType=order + 闲聊 | 无能力路径三件 | ✓ |
| ③（LIVE） | ③a `source=model` 恰 1；③b 主链路发起段 | ✓ |
| ④ 幂等 10004 | 两响应同 refundId；`succeeded` 1、`replayed` 1；status.get 1 条 | ✓ |
| ⑤ | route 日志 ≥ 3 | ✓ |
| ⑥（规则）「订单」无实体 | `run.started tool.selected tool.started tool.completed run.completed`；toolId = order.list.search | ✓ |

### 植入反例（先红后绿并还原）
| 门禁 | 植入 | 结果 |
|---|---|---|
| check-module-deps | runtime import `registry.application.SearchToolsUseCase` | 1 violation |
| check-registry | `PROPS_SCHEMAS` 改回非 freeze 形态 | 仍绿（两种写法都解析） |
| verify-pack (f) | 移走基线 | `baseline missing`；`--write-baseline` 后绿 |
| doctor L1 | CLAUDE.md 改回 `shared/ui/**` | 2 errors |
| e2e selfcheck | 新自检未打日志行 | 首次 e2e 红（真实发现） |

## agent-safety 自查
- §2：分类只在规则未命中时调用；枚举来自 `domains(principal)`（按权限过滤）；越界 → none + WARN（不含原文）；分类 Prompt 不含工具列表；`entityType` 白名单 `{order}` 且只作提示。
- §3 / §5：幂等改占位后 `refund.create` 同 key 真实执行恰 1 次（审计可证）；release 只在未 complete 时生效，自检第三条覆盖。
- §4：前端零行为变化（e2e 21/21）；`PROPS_SCHEMAS` 与两张注册表都冻结。
- §6：SSE 事件顺序零变化；`RouteDecision.source` 只进日志。

## 与 spec 的偏差
- `SelfCheck` 接口 Javadoc 未写「实现必须自己打 `selfcheck: <name> OK`」，e2e 依赖这一约定；建议下一 change 写进 `platform-spi` Javadoc 或改 e2e 只认 Runner 行。
- `EntityRequirementCheck.check` 的 `domain` 参数只用于选文案；spec 描述一致。
