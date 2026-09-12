# Code Review v1 — ci-github-actions-pipeline-20260912

- **mode**: execution
- **评审对象**: 本 change 全部改动、`coding/coding_report_v1.md`
- **依据**: `code-review/SKILL.md`、`expert-reviewer/SKILL.md`（execution 必查项）、`rules/{dev-workflow,project-structure,backend-standard,agent-safety}.md`
- **verdict**: **APPROVED**（0 条 MUST FIX，2 条 SHOULD）

> **独立性声明**：subagent 通道在本会话不可用，本文由编码者本人撰写，**独立性不满足**。补偿：所有结论以实跑退出码或源码行号为据；§5 列出建议他人复核的条目。

---

## 1. 机器化检查

| 命令 | 退出码 |
|---|---|
| `pnpm -C .harness run ci` | 0（9 步全绿） |
| `pnpm -C .harness run doctor` | 0（0 errors, 0 warnings） |
| `shellcheck` 五个脚本（默认级别） | 0 |
| `node check-log-assertions.mjs` | 0 |
| YAML 解析 | 通过，3 job |

**三个验收脚本在 java-home 改造后逐一实跑**（这是本 change 最主要的回归风险，代码审查不足以证明）：

| 脚本 | 结果 |
|---|---|
| `e2e-frontend.sh` | exit 0，**7 passed** |
| `deploy-verify.sh` | exit 0，**12 passed, 0 failed**，`planner=fake-e2e` |
| `e2e-backend.sh` | exit 0，**161 passed, 0 failed** |

## 2. 红线清单

- [x] 未触碰两端业务代码（`git status` 显示改动只在 `.harness/`、`.github/`、README、CONTRIBUTING）
- [x] 契约零改动，`check-contracts` 退出 0
- [x] `check-module-deps` 退出 0（未改后端模块）
- [x] 无密钥引用：工作流里 `grep -c 'secrets\.'` == 0；CI 不配 `SPARK_LLM_*`
- [x] 新增脚本只读不写源码（`check-log-assertions.mjs` 只有 `readFile`）
- [x] `scripts/lib/java-home.sh` 与 `check-log-assertions.mjs` 已进 doctor 的 required 列表

## 3. 逐条意见

### I-1 ｜ INFO ｜ `java-home.sh` 的三条解析路径均已实测

- **核对**: 阶段 3 分别验证了「无 `JAVA_HOME`（走 jenv 回落）」「显式 `JAVA_HOME`」「`JAVA_HOME` 指向不存在路径（应回落）」三种情况，输出符合预期。`set -u` 下的安全性：脚本内对 `JAVA_HOME` 的引用统一写 `${JAVA_HOME:-}`，未定义时不会触发 unbound。
- **核对 source 顺序**: 三个脚本的 `ROOT=` 都在 `source java-home.sh` 之前（`e2e-backend.sh` 5 < 8、`e2e-frontend.sh` 9 < 11、`deploy-verify.sh` 5 < 8）。顺序若错会立刻 unbound 报错，而三个脚本都实跑通过，反向印证了这一点。
- **分级**: INFO

### I-2 ｜ INFO ｜ `check-log-assertions` 的自证有效性已验证

- **核对**: 插入 `grep -c "runId=xxx .*source=memory"` → 脚本报 `✗ … no Java string literal in src/main contains "source="` 并退出 1；还原后复绿。这正是上一 change 里潜伏三次的那类问题，现在会在日志消失的当下就红。
- 脚本自身还有内置自测（正样本 `decision runId=` / `audit runId=` 专防「匹配源退回单行 log 调用」这个已踩过的坑；负样本 `source=memory` 等；`fixedPart` / `keyPart` / `coveredByFormat` 的行为用例）。规则漂移会让脚本自己报 self-test 失败。
- **分级**: INFO

### I-3 ｜ INFO ｜ 两处 spec 之外的发现都是真实缺口

- **发现 1（第 8 处本机路径）**: `ci.mjs` 的 `host-demo` 步骤直接调 `mvn`（`ci.mjs:71`），不经 `mvn.mjs`。核实 `mvn.mjs` 的 cwd 确实固定在 `spark-rooter/`（`mvn.mjs` 末行 `cwd: sparkRooterDir`），所以 host-demo 无法复用它。为该步骤单独注入 `env` 是正确处理，候选路径与另两处保持一致。
- **发现 2（`mvn -o` 空缓存必失）**: 核实 host-demo 的 parent 是 `spring-boot-starter-parent` 且 `relativePath` 为空（`pom.xml:9-13`），是外部 POM。`spark-rooter install` 不会下载它与 `spring-boot-maven-plugin`。这是 CI 首跑的确定性失败点，预热步骤是必要的。
- **分级**: INFO

### S-1 ｜ SHOULD ｜ 预热步骤的有效性未在空缓存下验证

- **位置**: `.github/workflows/ci.yml` 的「预热 host-demo 的 parent 与插件」步骤
- **核对**: 本机实跑 `mvn -q -B -DskipTests dependency:resolve-plugins` 退出 0，但**本机 `~/.m2` 已有全部缓存**，这个结果不能证明它在空缓存下够用。`dependency:resolve-plugins` 解析的是插件及其依赖，理论上覆盖 `spring-boot-maven-plugin`；但 parent POM 的解析发生在更早的 POM 读取阶段，是否被该 goal 覆盖，没有实证。
- **问题**: 若不够用，CI 首跑会在 `pnpm run ci` 的 host-demo 步骤失败，报 `Cannot access central in offline mode`。
- **建议**: coding_report §5 已把它列为待 CI 首跑验证项，并给了两个备选（去掉 `-o`、或加 `dependency:go-offline`）。**接受现状**：这属于「只有 CI 能验证」的一类，提前猜测不如让首跑暴露。若首跑失败，按备选方案迭代即属预期，不算返工。
- **分级**: SHOULD（不阻塞；已有明确的失败信号与应对方案）

### S-2 ｜ SHOULD ｜ e2e 与 deploy-verify 两个 job 有重复工作

- **位置**: `ci.yml` 的 `e2e` 与 `deploy-verify` 两 job
- **核对**: `deploy-verify.sh` 内部调 `preview-console.mjs` 做 console.error 检查，而 `e2e-frontend.sh` 的 Playwright 用例也检查 console.error（`playground.spec.ts` 每个用例都断言 `errors` 为空）。两者覆盖面不同（前者验生产 bundle 的 vite preview，后者验 dev server），所以不是纯冗余——但两个 job 各自装依赖、各自下 chromium，固定开销重复。
- **建议**: 首跑拿到真实耗时后再定。若两 job 的固定开销（checkout + 装依赖 + 下浏览器）占比过高，可合并为一个 job 串行跑两套脚本；若并行收益明显则保持。**本 change 不提前优化**，与 spec §7「先求完整」一致。
- **分级**: SHOULD

### L-1 ｜ LOW ｜ `ci.mjs` 的 skip 文案修正是顺带改动

- **位置**: `ci.mjs` 的执行循环
- **核对**: 原文案写死 `skipped (not initialized)`，对 shellcheck 这一步不准确（它 skip 的原因是「工具未安装」而非「未初始化」）。改为通用的 `skipped`，具体原因由各步的 `skipIf` 自己打印（shellcheck 那步会提示 `brew install shellcheck`）。
- 这超出了 spec 明列范围，但属于「新增 check-shell 步骤必然带来的连带修正」，不是顺手重构。
- **分级**: LOW

### L-2 ｜ LOW ｜ README 徽章从静态占位换成真实徽章

- **核对**: 原徽章是 `img.shields.io/badge/ci-pnpm...-blue` 的静态图，链接指向本地脚本文件——它看起来像 CI 状态但不反映任何真实状态。换成 `actions/workflows/ci.yml/badge.svg` 后才是真实状态。
- **分级**: LOW（记录，这是个小但实质的改进）

## 4. execution 必查项

| 项 | 结果 |
|---|---|
| 契约一致性 | N/A（契约零改动） |
| `agent-safety.md` §1–§6 | 六条均未变（本 change 不触碰运行时代码）。补充：工作流无密钥引用，新增脚本只读 |
| 前端红线 | N/A（未改前端源码；`spark-ui` ci 退出 0） |
| 后端红线 | N/A（未改后端源码；`check-module-deps` 退出 0） |
| 改动 ≠ spec 时显式标注偏差 | ✓ 两处发现已在 coding_report §3 记录，并解释了为何 spec 没预见到 |

## 5. 建议他人复核的条目

1. **预热步骤是否足够**（S-1）。只有 CI 空缓存能验证。
2. **工作流的 job 划分**（S-2）。需真实耗时数据支撑。
3. **`check-log-assertions` 的白名单是否过宽**。目前 7 项，我判断都合理（SSE 事件名来自契约、logger 名、框架日志、UI 文案），但白名单本身是校验强度的缺口。脚本会打印每项及原因，便于持续审视。
4. **`keyPart` 只校验 key= 的取舍**。这让「value 侧变化」逃过检查（例如日志把 `status=succeeded` 改成 `state=succeeded` 能被抓到，但 `succeeded` 改成 `ok` 抓不到）。这是 spec §2.4 的明确取舍，但值得第二双眼睛判断是否够用。

## 6. 回退

`APPROVED` → 进阶段 5。两条 SHOULD 都依赖 CI 首跑数据，已登记到 summary 的遗留债务。
