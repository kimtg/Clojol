@echo off
setlocal
set "CLJL_HOME=%~dp0"
if "%CLJL_HOME:~-1%"=="\" set "CLJL_HOME=%CLJL_HOME:~0,-1%"
set "BUILD_DIR=%CLJL_HOME%\build"
set "CLASSES_DIR=%BUILD_DIR%\classes"
set "JAR=%BUILD_DIR%\cljl.jar"
set "JSA=%BUILD_DIR%\cljl.jsa"

set "CMD=%~1"
if "%CMD%"=="" set "CMD=build"

if /i "%CMD%"=="clean" goto :clean
if /i "%CMD%"=="build" goto :build
if /i "%CMD%"=="rebuild" (
    call :do_clean
    goto :build
)
if /i "%CMD%"=="test" goto :test
if /i "%CMD%"=="bench" goto :bench
if /i "%CMD%"=="help" goto :help
if /i "%CMD%"=="-h" goto :help
if /i "%CMD%"=="--help" goto :help

echo Unknown target: %CMD%
goto :help_fail

:clean
call :do_clean
exit /b 0

:do_clean
if exist "%BUILD_DIR%" (
    echo [ClojureLite] Cleaning build directory...
    rmdir /s /q "%BUILD_DIR%"
)
goto :eof

:build
call :do_build
exit /b %ERRORLEVEL%

:do_build
echo [ClojureLite] Compiling Java sources...
if not exist "%CLASSES_DIR%" mkdir "%CLASSES_DIR%"
dir /s /b "%CLJL_HOME%\src\*.java" > "%BUILD_DIR%\sources.txt"
javac --release 21 -d "%CLASSES_DIR%" @"%BUILD_DIR%\sources.txt"
if errorlevel 1 exit /b 1

echo [ClojureLite] Packaging %JAR%...
jar --create --file "%JAR%" --main-class cljl.Main -C "%CLASSES_DIR%" .
if errorlevel 1 exit /b 1

echo [ClojureLite] Generating AppCDS archive %JSA%...
if exist "%JSA%" del /q "%JSA%"
java -XX:ArchiveClassesAtExit="%JSA%" -jar "%JAR%" -e "(+ 1 2)" >nul 2>&1
echo [ClojureLite] Build complete.
goto :eof

:test
if not exist "%JAR%" call :do_build
if errorlevel 1 exit /b 1
echo [ClojureLite] Running tests...
if exist "%JSA%" (
    java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" "%CLJL_HOME%\test\core_test.clj"
    if errorlevel 1 exit /b 1
    java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" "%CLJL_HOME%\test\cheatsheet_test.clj"
) else (
    java %CLJL_OPTS% -jar "%JAR%" "%CLJL_HOME%\test\core_test.clj"
    if errorlevel 1 exit /b 1
    java %CLJL_OPTS% -jar "%JAR%" "%CLJL_HOME%\test\cheatsheet_test.clj"
)
exit /b %ERRORLEVEL%

:bench
if not exist "%JAR%" call :do_build
if errorlevel 1 exit /b 1
echo [ClojureLite] Running benchmarks...
if exist "%JSA%" (
    java %CLJL_OPTS% -XX:SharedArchiveFile="%JSA%" -jar "%JAR%" "%CLJL_HOME%\test\bench.clj"
) else (
    java %CLJL_OPTS% -jar "%JAR%" "%CLJL_HOME%\test\bench.clj"
)
exit /b %ERRORLEVEL%

:help
echo Usage: make.bat [target]
echo.
echo Targets:
echo   build    Compile Java sources, package build\cljl.jar, generate AppCDS archive (default)
echo   rebuild  Clean and build from scratch
echo   clean    Remove build\ directory
echo   test     Build and run test\core_test.clj
echo   bench    Build and run test\bench.clj
echo   help     Show this help message
exit /b 0

:help_fail
call :help
exit /b 1
