#!/bin/sh
# Starts your installed RuneLite with the GE Tycoon plugin added (Mac/Linux).
# RuneLite must be installed and opened normally at least once.
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$HOME/.runelite/repository2"
JAVA=""
for j in "/Applications/RuneLite.app/Contents/Resources/jre/bin/java" \
         "$HOME/Applications/RuneLite.app/Contents/Resources/jre/bin/java" \
         "$HOME/.local/share/runelite/jre/bin/java"; do
  [ -x "$j" ] && JAVA="$j" && break
done
[ -z "$JAVA" ] && JAVA="$(command -v java)"
[ -z "$JAVA" ] && { echo "Couldn't find Java. Install RuneLite (or Java 11+) first."; exit 1; }
ls "$REPO"/client-*.jar >/dev/null 2>&1 || { echo "Couldn't find RuneLite's files in $REPO. Open RuneLite normally once first."; exit 1; }
# RuneLite keeps older client jars as patch sources. Put the current version's first,
# or Java may load an old client next to the new game.
V="$(ls "$REPO"/injected-client-*.jar 2>/dev/null | tail -n 1 | sed 's|.*/injected-client-\(.*\)\.jar$|\1|')"
CP="$HERE/ge-tycoon.jar"
[ -n "$V" ] && CP="$CP:$REPO/injected-client-$V.jar:$REPO/client-$V.jar:$REPO/runelite-api-$V-runtime.jar"
CP="$CP:$REPO/*"
MAC=""
[ "$(uname)" = "Darwin" ] && MAC="--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED"
echo "Starting RuneLite $V with GE Tycoon (Java: $JAVA) ..."
# The same Java options RuneLite's own launcher uses, plus -ea (required by RuneLite in developer mode).
exec "$JAVA" -ea -XX:+DisableAttachMechanism -Xmx768m -Xss2m -XX:CompileThreshold=1500 \
  --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED $MAC \
  -cp "$CP" com.getycoon.Launcher "$@"
