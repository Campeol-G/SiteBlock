#!/usr/bin/env bash
# run.sh — Linux/macOS convenience wrapper (does not replace the JAR).
# Forwards all arguments to the fat-jar: ./run.sh block tiktok.com --for 1h
set -euo pipefail

# Resolve the script itself without 'readlink -f' (GNU): on macOS < 12.3 it
# does not exist, and the fallback returned a relative path — ROOT became wrong
# and the jar was not found.
SCRIPT="${BASH_SOURCE[0]}"
while [ -L "$SCRIPT" ]; do
  SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT")" && pwd)"
  SCRIPT="$(readlink "$SCRIPT")"
  case "$SCRIPT" in
    /*) ;;
    *) SCRIPT="$SCRIPT_DIR/$SCRIPT" ;;
  esac
done
SCRIPT_DIR="$(cd -P "$(dirname "$SCRIPT")" && pwd)"
SCRIPT="$SCRIPT_DIR/$(basename "$SCRIPT")"
ROOT="$SCRIPT_DIR"

# Resolve 'java' to an absolute path: under 'sudo' the PATH is reduced
# (secure_path), so a relative lookup could fail.
JAVA_BIN="$(command -v java || true)"
if [[ -z "$JAVA_BIN" ]]; then
  echo "run.sh: 'java' (26+) not found in PATH." >&2
  exit 1
fi

# The jar is compiled for Java 26 (class file 70). On an older JVM the error
# would come as "UnsupportedClassVersionError", which does not say what to do.
java_major_version() {
  local output raw
  output="$("$JAVA_BIN" -version 2>&1)"
  raw="${output#*version \"}"
  raw="${raw%%\"*}"
  case "$raw" in
    1.*) raw="${raw#1.}" ;;
  esac
  printf '%s' "${raw%%[!0-9]*}"
}
JAVA_MAJOR="$(java_major_version)"
if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -lt 26 ]]; then
  echo "run.sh: found Java $JAVA_MAJOR; this project requires Java 26 or newer." >&2
  echo "run.sh: the jar was compiled for Java 26 and will not run on an older JVM." >&2
  exit 1
fi

# Project version, read from the pom: the jar with that name is the right one.
# Without this, a 'siteblock-0.9.0.jar' from an old bump would win over the
# current one just because it is newer.
project_version() {
  local pom="$1" raw
  [[ -f "$pom" ]] || return 1
  raw="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$pom")"
  printf '%s' "${raw%%$'\n'*}"
}

# A '-sources.jar' or '-javadoc.jar' has no Main-Class: running it fails with
# "no main manifest attribute". The jar that answers is the one that counts.
choose_jar() {
  local dir="$1" expected="$2" candidate
  if [[ -n "$expected" && -f "$expected" ]] \
      && "$JAVA_BIN" -jar "$expected" --version >/dev/null 2>&1; then
    printf '%s\n' "$expected"
    return 0
  fi
  while IFS= read -r candidate; do
    [[ -f "$candidate" ]] || continue
    case "$candidate" in
      *-sources.jar|*-javadoc.jar) continue ;;
    esac
    if "$JAVA_BIN" -jar "$candidate" --version >/dev/null 2>&1; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done < <(ls -1t "$dir"/siteblock-*.jar 2>/dev/null || true)
  return 1
}

TARGET_DIR="$ROOT/sitelock-app/target"
VERSION="$(project_version "$ROOT/pom.xml" || true)"
EXPECTED=""
if [[ -n "$VERSION" ]]; then
  EXPECTED="$TARGET_DIR/siteblock-$VERSION.jar"
fi

JAR="${SITEBLOCK_JAR:-}"
if [[ -n "$JAR" ]]; then
  # Relative to the caller's directory, like any command.
  case "$JAR" in
    /*) ;;
    *) JAR="$PWD/$JAR" ;;
  esac
  if [[ ! -f "$JAR" ]]; then
    echo "run.sh: the SITEBLOCK_JAR you provided does not exist: $JAR" >&2
    exit 1
  fi
else
  JAR="$(choose_jar "$TARGET_DIR" "$EXPECTED")" || JAR=""
  if [[ -z "$JAR" ]]; then
    echo "run.sh: fat-jar not found at $TARGET_DIR/siteblock-*.jar." >&2
    echo "run.sh: compile it first with: mvn clean package" >&2
    exit 1
  fi
fi

# -Dsiteblock.jar: the expiration daemon embeds this path in the autostart
# artifact, which runs without PATH and without this script.
# -Dsiteblock.bin: sudo hints then show the real path of this script instead
# of the bare name 'siteblock' (which may not be in PATH).
exec "$JAVA_BIN" -Dsiteblock.bin="$SCRIPT" -Dsiteblock.jar="$JAR" -jar "$JAR" "$@"
