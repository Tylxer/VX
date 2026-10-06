@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem  WXGroup - one-click build / install
rem
rem  usage:
rem    build.bat                compile only (clean + assembleDebug)
rem    build.bat install        compile, then adb install -r
rem    build.bat install --nopause   same, without the final pause
rem
rem  NOTE: this file is intentionally ASCII-only. cmd.exe decodes .bat with the
rem  system ANSI codepage (GBK on Chinese Windows), so UTF-8 Chinese comments
rem  turn into garbage and get executed as commands. Do not add CJK text here.
rem  It also pauses at the end so double-clicking does not flash the window.
rem ============================================================================

set "PROJECT_DIR=%~dp0"
rem %~dp0 ends with a backslash; a trailing "\" would escape the closing quote
rem and Gradle would receive  '...\wxgroup" clean assembleDebug'  as one arg.
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "LOCAL_GRADLE=%USERPROFILE%\.workbuddy\binaries\gradle\gradle-8.9\bin\gradle.bat"
set "TARGET=assembleDebug"
set "DO_INSTALL=0"
set "NO_PAUSE=0"

if /i "%~1"=="install" set "DO_INSTALL=1"
if /i "%~1"=="--nopause" set "NO_PAUSE=1"
if /i "%~2"=="--nopause" set "NO_PAUSE=1"

rem ---- JAVA_HOME fallback : double-click may not inherit IDE env ----
if not defined JAVA_HOME (
    for /d %%j in ("%ProgramFiles%\Microsoft\jdk-*") do if not defined JAVA_HOME set "JAVA_HOME=%%j"
)
if not defined JAVA_HOME (
    for /d %%j in ("%ProgramFiles%\Java\jdk-*") do if not defined JAVA_HOME set "JAVA_HOME=%%j"
)
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ---- locate adb ----
set "ADB="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else (
    where adb >nul 2>nul
    if "!errorlevel!"=="0" (
        for /f "delims=" %%i in ('where adb') do if "!ADB!"=="" set "ADB=%%i"
    )
)

echo [build] project : %PROJECT_DIR%
echo [build] java    : %JAVA_HOME%
echo [build] adb     : %ADB%
echo.

if exist "%LOCAL_GRADLE%" (
    echo [build] using local Gradle 8.9
    call "%LOCAL_GRADLE%" -p "%PROJECT_DIR%" clean %TARGET%
) else (
    echo [build] local Gradle not found, falling back to gradlew.bat
    call "%PROJECT_DIR%\gradlew.bat" clean %TARGET%
)

set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" goto :failed

set "APK=%PROJECT_DIR%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [build] APK not found: %APK%
    goto :failed
)

echo.
echo [build] SUCCESS
for %%A in ("%APK%") do echo [build] APK: %%~fA  (%%~zA bytes)

if "%DO_INSTALL%"=="1" (
    if "!ADB!"=="" (
        echo [install] adb not found, skipped. Install SDK platform-tools first.
        goto :done
    )
    echo [install] !ADB! install -r "%APK%"
    call "!ADB!" install -r "%APK%"
    if errorlevel 1 (
        echo [install] FAILED - check USB debugging authorization and cable
        goto :failed
    )
    echo [install] OK
)
goto :done

:failed
echo.
echo [build] FAILED with code %EXITCODE%
echo [build] see the Gradle output above for the real cause
set "EXITCODE=1"
goto :done

:done
echo.
if "%NO_PAUSE%"=="0" pause
endlocal & exit /b %EXITCODE%
