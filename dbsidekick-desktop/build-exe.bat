@echo off
setlocal enabledelayedexpansion

REM 依赖：
REM   JAVA_HOME_17  指向 JDK 17。系统 JAVA_HOME 可以继续留给 JDK 8
REM   JAVA_HOME     仅当没设 JAVA_HOME_17、且本身已是 JDK 17 时才使用
REM   MVN_CMD       可选。未设置时使用 PATH 里的 mvn
REM   npm           需在 PATH 中（脚本不写死 Node 路径）

set ROOT=%~dp0..
cd /d "%ROOT%"

if not "%JAVA_HOME_17%"=="" set "JAVA_HOME=%JAVA_HOME_17%"
if "%JAVA_HOME%"=="" (
  echo [ERROR] 请设置 JAVA_HOME_17，指向 JDK 17
  goto fail
)
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /C:"17.0" >nul
if errorlevel 1 (
  echo [ERROR] 打包需要 JDK 17，当前是：%JAVA_HOME%
  echo 请把 JAVA_HOME_17 指到 JDK 17。原来的 JAVA_HOME 不用改。
  goto fail
)
if "%MVN_CMD%"=="" set MVN_CMD=mvn
where "%MVN_CMD%" >nul 2>&1
if errorlevel 1 (
  if not exist "%MVN_CMD%" (
    echo [ERROR] 找不到 Maven：%MVN_CMD%
    echo 请把 mvn 加入 PATH，或设置 MVN_CMD 为 mvn.cmd 的完整路径
    goto fail
  )
)

echo [1/4] Build frontend...
cd dbsidekick-web
call npm install
if errorlevel 1 goto fail
call npm run build
if errorlevel 1 goto fail

echo [2/4] Copy dist to server static...
if exist "..\dbsidekick-server\src\main\resources\static" (
  rmdir /s /q "..\dbsidekick-server\src\main\resources\static"
)
mkdir "..\dbsidekick-server\src\main\resources\static"
xcopy /E /I /Y "dist\*" "..\dbsidekick-server\src\main\resources\static\"
if errorlevel 1 goto fail

echo [3/4] Maven package (reactor install，保证 desktop 用最新 server)...
cd /d "%ROOT%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%MVN_CMD%" clean install -DskipTests
if errorlevel 1 goto fail

echo [4/4] jpackage app-image...
cd dbsidekick-desktop

REM 正在运行的 DBSidekick / jcef 会锁住 dist\DBSidekick，导致 rmdir / jpackage 失败
echo 结束可能占用 dist 的进程...
taskkill /F /IM DBSidekick.exe >nul 2>&1
taskkill /F /IM jcef_helper.exe >nul 2>&1
ping -n 3 127.0.0.1 >nul

if exist dist (
  echo 删除旧 dist...
  rmdir /s /q dist
  if exist dist (
    echo [ERROR] 无法删除 dist，请先关掉 DBSidekick.exe 后重试
    goto fail
  )
)
mkdir dist
if exist target\jpackage-input rmdir /s /q target\jpackage-input
mkdir target\jpackage-input
copy /Y target\dbsidekick-desktop-0.1.0.jar target\jpackage-input\

REM Spring Boot 胖包入口必须是 JarLauncher。使用完整 JDK，裁剪运行时创建 JCEF 浏览器时会退出。
call jpackage ^
  --type app-image ^
  --name DBSidekick ^
  --app-version 0.1.0 ^
  --input target\jpackage-input ^
  --main-jar dbsidekick-desktop-0.1.0.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --dest dist ^
  --runtime-image "%JAVA_HOME%" ^
  --icon packaging\DBSidekick.ico ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Djava.awt.headless=false"

if errorlevel 1 (
  echo jpackage failed. If --type exe is needed later, install WiX 3.x and change type.
  echo 常见原因：dist\DBSidekick 仍被占用，或未先结束 DBSidekick.exe。
  goto fail
)

echo.
echo Build complete.
echo Run: dbsidekick-desktop\dist\DBSidekick\DBSidekick.exe
echo Dev smoke: java -Djava.awt.headless=false -jar dbsidekick-desktop\target\dbsidekick-desktop-0.1.0.jar
endlocal
exit /b 0

:fail
echo.
echo 打包未完成。看完上面的错误后按任意键关闭。
pause
exit /b 1
