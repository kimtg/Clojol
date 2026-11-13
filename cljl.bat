@echo off
setlocal
set "CLJL_HOME=%~dp0"
if "%CLJL_HOME:~-1%"=="\" set "CLJL_HOME=%CLJL_HOME:~0,-1%"
set "JAR=%CLJL_HOME%\build\cljl.jar"
set "JSA=%CLJL_HOME%\build\cljl.jsa"

if not exist "%JAR%" (
    echo [ClojureLite] Building cljl.jar...
    if not exist "%CLJL_HOME%\build\classes" mkdir "%CLJL_HOME%\build\classes"
    dir /s /b "%CLJL_HOME%\src\*.java" > "%CLJL_HOME%\build\sources.txt"
    javac --release 21 -d "%CLJL_HOME%\build\classes" @"%CLJL_HOME%\build\sources.txt"
    if errorlevel 1 exit /b 1
    jar --create --file "%JAR%" --main-class cljl.Main -C "%CLJL_HOME%\build\classes" .
    if errorlevel 1 exit /b 1
    java -XX:ArchiveClassesAtExit="%JSA%" -jar "%JAR%" -e "(+ 1 2)" >nul 2>&1
)

if exist "%JSA%" (
    java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" %*
) else (
    java %CLJL_OPTS% -jar "%JAR%" %*
)
exit /b %ERRORLEVEL%
