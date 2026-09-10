---
name: frontend-doctor
description: 任意阶段卡死、CI 失败、构建报错、运行时异常、Run 卡在某状态时的系统化诊断，前后端通用。触发场景："构建挂了"、"CI 红"、"页面白屏"、"Run 不动了"、"排查"。按 5 步法定位根因，禁止"再试一次"式盲修。
---

# Skill: frontend-doctor

## 何时触发
- 同一阶段连续失败 ≥ 2 次
- "本地能跑，CI 跑不过"
- 构建产物异常变大
- 预览页面出现 console.error
- Run 长时间停留在 `PLANNING` / `EXECUTING` / `WAITING_CONFIRMATION`

## 反模式
- ❌ "我再跑一次试试"——不知道根因就别复跑。
- ❌ "升级一下依赖看看"——升级前先复现并定位最小用例。

## 5 步诊断法

### 1. 复现到最小用例
- 稳定复现（≥3 次失败 / 5 次尝试）。
- 隔离变量：干净 worktree / 不同 Node 或 JDK 版本 / 关 cache。

### 2. 二分查找
- `git bisect` 定位首个引入失败的提交。
- 关 cache：`rm -rf spark-ui/node_modules/.vite`；`node .harness/scripts/mvn.mjs clean`。

### 3. 收集证据
| 现象 | 收集物 |
|---|---|
| 契约校验失败 | `pnpm -C .harness run check-contracts` 全文，定位 Schema 与示例的差异字段 |
| 前端 typecheck / lint 失败 | `pnpm -C spark-ui typecheck 2>&1 \| head -50`、`pnpm -C spark-ui lint` |
| 后端编译 / 格式失败 | `node .harness/scripts/mvn.mjs -e verify` 完整日志 |
| 构建失败 | `pnpm -C spark-ui build --mode=development`；`node .harness/scripts/mvn.mjs -X package` |
| 前端运行时错误 | 浏览器 console、network、source map 栈 |
| 后端运行时错误 | 按 `runId` grep 日志；`/actuator/health`；审计记录 |
| Run 卡住 | 该 Run 的状态机迁移日志、最后一条 SSE 事件、Gateway 审计中对应 `toolCallId` |

### 4. 形成假设
- 假设：______
- 反证条件：如果 ______ 出现则假设错误
- 验证步骤：______

### 5. 修 Harness 而不仅是修代码
- 能否写成自动检查（oxlint 规则 / Maven 插件 / `check-*.mjs` 脚本 / Skill checklist）？
- 是 → 编码进去，**让它再也不能发生**。

## Checklist
- [ ] 复现成功率记录（X/N 次）
- [ ] 至少 1 个反证条件被验证
- [ ] 修复同时更新某个 Skill / Rule 的 Checklist
- [ ] 在 change 的 `summary.md` 留下 root-cause 1 句话总结
