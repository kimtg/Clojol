@echo off
setlocal
set "CLJL_HOME=%~dp0"
if "%CLJL_HOME:~-1%"=="\" set "CLJL_HOME=%CLJL_HOME:~0,-1%"
set "JAR=%CLJL_HOME%\build\cljl.jar"
set "AOT=%CLJL_HOME%\build\cljl.aot"
set "JSA=%CLJL_HOME%\build\cljl.jsa"

if not exist "%JAR%" (
    call "%CLJL_HOME%\make.bat" build 1>&2
    if errorlevel 1 exit /b 1
)

rem Pass raw %* directly to cljl.Main outside any parenthesized if/else block
rem so arguments containing ')' are not split by cmd.exe.
if exist "%AOT%" goto :run_aot
if exist "%JSA%" goto :run_jsa
java %CLJL_OPTS% -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:run_aot
java %CLJL_OPTS% -XX:AOTCache="%AOT%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%

:run_jsa
java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" %*
exit /b %ERRORLEVEL%
