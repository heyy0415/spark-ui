# order-service mock 数据

- `schema.sql`：MySQL 8 DDL（硬格式：每列独占一行、列名反引号、约束行以 PRIMARY KEY / KEY / CONSTRAINT 开头、无行尾注释），供后续外置存储直接建表。
- `*.json`：数组，**键 = 列名（snake_case）**，金额为两位小数字符串，时间为 ISO-8601；由 `.harness/scripts/gen-seed.mjs` 生成（固定随机种子，可复现，请勿手改），`.harness/scripts/check-seed.mjs` 校验键集合 == DDL 列、外键、金额和、夹具表。
- 运行时由 `platform-spi` 的 `SeedLoader` 读入内存仓储；换 MySQL / Redis 只需替换 Repository 实现。

## 导入 MySQL

```bash
mysql -u root -p spark < schema.sql
# 用 jq 把 json 转 INSERT（以 orders 为例）
jq -r '.[] | "INSERT INTO `orders` (" + (keys_unsorted | map("`"+.+"`") | join(",")) + ") VALUES (" + ([.[]] | map(if type=="string" then "\x27"+(.|gsub("\x27";"\x27\x27"))+"\x27" elif .==null then "NULL" else tostring end) | join(",")) + ");"' orders.json | mysql -u root -p spark
```

## 导入 Redis（按主键 hash）

```bash
jq -r '.[] | "HSET orders:" + .order_id + " " + (to_entries | map("\(.key) \x27\(.value|tostring)\x27") | join(" "))' orders.json | redis-cli --pipe
```
