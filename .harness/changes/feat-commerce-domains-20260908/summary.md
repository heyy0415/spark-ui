# Change Summary: feat-commerce-domains-20260908

| 字段 | 值 |
|---|---|
| Change ID | feat-commerce-domains-20260908 |
| 类型 | feat |
| 状态 | DONE |
| 负责人 | Platform Owner Agent |
| 涉及端 | contracts / backed / fronted / harness |
| 起止时间 | 2026-09-08 ~ — |
| Spec | [request_analysis/spec.md](request_analysis/spec.md) |
| Tasks | [request_analysis/tasks.md](request_analysis/tasks.md) |

## 阶段进度表

| # | 阶段 | 状态 | 评审轮次 | 产出 | 时间 |
|---|---|---|---|---|---|
| 1 | 需求分析 | DONE（v3.2） | — | spec.md（8 章）, tasks.md（17 task）；v3.2：用户阶段 3 纠偏，core 组件收敛为 5 个 antd / antd-mobile 官方组件映射（Form / Card / Table / Result / Timeline），删 7 个业务命名 type；用户决策：行内按钮 = 自然语言快捷指令、删除 / 售后 / 退款都确认、JSON 种子 + DDL、单租户单用户 | 2026-09-08 |
| 2 | 需求评审 | IN PROGRESS | 1/3 | v1 RR（7 MUST：规则规划器排序推演与断言不符、实体抽取在拦截之后、路由顺序未定、spi 签名违红线、重校验语义矛盾、订单数三处不一致、task 超时；12 SHOULD）→ v2 RR（5 MUST：空 Form 违契约、⑮ 复用已退款夹具、10007 不在默认列表、自检数不一致、OrderSnapshot 缺 productName；6 SHOULD）→ v3 RR（3 MUST，均文字补丁：⑫ 断言漏改、夹具表缺金额、售后枚举 / 分布未定；5 SHOULD）→ 按评审「最小补丁清单」吸收为 v3.1。3 轮上限；HITL ② 用户「继续」 | 2026-09-08 |
| 3 | 编码实现 | DONE | — | 11 commits（T01–T11）；coding/coding_report_v1.md；rule e2e 107/107、LIVE 109/109、e2e-frontend 33/33、deploy-verify 12/12、harness ci 0、doctor 0 | 2026-09-08 ~ 09 |
| 4 | 编码评审 | DONE | 2/2 | 后端 v1 RR（2 MUST：退款确认屏虚构订单状态、报告含 LLM 字面量；8 SHOULD）→ v2 APPROVED（1 SHOULD N-1 + LOW，均已修）；前端 v1 RR（1 MUST：@ant-design/icons 残留；8 SHOULD）→ v2 APPROVED（4 LOW，已修）。回修记录见 coding_report_v1 尾部 | 2026-09-09 |
| 5 | 代码推送 | DONE | — | 18 个 commit（`e854104`…`dc5aef5` + 本 commit）；工作区干净；无远端（用户未提供） | 2026-09-09 |
| 6 | CI 验证 | DONE | — | 清 dist 后 `run ci` 五段 0 → `ci_result/ci_summary.md`；doctor 0；core dist 40 → 38 KB | 2026-09-09 |
| 7 | 部署验证 | DONE | — | `deploy-verify` 12/12（自检 7/7）→ `deployment/preview_report.md`；e2e rule 109 / LIVE 114 / frontend 35 全绿；不部署、不发包 | 2026-09-09 |
| 8 | 用户确认 | DONE | — | 用户以新需求（核心能力作为依赖引入领域服务、方法级注解、中心平台 + HTTP 工具协议、整体改名 spark-ui / spark-rooter）继续，视为验收通过。不部署、不发包、无远端 | 2026-09-09 |

## 契约变更
- v3.1：修改 `ui-schema.schema.json`（componentType +3；inlineAction / iconName）；新增 5 个示例（已提交 e854104）
- v3.2（T01b）：`componentType` 收敛为 5（Form / Card / Table / Result / Timeline），5 组件 props 全部契约级；示例 8 个（23 总数）；破坏性删除首期 4 个业务 type，无外部消费方

## 经验沉淀
- （留空，每发现一个 Agent 错误后**先**在此追加一行，再决定是否升级到 Skill / Rule）
- **阶段 3 纠偏（v3.2）**：spec v1–v3.1 及三轮评审都没有质疑「core 新增 OrderList / ProductList / LogisticsTimeline 业务组件」这个方向，直到用户在 T08b 前指出「我要求的是 antd / antd-mobile 官方组件，不是自定义组件，且只需覆盖四个领域」。根因：首期就存在的 `OrderCard / RefundConfirmCard / ResultCard / ConfirmationCard` 让 Agent 与评审都默认「业务组件是既定模式」，没有回到用户最初对渲染引擎的定位（白名单 = 官方组件映射）核对。教训：涉及「新增前端组件」的 spec 必须先回答「能否用现有官方组件映射表达」，只有回答「不能」才允许新增 type；已升级为 `check-registry` 门禁（组件文件 import 白名单 + 文件名 == 契约 type）与 `coding-standard.md` 红线（T08a）。
- **阶段 4 评审**：后端 M-1 是真实缺陷——退款确认屏用硬编码 `PAID` 兜底，冻结产物里 SHIPPED 订单显示为 PAID。根因：屏层对「前置输出缺失」用默认值兜底而不是 fail-closed。已升级为红线（`RefundScreens` 缺前置直接抛错；`ScreenBuilder.probeOutputs` 让自检也走真实路径）。另一条 M-2 是编码报告把 grep 命令里的密钥前缀 / 域名 / 模型名当字面量写进了 change 目录——「验证密钥不泄露」的命令本身泄露了模式。已升级为 doctor 形态扫描（不依赖环境变量、rule 模式也跑）。
- **阶段 3 LIVE 复验**：规则模式全绿后 LIVE 首轮 97/107，暴露 5 个问题（网关 URL 进冻结日志、SSE 超时、客户端断开被记 ERROR、模型对无权 / 缺实体的退化行为两条）。教训：① 第三方库默认重试策略的日志内容是「隐形出口」，必须显式接管（已加 LIVE 日志卫生断言）；② 规则模式能确定的前置判断（目标不在候选 / 缺实体）要放在调模型**之前**而不是之后校验模型输出——两种模式行为一致靠共享 `preflight`，不靠 prompt。
- 阶段 2：三轮共 15 条 MUST FIX。第 1 轮 7 条里 3 条是「规则规划器按排序推演的结果与 spec 断言不符」——spec 作者写了「自然结果就是 X」却没推演；第 2 轮 5 条里 3 条是「夹具 / 数字三处不一致」；第 3 轮 3 条全是「上一轮修一处漏两处」。教训：涉及数字、夹具、枚举的东西必须**只写一处**（表格）然后到处引用，而不是散写在 §2 / §4 / §6 / tasks 四处；评审方连续三轮用「推演到具体 toolId 序列 / 具体订单号」抓到问题，request-analysis Skill 应要求 spec 对每条核心场景给出可机械核对的推演。
