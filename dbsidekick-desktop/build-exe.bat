@echo off
setlocal enabledelayedexpansion

REM 依赖：
REM   JAVA_HOME  必须指向 JDK 17。jpackage 用它作为 --runtime-image
REM   MVN_CMD    可选。未设置时使用 PATH 里的 mvn
REM   npm        需在 PATH 中（脚本不写死 Node 路径）

set ROOT=%~dp0..
cd /d "%ROOT%"

echo [1/4] Build frontend...
cd dbsidekick-web
call npm install
if errorlevel 1 exit /b 1
call npm run build
if errorlevel 1 exit /b 1

echo [2/4] Copy dist to server static...
if exist "..\dbsidekick-server\src\main\resources\static" (
  rmdir /s /q "..\dbsidekick-server\src\main\resources\static"
)
mkdir "..\dbsidekick-server\src\main\resources\static"
xcopy /E /I /Y "dist\*" "..\dbsidekick-server\src\main\resources\static\"
if errorlevel 1 exit /b 1

echo [3/4] Maven package (reactor install，保证 desktop 用最新 server)...
cd /d "%ROOT%"
if "%JAVA_HOME%"=="" (
  echo [ERROR] 请先设置 JAVA_HOME
  exit /b 1
)
if "%MVN_CMD%"=="" set MVN_CMD=mvn
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%MVN_CMD%" clean install -DskipTests
if errorlevel 1 exit /b 1

echo [4/4] jpackage app-image...
cd dbsidekick-desktop
if exist dist rmdir /s /q dist
mkdir dist
if exist target\jpackage-input rmdir /s /q target\jpackage-input
mkdir target\jpackage-input
copy /Y target\dbsidekick-desktop-0.0.1-SNAPSHOT.jar target\jpackage-input\

REM Spring Boot 胖包入口必须是 JarLauncher。使用完整 JDK，裁剪运行时创建 JCEF 浏览器时会退出。
call jpackage ^
  --type app-image ^
  --name DBSidekick ^
  --app-version 0.0.1 ^
  --input target\jpackage-input ^
  --main-jar dbsidekick-desktop-0.0.1-SNAPSHOT.jar ^
  --main-class org.springframework.boot.loader.launch.JarLauncher ^
  --dest dist ^
  --runtime-image "%JAVA_HOME%" ^
  --icon packaging\DBSidekick.ico ^
  --java-options "-Dfile.encoding=UTF-8" ^
  --java-options "-Djava.awt.headless=false"

if errorlevel 1 (
  echo jpackage failed. If --type exe is needed later, install WiX 3.x and change type.
  exit /b 1
)

echo.
echo Build complete.
echo Run: dbsidekick-desktop\dist\DBSidekick\DBSidekick.exe
echo Dev smoke: java -Djava.awt.headless=false -jar dbsidekick-desktop\target\dbsidekick-desktop-0.0.1-SNAPSHOT.jar
endlocal
