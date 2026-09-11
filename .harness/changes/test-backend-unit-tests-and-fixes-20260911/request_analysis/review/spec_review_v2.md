---
mode: plan
verdict: APPROVED
reviewer: expert-reviewer（plan 模式）
round: 2/3
inputs:
  - request_analysis/spec.md（v1 评审后修订版）
  - request_analysis/tasks.md（v1 评审后修订版）
rules:
  - .harness/rules/project-structure.md
  - .harness/rules/contracts.md
  - .harness/rules/backend-standard.md
  - .harness/rules/dev-workflow.md（阶段 1 门禁）
previous: spec_review_v1.md（REVISION REQUIRED：M-1、M-2）
---

# Spec Review v2 — test-backend-unit-tests-and-fixes-20260911

> 独立性原则：未读 coding 目录；重读修订后的 spec / tasks，并再次核对源码事实（`RunOrchestrator.argsDigest` 可见性、`PromptBuilder.sanitize / MAX_TEXT`、`SchemaValidator.validateWithInlineSchema / bind`、`ContractsSelfCheck.run()`、`ManifestDeriver` 存在性、change 目录内是否记录 e2e 基线）。

## 0. v1 意见闭环核对

| v1 编号 | 分级 | 修订位置 | 结果 |
|---|---|---|---|
| M-1 | MUST FIX | spec §2.2.4 / §4 / §6.7；tasks T08 验收 | **已闭环**。三种启动路径（default / e2e / e2e-ttl）覆盖面与 WARN 归属（`backend.log` 不含、`backend-ttl.log` 恰 1 次）与源码事实一致 |
| M-2 | MUST FIX | tasks T07a / T07b | **已闭环**。脚手架 + 非确认路径 ①②③④⑨⑩⑪ / 确认路径 ⑤⑥⑦⑧⑫，各 ≤ 0.5 天 |
| S-1 | SHOULD | spec §6.1 ≥85 / ≥17；tasks 下限上调 | 部分闭环，仍差 2（见本轮 S-1） |
| S-2 | SHOULD | T02 验收改 `test-compile` + `test` | 已闭环 |
| S-3 | SHOULD | T06a / T06b | 已闭环 |
| S-4 | SHOULD | `check-contracts.mjs` 末尾调用 `sync-contracts --check`；`contracts.md` §1 / §6 | 已闭环，且比 v1 建议 (a) 更干净（阶段 3 门禁与 `contracts.md` 变更顺序自然覆盖） |
| S-5 | SHOULD | `PromptBuilderTest`、场景 ⑪⑫、§3 非目标补 web-mvc / `ManifestDeriver` / `LlmPlanner` | 已闭环；核对 `PromptBuilder.sanitize` 为包私有 static、`MAX_TEXT = 500`、转义花括号 / 反引号 / 换行，与 spec 描述一致 |
| S-6 | SHOULD | §6.4 grep 扩展 + 两处整句重写；删掉不存在的 `spark-rooter/src` | 已闭环 |
| S-7 | SHOULD | 去掉「规则模式」，改为「与基线相同模型配置」 | 已闭环 |
| S-8 | SHOULD | 整体加载 `SparkRooterAutoConfiguration` + `withBean` 兜底 + 记入经验沉淀 | 已闭环 |
| L-1 ~ L-7 | LOW | §2.1 / §7 / §2.2.3 / §6.6 / T05 / ⑩ 可见性 | 均已吸收；核对 `argsDigest` 确为包私有 static（`RunOrchestrator.java:784`），⑩ 可直接调用 |
| I-1 / I-2 | INFO | tasks 开头 `harness` 端定义；§7 root cause 断言 | 已吸收 |

## 1. 阶段 1 门禁与 plan 模式必查项

| 必查项 | 结果 | 备注 |
|---|---|---|
| spec 含 背景 / 范围 / 非目标 / 验收标准 / 风险 5 章节 | 通过 | |
| tasks 每个 task 含 目标 / 输入 / 输出 / 验收 / 依赖 5 项 | 通过 | T01–T11（含 a/b 拆分）共 13 个 task |
| 「非目标」存在且非空 | 通过 | §3 七条，新增 web-mvc / 扫描器 / `LlmPlanner` 边界 |
| 每条验收可被命令 / 断言校验 | 通过 | §6 八条全部为命令 + 退出码 / grep / 计数；§6.7 的 WARN 归属已与事实一致 |
| 风险 ≥1 失败模式 + 缓解 | 通过 | §7 十一条 |
| 每个 task 标注所属端 | 通过 | `harness` 端已在 tasks 开头定义 |
| contracts task 先于依赖者 | 通过 | T01 无依赖排首位；T03 / T04 / T06a 依赖 T01 副本 |
| 跨端结构列出契约文件 | 通过 | §5：Schema NONE + 副本目录 |
| 每个 task ≤ 0.5 天 | 通过 | T07a 含 7 个 Fake / Fixture + 7 场景，是最重的一块，但已在 0.5 天量级；T06a 夹具 + 两个测试类可接受 |

0 条 MUST FIX → **APPROVED**。以下 SHOULD / LOW 建议在编码阶段顺手处理，不阻塞进入 HITL 确认点 ②。

---

## 2. 意见清单

### SHOULD

**S-1**
- 位置：spec §6 验收第 1 条「`tests="N"` 之和 ≥ 85」 vs tasks 各 task 验收下限。
- 问题：T03 ≥6 + T04 ≥10 + T05 ≥18 + T06a ≥16 + T06b ≥18 + T07a ≥7 + T07b ≥5 + T08 3 = **83 < 85**。按 tasks 最低要求交付仍会让 spec 验收失败（v1 S-1 同类问题，差值从 5 缩到 2）。
- 建议：spec 改为 ≥ 83；或 T07a 下限改 ≥ 8（⑩ `argsDigest` 天然可拆「键序无关」「确定性」两个用例）、T07b 改 ≥ 6。
- 分级：SHOULD

**S-2**
- 位置：spec §6 验收第 7 条「基线 60 通过 / 101 失败」；tasks T08 验收「通过数 ≥ 60（基线，无模型）」。
- 问题：该基线数字在 change 目录内没有任何落盘证据（`deployment/` 只有上一轮 e2e 的 SSE 日志，`summary.md` / `ci_result/` 未记录），阶段 4 / 7 评审无法复核「与基线一致」。
- 建议：T08 输出增加一项：把修改前的一次完整 e2e 输出（含 `selfcheck:` 各行与末尾 pass/fail 计数）保存为 `ci_result/e2e_baseline.txt`，验收时 diff `selfcheck:` 行。
- 分级：SHOULD

### LOW

**L-1**
- 位置：tasks T08 验收「`mvn.mjs -q -B install` 退出 0 后 `SPARK_PORT=8091 bash .harness/scripts/e2e-backend.sh`」。
- 问题：`e2e-backend.sh` 运行的是 `examples/host-demo/target/host-demo.jar`，该 jar 由 host-demo 独立工程 `mvn -o package` 生成，根 `install` 不会重打它；漏掉这一步会用旧 jar 验证，`application.yml` 开关改动不生效却显示通过。
- 建议：验收步骤写全：`mvn.mjs -q -B install` → `cd spark-rooter/examples/host-demo && mvn -q -B -o package -DskipTests`（或直接跑 `pnpm -C .harness run ci`）→ e2e。
- 分级：LOW

**L-2**
- 位置：spec §2.1 表 contracts 行「9 个契约可加载；副本内全部示例通过对应契约」与 §6.6「`.harness/contracts` 临时改名后仍能 `package`」。
- 问题：两者已一致，但 `SchemaValidatorTest` 若继续依赖类路径副本，改名真源后跑 `test`（而非 `-DskipTests package`）也应通过；spec 只验证了 `package -DskipTests`。
- 建议：§6.6 命令去掉 `-DskipTests`（把「模块可独立构建」加强为「模块可独立构建并通过自身测试」），成本为零。
- 分级：LOW

**L-3**
- 位置：spec §4 核心场景第 2 条「契约校验 → 契约副本一致性 → 模块依赖 → …」。
- 问题：§2.2.3 已改为 `check-contracts.mjs` 内部调用、`ci.mjs` 不单列步骤，§4 仍把「契约副本一致性」写成独立一环，易让阶段 6 `ci_summary.md` 找不到对应步骤名。
- 建议：改为「契约校验（含副本一致性）→ 模块依赖 → …」。
- 分级：LOW

**L-4**
- 位置：tasks T07b 输出「可拆为 `RunOrchestratorConfirmTest`」。
- 问题：若拆为独立类，spec §6.1 报告数与 §2.1 表「`RunOrchestratorTest`（T07a + T07b）」措辞需同步；不拆则无事。
- 建议：编码时二选一并在 coding_report 标注；不需要改 spec。
- 分级：LOW

### INFO

**I-1**
- 位置：spec §3 非目标「`ManifestDeriver` 单测不在本 change」。
- 核对：`spark-rooter-spring-boot-starter/src/main/java/com/sparkrooter/starter/tool/ManifestDeriver.java` 存在，名称正确。
- 分级：INFO

**I-2**
- 位置：spec §2.1 contracts 行「`validateWithInlineSchema` 对不合规数据返回非空」。
- 核对：`SchemaValidator.validateWithInlineSchema(JsonNode, JsonNode)` 与 `bind(String, String, JsonNode, Class)` 均存在；`ContractsSelfCheck.run()` 为 public。
- 分级：INFO

---

## 3. 结论

- **verdict: APPROVED**
- MUST FIX：无。
- 建议进入 HITL 确认点 ② 前顺手改掉 S-1（阈值 83 vs 85 的 2 个用例差）与 S-2（e2e 基线落盘），避免阶段 4 门禁按字面执行时出现「tasks 全达标、spec 却失败」的误判。
