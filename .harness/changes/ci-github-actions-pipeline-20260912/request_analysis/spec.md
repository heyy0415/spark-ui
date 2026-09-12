# Spec: ci-github-actions-pipeline-20260912

> 改造清单第 4 项：配置 GitHub Actions，让门禁在 PR 上自动跑。同时解掉上一 change 的两笔债务（shellcheck、日志断言校验），并把脚本里的本机路径假设改掉——这是 CI 能跑 e2e 的前置条件。

## 1. 背景

- 仓库无 `.github/workflows`，八阶段的阶段 6 在此前四个 change 里一律 SKIP 或 DEFERRED。开源项目没有 CI，别人提的 PR 无法自动验证，这是协作硬前提。
- 前置已就绪：上一 change（`test-e2e-playwright-fake-llm`）让三套验收在无模型、无本机 Chrome 的机器上全绿（e2e-backend 161/161、e2e-frontend 7/7、deploy-verify 12/12）。在此之前 CI 跑 e2e 只能得到大批「因无模型而失败」的红。
- **阻塞点**：三个 shell 脚本硬编码 `$HOME/.jenv/versions/21/bin/java`（`e2e-backend.sh:8`、`e2e-frontend.sh:11`、`deploy-verify.sh:8`），`mvn.mjs:29-32` 的 JDK 候选路径也全是本机路径。CI 上 JDK 由 `actions/setup-java` 装在别处，脚本必然找不到。
- **待清债务**（上一 change 的 summary「遗留债务」）：
  - S-2 shell 脚本未经 shellcheck。编码期真踩到一个 `bash -n` 查不出的问题（`$FRONT_PORT）` 全角括号被吞进变量名，运行时 `unbound variable`）。
  - 「e2e 断言依赖的日志片段应有脚本校验其在 `src/main` 中存在」——连续三个 change 无人发现 7 条失实断言，因为它们混在大批模型相关失败里。

## 2. 范围（In Scope）

### 2.1 解除本机路径假设（CI 前置）

- `mvn.mjs`：候选列表前置 `process.env.JAVA_HOME`（若已是 21 则直接用），保留现有本机路径作为回落。**行为对本机开发者不变**。
- 三个 shell 脚本：`JAVA` 的取值改为 `${JAVA_HOME:+$JAVA_HOME/bin/java}` 优先，回落到现有 jenv 路径，最后回落 `$(command -v java)`；启动子进程时传当前 `JAVA_HOME` 而非写死路径。抽成 `lib/java-home.sh` 供三者 source，避免三份副本漂移。
- 新增 `lib/java-home.sh` 后，`harness-doctor` 的 required 列表同步。

### 2.2 GitHub Actions 工作流

`.github/workflows/ci.yml`，触发条件 `pull_request` + `push`（分支 `main`）。三个 job：

| job | 内容 | 预估耗时 |
|---|---|---|
| `gates` | `pnpm -C .harness run ci`（= check-rename / check-contracts / check-module-deps / check-seed / spark-ui ci（含单测）/ spark-rooter install（含单测 + spotless）/ host-demo 离线打包）+ `pnpm -C .harness run doctor` + shellcheck | 最长，含两端构建与单测 |
| `e2e` | 依赖 `gates` 的构建产物；跑 `e2e-backend.sh` 与 `e2e-frontend.sh` | 后端约 10 分钟（脚本含两次启动与 TTL 等待），前端约 1 分钟 |
| `deploy-verify` | 依赖 `gates` 产物；跑 `deploy-verify.sh` | 约 2 分钟 |

- **不设 `SPARK_LLM_*`**：CI 一律走 fake 规划器，`deploy-verify` 会自动启用 e2e profile 并打印 `planner=fake-e2e`。CI 验的是校验边界、编排、网关、领域与前端渲染；模型理解质量不在 CI 覆盖范围（需真 key，属发布前人工验证）。
- **必须显式 `SPARK_CHANGE`**：`change-dir.mjs:38` 要求恰有一个非 DONE 的 change，CI 上通常有多个。工作流用一个固定的 `SPARK_CHANGE=ci-run`（对应一个占位 change 目录）或从分支名推导——**取前者**，新增 `.harness/changes/ci-run/summary.md`（状态 `DONE` 以免干扰本机 `change-dir` 判定）专供 CI 落产物，`.gitignore` 忽略其 `deployment/`。
- 缓存：`pnpm store`（按 `pnpm-lock.yaml` 哈希）、`~/.m2`（按 `pom.xml` 哈希）、`~/.cache/ms-playwright`（按 `@playwright/test` 版本）。
- `actions/setup-java@v4` distribution `temurin` java-version `21`；`pnpm/action-setup` + `actions/setup-node@v4` node 20。
- 产物：e2e 失败时上传 `deployment/`（Playwright HTML 报告、trace、backend.log）作为 artifact，保留 7 天。

### 2.3 shellcheck 入门禁（清 S-2）

- `gates` job 加一步 `shellcheck .harness/scripts/*.sh .harness/scripts/lib/*.sh`（**不加 `-S warning`**，见下）。
- 本地对等入口：`ci.mjs` 新增 `check-shell` 步骤，`shellcheck` 不存在时**跳过并打印提示**（不因本机未装而红，但 CI 上必装）。
- **现存告警实测只有 6 条**（本机 shellcheck 0.11.0，阶段 1 已实跑）：

  | SC 码 | 数量 | 处置 |
  |---|---|---|
  | SC2012（用 `ls` 而非 `find`） | 3 | 都在体积统计行，`ls -la ... \| awk` 的输出格式是刻意的；加 `disable` + 原因 |
  | SC2329（函数从未被调用） | 1 | **误报**：`e2e-frontend.sh:17` 的 `cleanup` 由 `trap cleanup EXIT` 调用，shellcheck 不识别 trap；加 `disable` + 原因 |
  | SC2086（未加引号） | 1 | `deploy-verify.sh:20` 的 `-iTCP:$port`，加引号即可（真修） |
  | SC2034（变量未使用） | 1 | `deploy-verify.sh:47` 的循环变量 `i`，改 `_` |

- **默认级别而非 `-S warning`**：实测 warning 级只有 1 条，info/style 级 5 条里有 1 条是真问题（SC2086）。用默认级别（含 style）成本很低却能多拦一类，且能避免「将来某条真问题恰好是 info 级而被放过」。

### 2.4 日志断言校验（清 Hashimoto 候选）

新增 `.harness/scripts/check-log-assertions.mjs`，纳入 `ci.mjs`。

**设计已按阶段 1 的原型实测收窄**（三次迭代，过程见下）：

- **匹配源**：扫 `spark-rooter/**/src/main/java/**/*.java` 的**全部字符串字面量**（正则 `"[^"]{1,200}"`），而非「`log.xxx(` 调用」。原因：Java 格式化常把格式串换到下一行（`RunOrchestrator.java:175` 的 `"decision runId={} kind={} planner={}"`、`LogAuditSink.java:15` 的 `"audit runId={} …"` 都是），单行匹配 `log\.[a-z]+\("…"` 会产生**假阴性**——原型第一版正是这样漏掉了这两条现存日志。
- **提取**：从 `e2e-backend.sh` 的 `grep -c` / `grep -q` 参数里取字面量，剥掉 `$VAR` / `$(...)` 插值段与正则元字符（`.*` / `^` / `$`），得到固定文本片段。
- **只校验 key 侧**：片段形如 `key=value` 时只校验 `key=` 部分。原因：value 侧来源太杂——`status=replayed` 的 `replayed` 是 `InvokeToolUseCase.java:127` 的运行时拼接字面量，而 `status=succeeded` 的 `succeeded` 来自枚举 `SseEvent.ToolStatus` 的 `.name()`，源码里根本没有这个字符串。逐一追踪值的来源会让校验器复杂且脆弱，收益却低——**本次要防的那类问题（`source=memory`、`route runId=`、`source=model`）全部是 key 侧消失**，只校验 key 侧已足够。
- **自测（Hashimoto）**：正样本 `decision runId=` / `audit runId=`（必须命中，专防「匹配源退回单行 log 调用」这个已踩过的坑），负样本 `source=memory` / `route runId=` / `source=model`（必须不命中）。任一不符即脚本自身报 self-test 失败。
- **白名单**：确实不来自 Java 日志的片段用显式白名单排除，每项注明原因。已知需要白名单的：`run.completed` / `confirmation.required$` / `^run.failed$`（SSE 事件名，来自契约不是日志）、`请选择` / `退钱`（UI 文案与用户原话）、` ERROR `（日志级别本身）、`$LLM_HOST` / `$SPARK_LLM_API_KEY` / `$phrase` / `$s`（纯插值，剥离后为空）。

### 2.5 文档

- `README.md` 加 CI 徽章，门禁段说明「PR 自动跑 gates + e2e + deploy-verify，均走 fake 规划器」。
- `dev-workflow.md` 阶段 6 门禁：补「CI 在 PR 上自动运行；本地 `pnpm -C .harness run ci` 是其子集（不含 e2e）」。
- `CONTRIBUTING.md`（新增，开源协作必需）：环境要求、本地门禁命令、e2e 如何跑、change 流程简述。

## 3. 非目标（Out of Scope）

- 不配置发包流水线（npm / Maven Central）——独立一项，见改造清单。
- 不在 CI 里跑真模型 e2e（需 secret，且模型调用不稳定会让 CI 变成 flaky 源）。
- 不做多 OS / 多 JDK 矩阵（当前只保证 ubuntu-latest + JDK 21；provider 下限 17 的矩阵留给第三批拆分时加）。
- 不改任何 e2e 断言与 `check` 语义（上一 change 已清理完毕）。
- 不引入 issue / PR 模板（与 CONTRIBUTING 同属开源门面，但模板是纯文本，可随后补）。
- 不为 `PlanValidator.decide` 补单测（上一 change 的 S-1 债务，独立一项）。

## 4. 核心场景

- 开发者提 PR → `gates` 先跑（失败即止，省掉 e2e 的十几分钟）→ 通过后 `e2e` 与 `deploy-verify` 并行 → 全绿才可合并。
- e2e 失败 → `deployment/` 作为 artifact 可下载，内含 Playwright HTML 报告与失败 trace、后端日志。
- 本机开发者行为不变：`pnpm -C .harness run ci` 仍可跑（新增的 `check-shell` 在未装 shellcheck 时跳过）。

## 5. 契约影响

- **NONE**。

## 6. 验收标准

1. `pnpm -C .harness run ci` 退出 0（含新增的 `check-shell` 与 `check-log-assertions`）。
2. `pnpm -C .harness run doctor` 退出 0（含 `lib/java-home.sh` 的 required 检查）。
3. `shellcheck -S warning .harness/scripts/*.sh .harness/scripts/lib/*.sh` 退出 0（本机需先 `brew install shellcheck`；若装不上，改为在 CI 首次运行中验证并记录）。
4. `node .harness/scripts/check-log-assertions.mjs` 退出 0；**故意在 `e2e-backend.sh` 插入一条依赖 `source=memory` 的断言后必须报错**（自证有效，验证后还原）。
5. 三个脚本在 `JAVA_HOME` 指向非 jenv 路径时仍能启动后端：`JAVA_HOME=<临时软链> SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` 退出 0。
6. `JAVA_HOME` 未设时本机行为不变：`unset JAVA_HOME; node .harness/scripts/mvn.mjs -q -B compile` 退出 0。
7. `.github/workflows/ci.yml` 通过 `actionlint`（若本机无该工具，则以 CI 首次运行结果为准并记录在 coding_report）。
8. `README.md` 含 CI 徽章；`CONTRIBUTING.md` 存在且含本地门禁命令。

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| CI 首次运行必然暴露本机与 CI 的环境差异（路径、端口、时序） | §2.1 已把已知的路径假设解掉；端口在 CI 上独占无冲突；时序问题靠脚本既有的轮询等待。首次运行失败属预期，按 CI 日志迭代，不算返工 |
| e2e job 耗时约 13 分钟，PR 反馈慢 | `gates` 先行失败即止；e2e 与 deploy-verify 并行。后续可考虑只在 `main` push 时跑全量、PR 上跑子集——本 change 先求完整，不提前优化 |
| `check-log-assertions` 的正则误判 | 阶段 1 已用原型实测三轮并据此收窄设计（§2.4）：跨行格式串靠「扫全部字面量」解决、`key=value` 只校验 key 侧、已知非日志片段进白名单。正负样本自测防规则漂移，其中正样本专门覆盖已踩过的跨行坑 |
| 白名单被当成「加进去就不用管」的逃逸口 | 每项必须注明原因；白名单命中时脚本打印该项及原因（让它在 CI 日志里可见，而不是静默跳过） |
| 改 `mvn.mjs` 的 JDK 探测影响本机开发 | `JAVA_HOME` 前置但保留全部现有回落路径；验收 6 专门验「未设 JAVA_HOME 时行为不变」 |
| `SPARK_CHANGE=ci-run` 的占位 change 目录污染 `changes/` | 其 `summary.md` 状态置 `DONE`，不干扰 `change-dir` 的「恰一个非 DONE」判定；`deployment/` 进 `.gitignore` |
| shellcheck 修告警时改坏脚本 | 实测只 6 条且 4 条是加注释（见 §2.3），真改只 2 处；仍按验收 3 重跑两套脚本 |
| 本机与 CI 的 shellcheck 版本不同导致告警集合不同 | 工作流固定用 `ubuntu-latest` 自带版本并在 coding_report 记录 CI 实际版本；若 CI 报出本机没有的告警，按 CI 为准修（本机 0.11.0 已是较新版） |
| `actionlint` 与 `shellcheck` 本机缺失（阶段 1 已确认 actionlint 未装） | shellcheck 已 `brew install` 装好（0.11.0）；actionlint 不强求本机，验收 7 允许以 CI 首跑为准并记录 |
