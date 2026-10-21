@echo off
REM run.bat — conveniência Windows (não substitui o JAR).
REM Encaminha todos os argumentos para o fat-jar: run.bat block tiktok.com
REM Opcional: defina SITEBLOCK_JAR com o caminho completo do JAR.

setlocal

REM 'where' é o que procura o java. Sem ele, a busca falha por outro motivo e
REM a mensagem culparia o java à toa.
where where >nul 2>nul
if errorlevel 1 (
  echo run.bat: o comando 'where' nao existe neste sistema; nao da para procurar o 'java'. 1>&2
  echo Use um prompt completo do Windows, ou aponte o caminho do java manualmente. 1>&2
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo run.bat: 'java' ^(26+^) nao encontrado no PATH. 1>&2
  exit /b 1
)

REM O jar e compilado para Java 26 (class file 70). Numa JVM antiga o erro
REM viria como "UnsupportedClassVersionError", que nao diz o que fazer.
set "JAVA_VER_RAW="
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i /c:"version"') do if not defined JAVA_VER_RAW set "JAVA_VER_RAW=%%~v"
set "JAVA_MAJOR=%JAVA_VER_RAW%"
if "%JAVA_MAJOR:~0,2%"=="1." set "JAVA_MAJOR=%JAVA_MAJOR:~2%"
for /f "tokens=1 delims=." %%m in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%m"
echo(%JAVA_MAJOR%| findstr /r /c:"^[0-9][0-9]*$" >nul 2>nul
if errorlevel 1 goto :java_sem_versao
if %JAVA_MAJOR% LSS 26 (
  echo run.bat: Java %JAVA_VER_RAW% encontrado; este projeto exige Java 26 ou mais novo. 1>&2
  echo run.bat: o jar foi compilado para o Java 26 e nao roda em uma JVM antiga. 1>&2
  exit /b 1
)
:java_sem_versao

if defined SITEBLOCK_JAR (
  REM %%~fI resolve o caminho contra o diretorio de quem chamou.
  for %%I in ("%SITEBLOCK_JAR%") do set "JAR=%%~fI"
  if not exist "%JAR%" (
    echo run.bat: o SITEBLOCK_JAR que voce informou nao existe: "%JAR%". 1>&2
    exit /b 1
  )
) else (
  set "JAR="
  REM O jar mais novo ainda nao e o certo: um '-sources.jar' ou '-javadoc.jar'
  REM nao tem Main-Class e quebra com "no main manifest attribute". Vale o
  REM primeiro que responde a '--version'.
  for /f "delims=" %%J in ('dir /b /o-d "%~dp0sitelock-app\target\siteblock-*.jar" 2^>nul') do (
    if not defined JAR (
      java -jar "%~dp0sitelock-app\target\%%J" --version >nul 2>nul
      if not errorlevel 1 set "JAR=%~dp0sitelock-app\target\%%J"
    )
  )
  if not defined JAR (
    echo run.bat: fat-jar nao encontrado em sitelock-app\target\siteblock-*.jar. 1>&2
    echo Compile antes com: mvn clean package 1>&2
    exit /b 1
  )
)

REM -Dsiteblock.jar: o daemon de expiracao embute esse caminho no artefato de
REM autostart, que roda sem PATH e sem este script.
java -Dsiteblock.jar="%JAR%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
