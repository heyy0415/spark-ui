# Tasks: ci-github-actions-pipeline-20260912

编码顺序：解本机路径假设（CI 前置）→ 两个新门禁脚本 → 工作流 → 文档。契约影响 NONE，无 contracts task。每个 task ≤ 0.5 天。

## T01 解除本机 JDK 路径假设

- **目标**：三个 shell 脚本与 `mvn.mjs` 在 CI 的 JDK 布局下可用，本机行为不变。
- **所属端**：harness
- **输入**：`mvn.mjs:27-42`；`e2e-backend.sh:8`、`e2e-frontend.sh:11`、`deploy-verify.sh:8` 及各自的子进程启动行
- **输出**：
  - 新增 `.harness/scripts/lib/java-home.sh`：解析出 `$JAVA` 与 `$JAVA_HOME_RESOLVED`，优先 `$JAVA_HOME`（校验其 `bin/java -version` 含 21），回落现有 jenv 路径，最后 `command -v java`；全找不到则 exit 2 附可读提示。
  - 三个脚本 source 它，删掉各自的写死路径；启动子进程改传 `JAVA_HOME="$JAVA_HOME_RESOLVED"`。
  - `mvn.mjs` 候选列表前置 `process.env.JAVA_HOME`。
  - `harness-doctor.mjs` required 加 `scripts/lib/java-home.sh`。
- **验收**：
  - `unset JAVA_HOME; node .harness/scripts/mvn.mjs -q -B compile` 退出 0（本机回落仍有效）
  - `JAVA_HOME=$HOME/.jenv/versions/21 SPARK_PORT=8091 SPARK_CHANGE=<本 change> bash .harness/scripts/e2e-frontend.sh` 退出 0
  - `pnpm -C .harness run doctor` 退出 0
- **依赖**：无

## T02 check-log-assertions.mjs

- **目标**：e2e 断言引用的日志片段若在 `src/main` 中不存在，立即报错——防止「断言依赖已删除的日志」再次潜伏三个 change。
- **所属端**：harness
- **输入**：`e2e-backend.sh` 全部 `grep -c` / `grep -q` 断言；`spark-rooter/**/src/main/java/**/*.java` 的 `log.*("…")` 格式串
- **输出**：`.harness/scripts/check-log-assertions.mjs`；`ci.mjs` 新增该步骤；`.harness/package.json` 加 script `check-log-assertions`；`harness-doctor` required 同步。含正负样本自测（正：`decision runId=`；负：`source=memory`）。
- **验收**：
  - `node .harness/scripts/check-log-assertions.mjs` 退出 0
  - **自证有效**：临时在 `e2e-backend.sh` 插入 `check "x" 1 "$(grep -c 'source=memory' "$DEPLOY/backend.log")"` → 脚本退出非 0 并指出该片段；还原后复绿
  - 自测部分：把正样本改成一个不存在的片段 → 脚本自身报 self-test 失败
- **依赖**：无（可与 T01 并行）

## T03 shellcheck 入门禁并修现存告警

- **目标**：`bash -n` 查不出的问题（如变量名被全角字符吞掉）由 shellcheck 拦住。
- **所属端**：harness
- **输入**：`.harness/scripts/*.sh` 四个文件 + T01 新增的 `lib/java-home.sh`
- **输出**：`ci.mjs` 新增 `check-shell` 步骤（shellcheck 缺失时跳过并打印提示）；现存告警修掉；有意为之的加 `# shellcheck disable=SCxxxx` + 原因。
- **验收**：
  - `shellcheck -S warning .harness/scripts/*.sh .harness/scripts/lib/*.sh` 退出 0
  - 修完后 `SPARK_PORT=8091 bash .harness/scripts/e2e-frontend.sh` 与 `deploy-verify.sh` 各跑一次仍退出 0（改脚本不能改坏行为）
  - `pnpm -C .harness run ci` 退出 0
- **依赖**：T01（它新增了一个 .sh 文件）

## T04 GitHub Actions 工作流

- **目标**：PR 上自动跑三个 job，失败可下载产物排查。
- **所属端**：harness
- **输入**：spec §2.2 的 job 划分；`ci.mjs` 的步骤清单；三套验收脚本的前置条件
- **输出**：
  - `.github/workflows/ci.yml`：`gates` / `e2e` / `deploy-verify` 三 job，setup-java temurin 21 + pnpm + node 20，三类缓存，`SPARK_CHANGE=ci-run`，e2e 失败上传 `deployment/` artifact（7 天）。
  - `.harness/changes/ci-run/summary.md`（状态 `DONE`，说明其用途）；`.gitignore` 忽略 `.harness/changes/ci-run/deployment/`。
- **验收**：
  - 本机静态校验：`actionlint .github/workflows/ci.yml` 退出 0（无该工具则记录，以 CI 首跑为准）
  - YAML 可解析：`node -e "require('node:fs');"` + 用 `js-yaml` 或 `python3 -c "import yaml;yaml.safe_load(open(...))"` 解析成功
  - `SPARK_CHANGE=ci-run bash .harness/scripts/e2e-frontend.sh` 退出 0（验证占位 change 目录可用）
  - `pnpm -C .harness run doctor` 退出 0（`ci-run` 目录不破坏 change 检查）
- **依赖**：T01、T02、T03

## T05 文档与 CONTRIBUTING

- **目标**：CI 状态可见，外部贡献者知道怎么本地验证。
- **所属端**：harness
- **输入**：spec §2.5
- **输出**：`README.md` CI 徽章 + 门禁段说明；`dev-workflow.md` 阶段 6 门禁补注；新增 `CONTRIBUTING.md`（环境要求、本地门禁、e2e 跑法、change 流程简述、commit 规范）。
- **验收**：
  - `pnpm -C .harness run doctor` 退出 0（README 用词门禁：不得含口号式词汇）
  - `pnpm -C .harness run ci` 退出 0
  - `grep -c 'workflows/ci.yml/badge.svg' README.md` == 1
- **依赖**：T04
