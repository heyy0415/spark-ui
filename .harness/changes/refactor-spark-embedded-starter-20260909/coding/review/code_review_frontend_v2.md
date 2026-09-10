# Code Review (frontend + contracts) v2 — refactor-spark-embedded-starter-20260909

- mode: execution（有罪推定，第二轮：核对 v1 的 1 MUST / 4 SHOULD / 8 LOW 是否真正解决）
- 评审对象：`git diff dc0ab7f^ dc0ab7f -- spark-ui .harness/contracts .harness/scripts/check-contracts.mjs .harness/scripts/check-rename.mjs .harness/mcp .harness/rules/contracts.md`（17 文件，+200 / −23）；对照 `coding_report_v1.md`「阶段 4 回修记录 v1」前端表
- 本机复跑（均在仓库根，退出码为实测）：
  - `node .harness/scripts/check-contracts.mjs` → 9 schemas / 27 examples / 6 rejected，exit 0
  - `node .harness/scripts/check-rename.mjs` → exit 0
  - `pnpm -C spark-ui run verify-examples` → `23 examples OK, 4 invalid rejected`，exit 0
  - `pnpm -C spark-ui typecheck` exit 0；`pnpm -C spark-ui lint`（oxlint + check-deps + check-registry）exit 0
  - 绕过实验：临时移走 `examples/invalid/` 中 4 个前端投影反例、只留 2 个 `tool-*` 反例 → `verify-examples` 输出 `23 examples OK, 0 invalid rejected` exit 0，`check-contracts` exit 0（已原样恢复，`git status` 干净，目录仍 6 文件）
  - e2e-frontend 未复跑（需后端 + vite），只做静态核对

## 逐条核对

| v1 # | 原问题 | 回修声称 | 核对结果 | 证据 |
|---|---|---|---|---|
| M-1 | `baseUrl ?? ''` 永久短路 `VITE_API_BASE_URL` | Panel 缺省 `baseUrl ?? env.VITE_API_BASE_URL` | **已解决**（主体）；附带要求「加一条门禁」**未做** | `AgentChatPanel.tsx:35` `baseUrl: baseUrl ?? env.VITE_API_BASE_URL`；`import { env } from '@shared/config'`。FSD：`scripts/check-deps.mjs` `banned['features/'] = ['@app/', '@pages/']`，features → shared 是允许方向，`check-deps` 实跑 ✓。`sseClient.ts:39` / `httpClient.ts:47` 仍保留 `?? env.VITE_API_BASE_URL` 回落，双保险。但 `spark-ui` 内 `find -name "*.test.*"` 0 文件、`e2e-frontend.mjs` 无 `VITE_API_BASE_URL` 断言——v1 要求的「非空时请求 URL 以之为前缀」回归门禁未落地（见 N-1） |
| S-1 | `getRun` 绕开 `request()`，裸 `Error`、不读 env；`request()` 成死码 | `RequestOptions` 增 `fetch? / baseUrl?`，`getRun` 回到 `request()` | **已解决** | `httpClient.ts:23-26` 新增两个可选项；`agentRunApi.ts:16-20` 经 `request(path, { schema, baseUrl, fetch })`；非 2xx → `HttpError(status, msg, json)`；schema 不匹配 → `HttpError` + `[http-client]` 日志。错误语义与 SSE 路径一致。遗留：`request()` 在 `!res.ok` 之前先 `JSON.parse(text)`，网关返回 HTML 错误页时抛 `SyntaxError` 而非 `HttpError`（改前即如此，`sseClient` 已 try/catch，`httpClient` 未对齐，见 N-3）；`getRun` 仍 0 调用方（N-4） |
| S-2 | 破坏性变更原地改 v1 无说明 | §5a 补说明 | **已解决** | `contracts.md:60` 一段：破坏性变更、无外部消费方、`$id` 仍 `/v1/`、首个外部消费方后必须发 `/v2/`。与 §3 规则 + §4 先例一致 |
| S-3 | `check-rename` 按子串放行根目录名，掩盖 `servers.json` 本机路径 | 删根目录名放行；`servers.json` 改 `"."` | **已解决** | `check-rename.mjs` 已无 `basename` / `ROOT_DIR_NAME`，`line = lines[i]` 直接匹配；`servers.json:7` `"."` + `_note`。其余放行项复核：`SKIP_DIRS` 中 `.idea/.vscode` 在 `.gitignore`，`.claude` 下 `git ls-files .claude` 为空（`settings.local.json` 被忽略，其内含本机路径不入库）；`.harness/changes` 为历史记录，合理。`text.includes('\0')` 二进制跳过是**唯一**剩余放行——脚本源码里写的是字面 NUL 字节，副作用见 N-5 |
| S-4 | 门禁只证明合法示例通过，投影放宽不可见 | `examples/invalid/` 6 条；两端断言拒绝 | **基本解决**，绕过面见 N-2 | 覆盖对照 v1 指出的 5 处变更：pageContext 残留 ✓（`intent-request.page-context`）、principal 残留 ✓（`tool-search.principal`）、executionContext 旧字段 / 缺 sessionId ✓（`tool-invoke.user-id` 同时命中 `additionalProperties:false` 与 `required: sessionId`）、Card.actions 7 项 ✓（`ui-schema.card-actions-7`）、`tool-manifest.permission` 改可选属放宽、无反例可写（合理）；额外 2 条：components 重复、intent 含 URL。`check-contracts.mjs:94-108`：目录为空 / 不存在 → `fail`（`readdir(...).catch(() => [])` 后 `length === 0` 判红）✓；stem 无对应 schema → `fail` ✓。`verify-examples.ts:71` `readdirSync(invalidDir)` 无 catch → 目录不存在时 ENOENT 崩溃，退出码非 0，fail-closed ✓。**contracts-java 打包**：`spark-rooter-contracts/pom.xml:32-35` `<include>examples/*.example.json</include>`、antrun `fileset includes="*.example.json"`（非递归）；实测 `target/spark-rooter-contracts-0.1.0-SNAPSHOT.jar` 内 `grep -i invalid` 0 命中，`invalid/` 未混入 jar ✓ |
| L-1 | 三处仍写 `onIntent` 只来自 Table | 「全部修正」 | **部分**（2/3） | `packages/core/README.md:49` ✓、`registry/types.ts:22` ✓；`renderer/SchemaRenderer.tsx:20` 仍为「Table 行内指令点击：回调 intent 原文」未改（N-7） |
| L-2 | e2e 头注释「8 个契约示例」 | 改 9 | **已解决** | `e2e-frontend.mjs:11` 「9 个契约示例 × 1280 / 375」 |
| L-3 | verify-examples 头注释写死 16 | 改动态 | **已解决** | `verify-examples.ts:6-7` 「数量随 …/examples 变化」，输出 `N examples OK, M invalid rejected` |
| L-4 | 基线 `recordedAt` 手写 | 保留（理由：`--write-baseline` 会覆盖 note） | **未修，已说明** | `verify-pack.baseline.json` 仍 `2026-09-10T00:00:00.000Z` + `note`。接受为登记偏差；建议下一 change 让脚本写入时透传 `note`（不再重复计 LOW） |
| L-5 | `components` 缺 uniqueItems | 增 `.refine` | **已解决** | `types.ts:67-71` `.refine((a) => new Set(a).size === a.length)`；反例 `intent-request.duplicate-components` 被 Zod 与 Ajv 双侧拒绝（verify-examples 4 rejected 含之） |
| L-6 | placeholder「这个订单」 | 改 | **已解决** | `AgentChatPanel.tsx:93` 「例如：帮我把订单 10001 退款」 |
| L-7 | §5a 未记 `$id` 前缀 `strato.local → spark-rooter.local` | 「已在 §5a 首段与 T02 提交说明」 | **未解决** | `grep -F '$id' contracts.md` 只命中 §3 规则两行与 §5a「`$id` 仍为 `/v1/`」；全文无 `spark-rooter.local` / `strato.local` / `ID_PREFIX`。提交说明不是规则文档，后端 `$ref` 解析失败时仍无处可查（N-7） |
| L-8 | `transport.fetch(...)` 方法调用 this 绑定 | `getRun` 经 `request()` 内 `(doFetch ?? fetch)(…)` | **已解决** | `a ?? b` 表达式求值结果是值而非 Reference，`(doFetch ?? fetch)(url, init)` 是 plain call，`this === undefined`，浏览器按全局对象处理，不会 Illegal invocation；`sseClient.ts:38-39` `const doFetch = …; doFetch(...)` 同理。两条路径行为一致。副作用：Panel 注释「注入的 fetch 需绑定到 globalThis，否则 Illegal invocation」已不成立（N-6） |

## 新发现

| # | 位置 | 问题 | 失败场景 | 建议 | 分级 |
|---|---|---|---|---|---|
| N-1 | `spark-ui/`（无测试文件）；`.harness/scripts/e2e-frontend.mjs` | v1 M-1 的修复要求含「加一条门禁：`VITE_API_BASE_URL` 非空时请求 URL 以之为前缀」（Hashimoto 法则）。本轮只改了代码，未加任何断言；`spark-ui` 下无 `*.test.*`，e2e 走 vite proxy 同源，`VITE_API_BASE_URL` 永远为 `''`，同类回退（例如有人再把缺省写回 `''`，或 `Transport.baseUrl` 改成必填后宿主传 `''`）门禁仍全绿 | 与 v1 M-1 完全相同的失效路径再次发生时无人发现 | 最小做法：`scripts/verify-transport.ts`（vite-node，同 verify-examples 机制）用假 `fetch` 记录 URL，断言 `request('/agent/runs/x', { schema, fetch: fake })` 与 `consumeSse({ path, fetch: fake, body })` 在 `import.meta.env.VITE_API_BASE_URL='https://gw.test'` 下请求 `https://gw.test/agent/runs/...`；挂进 `spark-ui` `ci` 链。或在 e2e-frontend 增一步：`VITE_API_BASE_URL=http://localhost:8091` 起 vite，拦截 CDP `Network.requestWillBeSent` 断言前缀 | SHOULD |
| N-2 | `spark-ui/scripts/verify-examples.ts:71-88`；`.harness/scripts/check-contracts.mjs:108` | 反例门禁可被「只删前端投影的反例」绕过：实测移走 4 个 `intent-request.*` / `ui-schema.*` 反例后，`verify-examples` 输出 `23 examples OK, 0 invalid rejected` exit 0；`check-contracts` 因目录非空（剩 2 个 `tool-*`）也 exit 0。两处都只守「目录为空」，不守「每个契约 / 每个前端投影至少一条反例」 | Zod 投影再次放宽（如 `actions.max(7)`）且对应反例被误删 / 改名（`file.split('.')[0]` 拼错即静默 `continue`）时门禁绿 | `verify-examples`：`rejected === 0` 或某个 `schemaFor` 键 0 条反例 → 退出 1；`check-contracts`：改成按 schema 统计，对 §5a 列出的破坏性变更契约（intent-request / tool-search / tool-invoke / ui-schema）要求 ≥ 1 反例；反例文件名无匹配 schema 时 `verify-examples` 也应报错而非 `continue`（`tool-*` 白名单化） | SHOULD |
| N-3 | `spark-ui/apps/chat/src/shared/api/httpClient.ts:49-53` | `JSON.parse(text)` 在 `!res.ok` 判断之前、无 try/catch；网关 / 反代返回非 JSON 错误页（502 HTML、`text/plain` 限流）时抛 `SyntaxError`，绕过 `HttpError`。`sseClient.ts:42-47` 同场景已 try/catch，两条路径不一致。改前即存在，但 S-1 把 `getRun` 迁回 `request()` 后由它继承 | `catch (e) { if (e instanceof HttpError) … }` 对网关层错误失效，落到「请求失败」兜底且丢状态码 | 复用 `sseClient` 的解析方式：`let json: unknown = null; try { json = text ? JSON.parse(text) : null } catch { json = text }`，再判 `res.ok` | LOW |
| N-4 | `spark-ui/apps/chat/src/entities/agent-run/api/agentRunApi.ts:15`；`entities/agent-run/index.ts:26` | `getRun` 全仓 0 调用方（grep 仅定义与 barrel 导出），v1 S-1 给出「无人用则删」的选项，本轮选择保留 | 死出口继续存在；`Transport` 类型因它保留 `baseUrl` 必填而非可选 | 在 summary 登记：留作 run-summary 契约的唯一前端消费点（`verify-examples` 校验 `RunSummarySchema`）；或下一 change 删除 | LOW |
| N-5 | `.harness/scripts/check-rename.mjs:50` | `text.includes('\0')` 写的是**字面 NUL 字节**（`od -c` 可见 `\0`），非转义 `' '`。后果：(1) git 把脚本判为二进制，`git diff` 显示 `Binary files differ`，本轮 S-3 的修改在普通 diff 里不可审（须 `--text`）；(2) 脚本读到自身时因含 NUL 被当二进制跳过，恰好放行了自身头注释里的 `strato` 字样——这是一个隐式自我豁免，不在文档「排除」列表 | 评审者看不到门禁脚本的改动；将来改成转义写法后脚本会自己报红，让人误以为是回归 | 改为 `' '`，并显式 `SKIP_FILES = new Set(['.harness/scripts/check-rename.mjs'])` 或把头注释中的旧名改为 `s-t-r-a-t-o` 之类不触发的写法 | LOW |
| N-6 | `spark-ui/apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx:32` | 注释「注入的 fetch 需绑定到 globalThis，否则 Illegal invocation」在 L-8 修复后已不成立（两条路径都是 plain call）；且代码只对默认值 `bind`，与注释「注入的」主语不符（v1 L-8 已指出注释与行为二选一改齐，本轮只改了行为） | 宿主读注释后多做无意义 `bind`；或反过来相信注释以为传裸 `window.fetch` 会炸 | 改为「默认 `globalThis.fetch.bind(globalThis)` 仅为保守；`request` / `consumeSse` 均以普通函数调用方式使用 fetch，宿主传裸 `window.fetch` 亦可」 | LOW |
| N-7 | `spark-ui/packages/core/src/renderer/SchemaRenderer.tsx:20`；`.harness/rules/contracts.md` §5a | v1 L-1 第三处、L-7 均被回修表标为已处理，实际未改（见核对表） | 回修记录与代码不一致 | `SchemaRenderer.tsx:20` 改「Table 行内 / Card.actions 指令点击」；§5a 表补一行 `全部 9 个 schema | $id 前缀 strato.local → spark-rooter.local；后端 SchemaValidator.ID_PREFIX 同步 | 仓库改名 T02` | LOW |
| I-1 | `AgentChatPanel.tsx:35` + `sseClient.ts:39` / `httpClient.ts:47` | Panel 与 shared/api 两层都做 `?? env.VITE_API_BASE_URL` 回落，`Transport.baseUrl` 实际永不为 `undefined`，shared 层回落成为冗余 | — | 可接受（shared 层保持自洽，供未经 Panel 的调用方使用）；不建议再动 | INFO |
| I-2 | `verify-examples.ts:71` | `readdirSync(invalidDir)` 无 catch，目录缺失直接 ENOENT 退出非 0 | — | fail-closed，符合门禁预期；保留 | INFO |

## verdict: APPROVED（附 2 SHOULD 登记）

- v1 M-1 已解决（`AgentChatPanel` 缺省回落 env，FSD 方向 features → shared 合法且 `check-deps` 实跑通过）；S-1 / S-2 / S-3 已解决；S-4 基本解决（6 条反例覆盖 v1 点名的全部可写反例，`invalid/` 未混入 jar，目录为空已判红）。
- v1 LOW：L-2 / L-3 / L-5 / L-6 / L-8 解决；L-1 部分（1/3 处未改）；L-4 未修但已给出理由（接受为登记偏差）；L-7 未解决（回修表声称已处理，实际 §5a 无该行）。
- 新发现：**MUST 0 / SHOULD 2 / LOW 5 / INFO 2**。
  - N-1（M-1 未按 Hashimoto 法则配门禁）与 N-2（反例门禁只守目录为空，前端投影侧可被绕过——本机已实证）不阻塞本 change 合入，但必须写入 `summary.md` 的遗留项，下一 change 首批处理。
  - N-3 ~ N-7 为 LOW，可随 N-1 / N-2 一并吸收。
- 本轮回修**未引入**功能回退：`typecheck` / `lint` / `verify-examples` / `check-contracts` / `check-rename` 均实测 exit 0；`request()` 的 `fetch` / `baseUrl` 可选项对既有调用方无影响。
