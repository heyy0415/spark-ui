# CI 摘要 — feat-production-hardening-20260912

`pnpm -C .harness run ci` 于工作区（提交前）执行，退出码 **0**。

```
--- ci summary ---
check-rename       0
check-contracts    0
check-module-deps  0
check-seed         0
check-log-assertions 0
check-shell        0
spark-ui           0
spark-rooter       0
host-demo          0
ci: all steps passed (exit 0)
EXIT=0
```

## 各步骤

| 步骤 | 退出码 | 备注 |
|---|---|---|
| check-rename | 0 | |
| check-contracts | 0 | 9 schema / 28 example / invalid 示例全部被拒；契约零变更 |
| check-module-deps | 0 | **+2 规则**：Redis 模块不得倒灌平台；examples 不得回顶层 modules。双向自证 |
| check-seed | 0 | |
| check-log-assertions | 0 | e2e 新增断言片段 `run abandoned` 在源码中存在 |
| check-shell | 0 | 含新脚本 `e2e-multi-instance.sh` |
| spark-ui | 0 | typecheck / 107 单测 / lint / format / build / verify-pack；前端零改动 |
| spark-rooter | 0 | `mvnw install`：275 单测（含 Redis 18 条真实 Redis）；spotless 首轮红一次（javadoc 换行），apply 后绿 |
| host-demo | 0 | `mvn -o package`（离线，含新引的 spark-rooter-redis） |

## 体积

`host-demo.jar`：45.6 MB（上一 change 约 38.6 MB；+9 MB 来自 spring-data-redis + lettuce，host-demo 为演示多副本刻意引入；生产宿主不引 Redis 模块则没有这部分）

前端 bundle：零改动，见 `deployment/bundle_size.txt`。
