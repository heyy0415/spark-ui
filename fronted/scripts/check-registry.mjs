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
 * 纯文本解析，不执行 TS；退出码 0 = 一致。
 */
import { readFileSync, existsSync } from 'node:fs';
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
const pm = typesSrc.match(/export const PROPS_SCHEMAS = \{([\s\S]*?)\n\} as const;/);
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

if (errors > 0) {
  console.error(`\ncheck-registry: ${errors} errors`);
  process.exit(1);
}
console.log(
  `✓ check-registry: ${contractTypes.length} component types consistent across contract / COMPONENT_TYPES / desktop / mobile / props`,
);
