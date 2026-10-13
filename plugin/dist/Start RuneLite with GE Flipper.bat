@echo off
rem Starts your installed RuneLite with the GE Flipper plugin added.
rem RuneLite must be installed and opened normally at least once.
setlocal
set "HERE=%~dp0"
set "JAVA=%LOCALAPPDATA%\RuneLite\jre\bin\javaw.exe"
if not exist "%JAVA%" set "JAVA=%ProgramFiles%\RuneLite\jre\bin\javaw.exe"
if not exist "%JAVA%" (
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
if not exist "%HERE%ge-flipper.jar" (
  echo ge-flipper.jar must be in the same folder as this file.
  pause
  exit /b 1
)
start "" "%JAVA%" -ea -Xmx768m -Xss2m -cp "%HERE%ge-flipper.jar;%REPO%\*" com.geflipper.Launcher %*
