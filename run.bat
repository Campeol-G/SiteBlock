@echo off
REM run.bat — Windows convenience wrapper (does not replace the JAR).
REM Forwards all arguments to the fat-jar: run.bat block tiktok.com
REM Optional: set SITEBLOCK_JAR to the full path of the JAR.

setlocal

REM 'where' is what locates java. Without it, the lookup fails for another
REM reason and the message would wrongly blame java.
where where >nul 2>nul
if errorlevel 1 (
  echo run.bat: the 'where' command does not exist on this system; cannot look up 'java'. 1>&2
  echo Use a full Windows prompt, or set the java path manually. 1>&2
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo run.bat: 'java' ^(26+^) not found in PATH. 1>&2
  exit /b 1
)

REM The jar is compiled for Java 26 (class file 70). On an older JVM the error
REM would come as "UnsupportedClassVersionError", which does not say what to do.
set "JAVA_VER_RAW="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined JAVA_VER_RAW set "JAVA_VER_RAW=%%~v"
set "JAVA_MAJOR=%JAVA_VER_RAW%"
if "%JAVA_MAJOR:~0,2%"=="1." set "JAVA_MAJOR=%JAVA_MAJOR:~2%"
for /f "tokens=1 delims=." %%m in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%m"
echo(%JAVA_MAJOR%| findstr /r /c:"^[0-9][0-9]*$" >nul 2>nul
if errorlevel 1 goto :java_no_version
if %JAVA_MAJOR% LSS 26 (
  echo run.bat: found Java %JAVA_VER_RAW%; this project requires Java 26 or newer. 1>&2
  echo run.bat: the jar was compiled for Java 26 and will not run on an older JVM. 1>&2
  exit /b 1
)
:java_no_version

if defined SITEBLOCK_JAR (
  REM %%~fI resolves the path against the caller's directory.
  for %%I in ("%SITEBLOCK_JAR%") do set "JAR=%%~fI"
  if not exist "%JAR%" (
    echo run.bat: the SITEBLOCK_JAR you provided does not exist: "%JAR%". 1>&2
    exit /b 1
  )
) else (
  set "JAR="
  REM The newest jar is not necessarily the right one: a '-sources.jar' or
  REM '-javadoc.jar' has no Main-Class and fails with "no main manifest
  REM attribute". The first one that answers '--version' is the one that counts.
  for /f "delims=" %%J in ('dir /b /o-d "%~dp0sitelock-app\target\siteblock-*.jar" 2^>nul') do (
    if not defined JAR (
      java -jar "%~dp0sitelock-app\target\%%J" --version >nul 2>nul
      if not errorlevel 1 set "JAR=%~dp0sitelock-app\target\%%J"
    )
  )
  if not defined JAR (
    echo run.bat: fat-jar not found at sitelock-app\target\siteblock-*.jar. 1>&2
    echo Compile it first with: mvn clean package 1>&2
    exit /b 1
  )
)

REM -Dsiteblock.jar: the expiration daemon embeds this path in the autostart
REM artifact, which runs without PATH and without this script.
java -Dsiteblock.jar="%JAR%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
