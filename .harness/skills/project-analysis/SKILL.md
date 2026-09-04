---
name: project-analysis
description: 第一次进入仓库或仓库结构变更后，对 .harness/、.harness/contracts/、fronted/、backed/ 做全景索引。触发场景："新人入场"、"项目分析"、"全局走读"、"梳理项目"。返回一份 ≤150 行的项目地图供后续阶段使用。
---

# Skill: project-analysis

## 何时触发
- 全新会话且无进行中 change。
- `.harness/agents`、`.harness/rules`、`.harness/contracts/` 有新提交。
- 用户主动要求"先帮我熟悉项目"。

## 输入
- 仓库根目录
- 上一次分析产出（若存在）：`.harness/wiki/architecture.md`

## 步骤
1. `find .harness -maxdepth 3 -name "*.md"`，建立 Harness 资源索引。
2. `ls .harness/contracts/ .harness/contracts/examples/`，列出契约清单。
3. `cat fronted/src/main.tsx fronted/src/app/App.tsx`，确认前端入口与 Provider 链路；`ls fronted/src/{pages,features,entities,shared}` 生成切片清单。
4. `ls backed/ && grep -h "<module>" backed/pom.xml`，列出后端模块；`grep -rn "@RequestMapping\|@PostMapping\|@GetMapping" backed/*/src/main/java` 列出端点。
5. `grep -rn "domains" backed/agent-runtime/pom.xml backed/tool-registry/pom.xml` 必须为空，否则标记 RED LINE。
6. 比对 `.harness/wiki/architecture.md` 与实际；差异 > 3 处标 `STALE`。

## 输出（结构强约束）

```
## 项目摘要
- 平台定位
- 技术栈（前端 / 后端 / 契约）

## .harness/ 体系索引
- agents / rules / skills / wiki / scripts

## .harness/contracts/ 清单
| Schema | 方向 | 示例 |

## fronted/src 切片清单
| 层 | 切片 | 公共出口 |

## backed/ 模块清单
| 模块 | 职责 | 对外端点 | 依赖 |

## 红线检查
- (NONE 或具体违规)

## 文档漂移
- (NONE 或具体差异)

## 推荐阅读路径
1. ...
```

## Checklist
- [ ] 输出 ≤150 行
- [ ] 契约清单覆盖 `.harness/contracts/*.schema.json` 全部文件
- [ ] 模块清单覆盖 `backed/pom.xml` 全部 `<module>`
- [ ] 红线与漂移段落至少标注 NONE
