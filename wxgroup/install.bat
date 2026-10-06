@echo off
setlocal enabledelayedexpansion

rem ============================================================================
rem  WXGroup - build, then install to the connected phone.
rem  Double-click this file. No parameters needed.
rem
rem  It checks the device BEFORE compiling, so you do not waste a 90-second
rem  build only to find out the phone is not connected.
rem
rem  ASCII ONLY: cmd.exe decodes .bat with GBK on Chinese Windows, so UTF-8
rem  Chinese comments become garbage and get executed as commands.
rem ============================================================================

set "PROJECT_DIR=%~dp0"
if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

set "NO_PAUSE=0"
if /i "%~1"=="--nopause" set "NO_PAUSE=1"

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo [install] adb.exe not found at:
    echo [install]   %ADB%
    echo [install] Install Android SDK platform-tools first.
    goto :failed
)

echo ============================================================
echo  STEP 1/3 - checking device connection
echo ============================================================
"%ADB%" devices

set "DEVFILE=%TEMP%\wxgroup_devices.txt"
"%ADB%" devices > "%DEVFILE%" 2>&1

set "DEVCOUNT=0"
set "UNAUTH=0"
for /f "usebackq skip=1 tokens=1,2" %%a in ("%DEVFILE%") do (
    if "%%b"=="device" set /a DEVCOUNT+=1
    if "%%b"=="unauthorized" set /a UNAUTH+=1
)

if "%DEVCOUNT%"=="0" (
    echo.
    if not "%UNAUTH%"=="0" (
        echo [install] DEVICE IS UNAUTHORIZED.
        echo [install] Look at the phone screen: tap "Allow USB debugging"
        echo [install] and check "Always allow from this computer", then run again.
    ) else (
        echo [install] NO DEVICE FOUND. Check in order:
        echo [install]   1. Phone: Settings - About phone - tap "Build number" 7 times
        echo [install]      to unlock Developer options
        echo [install]   2. Phone: Developer options - USB debugging = ON
        echo [install]   3. Reconnect the cable. Try a data cable, not a charge-only one.
        echo [install]   4. Pull down the notification bar, set USB mode to "File transfer"
        echo [install]   5. Some phones need the OEM vendor driver on Windows.
        echo [install] Alternative without cable - Wireless debugging:
        echo [install]   Developer options - Wireless debugging - pair using code
        echo [install]   then: adb connect ^<phone-ip^>:^<port^>
    )
    goto :failed
)

if %DEVCOUNT% GTR 1 (
    echo.
    echo [install] %DEVCOUNT% devices connected. Ambiguous - aborting.
    echo [install] Unplug the extra ones, or use: adb -s ^<serial^> install -r ^<apk^>
    goto :failed
)

echo [install] device OK
echo.
echo ============================================================
echo  STEP 2/3 - compiling  (about 90 seconds first time)
echo ============================================================
call "%PROJECT_DIR%\build.bat" install --nopause
set "EXITCODE=%ERRORLEVEL%"
if not "%EXITCODE%"=="0" goto :failed

echo.
echo ============================================================
echo  STEP 3/3 - after installing, do this on the phone
echo ============================================================
echo   1. Open LSPosed - Settings - turn OFF "API call protection"
echo   2. LSPosed - Modules - tick WXGroup - Scope: ONLY WeChat
echo   3. Force stop WeChat (not just background it), then reopen
echo   4. On PC:  adb logcat ^| findstr WXGroup
echo      Expect the last line:  M0 boot completed.
goto :done

:failed
echo.
echo [install] FAILED
set "EXITCODE=1"
goto :done

:done
echo.
if "%NO_PAUSE%"=="0" pause
endlocal & exit /b %EXITCODE%
