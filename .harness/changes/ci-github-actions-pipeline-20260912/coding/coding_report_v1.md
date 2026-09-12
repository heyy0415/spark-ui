# Coding Report v1 — ci-github-actions-pipeline-20260912

- **阶段**: 3 编码实现
- **依据**: `request_analysis/spec.md`、`request_analysis/tasks.md`、`request_analysis/review/spec_review_v1.md`（APPROVED，3 条 SHOULD 已全部采纳）
- **编码顺序**: T01 → T02 → T03 → T04 → T05（契约无变更）

## 0. 门禁结果（真实退出码）

| 命令 | 退出码 |
|---|---|
| `pnpm -C .harness run ci` | 0 —— **9 步全绿**（新增 check-log-assertions、check-shell） |
| `pnpm -C .harness run doctor` | 0（0 errors, 0 warnings） |
| `shellcheck .harness/scripts/*.sh .harness/scripts/lib/*.sh` | 0（默认级别，含 style） |
| `node .harness/scripts/check-log-assertions.mjs` | 0（8 个片段验证通过，7 项白名单可见） |
| `unset JAVA_HOME; node .harness/scripts/mvn.mjs -q -B compile` | 0（本机回落仍有效） |
| `JAVA_HOME=… SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` | 0（7 passed，脚本改造未破坏行为） |
| `SPARK_CHANGE=ci-run node .harness/scripts/lib/change-dir.mjs` | 0（占位目录可定位） |
| YAML 解析（python yaml.safe_load） | 通过，3 个 job |

## 1. 改动文件

### harness / scripts

| 文件 | 变化 | 说明 |
|---|---|---|
| `scripts/lib/java-home.sh` | **新增 57 行** | 解析 `$JAVA_BIN` / `$JAVA_HOME_RESOLVED`。顺序：`$JAVA_HOME`（校验 `-version` 含 21）→ 本机 jenv / 系统路径 → PATH。三个脚本共用，避免三份副本漂移 |
| `scripts/e2e-backend.sh` | 改 3 行 | source `java-home.sh`；两处启动行改用 `$JAVA_HOME_RESOLVED` / `$JAVA_BIN`；头注释同步 |
| `scripts/e2e-frontend.sh` | 改 4 行 | 同上 + 两处 shellcheck disable 注释 |
| `scripts/deploy-verify.sh` | 改 6 行 | 同上 + SC2086 加引号、两处 `for i` → `for _`、一处 disable 注释、段标题「headless Chrome」→「Playwright chromium」 |
| `scripts/mvn.mjs` | 改 8 行 | JDK 候选列表前置 `process.env.JAVA_HOME`。**注释说明为何不校验路径含 "21"**：CI 的 JAVA_HOME 形如 `/opt/hostedtoolcache/Java_Temurin.../x64`，版本由 maven-enforcer 兜底 |
| `scripts/check-log-assertions.mjs` | **新增 176 行** | 见 §2 |
| `scripts/ci.mjs` | 改 40 行 | 新增 check-log-assertions、check-shell 两步；`host-demo` 步骤注入 JAVA_HOME；执行循环支持 `env`；skip 文案修正（原来对 shellcheck 会显示「not initialized」） |
| `scripts/harness-doctor.mjs` | 改 2 行 | required 加 `scripts/lib/java-home.sh`、`scripts/check-log-assertions.mjs` |
| `package.json` | 改 1 行 | script `check-log-assertions` |

### GitHub Actions

| 文件 | 变化 |
|---|---|
| `.github/workflows/ci.yml` | **新增 196 行**，三个 job |
| `.github/pull_request_template.md` | **新增**（评审 S-3） |
| `.harness/changes/ci-run/summary.md` | **新增**，CI 专用占位目录（评审 S-2 的写法建议已采纳：状态 `DONE`、无阶段表、不含 TODO 字样） |
| `.gitignore` | 加 `.harness/changes/ci-run/deployment/` |

### 文档与规则

| 文件 | 变化 |
|---|---|
| `README.md` | CI 徽章换成真实 workflow 徽章（原为静态占位）；门禁段补 CI 说明 |
| `CONTRIBUTING.md` | **新增**：环境表、本地门禁、端到端命令、改动边界（5 条机械门禁）、commit 规范、八阶段流程简述 |
| `.harness/rules/dev-workflow.md` | 阶段 6 门禁改为 GitHub Actions 三 job；新增「规划器口径」与「失败排查」两行 |

## 2. `check-log-assertions.mjs` 的设计与三轮迭代

这个脚本是为了让上一 change 那类问题（断言依赖已删除的日志，潜伏三个 change）在日志消失的当下就红。设计过程有三次修正，每次都由实测驱动：

**第一版：按 `log.xxx("…")` 匹配 → 假阴性。**
`decision runId=` 与 `audit runId=` 明明存在却报不存在。原因是 Java 格式化把格式串换到了下一行（`RunOrchestrator.java:175`、`LogAuditSink.java:15`），单行正则抓不到。**改为扫全部字符串字面量**，跨行问题消失。

**第二版：整段精确匹配 → 又一批假阴性。**
`spark-rooter: 14 tools registered from 6 beans` 报不存在——源码里是 `"spark-rooter: {} tools registered from {} beans"`，数字运行时填入。**新增 `coveredByFormat`，把 `{}` 当通配符**。这条本是 spec §2.4 的设计意图，实现时漏了，自测现在专门覆盖它。

**第三版：剩余 3 条误报 → 定位为真实白名单。**
`Application run failed`（Spring 框架日志，不在本仓）、`SelfCheckRunner`（logger 名，出现在日志的 logger 字段而非消息内容）、`session mismatch`（与 `runId=` 拼接后才成句）。**加进白名单并各自注明原因**。

**最终口径**：
- 匹配源 = 全部字符串字面量（不是 log 调用）
- `{}` 当通配符
- `key=value` 片段只校验 `key=`（value 侧来源太杂：`status=replayed` 是运行时拼的字面量，`status=succeeded` 来自枚举 `.name()`，源码里没有这个串）
- 白名单命中时**打印该项与原因**，让它在 CI 日志里持续可见，不变成静默逃逸口

**自证有效**（验收 4）：在 `e2e-backend.sh` 插入一条 `grep -c "runId=xxx .*source=memory"` 的断言 → 脚本报 `✗ … no Java string literal in src/main contains "source="` 并退出 1；还原后复绿。

## 3. spec 之外的发现

**发现 1：本机路径假设有 8 处，不是 spec 说的 7 处。**

spec §2.1 列了三个 shell 脚本（7 处引用）与 `mvn.mjs`。编码时发现 `ci.mjs` 的 `host-demo` 步骤**直接调 `mvn`**（不经 `mvn.mjs`，因为后者 cwd 固定在 `spark-rooter/`），CI 上同样拿不到 JDK。已为该步骤单独注入 `env`，候选路径与 `mvn.mjs` / `java-home.sh` 保持一致。

**发现 2：`mvn -o` 在 CI 空缓存下必然失败。**

`ci.mjs` 的 host-demo 步骤用 `-o`（离线，证明只依赖本地仓）。但 host-demo 的 parent 是**外部的** `spring-boot-starter-parent`（`relativePath` 为空），还要用 `spring-boot-maven-plugin` —— 这两者不在 `spark-rooter install` 的下载范围内。本机早已缓存所以从未暴露，CI 首跑会直接失败。

已在工作流加一步 `mvn -q -B -DskipTests dependency:resolve-plugins`（在 `pnpm run ci` 之前）预热。本机验证该命令独立可跑（exit 0，不需要 spark 模块先 install，因为 `resolve-plugins` 只解析插件不解析项目依赖）。**但本机有缓存，不能完全代表 CI 空缓存**——列为待 CI 首跑验证项。

**发现 3：shellcheck 实测告警比预估少。**

spec 预估「现存告警只 6 条」，实测确认 6 条，其中 4 条是加 `disable` 注释、2 条是真修（SC2086 未加引号、SC2034 循环变量未用）。改完后又暴露一条同类的 `for i`（`deploy-verify.sh:41`，spec 只提了 `:47`），一并修掉。`java-home.sh` 本身零告警。

## 4. 评审 SHOULD 的落实

| 条目 | 落实 |
|---|---|
| S-1 e2e 耗时被低估，需 `timeout-minutes` | 三个 job 显式设置：gates 25 / e2e 35 / deploy-verify 20。另加 `concurrency.cancel-in-progress` 避免过期运行占 runner |
| S-2 `ci-run` 占位 summary 的写法 | 按建议写成最小形态：状态 `DONE`、无阶段表、不含 `TODO` 字样。实测 doctor 输出 `✓ change: ci-run (0 TODO)`，不触发 7 天告警 |
| S-3 PR 模板 | 已加 `.github/pull_request_template.md`，4 行 checklist |

## 5. 待 CI 首跑验证的事项

本机无法验证，CI 首次运行会暴露：

1. **`mvn -o` 预热是否足够**（见发现 2）。若仍失败，备选方案是去掉 `-o`（放弃「只依赖本地仓」的证明）或在预热步骤加 `dependency:go-offline`。
2. **ubuntu runner 的工具可用性**：脚本用了 `lsof`（3 处）、`pkill`（5 处）、`python3`（4 处）。ubuntu-latest 默认都有，但 `lsof` 在精简镜像里偶有缺失。
3. **`upload/download-artifact@v4` 的路径还原**。已显式写 `path: .` 不依赖公共前缀推导，但三个 path 的还原结构需实跑确认。
4. **actionlint 未在本机运行**（未安装，spec 验收 7 已允许以 CI 首跑为准）。
5. **CI 的 shellcheck 版本**可能与本机 0.11.0 不同，告警集合可能有差异。
6. **e2e job 的真实耗时**（S-1），据此决定是否在下一 change 拆分 PR / main 的门禁范围。

## 6. agent-safety 六条边界自查

本 change 不触碰运行时代码，只改验收脚本、CI 配置与文档。六条边界均无变化：

| 条 | 结论 |
|---|---|
| §1 四面职责 | 未变 |
| §2 工具发现 | 未变 |
| §3 确认机制 | 未变 |
| §4 前端边界 | 未变 |
| §5 Gateway | 未变 |
| §6 流式输出 | 未变 |

补充：CI 不配置任何 `SPARK_LLM_*` secret，工作流里无密钥引用；`check-log-assertions` 只读源码不执行。
