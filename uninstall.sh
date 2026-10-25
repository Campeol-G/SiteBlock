#!/usr/bin/env bash
# uninstall.sh — completely uninstalls SiteBlock.
# WHY: the manual says git clone + mvn package, so removing the repo is part of
# uninstallation, along with saved configs (hosts + .sitelock + DNS).
set -euo pipefail

# ------------------------------------------------------------------
# 1b. Detect the real user (if run with sudo) — as SiteLockPaths does
# ------------------------------------------------------------------
REAL_USER="${SUDO_USER:-${USER:-$(whoami)}}"
if [[ "${SUDO_USER:-}" != "" && "${SUDO_USER}" != "root" ]]; then
  REAL_HOME="$(getent passwd "$SUDO_USER" 2>/dev/null | cut -d: -f6 || echo "/home/$SUDO_USER")"
else
  REAL_HOME="${HOME}"
fi

# ------------------------------------------------------------------
# 1. Identify where it is running from (may be inside the repo)
# ------------------------------------------------------------------
SELF_ABS="$(readlink -f "${BASH_SOURCE[0]}")"
REPO_DIR="$(cd -P "$(dirname "$SELF_ABS")" && pwd)"

# ------------------------------------------------------------------
# 2. Interactive confirmation (disable with ENV UNINSTALL_YES=1)
# ------------------------------------------------------------------
if [[ "${UNINSTALL_YES:-}" != "1" ]]; then
  echo "=== SiteBlock — Uninstallation ==="
  echo "What will be done (with sudo when needed):"
  echo "  1. Restore /etc/hosts from the backup at ~/.sitelock/hosts.backup (if it exists)"
  echo "     or remove the SITELOCK-START/END block directly."
  echo "  2. Remove ~/.sitelock (preferences, blocks.json state, backup)."
  echo "  3. Remove this repository: $REPO_DIR"
  echo "  4. Remove the siteblock binary if installed in /usr/local/bin or /usr/bin."
  echo ""
  read -r -p "Confirm? [y/N] " confirm < /dev/tty
  [[ "$confirm" == "y" || "$confirm" == "Y" ]] || { echo "Cancelled."; exit 0; }
fi

# ------------------------------------------------------------------
# 3. Resolve paths (basic cross-OS; Linux/macOS primary)
# ------------------------------------------------------------------
HOSTS="/etc/hosts"
BACKUP="${REAL_HOME}/.sitelock/hosts.backup"
STATE_DIR="${REAL_HOME}/.sitelock"

# ------------------------------------------------------------------
# 4. Restore hosts (preference: backup; fallback: remove marks)
# ------------------------------------------------------------------
if [[ -f "$BACKUP" ]]; then
  echo "[4] Restoring hosts from backup ($BACKUP) ..."
  sudo cp -p "$BACKUP" "$HOSTS"
  # Ensures read permission for the real user (as the app itself does)
  if command -v sudo >/dev/null 2>&1; then
    sudo chmod a+r "$HOSTS" || true
  fi
else
  echo "[4] Backup not found; removing SITELOCK block from hosts ..."
  if [[ -f "$HOSTS" ]]; then
    # WARNING: removes only the block managed by the marks
    sudo awk '
      /# SITELOCK-START/ { skip = 1; next }
      /# SITELOCK-END/   { skip = 0; next }
      skip == 0          { print }
    ' "$HOSTS" > "${HOSTS}.tmp" && sudo mv -f "${HOSTS}.tmp" "$HOSTS"
  fi
fi

# ------------------------------------------------------------------
# 5. Clean preferences / state / backup (what the user prioritized)
# ------------------------------------------------------------------
echo "[5] Removing saved configurations (${STATE_DIR}) ..."
rm -rf "$STATE_DIR"

# ------------------------------------------------------------------
# 6. Remove the optional DNS service if it is registered (best-effort)
# ------------------------------------------------------------------
echo "[6] Stopping/removing DNS service (if present) ..."
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
# 7. Remove the installed binary (the app may have been copied manually)
# ------------------------------------------------------------------
echo "[7] Removing installed binary ..."
for bin_path in /usr/local/bin/siteblock /usr/bin/siteblock /usr/local/bin/siteblock.jar /opt/siteblock/siteblock; do
  if [[ -e "$bin_path" ]]; then
    echo "  -> $bin_path"
    sudo rm -rf "$bin_path"
  fi
done

# ------------------------------------------------------------------
# 8. Remove the repository (careful: runs from /tmp)
# ------------------------------------------------------------------
echo "[8] Removing repository: $REPO_DIR ..."
# Copies to /tmp before deleting, so this script does not break
TMP_COPY="/tmp/siteblock-uninstall-$$"
mkdir -p "$TMP_COPY"
cp -r "$REPO_DIR" "$TMP_COPY/" 2>/dev/null || true
# Delete the original (on Linux, rm works even with an open FD)
cd ~
rm -rf "$REPO_DIR"
# Cleans the temporary copy (not necessary)
rm -rf "$TMP_COPY"

echo "=== Uninstallation completed ==="
echo "- /etc/hosts restored (or cleaned of marks)"
echo "- ~/.sitelock removed"
echo "- DNS service stopped (if it existed)"
echo "- SiteBlock repo removed"
echo "- Binary removed (if it existed)"
