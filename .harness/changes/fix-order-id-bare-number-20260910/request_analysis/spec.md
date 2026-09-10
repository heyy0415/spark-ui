# Spec: fix-order-id-bare-number-20260910

## 1. 背景
用户输入「10030查看物流」返回了 20 条订单列表。原因：`ArgumentExtractor.ORDER = 订单\s*(\d{5})(?!\d)` 要求带「订单」前缀；没有前缀时抽不到实体，规则规划器把「物流」动词降级到领域默认的 `order.list.search`。

## 2. 范围
- `ArgumentExtractor.extractEntities`：先按带前缀的正则匹配；未命中时接受**独立的 5 位数字**（前后都不是数字、不是 `-`，避免 `P-1003` 与更长数字）作为订单号。首个命中即取。
- `PlanSelfCheck` 增用例：「10030查看物流」→ `order.logistics.get{orderId:10030}`；「10002 的详情」→ `order.detail.get`。
- e2e-backend 增 ⑧'：「10030查看物流」→ `order.logistics.get`，Card 标题含 10030。
- 前端不改。

## 3. 非目标
- 不识别 4 位或 6 位以上数字；不识别「单号」等别名（后续按需）。

## 4. 验收
- 自检 `plan 12 messages OK`；e2e 规则模式全绿（+2）；harness ci 0。
