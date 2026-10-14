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
echo Starting RuneLite with GE Tycoon (Java: %JAVA%) ...
"%JAVA%" -ea -Xmx768m -Xss2m -cp "%HERE%ge-tycoon.jar;%REPO%\*" com.getycoon.Launcher %*
if errorlevel 1 (
  echo.
  echo RuneLite stopped with an error. Copy the lines above and send them over.
  pause
)
