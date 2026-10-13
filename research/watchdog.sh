#!/bin/bash
# Self-healing watchdog: (re)starts the bot loop if it isn't running or its state
# hasn't updated for 20 minutes. flock guarantees a single bot loop and watchdog.
exec 9>${OSF_STATE:-/home/user/osrs-data}/watchdog.lock; flock -n 9 || exit 0
S=${LIVE_DIR:-/home/user/osrs-live}/data/live/state.json
start_bot() {
  LIVE_DIR=${LIVE_DIR:-/home/user/osrs-live} setsid nohup flock -n ${OSF_STATE:-/home/user/osrs-data}/bot.lock \
    $(dirname "$0")/bot_loop.sh >> ${OSF_STATE:-/home/user/osrs-data}/bot-loop.log 2>&1 < /dev/null &
}
while true; do
  # The bot holds bot.lock while it runs; if we can take the lock, it isn't running.
  if flock -n ${OSF_STATE:-/home/user/osrs-data}/bot.lock true; then running=0; else running=1; fi
  age=$(( $(date +%s) - $(stat -c %Y "$S" 2>/dev/null || echo 0) ))
  if [ "$running" -eq 0 ]; then
    echo "$(date -u +%FT%TZ) bot loop not running - starting"; start_bot; sleep 300
  elif [ "$age" -gt 1200 ]; then
    echo "$(date -u +%FT%TZ) bot state stale (${age}s) - restarting"
    fuser -k ${OSF_STATE:-/home/user/osrs-data}/bot.lock 2>/dev/null
    sleep 5; start_bot; sleep 300
  fi
  sleep 60
done
