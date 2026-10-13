#!/bin/sh
# Starts your installed RuneLite with the Flip Planner plugin added (Mac/Linux).
# RuneLite must be installed and opened normally at least once.
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HOME/.runelite/repository2"
JAVA=""
for j in "/Applications/RuneLite.app/Contents/Resources/jre/Contents/Home/bin/java" "$HOME/.local/share/runelite/jre/bin/java"; do
  [ -x "$j" ] && JAVA="$j" && break
done
[ -z "$JAVA" ] && JAVA="$(command -v java)"
[ -z "$JAVA" ] && { echo "Couldn't find Java. Install RuneLite (or Java 11+) first."; exit 1; }
ls "$REPO"/client-*.jar >/dev/null 2>&1 || { echo "Couldn't find RuneLite's files in $REPO. Open RuneLite normally once first."; exit 1; }
exec "$JAVA" -ea -Xmx768m -Xss2m -cp "$HERE/flip-planner.jar:$REPO/*" com.flipplanner.Launcher "$@"
