@echo off
REM GE Tycoon: runs the paper-trading bot and the app on this PC.
cd /d "%~dp0"
set PYTHONIOENCODING=utf-8
where python >nul 2>nul
if errorlevel 1 (
  echo Python 3.11 or newer is needed. Install it from https://www.python.org/downloads/
  echo and tick "Add python.exe to PATH" during setup, then run this again.
  pause
  exit /b 1
)
echo Installing/updating Python packages (first run takes a minute)...
python -m pip install --quiet --disable-pip-version-check -r research\requirements.txt
python research\run_bot.py
pause
