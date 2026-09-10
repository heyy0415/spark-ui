#!/usr/bin/env node
/**
 * node .harness/scripts/check-seed.mjs
 *
 * 校验四个领域的 mock 种子（spec feat-commerce-domains §2.2）：
 *   1. 每个 json 的键集合 == 同名表 DDL 列集合（DDL 硬格式：每列独占一行、列名反引号、约束行以 PRIMARY KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN 开头、无行尾注释）
 *   2. 外键可解析：order_items.order_id / logistics_events.order_id / aftersales.order_id / refunds.order_id → orders；order_items.product_id → products
 *   3. 订单金额 == Σ order_items.line_amount，且 line_amount == unit_price × quantity
 *   4. 状态-物流一致：PAID / CANCELLED 无物流事件；SHIPPED / COMPLETED / REFUNDED 事件 ≥ 3；同单 seq 从 1 连续
 *   5. 同表 created_at 严格递增（orders）；手机号 ^1\d{2}\*{4}\d{4}$
 *   6. 夹具表（状态 + 金额 + 相对位置）；10030 为最大 created_at 且 SHIPPED；10029 COMPLETED；10011–10028 五状态各 ≥ 2
 *   7. 每单进行中售后（SUBMITTED / APPROVED）≤ 1；退款一单一条且只挂 REFUNDED 单
 * 退出码 0 = 全部通过。
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const dataDir = (svc) => join(root, 'spark-rooter', 'examples', 'domains', `${svc}-service`, 'src', 'main', 'resources', 'data');
let errors = 0;
const fail = (m) => {
  console.error(`✗ ${m}`);
  errors++;
};
const ok = (m) => console.log(`✓ ${m}`);
const load = (svc, f) => JSON.parse(readFileSync(join(dataDir(svc), f), 'utf-8'));

// ---- 1. DDL 列 == json 键
function parseDdl(svc) {
  const text = readFileSync(join(dataDir(svc), 'schema.sql'), 'utf-8');
  const tables = {};
  let cur = null;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (line === '' || line.startsWith('--')) continue;
    if (/--/.test(line)) fail(`${svc}/schema.sql: 行尾注释不允许: ${line}`);
    const m = line.match(/^CREATE TABLE IF NOT EXISTS `([a-z_]+)` \($/);
    if (m) {
      cur = m[1];
      tables[cur] = [];
      continue;
    }
    if (line.startsWith(')')) {
      cur = null;
      continue;
    }
    if (!cur) continue;
    if (/^(PRIMARY KEY|UNIQUE|KEY|INDEX|CONSTRAINT|FOREIGN)/.test(line)) continue;
    const col = line.match(/^`([a-z_]+)` /);
    if (!col) fail(`${svc}/schema.sql: 列定义必须以反引号列名开头: ${line}`);
    else tables[cur].push(col[1]);
  }
  return tables;
}
const TABLES = {
  order: ['orders', 'order_items', 'logistics_events'],
  product: ['products'],
  aftersale: ['aftersales'],
  refund: ['refunds'],
};
const data = {};
for (const [svc, tbls] of Object.entries(TABLES)) {
  const ddl = parseDdl(svc);
  for (const t of tbls) {
    const rows = load(svc, `${t}.json`);
    data[t] = rows;
    if (!ddl[t]) {
      fail(`${svc}: DDL 缺表 ${t}`);
      continue;
    }
    const cols = [...ddl[t]].sort().join(',');
    rows.forEach((r, i) => {
      const keys = Object.keys(r).sort().join(',');
      if (keys !== cols) fail(`${t}.json[${i}] 键 {${keys}} != DDL 列 {${cols}}`);
    });
  }
}
ok(`DDL 列 == json 键（${Object.values(TABLES).flat().length} 表）`);

const orders = data.orders;
const byId = Object.fromEntries(orders.map((o) => [o.order_id, o]));
const products = new Set(data.products.map((p) => p.product_id));

// ---- 2. 外键
for (const [t, col, target] of [
  ['order_items', 'order_id', byId],
  ['logistics_events', 'order_id', byId],
  ['aftersales', 'order_id', byId],
  ['refunds', 'order_id', byId],
]) {
  data[t].forEach((r) => {
    if (!target[r[col]]) fail(`${t}.${col}=${r[col]} 悬空`);
  });
}
data.order_items.forEach((r) => {
  if (!products.has(r.product_id)) fail(`order_items.product_id=${r.product_id} 悬空`);
});
ok('外键全部可解析');

// ---- 3. 金额
const cents = (s) => Math.round(Number(s) * 100);
for (const o of orders) {
  const items = data.order_items.filter((i) => i.order_id === o.order_id);
  if (items.length === 0) fail(`订单 ${o.order_id} 无商品行`);
  const sum = items.reduce((s, i) => s + cents(i.line_amount), 0);
  if (sum !== cents(o.amount)) fail(`订单 ${o.order_id} 金额 ${o.amount} != Σ line_amount ${(sum / 100).toFixed(2)}`);
  items.forEach((i) => {
    if (cents(i.line_amount) !== Math.round(cents(i.unit_price) * i.quantity)) fail(`${i.item_id} line_amount != unit_price×quantity`);
  });
  if (o.quantity !== items.reduce((s, i) => s + i.quantity, 0)) fail(`订单 ${o.order_id} quantity 与行不符`);
}
ok('订单金额 == Σ 行金额；行金额 == 单价×数量');

// ---- 4. 状态-物流
for (const o of orders) {
  const ev = data.logistics_events.filter((e) => e.order_id === o.order_id).sort((a, b) => a.seq - b.seq);
  if (['PAID', 'CANCELLED'].includes(o.status) && ev.length > 0) fail(`订单 ${o.order_id}(${o.status}) 不应有物流事件`);
  if (['SHIPPED', 'COMPLETED', 'REFUNDED'].includes(o.status) && ev.length < 3) fail(`订单 ${o.order_id}(${o.status}) 物流事件 ${ev.length} < 3`);
  ev.forEach((e, i) => {
    if (e.seq !== i + 1) fail(`订单 ${o.order_id} 物流 seq 不连续`);
  });
}
ok('状态-物流一致');

// ---- 5. created_at 递增；手机号
for (let i = 1; i < orders.length; i++) {
  if (!(orders[i].created_at > orders[i - 1].created_at)) fail(`orders[${i}] created_at 未严格递增`);
}
orders.forEach((o) => {
  if (!/^1\d{2}\*{4}\d{4}$/.test(o.phone_masked)) fail(`订单 ${o.order_id} 手机号未脱敏: ${o.phone_masked}`);
});
ok('created_at 严格递增；手机号已脱敏');

// ---- 6. 夹具表
const FIX = {
  10001: ['PAID', '128.00'],
  10002: ['SHIPPED', '299.00'],
  10003: ['PAID', '1.00'],
  10004: ['PAID', '59.00'],
  10005: ['COMPLETED', null],
  10006: ['PAID', '88.00'],
  10007: ['REFUNDED', null],
  10008: ['REFUNDED', null],
  10009: ['REFUNDED', null],
  10010: ['COMPLETED', null],
  10029: ['COMPLETED', null],
  10030: ['SHIPPED', null],
};
for (const [id, [st, amt]] of Object.entries(FIX)) {
  const o = byId[id];
  if (!o) {
    fail(`夹具 ${id} 不存在`);
    continue;
  }
  if (o.status !== st) fail(`夹具 ${id} 状态 ${o.status} != ${st}`);
  if (amt && o.amount !== amt) fail(`夹具 ${id} 金额 ${o.amount} != ${amt}`);
}
const newest = orders.reduce((a, b) => (a.created_at > b.created_at ? a : b));
if (newest.order_id !== '10030') fail(`最新订单应为 10030，实际 ${newest.order_id}`);
if (orders.length !== 30) fail(`订单数 ${orders.length} != 30`);
if (data.products.length !== 20) fail(`商品数 ${data.products.length} != 20`);
const cnt = {};
orders.filter((o) => +o.order_id >= 10011 && +o.order_id <= 10028).forEach((o) => (cnt[o.status] = (cnt[o.status] || 0) + 1));
for (const st of ['PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED', 'CANCELLED']) {
  if ((cnt[st] || 0) < 2) fail(`可见区状态 ${st} 只有 ${cnt[st] || 0} 单 (< 2)`);
}
ok('夹具表与可见区分布');

// ---- 7. 售后 / 退款约束
const ACTIVE = new Set(['SUBMITTED', 'APPROVED']);
const activeBy = {};
data.aftersales.forEach((a) => {
  if (!['SUBMITTED', 'APPROVED', 'REJECTED', 'COMPLETED', 'CANCELLED'].includes(a.status)) fail(`售后 ${a.aftersale_id} 状态非法 ${a.status}`);
  if (ACTIVE.has(a.status)) activeBy[a.order_id] = (activeBy[a.order_id] || 0) + 1;
});
Object.entries(activeBy).forEach(([oid, n]) => {
  if (n > 1) fail(`订单 ${oid} 进行中售后 ${n} > 1`);
});
if (data.aftersales.length !== 4) fail(`售后数 ${data.aftersales.length} != 4`);
const refundOrders = data.refunds.map((r) => r.order_id);
if (new Set(refundOrders).size !== refundOrders.length) fail('退款单一单多条');
refundOrders.forEach((oid) => {
  if (byId[oid]?.status !== 'REFUNDED') fail(`退款单挂在非 REFUNDED 订单 ${oid}`);
});
if (data.refunds.length !== 3) fail(`退款数 ${data.refunds.length} != 3`);
ok('售后 / 退款约束');

if (errors > 0) {
  console.error(`\ncheck-seed: ${errors} errors`);
  process.exit(1);
}
console.log('check-seed: all seed invariants hold');
