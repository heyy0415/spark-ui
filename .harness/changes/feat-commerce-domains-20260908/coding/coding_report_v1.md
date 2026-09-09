# Coding Report v1 — feat-commerce-domains-20260908

日期：2026-09-08 ~ 09 · 基线：T01 前 e2e-backend 62/62、e2e-frontend 21/21、deploy-verify 12/12（change 3 末态）

## 提交

| commit | task | 内容 |
|---|---|---|
| `e854104` feat(contracts) | T01 | v3.1 形态的 ui-schema 扩展（后被 T01b 覆盖） |
| `c75ad6a` feat(backed) | T02a / T02b | `gen-seed.mjs`（30 单 / 20 品 / 4 售后 / 3 退款）、四领域 DDL / json / README、`check-seed.mjs` 纳入 ci、spi `SeedLoader` / `OrderSnapshot(Provider)` / `ToolNameSink` / `ScreenBuilder` / `ScreenContext` / `ConfirmationRecheck`、check-module-deps「spi 不依赖任何 com.strato」 |
| `5c58938` feat(order) | T03 | `Order` 聚合（items / address / logistics / `LogisticsStatus`）、`DeletionPolicy`、`SeededOrderRepository`（加载校验金额和与 seq）、`order.logistics.get` / `order.delete`、list / detail 1.1.0、`OrderSnapshotAdapter`、权限表 |
| `688021b` feat(backed) | T04 / T05 | product-service、aftersale-service（`AftersalePolicy`、`aftersale.list.get` 带 order 摘要）、refund-service 改 `SnapshotOrderLookup` + `SeededRefundRepository`、check-module-deps「domains 互不 import」 |
| `f578c71` docs(harness) | — | **用户阶段 3 纠偏 → spec / tasks v3.2**（组件白名单收敛为 5 个官方组件映射） |
| `f91895d` feat(contracts) | T01b | `componentType` 收敛为 `Form / Card / Table / Result / Timeline`，五组件 props 契约级 if/then；8 个 ui-schema 示例 + 修 3 个内嵌示例；contracts.md 红线与变更记录 |
| `1ef7b12` feat(runtime) | T06a / T06b | `ScreenRegistry` / `FallbackScreenBuilder` / `RecheckRegistry`、`executeConfirmed` 通用化、`RunFailure.userText`、`ToolDisplayNames` 改 `ToolNameSink` Bean、四领域 `*Screens` + 三个 `*Recheck`、spi `UiNodes`、`ConfirmationCoverageSelfCheck` / `InlineActionSelfCheck` |
| `d0642b5` feat(runtime) | T07a / T07b | `EntityExtractor`、四领域路由表、`IntentVerbs`（动词 → 目标、目标 → 前置）、`RuleBasedLlmClient` 重写、`ToolSelectionValidator` 前置 / 实体校验、`MissingEntity` 信号、`PlanSelfCheck` 5 条消息 |
| `ba179d1` feat(fronted) | T08a / T08b / T09 | core 删 8 个业务组件文件，`Result` / `Timeline` / `Table` 行内指令列 / `Card.tone`；`check-registry` 文件名 + import 白名单红线；`onIntent`；chat 连续对话 / 示例 chip；e2e-frontend 步骤 6–7 |
| `d4caa3b` test(harness) | T10a / T10b | e2e-backend ⑦–⑯ + ⑬'、既有断言同步、`STRATO_PORT`；wiki / rules / README 同步 |
| （本 commit） | T11 | LIVE 复验暴露的 3 项修复（见「LIVE 模式发现」）、`deploy-verify` `STRATO_PORT` / `STRATO_BACKEND`、产物冻结、本报告 |

## 关键决策与偏差

1. **组件白名单收敛（v3.2）**：spec v1–v3.1 与三轮评审都沿用首期「业务命名组件」模式，用户在 T08b 前纠偏。收敛后 core 内没有任何业务组件；`check-registry` 增加两条机械规则（文件名 == 契约 type ∪ {ActionBar}；import 只允许 antd / antd-mobile / react / 本包 registry / schema），植入 `OrderCard.tsx` 与 `lodash` import 都红。首期 4 个 type 删除无 deprecation（无外部消费方）。
2. **屏与策略出 runtime**：`RunOrchestrator` 不再含 `refund*` 词汇；确认屏两遍生成（占位令牌读 action id / Form 字段 → 签发 → 正式屏），不同屏可有不同 submit id（`confirm-refund` / `confirm-delete` / `confirm-aftersale`）。`RefundRecheck` 金额比对改为 `components[id=refund-summary].props.items[label=退款金额]`，首期 M3 语义不变。
3. **规划器两种模式共用 `preflight`**：动词命中 → 目标不在候选 → `TOOL_SELECTION_INVALID`；目标缺必填实体 → `MissingEntity`。真模型只在两条都通过后才被调用，使 ⑭ / ⑯ 在 LIVE 模式与规则模式行为一致（LIVE 首轮实测：模型对无权用户的「删除订单 10005」退化为只调 `order.detail.get`，对无号码的「删除订单」退化为带非法 status 的 `order.list.search`——都被 preflight 拦在模型之前）。
4. **spi `UiNodes`**：四领域屏共用的树构造小工具（无校验）；契约校验仍只在 runtime `ScreenRegistry` 一处。
5. **`Table.rows[].cells ⊆ columns`** 跨字段约束契约做不到，runtime 在契约校验后追加检查（spec §2.3）——本轮未单独实现为独立检查，靠前端渲染缺键显示空 + 后端投影只写 columns 声明的键；记为下一 change 可选加固。
6. **示例数 26 而非 spec 写的 23**：spec 误把「ui-schema 示例 8」当总数，实际 18 个非 ui-schema + 8 = 26，已回改 spec / tasks。
7. **`thumbnail`** 保留为工具输出与种子字段，不渲染，不引入 `@ant-design/icons`（peer 仍在 catalog，本 change 不动 peer 列表以免牵连 verify-pack (b)）。

## 验收（真实输出）

```
node .harness/scripts/mvn.mjs -q -B verify                       exit 0（11 模块）
STRATO_PORT=8091 bash .harness/scripts/e2e-backend.sh（规则）      107 passed / 0 failed；⑥ 用例形态变为含 ui.replace
… （LIVE，OpenAI 兼容端点；地址 / 密钥 / 模型名只在 shell）        109 passed / 0 failed（+2 条 LIVE 日志卫生断言）；⑥ skipped
  route 统计（LIVE）：source=rule 14 / model 1 / none 2；planner=spring-ai 12 次；规划耗时 9.7–39.0s；LLM 重试 0
STRATO_PORT=8091 bash .harness/scripts/deploy-verify.sh            12 passed / 0 failed；selfcheck all OK 7
STRATO_FRONT_BASE=http://localhost:5199 node e2e-frontend.mjs      33 passed / 0 failed（原 21 + 步骤 6 四项 + 步骤 7 八项）
rm -rf fronted/*/dist && pnpm -C fronted run ci                    exit 0；check-registry 5 + 两条新规则；verify-pack 17 运行时 / 19 类型；dist 38 KB（基线 40 → 38 重写）
pnpm -C .harness run ci                                            check-contracts（9 schema / 26 example）/ check-seed（7 项）/ check-module-deps / fronted / backed 全 0
pnpm -C .harness run doctor                                        0 errors 0 warnings
grep -r <LLM 网关主机名|密钥前缀|模型名>（取自 STRATO_LLM_* 环境变量，字面量不落盘）全树   0 命中；deployment/ 冻结产物 0 命中
```

### 新 e2e 用例（规则模式实测）

| 用例 | 断言 | 结果 |
|---|---|---|
| ⑦ 看看我的订单 | `[Table]` id orders；rows 20 / total 30 / rows[0] 10030；10029 行含「删除订单」 | ✓ |
| ⑧ 查看订单 10002 的物流 | `order.logistics.get`；`[Card, Timeline]`；items ≥ 3；Card 含运单号 | ✓ |
| ⑨ 有什么商品 | `product.list.search`；rows 20 / total 20 | ✓ |
| ⑩ 查看商品 P-1003 的详情 | `[Card]` title 无线耳机 Pro | ✓ |
| ⑪ 订单 10002 申请售后 | `aftersale.list.get` → `[Card, Form]` submit confirm-aftersale → 确认 → `[Result]`；list 1 条 | ✓ |
| ⑫ 删除订单 10005 | `[Card]` 末项 tone danger，无 Form，`{}` 提交 → `[Result]`；total 29 | ✓ |
| ⑬ 删除订单 10001 | 确认 → `CONFIRMATION_REJECTED` +「订单状态已变化，本次操作未执行」；runId 内 order.delete 审计 0 | ✓ |
| ⑬' Gateway 直调 order.delete 10001 | 502 INTERNAL_ERROR；审计 failed；10001 仍在列表 | ✓ |
| ⑭ user_002 删除订单 10005 | `run.started run.failed` TOOL_SELECTION_INVALID | ✓（LIVE 靠 preflight） |
| ⑮ 订单 10006 退款（无 pageContext） | 与 §6.2.8 / §6.2.9 同序列；`[Card, Card, Form]` → `[Result]`；refunds 1 | ✓ |
| ⑯ 删除订单（无号码） | `run.started message.delta run.completed`，text 含「选择一个订单」 | ✓（LIVE 靠 preflight） |

### 植入反例（先红后绿并还原）

| 门禁 | 植入 | 结果 |
|---|---|---|
| check-seed | 外键悬空 / 10004 金额 60.00 / 10030 改 PAID / DDL 行尾注释 / 同单两条进行中售后 | 各红（4 / 2 / 2 / 1 / 1 条） |
| check-module-deps | spi pom 加 contracts-java；aftersale import `com.strato.domain.order`；refund pom 依赖 order-service | 各 1 violation |
| check-contracts | `type:"OrderCard"`；`Card.items[0].tone:"red"`；`Table.rows[0]` 缺 id；`intent:"http://x"` | 各红 |
| check-registry | `components/desktop/OrderCard.tsx`；`import lodash` | 各红（业务命名 / import 白名单） |
| 启动自检 | `ConfirmationCoverageSelfCheck` 在 T06b 之前启动 → 「no confirmation ScreenBuilder for order.delete」启动失败 | 真实发现（fail-closed 生效） |

## LIVE 模式发现（首轮 97/107 → 修复后 109/109）

| 现象 | 根因 | 修复 |
|---|---|---|
| 冻结日志出现 4 次 LLM 网关 URL | Spring AI 默认 `RetryTemplate` 监听器把 `ResourceAccessException` 全文（含 URL）打 WARN；上游 EOF 抖动触发 | 自建 `RetryTemplate`（2 次、500ms、监听器只记异常类名）；`SpringAiLlmClient` 把传输异常包成不带 cause 的 `INTERNAL_ERROR`；e2e LIVE 增「host / key 不在日志」两条断言 |
| ⑨ / ⑪ 60s 内无事件 | 单次规划 39s + 重试退避超过 SSE_T | LIVE `SSE_T` 60 → 90；退避缩短后重试 0 次 |
| `ERROR runtime_unhandled AsyncRequestNotUsableException` | 客户端超时断开后容器再写响应 | `RuntimeExceptionHandler` 对该异常只记 debug |
| ⑭ user_002 → `TOOL_EXECUTION_FAILED` | 模型看到候选无 `order.delete`，退化成只调 `order.detail.get`（10005 对 user_002 也 HANDLER_ERROR） | `preflight`：动词目标不在候选 → 直接 TOOL_SELECTION_INVALID |
| ⑯ → `INTERNAL_ERROR` / `TOOL_EXECUTION_FAILED` | 首轮 `MissingEntity` 被当传输错误包装；次轮模型发明 `order.list.search {status:"DELETED"}` | `MissingEntity` 直接透传；`preflight` 对动词目标做必填实体检查 |

## agent-safety 自查

- §2：路由顺序与关键词表写死；实体只从正则 / 白名单格式的页面实体取，日志只记类型与 ID；模型只在候选内选工具，并在 `preflight` 之后才被调用。
- §3：三个需确认工具都有领域 `ConfirmationRecheck` 与确认屏，`ConfirmationCoverageSelfCheck` 启动断言；`trustedArgs` 与 Form 字段互斥；策略拒绝与令牌拒绝文案区分、内部原因只进日志；⑬ / ⑬' 双保险各自验证。
- §4：前端只渲染 5 个官方组件映射；行内指令 `intent` 只回调纯文本，宿主原样作为新消息发送（e2e 步骤 7 证明走完整链路）；`check-registry` 守文件名与 import。
- §5：Gateway 侧 `order.delete` / `aftersale.create` 的 handler 自带策略；权限表 user_002 四项只读。
- 日志卫生：LIVE 冻结产物 0 处网关地址 / 密钥。

## 与 spec 的偏差

- §2.3「`Table.cells ⊆ columns` 由 runtime 追加检查」未作为独立检查实现（见决策 5）。
- §2.6 e2e ⑥ 断言形态由「无 ui.replace」改为「含 ui.replace」：列表工具现在有结果屏。
- 示例总数 23 → 26（spec 计数错误，已回改）。
- 为让验收脚本在 8080 / 5173 被 IDE 实例占用时也能跑，`e2e-backend.sh` / `deploy-verify.sh` 增 `STRATO_PORT`，vite 代理增 `STRATO_BACKEND`，`e2e-frontend.mjs` 增 `STRATO_FRONT_BASE`（默认值全部不变）。

---

# 阶段 4 回修记录 v1（响应 `coding/review/code_review_backend_v1.md`：2 MUST / 8 SHOULD；`code_review_frontend_v1.md`：1 MUST / 8 SHOULD）

## 后端

| # | 意见 | 处理 | 证据 |
|---|---|---|---|
| M-1 | 退款确认屏订单 Card 读不存在的 `order.detail.get`，回退到硬编码 `PAID`（冻结产物 run2 显示 10002 为 PAID，实为 SHIPPED） | `refund.eligibility.check` 升 **1.3.0**，输出增 `orderStatus / productName / quantity / orderAmount`（`RefundService.eligibility()` 返回结果 + 快照）；`RefundScreens.confirmation` 只用该输出，缺字段不显示、**不填任何业务默认值**，前置输出缺失直接抛错（fail-closed）；spi `ScreenBuilder.probeOutputs` 供自检探测；e2e 新增断言「confirm screen shows real order status == SHIPPED」 | 冻结 run2 现为 `状态: SHIPPED`；rule 108/108 |
| M-2 | coding_report 含 LLM 主机名 / 密钥前缀 / 模型名字面量 | 报告改写为环境变量名；e2e LIVE 增三条断言「host / key / model 不在 change 目录任何文件」；顺带发现 `planner=spring-ai:<model>` 与 `LLM enabled model=` 两处日志泄露模型名 → `name()` 改 `spring-ai`，启动日志只记 completionsPath | 全树 grep 0；LIVE 113/113 |
| S-1 | 覆盖自检用空节点探 `trustedArgs`，refund 对空输出返回空 map，互斥断言空转 | spi `ConfirmationRecheck.trustedArgKeys()` 静态声明；自检改用它 | `RefundRecheck` 返回 `{amount}` |
| S-2 | 契约声称 runtime 校验 `Table.cells ⊆ columns`，无代码 | `ScreenRegistry.assertTableCells` 在契约校验后执行 | — |
| S-3 | 动词表顺序与路由顺序打架（「退货退款」→ refund 域却选 aftersale.create）；`refund.detail.get` 不存在 | `IntentVerbs.target` 先匹配路由领域自己的动词再退全表；`DETAIL_TOOL` 表（refund / aftersale 落到查询工具），纳入 `referencedToolIds` 启动校验 | PlanSelfCheck 5 条不变 |
| S-4 | `订单\s*(\d{5})` 无尾边界，「订单 100021」抓成 10002 | 两个正则加 `(?!\d)` | — |
| S-5 | 校验器不比对实体参数值，LIVE 模型可换 orderId | `validate(..., entities)`：实体类参数值必须等于已识别实体 | LIVE 113/113 |
| S-6 | 脚本无条件 `pkill app.jar` 会杀 IDE 实例 | 只 kill 带 `--server.port=$PORT` 的自起实例 | 验收后 8080 实例仍在 |
| S-7 | 模型输出解析失败被当传输错误直接 INTERNAL_ERROR | 只有 `RestClientException` 走传输错误；其它 RuntimeException 视为输出不合规、再试一次 | — |
| S-8 | `stepOutputs` 只在 confirm 清理 | `complete()` / `fail()` 都清理 | — |

## 前端

| # | 意见 | 处理 |
|---|---|---|
| F-01 | `@ant-design/icons` 仍在 peer / dev / catalog / verify-pack / README | 全部移除；PEERS 5 |
| F-02 | check-registry import 白名单可被 `export…from` / 动态 import / require 绕过；子目录崩溃 | 四种引用形态都扫；子目录报错。植入 `export * from 'lodash'` / `await import('lodash')` / 子目录 → 各红 |
| F-03 | 新一轮开始保留等待确认的旧屏，Form 仍可填 | `beginTurn` 在上一 phase 为 waiting_confirmation 时清空 ui |
| F-04 | 行内按钮 key 用 intent，重复即 console.error | 索引 key |
| F-05 | Zod `cells` 无 maxProperties 16 | `.refine` |
| F-06 | e2e 步骤 6 只断言 `rendered > 0` | 逐示例组件数 == 期望 + 无 UnknownComponent（35/35） |
| F-07 | e2e 残留 `['OrderCard','RefundConfirmCard','Form'].length` | 改 `['Card','Card','Form']` |
| F-08 / F-09 | backed/README 表格断裂；verify-pack / project-structure 计数与目录说明过期 | 修正 |
| F-10 / F-11 / F-12 | `send` 未 memo；chips 容器 aria；行内按钮不随 busy 禁用 | `useCallback`；`role="group"`；无 `onIntent` 时 disabled |

## 回修后复验

```
mvnw -q verify                                  exit 0
e2e-backend 规则（STRATO_PORT=8091）              108 passed / 0 failed（+1 M-1 回归断言）
e2e-backend LIVE                                 113 passed / 0 failed（+3 change 目录卫生断言）；planner=spring-ai 12；unparseable / retry 0
deploy-verify（STRATO_PORT=8091）                 12 passed / 0 failed
e2e-frontend（STRATO_FRONT_BASE=5199）            35 passed / 0 failed
pnpm -C fronted run ci（clean dist）              exit 0；PEERS 5
pnpm -C .harness run ci                          exit 0；doctor 0
grep 全树 + .harness/changes（主机名 / 密钥 / 模型名）  0
```

---

# 阶段 4 回修记录 v2（响应 `code_review_backend_v2.md`：APPROVED，1 SHOULD + 7 LOW/INFO；`code_review_frontend_v2.md`：APPROVED，4 LOW）

| # | 意见 | 处理 |
|---|---|---|
| 后端 N-1 (SHOULD) | Spring AI 的 `TransientAiException` / `NonTransientAiException` 不继承 `RestClientException`，网关 4xx/5xx 落进「输出不可解析」分支 | 传输分支同时捕获这两个类型 → `INTERNAL_ERROR`，不再二次重试 |
| N-2 | Javadoc 仍写 1.2.0 | 改 1.3.0 |
| N-3 | S-3 / S-5 无自检覆盖 | `PlanSelfCheck` 增「订单 10002 退货退款 → refund 三步」（6 条消息）与「实体参数换号 → 拒绝」（`foreign entity arg rejected OK`）；e2e 断言同步 |
| N-4 | 四参 `validate` 重载语义等于「禁止一切实体参数」 | 删除重载，调用点显式传 entities |
| N-5 | change 目录密钥卫生门禁只在 LIVE 生效 | `harness-doctor` 新增形态扫描（`sk-…` / 内部网关域名 / 模型名形态，模式拼接构造），rule 模式与 doctor 都生效；植入模型名字面量 → doctor 红 |
| N-6 | `pkill vite preview` 无条件 | 只 kill 带 `--port 4173 --strictPort` 的自起预览 |
| N-7 | 运行期未断言 `trustedArgs` 键 ⊆ `trustedArgKeys` | `executeConfirmed` 增断言，违反 INTERNAL_ERROR |
| N-9 | `<domain>.detail.get` 占位对 refund / aftersale 解析到不存在的工具 | 占位经 `DETAIL_TOOL` 解析 |
| 前端 N-01 | `useCallback` 依赖整个 `useMutation` 返回对象 | 依赖 `mutate` |
| 前端 N-02 | 取消 / 确认后失败的旧确认屏残留 | 新一轮开始时凡含 submit 动作的旧屏一律撤掉 |
| 前端 N-03 / N-04 | README、verify-pack 注释陈旧 | 修正 |

## 回修后复验

```
mvnw -q verify                                  exit 0
e2e-backend 规则（8091）                          109 passed / 0 failed（+1 foreign entity 自检断言）
e2e-backend LIVE                                 114 passed / 0 failed
deploy-verify（8091）                             12 passed / 0 failed
e2e-frontend（5199）                              35 passed / 0 failed
pnpm -C .harness run ci（clean dist）             exit 0；doctor 0（含新 changes/** 密钥形态扫描）
grep 全树 + .harness/changes（主机名 / 密钥 / 模型名）  0
```
