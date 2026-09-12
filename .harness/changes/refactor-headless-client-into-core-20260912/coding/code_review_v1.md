# 代码评审 v1 — refactor-headless-client-into-core-20260912

## 0. 独立性声明（必读）

**本评审是自评审，不满足独立性要求。** 本仓的 subagent 通道返回 `400 专用渠道限制: 接口仅可用于CC官方客户端`，无法派出独立评审 agent。作者与评审者是同一个 agent，存在系统性盲区：我倾向于认为自己的设计意图已经实现，而"意图"与"实际行为"的差距正是评审要找的东西。

本轮通过三条手段部分弥补，但都不能替代第二双眼睛：

1. **机械证据优先于主观判断**——凡是能变成脚本断言的结论，都写成门禁并**双向自证**（插入违规必须变红、恢复必须变绿）；
2. **对自己写的文档逐条核对代码**——结果发现 3 处我自己编造的内容（见 §2.1）；
3. **对自己写的门禁做假绿测试**——结果抓到 1 个永不报警的假门禁（见 §2.2）。

**建议他人评审的重点**（按价值排序）：

- §3.1 `baseUrl` 从「可选 + 内部回落 env」改为「类型必填」，这是本次唯一的**对外行为变更**，我判断它不影响现有宿主（chat 已显式传值），但这个判断没有第三方确认；
- §3.2 `createRunStore` 替代 TanStack Query 的取舍；
- `entities/` 层保留空位次是否合理（我按 surgical changes 原则保留，也可以主张一并清理）。

## 1. 核对范围

34 文件改动（364 插入 / 368 删除，其中 11 个为 git 识别的 rename），逐文件读过 diff。四份规则 / wiki 文档、core README、5 个门禁脚本。

## 2. 自评审发现的缺陷（均已修）

### 2.1 我自己写的文档有 3 处不实内容

按 Hashimoto 法则，这类错误最该警惕——它不会让任何门禁变红，只会误导后来人。

| 位置 | 我写的 | 实际 | 处理 |
|---|---|---|---|
| `contracts.md` §1、`coding-standard.md` §2、core README | 「前端 **9 个**投影全在 core」 | 只有 **6 个**（ui-schema + 5 个）。`tool-manifest` / `tool-search` / `tool-invoke` 是 Runtime ↔ Registry ↔ Gateway 的后端内部契约，前端拿不到也不该拿到 | 三处全部改为准确表述并点明为什么没有前端投影 |
| core README「公共 API」 | 列出 `ToolManifestSchema`、`ToolSearchSchema`、`ToolInvokeSchema` | **这三个导出不存在**，是我按上一条的错误推论编的 | 对照 `grep -E '^export'` 的真实输出逐一改写 |
| core README「安装」 | 「peer 全部 `optional`，只用 `./client` 时不会因为缺 antd 报警」 | `peerDependenciesMeta` 当时是 **undefined**，这句是假的 | 见 §2.3——选择把配置改成与承诺一致，而不是删掉承诺 |

顺带修掉一处与本次改造无关但已腐烂的数字：`project-structure.md` 写「index.ts 唯一公共入口（17 运行时 + 19 类型导出）」，实测 19 + 23。改为不带数字的表述 + 指向 `verify-pack.mjs` 这个真源，避免同类腐烂再发生。

**教训**：我写文档时是在描述「我以为的设计」，不是在描述代码。凡涉及具体数字、具体导出名、具体行为承诺，必须当场 grep 核对。

### 2.2 我自己写的门禁有 1 个是假绿（本轮最危险的发现）

新增 `verify-pack` 检查 (h)「三入口外部依赖闭包」后，我做自证：给 `client/runStore.ts` 插入 `export { useState } from 'react'`，重新构建，跑门禁——**全绿**。

如果我当时接受这个结果，就会留下一个**永远不会报警的门禁**，比没有门禁更危险：它会让后来人（包括我自己）相信 headless 边界有产物级保护，而实际上没有。

根因：rollup 把「只为副作用保留的外部依赖」编译成**无 `from` 关键字的裸导入** `import "react";`，而我的正则是 `/from\s*['"]([^'"]+)['"]/`，漏掉这种形态。

修法：改用 `es-module-lexer`（脚本已依赖且已 `await init`），它是真正的 ES 模块解析器，三种形态（`import x from`、`import '...'`、动态 `import()`）全覆盖。重新自证两种形态：

```
自证 A（裸导入，原先漏检的形态）：
  ✗ (h) client/index.js external deps = [react, zod], expected [zod]
自证 B（带 from 的真实使用）：
  ✗ (h) client/index.js external deps = [react, zod], expected [zod]
恢复后：
  ✓ (h) client/index.js external deps = [zod]
```

**教训**：自证必须"看到红"才算通过。自证全绿有两种可能——代码没问题，或门禁没作用——不能默认是前者。已把这条写进脚本注释，说明第一版栽在哪。

### 2.3 承诺与配置不一致：peer 未标 optional

core README 承诺「只装 `./client` 不会因为缺 antd / react 报警」，但 `peerDependenciesMeta` 缺失，包管理器会照常告警。

选择改配置而非删承诺——antd / antd-mobile / react / react-dom 对 `./client` 消费者确实是可选的，这是三入口拆分的应有之义。`zod` 保持必填（三个入口都要）。

加 `verify-pack` 断言守住它，双向自证：

```
去掉 antd 的 optional → ✗ not-optional=[antd]
给 zod 标 optional    → ✗ zodMarked=true
恢复                  → ✓ render-layer peers are optional, zod stays required
```

### 2.4 两个门禁脚本引用已删除模块（T06 才暴露）

`verify-examples.ts`（`@entities/agent-run`）、`verify-transport.ts`（`@shared/api`）。

`verify-transport.ts` 不是改 import 就完事——它有一条断言「缺省 `baseUrl` 时回落 env 而不是 `''`」（评审 M-1 的原始命题），而**该不变量的落点已经换层**：core 侧 `baseUrl` 现在必填、不读 env，回落搬到了 `AgentChatPanel`。

如果只改 import 让它编译通过，这条断言就会变成在错误的层验一个已经不成立的命题——**看起来还在守，实际守空了**。拆成两层验：core 侧验前缀拼接与 `''` 同源语义，宿主侧验 `baseUrl ?? env.VITE_API_BASE_URL` 这行还在。5 项全绿。

### 2.5 死配置：失效的路径别名

`apps/chat` 的 `vite.config.ts` 与 `tsconfig.json` 仍有 `@entities` → `./src/entities`（目录已删）。typecheck 不会报（没人 import 了），但会把后来人指向一个空目录。删除，并在 vite.config 留一行注释说明为什么没有这个别名。

`.oxlintrc.json` 与 `check-deps.mjs` 里的 `@entities/*` 规则**保留**——那是 FSD 分层红线，与目录是否存在无关，且属改动前已有配置（surgical changes）。已确认 `check-deps.mjs` 的层级表是前缀匹配，空目录不会让它失败。

### 2.6 oxlint 规则过时地禁掉了合法入口

`{"group": ["@spark-ui/core/*"]}` 是 core 只有单入口时写的，会连 `./client` / `./react` 一起禁。精确化为只禁 `src` / `src/*` / `dist` / `dist/*`——真正该禁的是**绕过公共 API**，不是所有子路径。4 处 override 同步。

### 2.7 声明了入口但产物里没有

`publishConfig` 加了 `./client` / `./react`，但 vite `lib.entry` 还是单入口字符串，`pnpm pack` 出来这两个入口是死链。改为对象形式三入口，并把「四项必须在 `exports` / `publishConfig` / `lib.entry` 三处同时存在」写进 `project-structure.md`。

## 3. 设计取舍复核（建议他人重点看这两条）

### 3.1 `baseUrl` 改为类型必填 —— 本次唯一的对外行为变更

**改动**：原 `baseUrl?: string`，内部 `baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? ''`；现 `baseUrl: string`（必填，同源显式传 `''`），不读 env。

**理由**：headless 层读 `import.meta.env` 有两个问题——(1) 它是 Vite 构建期注入，非 Vite 工程（Node、Webpack、纯 TS）拿不到，与「任何 TS 工程都能用」直接冲突；(2) 缺省值是**应用层决策**，包不该替宿主决定。

**我的判断**：不影响现有宿主。唯一消费方 `AgentChatPanel` 已显式传 `baseUrl ?? env.VITE_API_BASE_URL`，行为与改动前逐字节一致；`verify-transport` 第 5 项断言这行还在。

**为什么需要他人看**：这是我自己既做改动又做判断的地方。`''` 与 `undefined` 的语义区分（前者是「显式同源」，后者在改动前会触发 env 回落）容易被后来人"顺手简化"成 `baseUrl || ''`，那会静默短路宿主配置。已有断言守护，但断言是我写的。

### 3.2 删掉 TanStack Query

原实现只用 `setQueryData` / `getQueryData` 当状态容器；缓存失效、重试、后台刷新、`staleTime` 一个都没用上。根因是 **SSE 是推送模型**：视图状态完全由事件序列决定，不存在「数据过期需要重新获取」这一语义。

为这点功能让 headless 层背一个 React 专属依赖，会让「非 React 宿主只装 `./client`」直接落空。替换为 25 行 `createRunStore`（接口形状对齐 `useSyncExternalStore` 的 `subscribe` / `getSnapshot`，自身不依赖 React）。

已在 `coding-standard.md` §4 登记决策与复原条件（「将来若出现真正的请求-缓存-失效场景，以 change 引入」）。

**风险**：自研状态容器少了 TanStack 的成熟度。缓解——它只有 25 行、6 个单测覆盖（含「引用相等不通知」与「监听器在回调内取消订阅」两个边界），且 `runView` 的 24 个归约用例全部保留。

## 4. 契约与安全边界核对

| 项 | 结论 |
|---|---|
| `.harness/contracts/` 文件改动 | **零**。只改投影位置，契约本身一字未动（`check-contracts` 0） |
| 契约投影是否仍被机械校验 | 是。`verify-examples` 23 examples OK + 4 invalid rejected，且它现在从 `@spark-ui/core/client` 取投影 |
| 前端是否仍只发自然语言 | 是。`buildIntentRequest` 原样搬迁，无字段增减 |
| `confirmationToken` 是否仍只回传不解析 | 是。`useSparkRun` 只做 `action.confirmationToken` 透传 |
| 一次性令牌是否仍被本地必填校验保护 | 是。`missingRequiredFields` 随迁，缺失时抛 `FormIncompleteError` 且**不发请求** |
| 渲染层白名单 | 未触碰。`check-registry` 5 组件一致 |
| 身份边界（内核不识别用户） | 未触碰。`baseUrl` / `fetch` 由宿主注入，包内无身份概念 |

## 5. 新增 / 强化的机械门禁（全部双向自证）

| 门禁 | 守什么 | 自证 |
|---|---|---|
| `check-deps.mjs` 第 3 组 | `client/` 与 `react/` 禁 import 渲染层入口 `../index` | ✓ 含测试文件、含 `import type` 两种形态均变红 |
| `check-deps.mjs` 第 2 组（已有，本次覆盖面扩大） | `client/` 禁 react / `import.meta.env` | ✓ 变红 |
| `verify-pack` (b) 新增 | 渲染层 peer 标 optional、zod 必填 | ✓ 双向变红 |
| `verify-pack` (h) 新增 | 三入口外部依赖闭包（`./client` 只需 zod） | ✓ 两种 import 形态均变红（第一版假绿已修） |
| `verify-transport`（重写） | core 侧前缀拼接 + `''` 语义；宿主侧 env 回落 | 5 项全绿 |
| 红线 12（`project-structure.md`） | 上述 headless 边界的规则化表述 | 对应 `check-deps.mjs` |

`check-deps.mjs` 的 `import type` 处理值得说明：第 2 组检查用 `runtimeImports()`（跳过 type-only，因为对「运行时依赖」语义正确），但第 3 组**必须连 type-only 一起拦**——vite / vitest 按模块图解析，`import type { X } from '../index'` 同样会加载该入口并拉进 antd。这正是我原先踩坑的写法。两组用不同策略，注释已说明原因。

## 6. 验收实测

| 门禁 | 目标 | 实测 |
|---|---|---|
| `pnpm -C spark-ui run ci` | 0 | **0** |
| `pnpm -C .harness run doctor` | 0 | **0 errors, 0 warnings** |
| `pnpm -C .harness run ci`（仓库根全量，含后端 Maven） | 0 | **0** |
| `pnpm -C spark-ui run test` | ≥ 100 | **106 passed / 10 文件** |
| `e2e-frontend.sh` | 7 passed | **7 passed** |
| `e2e-backend.sh` | 161 passed, 0 failed | **161 passed, 0 failed** |
| `deploy-verify.sh` | 12 passed, 0 failed | **12 passed, 0 failed** |

### 「纯重构」的三层证据

1. **git 层**：11 个文件被识别为 rename，34 文件净 −4 行；
2. **测试层**：69 个迁移用例逐文件对齐（`runView` 精确 24 → 24，评审 S-2 要求的数字断言），总数 100 → 106（+6 为新增 `runStore` 的测试）；
3. **产物层**：依赖闭包 `./client` = [zod]、`./react` = [react, zod]、`.` = [antd, antd-mobile, react, zod]，与设计意图逐项吻合，且 zod 正确外部化（未打进包，否则宿主与本包 schema 实例不一致）。

## 7. 结论

- **MUST FIX**：0 项遗留（§2 的 7 项全部已修并回归）。
- **建议他人复核**：§3.1（`baseUrl` 行为变更）、§3.2（状态容器取舍）、`entities/` 空层保留。
- **阻塞项**：无。

## 8. 遗留与已知限制

1. **本评审非独立**（见 §0）。
2. **commit 仅本地**，未 push。依公司规则「仅在用户明确要求时执行 git commit / git push」与「禁止直接 push 到主分支」。
3. `verify-pack` 保留但**不发包**（用户决策：本仓仅提供代码）。它验的是「包结构自洽」，对自取源码构建的人同样有意义——尤其 (h) 的依赖闭包断言。
4. `verify-pack.baseline.json` 47 → 80 KB：**代码位置变更而非体积增长**（同一批代码从不计入包体积的 `apps/chat` 移入计入的 `packages/core`，chat 侧相应减少）。已在 baseline 文件内注明理由。
5. `apps/chat` UI 代码一行未动（T06 明确要求），仅换 import 来源。
