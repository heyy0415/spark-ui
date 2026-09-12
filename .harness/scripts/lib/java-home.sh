#!/usr/bin/env bash
# source 后提供：
#   $JAVA_BIN           —— 可执行的 java 路径
#   $JAVA_HOME_RESOLVED —— 对应的 JAVA_HOME（启动子进程时传它，而不是写死路径）
#
# 解析顺序：$JAVA_HOME（校验其 java -version 确为 21）→ 本机 jenv / 系统安装路径 → PATH 上的 java。
# 三个验收脚本共用本文件，避免各自写一份 `$HOME/.jenv/versions/21/bin/java` 而在 CI 上失效
# （CI 由 actions/setup-java 装 JDK，路径与本机无关）。
_jh_is21() { # $1 = java 可执行路径
  [ -x "$1" ] || return 1
  "$1" -version 2>&1 | head -1 | grep -qE '"21[.\"]|version "21'
}

JAVA_BIN=""
JAVA_HOME_RESOLVED=""

# 1. 显式 JAVA_HOME（CI 与显式指定场景）
if [ -n "${JAVA_HOME:-}" ] && _jh_is21 "$JAVA_HOME/bin/java"; then
  JAVA_HOME_RESOLVED="$JAVA_HOME"
  JAVA_BIN="$JAVA_HOME/bin/java"
fi

# 2. 本机常见安装位置（保留原有行为，本机开发者无需设 JAVA_HOME）
if [ -z "$JAVA_BIN" ]; then
  for _jh_cand in \
    "$HOME/.jenv/versions/21" \
    "$HOME/.jenv/versions/openjdk64-21.0.11" \
    "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home"; do
    if _jh_is21 "$_jh_cand/bin/java"; then
      JAVA_HOME_RESOLVED="$_jh_cand"
      JAVA_BIN="$_jh_cand/bin/java"
      break
    fi
  done
  unset _jh_cand
fi

# 3. PATH 上的 java（最后兜底）
if [ -z "$JAVA_BIN" ]; then
  _jh_path_java="$(command -v java 2>/dev/null || true)"
  if [ -n "$_jh_path_java" ] && _jh_is21 "$_jh_path_java"; then
    JAVA_BIN="$_jh_path_java"
    # 由 java.home 反推 JAVA_HOME，拿不到就留空（子进程继承当前环境即可）
    JAVA_HOME_RESOLVED="$("$_jh_path_java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | head -1)"
  fi
  unset _jh_path_java
fi

if [ -z "$JAVA_BIN" ]; then
  echo "[java-home] JDK 21 not found." >&2
  echo "  tried: \$JAVA_HOME, ~/.jenv/versions/21, /Library/Java/.../openjdk-21.jdk, PATH" >&2
  echo "  fix:   export JAVA_HOME=/path/to/jdk21   (CI: actions/setup-java with java-version 21)" >&2
  exit 2
fi

export JAVA_BIN JAVA_HOME_RESOLVED
