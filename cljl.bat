@echo off
setlocal
set "CLJL_HOME=%~dp0"
if "%CLJL_HOME:~-1%"=="\" set "CLJL_HOME=%CLJL_HOME:~0,-1%"
set "JAR=%CLJL_HOME%\build\cljl.jar"
set "JSA=%CLJL_HOME%\build\cljl.jsa"

if not exist "%JAR%" (
    echo [ClojureLite] Building cljl.jar... 1>&2
    if not exist "%CLJL_HOME%\build\classes" mkdir "%CLJL_HOME%\build\classes"
    setlocal enabledelayedexpansion
    (for /r "%CLJL_HOME%\src" %%f in (*.java) do @(
        set "SRC_FILE=%%f"
        echo "!SRC_FILE:\=/!"
    )) > "%CLJL_HOME%\build\sources.txt"
    endlocal
    javac --release 21 -d "%CLJL_HOME%\build\classes" @"%CLJL_HOME%\build\sources.txt" 1>&2
    if errorlevel 1 exit /b 1
    jar --create --file "%JAR%" --main-class cljl.Main -C "%CLJL_HOME%\build\classes" . 1>&2
    if errorlevel 1 exit /b 1
    java -XX:ArchiveClassesAtExit="%JSA%" -jar "%JAR%" -Srepro -Sdeps "{}" -e "(+ 1 2)" >nul 2>&1
)

rem Pass raw %* directly to cljl.Main (which handles -J, -S*, -A/-M/-X/-T/-P, and clojure.main)
rem so cmd.exe does not split or mangle =, ;, !, commas, or EDN/Clojure quotes.
if exist "%JSA%" (
    java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" %*
) else (
    java %CLJL_OPTS% -jar "%JAR%" %*
)
exit /b %ERRORLEVEL%
