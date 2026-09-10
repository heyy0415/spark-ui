#!/usr/bin/env node
/**
 * node .harness/scripts/gen-seed.mjs
 *
 * 生成四个领域的 mock 种子数据（可复现：固定 PRNG 种子）。生成物提交进仓库，check-seed.mjs 校验。
 * 夹具与可见区状态表（spec feat-commerce-domains §2.2）在本文件以断言写死：
 *   10001 PAID 128.00 | 10002 SHIPPED 299.00 | 10003 PAID 1.00 | 10004 PAID 59.00 | 10005 COMPLETED | 10006 PAID 88.00
 *   10007–10009 REFUNDED（各 1 条退款）| 10010 COMPLETED（1 条 APPROVED 售后）| 10011–10028 五状态各 ≥ 2
 *   10029 COMPLETED | 10030 SHIPPED（created_at 最新）
 * json 键 = MySQL 列名（snake_case）；金额字符串两位小数；时间 ISO-8601。
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const domains = join(root, 'spark-rooter', 'examples', 'domains');
const out = (svc, file, data) => {
  const dir = join(domains, `${svc}-service`, 'src', 'main', 'resources', 'data');
  mkdirSync(dir, { recursive: true });
  writeFileSync(join(dir, file), JSON.stringify(data, null, 2) + '\n');
};

// ---- 确定性 PRNG（mulberry32）
let seed = 20260908;
const rand = () => {
  seed |= 0;
  seed = (seed + 0x6d2b79f5) | 0;
  let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
  t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
};
const pick = (arr) => arr[Math.floor(rand() * arr.length)];
const int = (lo, hi) => lo + Math.floor(rand() * (hi - lo + 1));
const money = (n) => n.toFixed(2);
const iso = (d) => new Date(d).toISOString().replace(/\.\d{3}Z$/, '.000Z');
const TENANT = 'tenant_001';
const PHONE = () => `1${int(30, 89)}****${String(int(0, 9999)).padStart(4, '0')}`;

// ---- 商品 20：4 类，含库存 0
const PRODUCTS = [
  ['P-1001', '智能手机 X1', '4999.00', '数码', 'phone', 35],
  ['P-1002', '轻薄笔记本 14', '6999.00', '数码', 'laptop', 12],
  ['P-1003', '无线耳机 Pro', '899.00', '数码', 'headphones', 120],
  ['P-1004', '智能手表 S', '1299.00', '数码', 'watch', 0],
  ['P-1005', '蓝牙耳机', '299.00', '数码', 'headphones', 80],
  ['P-1006', '北欧风落地灯', '459.00', '家居', 'lamp', 22],
  ['P-1007', '记忆棉枕头', '129.00', '家居', 'home', 200],
  ['P-1008', '香薰机', '169.00', '家居', 'home', 0],
  ['P-1009', '收纳箱三件套', '89.00', '家居', 'box', 300],
  ['P-1010', '保温杯 500ml', '129.00', '家居', 'coffee', 150],
  ['P-1011', '智能台灯', '199.00', '家居', 'lamp', 0],
  ['P-1012', '纯棉 T 恤', '79.00', '服饰', 'shirt', 500],
  ['P-1013', '运动鞋', '459.00', '服饰', 'shoe', 60],
  ['P-1014', '羽绒服', '899.00', '服饰', 'shirt', 18],
  ['P-1015', '牛仔裤', '199.00', '服饰', 'shirt', 90],
  ['P-1016', '手工曲奇礼盒', '68.00', '食品', 'cookie', 400],
  ['P-1017', '精品咖啡豆 500g', '128.00', '食品', 'coffee', 75],
  ['P-1018', '坚果礼盒', '158.00', '食品', 'gift', 0],
  ['P-1019', '有机茶叶', '9.90', '食品', 'coffee', 1000],
  ['P-1020', '巧克力礼盒', '99.00', '食品', 'gift', 230],
];
const products = PRODUCTS.map(([id, title, price, category, thumbnail, stock], i) => ({
  product_id: id,
  title,
  description: `${title}，${category}品类热销款，支持 7 天无理由退换。`,
  price,
  currency: 'CNY',
  stock,
  category,
  thumbnail,
  sales_count: int(50, 5000),
  specs: JSON.stringify(
    category === '数码'
      ? [{ name: '颜色', value: pick(['黑', '白', '银']) }, { name: '保修', value: '12 个月' }]
      : category === '服饰'
        ? [{ name: '尺码', value: pick(['S', 'M', 'L', 'XL']) }, { name: '材质', value: '棉' }]
        : [{ name: '规格', value: pick(['标准', '加大']) }],
  ),
  created_at: iso(Date.UTC(2026, 6, 1 + i)),
}));
const priceOf = Object.fromEntries(PRODUCTS.map(([id, , p]) => [id, Number(p)]));

// ---- 订单 30：夹具表 + 可见区
const STATUSES = ['PAID', 'SHIPPED', 'COMPLETED', 'REFUNDED', 'CANCELLED'];
/** [orderId, status, 固定金额(或 null), 固定商品行(或 null)] */
const FIXTURES = {
  10001: ['PAID', '128.00', [['P-1017', 1]]],
  10002: ['SHIPPED', '299.00', [['P-1005', 1]]],
  10003: ['PAID', '1.00', null], // 自检专用：单行虚拟商品 P-1019 无法凑 1.00，用 P-1019 但金额固定
  10004: ['PAID', '59.00', null],
  10005: ['COMPLETED', null, [['P-1013', 1]]],
  10006: ['PAID', '88.00', null],
  10007: ['REFUNDED', null, null],
  10008: ['REFUNDED', null, null],
  10009: ['REFUNDED', null, null],
  10010: ['COMPLETED', null, null],
  10029: ['COMPLETED', null, null],
  10030: ['SHIPPED', null, [['P-1003', 1]]],
};
// 可见区 10011–10028：18 单，五状态各 ≥ 2（先各放 2，再随机 8）
const visible = [...STATUSES, ...STATUSES];
while (visible.length < 18) visible.push(pick(STATUSES));

const orders = [];
const orderItems = [];
const logisticsEvents = [];
const base = Date.UTC(2026, 7, 1, 8, 0, 0); // 2026-08-01T08:00Z，每单 +（29~31h）保证严格递增
let t = base;
for (let n = 10001; n <= 10030; n++) {
  const id = String(n);
  t += (29 + int(0, 2)) * 3600_000 + int(0, 3599) * 1000;
  const fx = FIXTURES[id];
  const status = fx ? fx[0] : visible[n - 10011];
  // 商品行
  let lines = fx?.[2];
  if (!lines) {
    const k = int(1, 3);
    lines = Array.from({ length: k }, () => [pick(PRODUCTS)[0], int(1, 2)]);
  }
  // 金额：夹具固定金额时用「单行虚拟单价」凑齐（单价 = 金额 / 数量，item 行独立记录单价，check-seed 校验 Σ 行 == 订单）
  let amount;
  if (fx?.[1]) {
    amount = Number(fx[1]);
    if (!fx[2]) lines = [[id === '10003' ? 'P-1019' : 'P-1016', 1]];
  } else {
    amount = lines.reduce((s, [pid, q]) => s + priceOf[pid] * q, 0);
  }
  const first = PRODUCTS.find((p) => p[0] === lines[0][0]);
  orders.push({
    order_id: id,
    tenant_id: TENANT,
    user_id: 'user_001',
    product_name: first[1] + (lines.length > 1 ? ` 等 ${lines.length} 件` : ''),
    thumbnail: first[4],
    quantity: lines.reduce((s, [, q]) => s + q, 0),
    amount: money(amount),
    currency: 'CNY',
    status,
    receiver: pick(['张伟', '李娜', '王芳', '刘洋', '陈静']),
    phone_masked: PHONE(),
    region: pick(['上海市 浦东新区', '北京市 海淀区', '深圳市 南山区', '杭州市 西湖区']),
    created_at: iso(t),
  });
  lines.forEach(([pid, q], idx) => {
    const unit = fx?.[1] ? amount / q : priceOf[pid];
    orderItems.push({
      item_id: `${id}-${idx + 1}`,
      order_id: id,
      product_id: pid,
      product_name: PRODUCTS.find((p) => p[0] === pid)[1],
      unit_price: money(unit),
      quantity: q,
      line_amount: money(unit * q),
    });
  });
  // 物流：SHIPPED / COMPLETED 3~6 条；REFUNDED 也曾发货 → 3 条；PAID / CANCELLED 无
  if (status === 'SHIPPED' || status === 'COMPLETED' || status === 'REFUNDED') {
    const carrier = pick(['顺丰速运', '京东物流', '中通快递']);
    const tracking = `${carrier === '顺丰速运' ? 'SF' : carrier === '京东物流' ? 'JD' : 'ZT'}${String(n).padStart(4, '0')}${int(100000, 999999)}`;
    const steps = [
      ['深圳', '已揽收'],
      ['深圳转运中心', '已发出'],
      ['上海转运中心', '到达'],
      ['上海市 浦东新区', '派送中'],
      ['上海市 浦东新区', '已签收'],
      ['上海市 浦东新区', '客户已确认'],
    ];
    const k = status === 'SHIPPED' ? int(3, 4) : int(5, 6);
    let et = t + 3 * 3600_000;
    for (let i = 0; i < k; i++) {
      et += int(4, 14) * 3600_000;
      logisticsEvents.push({
        event_id: `${id}-L${i + 1}`,
        order_id: id,
        carrier,
        tracking_no: tracking,
        seq: i + 1,
        event_time: iso(et),
        location: steps[i][0],
        description: steps[i][1],
      });
    }
  }
}

// ---- 售后 4：APPROVED 挂 10010；REJECTED / COMPLETED / CANCELLED 各 1 挂 10007–10009
const aftersales = [
  ['AS-0001', '10010', 'REPAIR', 'APPROVED', '屏幕有划痕'],
  ['AS-0002', '10007', 'RETURN', 'COMPLETED', '尺码不合适'],
  ['AS-0003', '10008', 'EXCHANGE', 'REJECTED', '颜色不喜欢'],
  ['AS-0004', '10009', 'RETURN', 'CANCELLED', '重复下单'],
].map(([id, oid, type, status, reason], i) => ({
  aftersale_id: id,
  order_id: oid,
  tenant_id: TENANT,
  type,
  status,
  reason,
  created_at: iso(Date.UTC(2026, 8, 3 + i, 10, 0, 0)),
}));

// ---- 退款 3：10007–10009 各 1，金额 = 订单金额
const refunds = ['10007', '10008', '10009'].map((oid, i) => {
  const o = orders.find((x) => x.order_id === oid);
  return {
    refund_id: `rf_seed${String(i + 1).padStart(6, '0')}`,
    order_id: oid,
    tenant_id: TENANT,
    amount: o.amount,
    currency: 'CNY',
    reason: pick(['DAMAGED', 'NOT_RECEIVED', 'CHANGED_MIND']),
    status: 'SUBMITTED',
    idempotency_key: `seed-refund-${oid}`,
    created_at: iso(Date.UTC(2026, 8, 5 + i, 12, 0, 0)),
  };
});

out('product', 'products.json', products);
out('order', 'orders.json', orders);
out('order', 'order_items.json', orderItems);
out('order', 'logistics_events.json', logisticsEvents);
out('aftersale', 'aftersales.json', aftersales);
out('refund', 'refunds.json', refunds);
console.log(
  `gen-seed: products ${products.length}, orders ${orders.length}, items ${orderItems.length}, logistics ${logisticsEvents.length}, aftersales ${aftersales.length}, refunds ${refunds.length}`,
);
