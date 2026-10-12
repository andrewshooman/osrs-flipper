#!/bin/bash
# Run the paper-trading bot on this machine: one step every 5 minutes, then
# publish its state to the `live` branch (single force-pushed commit) for the
# app's Bot tab. No GitHub Actions needed.
#   LIVE_DIR=/path/to/live-branch-checkout research/bot_loop.sh
set -u
REPO="$(cd "$(dirname "$0")/.." && pwd)"
LIVE_DIR="${LIVE_DIR:-$REPO/../osrs-live}"
cd "$LIVE_DIR" || exit 1
git fetch -q origin live && git reset -q --hard origin/live
while true; do
  OSF_DATA="$LIVE_DIR/data" python3 "$REPO/research/live.py" --once --model "$LIVE_DIR/data/model"
  cp data/live/state.json state.json
  git add -A
  git -c user.name="osrs-flipper-bot" -c user.email="osrs-flipper-bot@users.noreply.github.com" commit -q --amend -m "Bot state" \
    && git push -q -f origin live
  sleep 300
done
