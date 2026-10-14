@echo off
rem Starts your installed RuneLite with the GE Tycoon plugin added.
rem RuneLite must be installed and opened normally at least once.
rem The window shows RuneLite's log; closing it closes RuneLite.
setlocal
set "HERE=%~dp0"
set "JAVA="
for %%J in ("%LOCALAPPDATA%\RuneLite\jre\bin\java.exe" "%ProgramFiles%\RuneLite\jre\bin\java.exe" "%ProgramFiles(x86)%\Jagex Launcher\Games\RuneLite\jre\bin\java.exe" "%ProgramFiles%\Jagex Launcher\Games\RuneLite\jre\bin\java.exe") do (
  if not defined JAVA if exist %%J set "JAVA=%%~J"
)
if not defined JAVA where java >nul 2>nul && set "JAVA=java"
if not defined JAVA (
  echo Couldn't find RuneLite's Java. Install RuneLite from runelite.net, open it once, then try again.
  pause
  exit /b 1
)
set "REPO=%USERPROFILE%\.runelite\repository2"
if not exist "%REPO%\client-*.jar" (
  echo Couldn't find RuneLite's files in %REPO%. Open RuneLite normally once, then try again.
  pause
  exit /b 1
)
if not exist "%HERE%ge-tycoon.jar" (
  echo ge-tycoon.jar must be in the same folder as this file.
  pause
  exit /b 1
)
rem RuneLite keeps older client jars as patch sources. Put the current version's first,
rem or Java may load an old client next to the new game.
set "RLV="
for %%F in ("%REPO%\injected-client-*.jar") do set "RLV=%%~nF"
if defined RLV set "RLV=%RLV:injected-client-=%"
set "CP=%HERE%ge-tycoon.jar"
if defined RLV set "CP=%CP%;%REPO%\injected-client-%RLV%.jar;%REPO%\client-%RLV%.jar;%REPO%\runelite-api-%RLV%-runtime.jar"
set "CP=%CP%;%REPO%\*"
set "DLLS=RTSSHooks.dll,RTSSHooks64.dll,NahimicOSD.dll,NahimicMSIOSD.dll,Nahimic2OSD.dll,Nahimic2DevProps.dll,k_fps32.dll,k_fps64.dll,SS2DevProps.dll,SS2OSD.dll,GTIII-OSD64-GL.dll,GTIII-OSD64-VK.dll,GTIII-OSD64.dll,OWExplorer.dll,OWClient.dll,OWUtils.dll"
echo Starting RuneLite %RLV% with GE Tycoon (Java: %JAVA%) ...
rem The same Java options RuneLite's own launcher uses, plus -ea (required by RuneLite in developer mode).
"%JAVA%" -ea -XX:+DisableAttachMechanism "-Drunelite.launcher.blacklistedDlls=%DLLS%" -Xmx768m -Xss2m -XX:CompileThreshold=1500 --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED -cp "%CP%" com.getycoon.Launcher %*
if errorlevel 1 (
  echo.
  echo RuneLite stopped with an error. Copy the lines above and send them over.
  pause
)
