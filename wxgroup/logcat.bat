@echo off
setlocal

rem ============================================================================
rem  WXGroup - grab module logs. Double-click, no parameters.
rem  Clears the log buffer, force-stops WeChat, relaunches it, waits, then
rem  prints only the WXGroup lines.
rem
rem  ASCII ONLY: cmd.exe decodes .bat with GBK on Chinese Windows.
rem ============================================================================

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" (
    echo [logcat] adb.exe not found at %ADB%
    pause
    exit /b 1
)

echo [logcat] clearing buffer + restarting WeChat ...
"%ADB%" logcat -c
"%ADB%" shell am force-stop com.tencent.mm
"%ADB%" shell monkey -p com.tencent.mm -c android.intent.category.LAUNCHER 1 >nul 2>&1
timeout /t 12 /nobreak >nul

echo.
echo ================= WXGroup logs =================
"%ADB%" logcat -d -v brief | findstr /i /c:"WXGroup"
echo ===============================================
echo.
echo Look for the last line:  M0 boot completed.
echo.
pause
