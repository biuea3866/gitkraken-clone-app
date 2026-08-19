#!/usr/bin/env bash
# SessionStart hook — 단일 Gradle 프로젝트 기준으로 JDK/툴체인/브랜치 상태를 1회 안내한다.
# stdout 은 Claude 세션 컨텍스트에 주입된다.

set -euo pipefail

# 직접 호출 시 stdin 이 비어 있을 수 있으므로 소비만 하고 무시한다.
cat >/dev/null 2>&1 || true

# ROOT 결정: Claude Code 가 주입하는 CLAUDE_PROJECT_DIR 우선.
# 직접 호출 시 fallback 은 스크립트 위치(.agent/hooks/) 기준 두 단계 위로 역산.
if [[ -n "${CLAUDE_PROJECT_DIR:-}" ]]; then
  ROOT="$CLAUDE_PROJECT_DIR"
else
  ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fi

# 요구 JDK 는 루트 gradle.properties 의 undine.jvm 을 SSOT 로 읽는다.
# 하드코딩하면 툴체인을 올릴 때 훅과 빌드가 어긋난다.
REQUIRED_JVM=""
if [[ -f "$ROOT/gradle.properties" ]]; then
  REQUIRED_JVM=$(grep -oE '^undine\.jvm[[:space:]]*=[[:space:]]*[0-9]+' "$ROOT/gradle.properties" \
    | grep -oE '[0-9]+$' || true)
fi

echo "=== Undine Harness ==="
echo "root:   $ROOT"

# 1) 브랜치 — 메인라인에서 직접 작업하는 사고를 세션 진입 시점에 잡는다.
if branch=$(git -C "$ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null); then
  echo "branch: $branch"
  if [[ "$branch" == "main" || "$branch" == "master" ]]; then
    echo "⚠ 메인라인에서 작업 중입니다 — 티켓 브랜치(feat/UND-NN)를 먼저 만드세요."
  fi
fi

# 2) JDK 정합 — Gradle 은 JAVA_HOME 을 우선한다. gradlew guard 와 동일 규칙.
if [[ -n "$REQUIRED_JVM" ]]; then
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    current_jvm=$("${JAVA_HOME}/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "?")
  else
    current_jvm=$(java -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"' || echo "?")
  fi
  if [[ "$current_jvm" == "?" || -z "$current_jvm" ]]; then
    echo "ℹ JVM 추출 실패 — \`export JAVA_HOME=\$(/usr/libexec/java_home -v $REQUIRED_JVM)\` 후 재시도."
  elif [[ "$current_jvm" != "$REQUIRED_JVM" ]]; then
    echo "⚠ JVM 미스매치: 현재=$current_jvm, 요구=$REQUIRED_JVM (gradlew guard 가 차단합니다)"
  else
    echo "jdk:    $current_jvm ✓"
  fi
else
  echo "ℹ gradle.properties 에 undine.jvm 이 없어 JDK 가드가 비활성입니다."
fi

# 3) .mcp.json preflight — required env 가 unset 이면 ${VAR} 확장이 실패해
#    MCP 서버가 기동하지 않거나 빈 인자로 떠 도구 호출이 실패한다.
if [[ -f "$ROOT/.mcp.json" && -z "${CONTEXT7_API_KEY:-}" ]]; then
  echo "✗ CONTEXT7_API_KEY 미설정 — context7 MCP 로 라이브러리 문서 조회가 실패할 수 있음."
fi

echo "harness: .agent/HARNESS.md · 티켓 tickets/"
echo "======================"

# 4) LLM 협업 가이드라인 본문 주입 — 매 세션 강한 reminder 가 목적이라 import 대신 hook 으로 echo 한다.
guideline="$ROOT/.agent/guidelines/llm-collaboration.md"
if [[ -f "$guideline" ]]; then
  echo ""
  echo "=== LLM 협업 가이드라인 (매 세션 reminder) ==="
  cat "$guideline"
  echo "============================================="
else
  echo "✗ LLM 협업 가이드라인 파일 누락 — 본 hook 의 핵심 페이로드가 주입되지 않습니다."
  echo "  expected: $guideline"
fi
