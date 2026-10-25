@echo off
setlocal enabledelayedexpansion
REM uninstall.bat — desinstala SiteBlock no Windows.
REM POR QUE: o projeto define Windows como plataforma (README); portanto precisa de equivalente.
echo === SiteBlock — desinstalação (Windows) ===
echo O que sera feito (requer Administrador para hosts):
echo   1. Restaurar hosts do backup (%USERPROFILE%\.sitelock\hosts.backup) OU remover marcas SITELOCK.
echo   2. Remover %USERPROFILE%\.sitelock
echo   3. Remover este repositorio.
echo   4. Remover binario siteblock se instalado.
echo.
set /p confirm="Confirmar? [y/N] "
if /I not "%confirm%"=="y" exit /b 0

REM 1. Hosts
set HOSTS=%SystemRoot%\System32\drivers\etc\hosts
set BACKUP=%USERPROFILE%\.sitelock\hosts.backup
if exist "%BACKUP%" (
  echo [1] Restaurando hosts do backup...
  copy /Y "%BACKUP%" "%HOSTS%" >nul
) else (
  echo [1] Backup nao encontrado; removendo marcas SITELOCK do hosts...
  powershell -NoProfile -Command "
    \$content = Get-Content '%HOSTS%' -Encoding UTF8
    \$out = @()
    \$skip = \$false
    foreach (\$line in \$content) {
      if (\$line -match '# SITELOCK-START') { \$skip = \$true; continue }
      if (\$line -match '# SITELOCK-END')   { \$skip = \$false; continue }
      if (-not \$skip) { \$out += \$line }
    }
    \$out | Set-Content '%HOSTS%' -Encoding UTF8
  "
)

REM 2. Preferencias / estado
if exist "%USERPROFILE%\.sitelock" rmdir /S /Q "%USERPROFILE%\.sitelock"

REM 3. Binario
for %%p in (%ProgramFiles%\siteblock %LOCALAPPDATA%\Programs\siteblock %SystemRoot%\system32\siteblock.exe) do (
  if exist "%%p" (
    echo [3] Removendo %%p
    del /F /Q "%%p" 2>nul
  )
)

REM 4. Repositorio (só se confirmar que e SiteBlock)
set "REPO_DIR=%~dp0"
if exist "%REPO_DIR%pom.xml" (
  REM Copia para temp antes para nao quebrar
  set "TMP_COPY=%TEMP%\sb_uninstall_tmp_%RANDOM%"
  mkdir "%TMP_COPY%" 2>nul
  xcopy /E /I /H "%REPO_DIR%" "%TMP_COPY%\" >nul 2>&1
  REM Apaga original
  rmdir /S /Q "%REPO_DIR%"
  rmdir /S /Q "%TMP_COPY%" 2>nul
  echo [4] Repositorio removido: %REPO_DIR%
) else (
  echo [4] Diretorio nao parece ser SiteBlock (%REPO_DIR%); nao removendo repo automaticamente.
)
echo === Desinstalacao concluida ===
pause
