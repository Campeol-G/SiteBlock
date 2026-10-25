#!/usr/bin/env bash
# uninstall.sh — desinstala SiteBlock completamente.
# POR QUE: o manual manda git clone + mvn package; portanto remover o repo
# é parte da desinstalação, junto com configs salvas (hosts + .sitelock + DNS).
set -euo pipefail

# ------------------------------------------------------------------
# 1b. Detecta usuário real (se rodou com sudo) — como SiteLockPaths faz
# ------------------------------------------------------------------
REAL_USER="${SUDO_USER:-${USER:-$(whoami)}}"
if [[ "${SUDO_USER:-}" != "" && "${SUDO_USER}" != "root" ]]; then
  REAL_HOME="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6 || echo "/home/$SUDO_USER")"
else
  REAL_HOME="${HOME}"
fi

# ------------------------------------------------------------------
# 1. Identifica onde está rodando (pode ser de dentro do repo)
# ------------------------------------------------------------------
SELF_ABS="$(readlink -f "${BASH_SOURCE[0]}")"
REPO_DIR="$(cd -P "$(dirname "$SELF_ABS")" && pwd)"

# ------------------------------------------------------------------
# 2. Confirmação interativa (desativa com ENV UNINSTALL_YES=1)
# ------------------------------------------------------------------
if [[ "${UNINSTALL_YES:-}" != "1" ]]; then
  echo "=== SiteBlock — desinstalação ==="
  echo "O que será feito (com sudo quando necessário):"
  echo "  1. Restaurar /etc/hosts do backup em ~/.sitelock/hosts.backup (se existir)"
  echo "     ou remover o bloco SITELOCK-START/END diretamente."
  echo "  2. Remover ~/.sitelock (preferências, estado blocks.json, backup)."
  echo "  3. Remover este repositório: $REPO_DIR"
  echo "  4. Remover binário siteblock se instalado em /usr/local/bin ou /usr/bin."
  echo ""
  read -r -p "Confirmar? [y/N] " confirm < /dev/tty
  [[ "$confirm" == "y" || "$confirm" == "Y" ]] || { echo "Cancelado."; exit 0; }
fi

# ------------------------------------------------------------------
# 3. Resolve caminhos (cross-os básico; Linux/macOS primários)
# ------------------------------------------------------------------
HOSTS="/etc/hosts"
BACKUP="${REAL_HOME}/.sitelock/hosts.backup"
STATE_DIR="${REAL_HOME}/.sitelock"

# ------------------------------------------------------------------
# 4. Restaurar hosts (preferência: backup; fallback: remover marcas)
# ------------------------------------------------------------------
if [[ -f "$BACKUP" ]]; then
  echo "[4] Restaurando hosts do backup ($BACKUP) ..."
  sudo cp -p "$BACKUP" "$HOSTS"
  # Garante permissão de leitura pelo usuário real (como o próprio app faz)
  if command -v sudo >/dev/null 2>&1; then
    sudo chmod a+r "$HOSTS" || true
  fi
else
  echo "[4] Backup não encontrado; removendo bloco SITELOCK do hosts ..."
  if [[ -f "$HOSTS" ]]; then
    # ATENÇÃO: remove apenas o bloco gerenciado pelas marcas
    sudo awk '
      /# SITELOCK-START/ { skip = 1; next }
      /# SITELOCK-END/   { skip = 0; next }
      skip == 0          { print }
    ' "$HOSTS" > "${HOSTS}.tmp" && sudo mv -f "${HOSTS}.tmp" "$HOSTS"
  fi
fi

# ------------------------------------------------------------------
# 5. Limpar preferências / estado / backup (o que o usuário pediu como prioridade)
# ------------------------------------------------------------------
echo "[5] Removendo configurações salvas (${STATE_DIR}) ..."
rm -rf "$STATE_DIR"

# ------------------------------------------------------------------
# 6. Remover serviço DNS opcional se estiver registrado (best-effort)
# ------------------------------------------------------------------
echo "[6] Parando/removendo serviço DNS (se existir) ..."
if command -v systemctl >/dev/null 2>&1; then
  sudo systemctl stop siteblock-dns 2>/dev/null || true
  sudo systemctl disable siteblock-dns 2>/dev/null || true
fi
if [[ -f /etc/systemd/system/siteblock-dns.service ]]; then
  sudo rm -f /etc/systemd/system/siteblock-dns.service
  sudo systemctl daemon-reload 2>/dev/null || true
fi
# macOS / init.d best-effort
for svc in siteblock-dns; do
  if [[ -f "/etc/init.d/$svc" ]]; then
    sudo /etc/init.d/$svc stop 2>/dev/null || true
    sudo rm -f "/etc/init.d/$svc"
  fi
done

# ------------------------------------------------------------------
# 7. Remover binário instalado (o app pode ser copiado manualmente)
# ------------------------------------------------------------------
echo "[7] Removendo binário instalado ..."
for bin_path in /usr/local/bin/siteblock /usr/bin/siteblock /usr/local/bin/siteblock.jar /opt/siteblock/siteblock; do
  if [[ -e "$bin_path" ]]; then
    echo "  -> $bin_path"
    sudo rm -rf "$bin_path"
  fi
done

# ------------------------------------------------------------------
# 8. Remover o repositório (cuidadoso: executa a partir de /tmp)
# ------------------------------------------------------------------
echo "[8] Removendo repositório: $REPO_DIR ..."
# COPIA para /tmp antes de apagar, para não quebrar este script
TMP_COPY="/tmp/siteblock-uninstall-$$"
mkdir -p "$TMP_COPY"
cp -r "$REPO_DIR" "$TMP_COPY/" 2>/dev/null || true
# Apaga o original (em Linux, rm funciona mesmo com FD aberto)
cd ~
rm -rf "$REPO_DIR"
# Limpa a cópia temporária (não é necessária)
rm -rf "$TMP_COPY"

echo "=== Desinstalação concluída ==="
echo "- /etc/hosts restaurado (ou limpo das marcas)"
echo "- ~/.sitelock removido"
echo "- Serviço DNS parado (se existia)"
echo "- Repo SiteBlock removido"
echo "- Binário removido (se existia)"
