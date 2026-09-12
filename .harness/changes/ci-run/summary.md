# Change Summary: ci-run

CI 专用占位目录，**不是真实需求**。

GitHub Actions 用 `SPARK_CHANGE=ci-run` 让 `scripts/lib/change-dir` 有一个确定的
`deployment/` 落盘位置——否则它会因「存在多个非 DONE 的 change」而退出码 2
（判定逻辑见 `scripts/lib/change-dir.mjs`）。

| 字段 | 值 |
|---|---|
| Change ID | ci-run |
| 类型 | ci |
| 状态 | DONE |
| 负责人 | — |

状态固定为 `DONE`：本机跑验收脚本时 `change-dir` 会跳过它，不干扰「恰一个非 DONE」的判定。
本目录不含阶段表，`deployment/` 已在 `.gitignore` 中忽略（CI 产物不进版本库）。
