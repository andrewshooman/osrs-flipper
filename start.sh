#!/bin/bash
# GE Tycoon: runs the paper-trading bot and the app on this computer (Mac/Linux).
cd "$(dirname "$0")"
python3 -m pip install --quiet --disable-pip-version-check -r research/requirements.txt
python3 research/run_bot.py
