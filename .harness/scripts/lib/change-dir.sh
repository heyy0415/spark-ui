#!/usr/bin/env bash
# source 后提供 $DEPLOY（当前 change 的 deployment/ 绝对路径）；判定逻辑见 change-dir.mjs。失败时以其退出码退出。
_CD_HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(node "$_CD_HERE/change-dir.mjs")" || exit $?
export DEPLOY
mkdir -p "$DEPLOY"
