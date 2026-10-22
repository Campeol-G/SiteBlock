#!/usr/bin/env bash
# run.sh — conveniência Linux/macOS (não substitui o JAR).
# Encaminha todos os argumentos para o fat-jar: ./run.sh block tiktok.com --for 1h
set -euo pipefail

# Resolve o próprio script sem 'readlink -f' (GNU): no macOS < 12.3 ele não
# existe, e o fallback devolvia caminho relativo — o ROOT virava errado e o
# jar não era encontrado.
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

# Resolve o 'java' para caminho absoluto: sob 'sudo' o PATH é reduzido
# (secure_path) e a busca relativa poderia falhar.
JAVA_BIN="$(command -v java || true)"
if [[ -z "$JAVA_BIN" ]]; then
  echo "run.sh: 'java' (26+) nao encontrado no PATH." >&2
  exit 1
fi

# O jar é compilado para Java 26 (class file 70). Numa JVM antiga o erro
# viria como "UnsupportedClassVersionError", que não diz o que fazer.
java_maior() {
  local saida bruto
  saida="$("$JAVA_BIN" -version 2>&1)"
  bruto="${saida#*version \"}"
  bruto="${bruto%%\"*}"
  case "$bruto" in
    1.*) bruto="${bruto#1.}" ;;
  esac
  printf '%s' "${bruto%%[!0-9]*}"
}
JAVA_MAJOR="$(java_maior)"
if [[ -n "$JAVA_MAJOR" && "$JAVA_MAJOR" -lt 26 ]]; then
  echo "run.sh: Java $JAVA_MAJOR encontrado; este projeto exige Java 26 ou mais novo." >&2
  echo "run.sh: o jar foi compilado para o Java 26 e nao roda em uma JVM antiga." >&2
  exit 1
fi

# Versão do projeto, lida do pom: o jar com esse nome é o certo. Sem isso, um
# 'siteblock-0.9.0.jar' de um bump antigo ganha do atual só por ser mais novo.
versao_do_projeto() {
  local pom="$1" bruto
  [[ -f "$pom" ]] || return 1
  bruto="$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$pom")"
  printf '%s' "${bruto%%$'\n'*}"
}

# Um '-sources.jar' ou '-javadoc.jar' não tem Main-Class: rodá-lo quebra com
# "no main manifest attribute". Vale o jar que responde.
escolher_jar() {
  local dir="$1" esperado="$2" candidato
  if [[ -n "$esperado" && -f "$esperado" ]] \
      && "$JAVA_BIN" -jar "$esperado" --version >/dev/null 2>&1; then
    printf '%s\n' "$esperado"
    return 0
  fi
  while IFS= read -r candidato; do
    [[ -f "$candidato" ]] || continue
    case "$candidato" in
      *-sources.jar|*-javadoc.jar) continue ;;
    esac
    if "$JAVA_BIN" -jar "$candidato" --version >/dev/null 2>&1; then
      printf '%s\n' "$candidato"
      return 0
    fi
  done < <(ls -1t "$dir"/siteblock-*.jar 2>/dev/null || true)
  return 1
}

TARGET_DIR="$ROOT/sitelock-app/target"
VERSAO="$(versao_do_projeto "$ROOT/pom.xml" || true)"
ESPERADO=""
if [[ -n "$VERSAO" ]]; then
  ESPERADO="$TARGET_DIR/siteblock-$VERSAO.jar"
fi

JAR="${SITEBLOCK_JAR:-}"
if [[ -n "$JAR" ]]; then
  # Relativo ao diretório de quem chamou, como qualquer comando.
  case "$JAR" in
    /*) ;;
    *) JAR="$PWD/$JAR" ;;
  esac
  if [[ ! -f "$JAR" ]]; then
    echo "run.sh: o SITEBLOCK_JAR que voce informou nao existe: $JAR" >&2
    exit 1
  fi
else
  JAR="$(escolher_jar "$TARGET_DIR" "$ESPERADO")" || JAR=""
  if [[ -z "$JAR" ]]; then
    echo "run.sh: fat-jar nao encontrado em $TARGET_DIR/siteblock-*.jar." >&2
    echo "run.sh: compile antes com: mvn clean package" >&2
    exit 1
  fi
fi

# -Dsiteblock.jar: o daemon de expiração embute esse caminho no artefato de
# autostart, que roda sem PATH e sem este script.
# -Dsiteblock.bin: as dicas de sudo passam a mostrar o caminho real deste
# script, em vez do nome solto 'siteblock' (que pode não estar no PATH).
exec "$JAVA_BIN" -Dsiteblock.bin="$SCRIPT" -Dsiteblock.jar="$JAR" -jar "$JAR" "$@"
