@echo off
setlocal enabledelayedexpansion
REM uninstall.bat — uninstalls SiteBlock on Windows.
REM WHY: the project lists Windows as a supported platform (README), so it needs an equivalent.
echo === SiteBlock — uninstallation (Windows) ===
echo What will be done (requires Administrator for hosts):
echo   1. Restore hosts from the backup (%USERPROFILE%\.sitelock\hosts.backup) OR remove SITELOCK markers.
echo   2. Remove %USERPROFILE%\.sitelock
echo   3. Remove this repository.
echo   4. Remove the siteblock binary if installed.
echo.
set /p confirm="Confirm? [y/N] "
if /I not "%confirm%"=="y" exit /b 0

REM 1. Hosts
set HOSTS=%SystemRoot%\System32\drivers\etc\hosts
set BACKUP=%USERPROFILE%\.sitelock\hosts.backup
if exist "%BACKUP%" (
  echo [1] Restoring hosts from backup...
  copy /Y "%BACKUP%" "%HOSTS%" >nul
) else (
  echo [1] Backup not found; removing SITELOCK markers from hosts...
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

REM 2. Preferences / state
if exist "%USERPROFILE%\.sitelock" rmdir /S /Q "%USERPROFILE%\.sitelock"

REM 3. Binary
for %%p in (%ProgramFiles%\siteblock %LOCALAPPDATA%\Programs\siteblock %SystemRoot%\system32\siteblock.exe) do (
  if exist "%%p" (
    echo [3] Removing %%p
    del /F /Q "%%p" 2>nul
  )
)

REM 4. Repository (only if it is confirmed to be SiteBlock)
set "REPO_DIR=%~dp0"
if exist "%REPO_DIR%pom.xml" (
  REM Copy to temp first so this does not break
  set "TMP_COPY=%TEMP%\sb_uninstall_tmp_%RANDOM%"
  mkdir "%TMP_COPY%" 2>nul
  xcopy /E /I /H "%REPO_DIR%" "%TMP_COPY%\" >nul 2>&1
  REM Delete the original
  rmdir /S /Q "%REPO_DIR%"
  rmdir /S /Q "%TMP_COPY%" 2>nul
  echo [4] Repository removed: %REPO_DIR%
) else (
  echo [4] Directory does not look like SiteBlock (%REPO_DIR%); not removing the repo automatically.
)
echo === Uninstallation complete ===
pause
