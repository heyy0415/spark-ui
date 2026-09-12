# Spec Review v1 — ci-github-actions-pipeline-20260912

- **mode**: plan
- **评审对象**: `request_analysis/spec.md`、`request_analysis/tasks.md`
- **依据**: `expert-reviewer/SKILL.md`（plan 必查项）、`rules/{dev-workflow,project-structure,backend-standard,agent-safety,contracts}.md`
- **verdict**: **APPROVED**（0 条 MUST FIX，3 条 SHOULD）

> **独立性声明**：subagent 通道在本会话不可用（阶段 2 评审时 7 个 subagent 全部 `400 专用渠道限制`），本文由 spec 作者本人撰写，**独立性不满足**。补偿措施：spec 中的每一条技术断言都在阶段 1 用可复现命令实测过（`check-log-assertions` 的匹配逻辑迭代了三轮、shellcheck 告警实跑、change 状态分布实查），本文只复核「实测是否支撑结论」，不复核「作者的判断是否正确」。§4 列出建议他人复核的条目。

---

## 0. 与上一 change 的教训对照

上一 change 的头号教训是「spec 里写『已核对』必须附可复现命令」。本 spec 在这一点上有实质改进：

| spec 断言 | 是否实测 | 证据 |
|---|---|---|
| 三个脚本硬编码 `$HOME/.jenv/...` | 是 | `grep -n 'jenv' .harness/scripts/*.sh` 命中 7 处（`e2e-backend.sh:8,33,410`、`e2e-frontend.sh:11,42`、`deploy-verify.sh:8,40`） |
| shellcheck 现存告警只 6 条 | 是 | `brew install shellcheck` 后实跑，SC2012×3 / SC2329×1 / SC2086×1 / SC2034×1 |
| SC2329 是误报 | 是 | `grep -n 'trap' e2e-frontend.sh` 确认 `trap cleanup EXIT` 存在于 `:18` |
| `change-dir` 在 CI 上必失 | 是 | 实查 12 个 change 目录，5 个非 DONE，`change-dir.mjs:38` 要求恰 1 个 |
| 跨行格式串会导致假阴性 | 是 | 原型第一版实际漏掉 `decision runId=` 与 `audit runId=`，`grep -rn` 确认两者格式串都在 `log.info(` 的下一行 |
| `status=succeeded` 的值来自枚举非字面量 | 是 | `grep -rn 'replayed'` 显示 `InvokeToolUseCase.java:127` 是三元拼接，`succeeded` 来自 `ToolStatus.name()` |
| actionlint / shellcheck 本机状态 | 是 | `which` 实查，shellcheck 已装 0.11.0，actionlint 未装 |

**结论**：无「未经核对的事实断言」。这是本 spec 相比上一 change 的 v1 最明显的改进。

## 1. 逐条意见

### S-1 ｜ SHOULD ｜ e2e job 耗时被低估，PR 反馈可能过慢

- **位置**: spec §2.2 的耗时表（「后端约 10 分钟」）、§7 第 2 行
- **核对**: 上一 change 实跑 `e2e-backend.sh` 时 600s 超时仍未完成，需转后台。脚本含两次 JVM 启动（主轮 + `e2e-ttl` profile 轮）、TTL 等待、26 个 SSE 用例。CI 上 JVM 冷启动与磁盘 I/O 通常慢于本机，实际可能 12–18 分钟。加上 `gates` job 本身（两端构建 + 单测 + host-demo 离线打包，本机约 3–5 分钟），串行总时长可能 20 分钟以上。
- **问题**: spec §7 已承认「PR 反馈慢」并给了缓解（gates 先行、e2e 与 deploy-verify 并行），但耗时数字偏乐观，可能导致 T04 配置时不设 `timeout-minutes` 而撞上 GitHub 默认 360 分钟——那不是保护而是浪费。
- **建议**: T04 的工作流给每个 job 显式 `timeout-minutes`（gates 20 / e2e 30 / deploy-verify 15），并在 coding_report 记录 CI 首跑的真实耗时，据此在下一 change 决定是否拆分（如 PR 只跑 gates + e2e-frontend，main push 跑全量）。**不在本 change 提前优化**——spec §7 的判断（先求完整）是对的。

### S-2 ｜ SHOULD ｜ `ci-run` 占位 change 的状态设计需再确认

- **位置**: spec §2.2 第 3 点、tasks T04 输出
- **核对**: spec 让 `ci-run/summary.md` 状态为 `DONE`，理由是「不干扰本机 `change-dir` 的恰一个非 DONE 判定」。实查 `change-dir.mjs:37` 的 `.filter((name) => !TERMINAL.has(statusOf(...) ?? 'DONE'))` —— 状态为 `DONE` 确实会被过滤掉，本机判定不受影响。**设计成立**。
- **问题**: 但 `harness-doctor.mjs:289` 有一条「change 有 TODO 且 7 天未活动则 warn」的检查，`ci-run` 作为长期存在的占位目录，其 `summary.md` 若含 `TODO` 字样会持续告警。另外 doctor 会把它算进 change 列表（`ok('change: ...')`），列表里多一个非真实需求的条目。
- **建议**: `ci-run/summary.md` 写成最小形态：状态 `DONE`、阶段表全部 `N/A`（不含 `TODO` 字样）、正文一句说明「CI 专用占位目录，非真实需求，仅供 `SPARK_CHANGE` 定位 deployment 落盘位置」。T04 验收已含 `doctor` 退出 0，此条只是提醒具体写法。

### S-3 ｜ SHOULD ｜ CONTRIBUTING.md 与 issue 模板被拆到两处

- **位置**: spec §2.5（含 CONTRIBUTING）、§3 非目标（不含 issue / PR 模板）
- **核对**: 两者同属开源协作门面，spec 把 CONTRIBUTING 放进范围、模板排除在外，理由是「模板是纯文本，可随后补」。
- **问题**: 理由成立但会留一个半成品状态——有 CONTRIBUTING 说明「怎么提 PR」，却没有 PR 模板承载那些要求（如「勾选已跑本地门禁」）。
- **建议**: 要么本 change 一并加一个极简 PR 模板（`.github/pull_request_template.md`，5 行：改动说明 / 关联 change / 本地门禁是否通过），要么在 CONTRIBUTING 里明确「模板待补」。前者成本约 10 分钟，倾向前者。不阻塞。

### L-1 ｜ LOW ｜ shellcheck 默认级别的选择理由已充分

- **位置**: spec §2.3 末段
- **核对**: 实测 warning 级 1 条、全级别 6 条，其中 info 级的 SC2086（`-iTCP:$port` 未加引号）是真问题。spec 因此选默认级别而非 `-S warning`，理由正确且有数据支撑。
- **建议**: 无需改动，记录核对结论。

### L-2 ｜ LOW ｜ 白名单可见性的设计值得保留

- **位置**: spec §7 新增的「白名单被当成逃逸口」风险条
- **核对**: 缓解措施是「白名单命中时打印该项及原因」。这是个好设计——它让白名单在 CI 日志里持续可见，而不是静默跳过。符合 Hashimoto 精神（让问题显形而非隐藏）。
- **建议**: 无需改动。

### I-1 ｜ INFO ｜ 非目标划分合理

`check-log-assertions` 的设计收窄（只校验 key 侧）是基于实测的正确取舍：本次要防的三类失实断言（`source=memory` / `route runId=` / `source=model`）全部是 key 侧消失，而追踪 value 侧来源（字面量 / 枚举 / 运行时拼接）会让校验器脆弱。spec §2.4 把这个取舍与理由都写清了，未来有人想扩展时知道边界在哪。

`decide` 单测（上一 change 的 S-1）被排除在本 change 外，合理——它属测试基建而非 CI 配置，混进来会让本 change 的范围失焦。

## 2. plan 必查项

| 项 | 结果 |
|---|---|
| 「非目标」存在且非空 | ✓ 6 条，与 §1/§2/§6 无冲突 |
| 每条验收可被命令 / 断言校验 | ✓ 8 条全部可命令化。验收 3 与 7 对本机工具缺失有明确降级路径（shellcheck 已装；actionlint 允许以 CI 首跑为准并记录）——这是诚实的处理，不是「以实测为准」式免责 |
| 风险章节 ≥1 失败模式 + 缓解 | ✓ 8 条 |
| 每个 task 标注所属端；contracts task 前置 | ✓ 契约影响 NONE，无 contracts task |
| 跨端结构列契约文件 | N/A |
| 每个 task ≤ 0.5 天 | ✓ T01（4 文件路径改造）、T02（一个脚本 + 自测）、T03（实测只 6 条告警）、T04（一个 yml + 占位目录）、T05（3 份文档）均在范围内 |

## 3. 回退

`APPROVED` → 进阶段 3 编码。三条 SHOULD 不阻塞，建议在编码时顺带处理：S-1 加 `timeout-minutes`、S-2 按建议写占位 summary、S-3 加极简 PR 模板。

## 4. 建议他人复核的条目

1. **`check-log-assertions` 的白名单是否过宽**。spec §2.4 已列 9 项白名单，我判断都是合理的（SSE 事件名、UI 文案、纯插值），但白名单本身就是校验强度的缺口，值得第二双眼睛看。
2. **工作流的 job 划分是否最优**。我选了 gates → (e2e ∥ deploy-verify)，依据是 gates 最快失败。但 deploy-verify 其实包含 e2e-frontend 的子集（preview-console），可能有重复。
3. **耗时估算**（见 S-1）。只有 CI 首跑能给出真实数字。
