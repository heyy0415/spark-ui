## 改动

<!-- 一两句说清改了什么、为什么 -->

## 关联

<!-- 若走了八阶段流程，填 change id（如 ci-github-actions-pipeline-20260912）；外部 PR 填 Issue 号或留空 -->

## 本地门禁

- [ ] `pnpm -C .harness run ci` 退出码 0
- [ ] `pnpm -C .harness run doctor` 退出码 0
- [ ] 改了契约 → 先改 `.harness/contracts/` 与示例，再改两端（无契约改动可勾选此项）

<!-- e2e 由 CI 跑，本地可选。详见 CONTRIBUTING.md -->
