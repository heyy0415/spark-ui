#!/usr/bin/env node
/**
 * pnpm -C .harness run check-contracts
 *
 * 校验 .harness/contracts/ 真源：
 *   1. 每个 *.schema.json 是合法的 JSON Schema 2020-12（Ajv 编译通过）
 *   2. 每个 Schema 至少有一个 examples/{name}.example.json 或 examples/{name}.*.example.json
 *   3. 每个示例都能通过对应 Schema 校验
 *   4. Schema 有 $id / title / description
 *
 * 退出码 0 = 全部通过。
 */
import { readdir, readFile } from 'node:fs/promises';
import { join, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';

const __dirname = dirname(fileURLToPath(import.meta.url));
const harness = join(__dirname, '..');
const contractsDir = join(harness, 'contracts');
const examplesDir = join(contractsDir, 'examples');

let errors = 0;
const fail = (msg) => {
  console.error(`✗ ${msg}`);
  errors++;
};
const ok = (msg) => console.log(`✓ ${msg}`);

const ajv = new Ajv2020({ strict: true, allErrors: true });
addFormats(ajv);

const schemaFiles = (await readdir(contractsDir)).filter((f) => f.endsWith('.schema.json')).sort();
if (schemaFiles.length === 0) {
  fail('.harness/contracts/ contains no *.schema.json');
}

const exampleFiles = (await readdir(examplesDir).catch(() => [])).filter((f) =>
  f.endsWith('.example.json'),
);

const schemas = new Map();
for (const f of schemaFiles) {
  const raw = await readFile(join(contractsDir, f), 'utf-8');
  let schema;
  try {
    schema = JSON.parse(raw);
  } catch (e) {
    fail(`${f}: invalid JSON (${e.message})`);
    continue;
  }
  for (const key of ['$id', 'title', 'description']) {
    if (!schema[key]) fail(`${f}: missing "${key}"`);
  }
  try {
    ajv.addSchema(schema, schema.$id ?? f);
    schemas.set(f, schema);
  } catch (e) {
    fail(`${f}: cannot add schema (${e.message})`);
  }
}

for (const [f, schema] of schemas) {
  let validate;
  try {
    validate = ajv.compile(schema);
  } catch (e) {
    fail(`${f}: does not compile (${e.message})`);
    continue;
  }
  ok(`schema: ${f}`);

  const stem = basename(f, '.schema.json');
  const mine = exampleFiles.filter((e) => e === `${stem}.example.json` || e.startsWith(`${stem}.`));
  if (mine.length === 0) {
    fail(`${f}: no example found in .harness/contracts/examples/ (expected ${stem}.example.json)`);
    continue;
  }
  for (const e of mine) {
    const data = JSON.parse(await readFile(join(examplesDir, e), 'utf-8'));
    if (validate(data)) {
      ok(`  example: ${e}`);
    } else {
      fail(`  example ${e} violates ${f}:`);
      for (const err of validate.errors ?? []) {
        console.error(`      ${err.instancePath || '/'} ${err.message}`);
      }
    }
  }
}

console.log('');
if (errors > 0) {
  console.error(`check-contracts: ${errors} errors`);
  process.exit(1);
}
console.log(`check-contracts: ${schemas.size} schemas OK`);
