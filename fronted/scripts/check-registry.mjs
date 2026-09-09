#!/usr/bin/env node
/**
 * node scripts/check-registry.mjs
 *
 * 校验 Strato UI 注册表三方一致（project-structure §1）：
 *   ui-schema.schema.json 的 componentType enum
 *   == packages/core/src/registry/componentRegistry.ts 的 desktopRegistry 键
 *   == mobileRegistry 键
 *   == packages/core/src/registry/types.ts 的 PROPS_SCHEMAS 键
 *   == packages/core/src/schema/uiSchema.ts 的 COMPONENT_TYPES（整体校验白名单 / clientCapabilities）
 * 并检查 components/desktop/ 与 components/mobile/ 下每个 type 都有对应实现文件。
 * 红线（coding-standard §4，spec feat-commerce-domains v3.2）：core 组件只能是 antd / antd-mobile 官方组件的直接映射——
 *   components/{desktop,mobile}/*.tsx 文件名 ∈ 契约 enum ∪ {ActionBar}（禁止业务命名组件）；
 *   这些文件只允许 import 'antd' / 'antd-mobile' / 'react' / '../../registry/*' / '../../schema/*'（不许引第三方或业务模块）。
 * 纯文本解析，不执行 TS；退出码 0 = 一致。
 */
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const fronted = join(__dirname, '..');
const schemaPath = join(fronted, '..', '.harness', 'contracts', 'ui-schema.schema.json');
const core = join(fronted, 'packages', 'core', 'src');
const registryPath = join(core, 'registry', 'componentRegistry.ts');
const typesPath = join(core, 'registry', 'types.ts');
const uiSchemaPath = join(core, 'schema', 'uiSchema.ts');

let errors = 0;
const fail = (m) => {
  console.error(`✗ ${m}`);
  errors++;
};

const schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));
const contractTypes = schema.$defs.componentType.enum;

const registrySrc = readFileSync(registryPath, 'utf-8');
function keysOf(constName) {
  const m = registrySrc.match(
    // 接受 `= {…};` 与 `= Object.freeze({…});` 两种写法
    new RegExp(
      `export const ${constName}: Registry = (?:Object\\.freeze\\()?\\{([\\s\\S]*?)\\n\\}\\)?;`,
    ),
  );
  if (!m) {
    fail(`cannot find ${constName} in componentRegistry.ts`);
    return [];
  }
  return [...m[1].matchAll(/^\s{2}([A-Za-z]+):/gm)].map((x) => x[1]);
}
const desktop = keysOf('desktopRegistry');
const mobile = keysOf('mobileRegistry');

const typesSrc = readFileSync(typesPath, 'utf-8');
// PROPS_SCHEMAS 真源在 schema/uiSchema.ts（types.ts 只 re-export）；接受 `= {…} as const;` 与 `= Object.freeze({…} as const);` 两种写法
const propsSrc = readFileSync(uiSchemaPath, 'utf-8');
if (!/export \{ PROPS_SCHEMAS \} from '\.\.\/schema\/uiSchema';/.test(typesSrc))
  fail('registry/types.ts must re-export PROPS_SCHEMAS from schema/uiSchema.ts');
const pm = propsSrc.match(
  /export const PROPS_SCHEMAS = (?:Object\.freeze\()?\{([\s\S]*?)\n\}(?: as const)?\)?;/,
);
const propsKeys = pm
  ? [...pm[1].matchAll(/^\s{2}([A-Za-z]+):/gm)].map((x) => x[1])
  : (fail('cannot find PROPS_SCHEMAS'), []);

const same = (a, b) => {
  const sa = a.toSorted();
  const sb = b.toSorted();
  return sa.length === sb.length && sa.every((v, i) => v === sb[i]);
};
if (!same(contractTypes, desktop))
  fail(
    `desktopRegistry keys ${JSON.stringify(desktop)} != contract enum ${JSON.stringify(contractTypes)}`,
  );
if (!same(contractTypes, mobile))
  fail(
    `mobileRegistry keys ${JSON.stringify(mobile)} != contract enum ${JSON.stringify(contractTypes)}`,
  );
const uiSrc = readFileSync(uiSchemaPath, 'utf-8');
const cm = uiSrc.match(/export const COMPONENT_TYPES = \[([\s\S]*?)\] as const;/);
const componentTypes = cm
  ? [...cm[1].matchAll(/'([A-Za-z]+)'/g)].map((x) => x[1])
  : (fail('cannot find COMPONENT_TYPES in schema/uiSchema.ts'), []);
if (!same(contractTypes, componentTypes))
  fail(
    `COMPONENT_TYPES ${JSON.stringify(componentTypes)} != contract enum ${JSON.stringify(contractTypes)}`,
  );
if (!same(contractTypes, propsKeys))
  fail(
    `PROPS_SCHEMAS keys ${JSON.stringify(propsKeys)} != contract enum ${JSON.stringify(contractTypes)}`,
  );

for (const t of contractTypes) {
  for (const side of ['desktop', 'mobile']) {
    const f = join(core, 'components', side, `${t}.tsx`);
    if (!existsSync(f)) fail(`missing implementation ${side}/${t}.tsx`);
  }
}

// 官方组件映射红线：文件名白名单 + import 白名单
const ALLOWED_FILES = new Set([...contractTypes, 'ActionBar']);
const ALLOWED_IMPORT =
  /^(antd|antd-mobile|react|\.\.\/\.\.\/registry\/[a-zA-Z]+|\.\.\/\.\.\/schema\/[a-zA-Z]+)$/;
for (const side of ['desktop', 'mobile']) {
  const dir = join(core, 'components', side);
  for (const f of readdirSync(dir)) {
    const name = f.replace(/\.tsx$/, '');
    if (!f.endsWith('.tsx') || !ALLOWED_FILES.has(name)) {
      fail(
        `components/${side}/${f}: 组件文件名必须是契约 type（官方组件名）或 ActionBar，禁止业务命名组件`,
      );
    }
    if (statSync(join(dir, f)).isDirectory()) {
      fail(`components/${side}/${f}: 组件目录下不得有子目录（每个 type 一个文件）`);
      continue;
    }
    const src = readFileSync(join(dir, f), 'utf-8');
    // 覆盖 import / export … from / 动态 import() / require()；任一来源不在白名单即红
    const specifiers = [
      ...src.matchAll(/^\s*(?:import|export)[^'"]*?\bfrom\s*['"]([^'"]+)['"]/gm),
      ...src.matchAll(/^\s*import\s*['"]([^'"]+)['"]/gm),
      ...src.matchAll(/\bimport\(\s*['"]([^'"]+)['"]\s*\)/g),
      ...src.matchAll(/\brequire\(\s*['"]([^'"]+)['"]\s*\)/g),
    ];
    for (const m of specifiers) {
      if (!ALLOWED_IMPORT.test(m[1]))
        fail(
          `components/${side}/${f}: 不允许引用 '${m[1]}'（只能 antd / antd-mobile / react / 本包 registry / schema）`,
        );
    }
  }
}

if (errors > 0) {
  console.error(`\ncheck-registry: ${errors} errors`);
  process.exit(1);
}
console.log(
  `✓ check-registry: ${contractTypes.length} component types consistent across contract / COMPONENT_TYPES / desktop / mobile / props; component files are official-component mappings`,
);
