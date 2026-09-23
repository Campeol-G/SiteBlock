@echo off
REM run.bat — conveniência Windows (não substitui o JAR).
REM Encaminha todos os argumentos para o fat-jar: run.bat block tiktok.com
REM Opcional: defina SITEBLOCK_JAR com o caminho completo do JAR.

setlocal

if defined SITEBLOCK_JAR (
  set "JAR=%SITEBLOCK_JAR%"
) else (
  set "JAR="
  for /f "delims=" %%J in ('dir /b /o-d "%~dp0sitelock-app\target\siteblock-*.jar" 2^>nul') do (
    if not defined JAR set "JAR=%~dp0sitelock-app\target\%%J"
  )
)

if not defined JAR (
  echo run.bat: fat-jar nao encontrado em sitelock-app\target\siteblock-*.jar. 1>&2
  echo Compile antes com: mvn clean package 1>&2
  exit /b 1
)

if not exist "%JAR%" (
  echo run.bat: JAR nao existe: "%JAR%". 1>&2
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo run.bat: 'java' ^(26+^) nao encontrado no PATH. 1>&2
  exit /b 1
)

java -jar "%JAR%" %*
