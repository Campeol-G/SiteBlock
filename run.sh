#!/usr/bin/env bash
# run.sh — conveniência Linux/macOS (não substitui o JAR).
# Encaminha todos os argumentos para o fat-jar: ./run.sh block tiktok.com --for 1h
set -euo pipefail

SCRIPT="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || realpath "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")"
ROOT="$(cd "$(dirname "$SCRIPT")" && pwd)"

JAR="${SITEBLOCK_JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls -1t "$ROOT"/sitelock-app/target/siteblock-*.jar 2>/dev/null | head -n 1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "run.sh: fat-jar nao encontrado em $ROOT/sitelock-app/target/siteblock-*.jar." >&2
  echo "Compile antes com: mvn clean package" >&2
  exit 1
fi

JAVA_BIN="$(command -v java || true)"
if [[ -z "$JAVA_BIN" ]]; then
  echo "run.sh: 'java' (26+) nao encontrado no PATH." >&2
  exit 1
fi

exec "$JAVA_BIN" -jar "$JAR" "$@"
