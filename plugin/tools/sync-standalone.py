#!/usr/bin/env python3
"""Copy plugin/ (minus tools/, dist/ and TESTING.md) into the standalone ge-tycoon repo for the
RuneLite Plugin Hub, commit it, and point the Plugin Hub submission at that commit.

    python plugin/tools/sync-standalone.py --src <osrs-flipper checkout> \
        --dest <ge-tycoon checkout> [--hub <plugin-hub fork checkout>] [--push]

The standalone README gets a header pointing back here, and drops the pre-Hub
start-script and test-checklist section (those files only exist in this repo).
"""
import argparse, os, re, shutil, subprocess, sys

def run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()

ap = argparse.ArgumentParser()
ap.add_argument("--src", required=True)
ap.add_argument("--dest", required=True)
ap.add_argument("--hub")
ap.add_argument("--push", action="store_true")
a = ap.parse_args()

plugin = os.path.join(a.src, "plugin")
files = [f for f in run(["git", "ls-files"], plugin).splitlines() if not f.startswith(("tools/", "dist/")) and f != "TESTING.md"]
version = re.search(r"^version=(.+)$", open(os.path.join(plugin, "runelite-plugin.properties")).read(), re.M).group(1).strip()
src_sha = run(["git", "rev-parse", "--short", "HEAD"], a.src)

# Replace the tracked contents of the standalone repo.
for f in run(["git", "ls-files"], a.dest).splitlines():
    if f != ".gitignore":
        os.remove(os.path.join(a.dest, f))
for f in files:
    os.makedirs(os.path.dirname(os.path.join(a.dest, f)) or a.dest, exist_ok=True)
    shutil.copy2(os.path.join(plugin, f), os.path.join(a.dest, f))
with open(os.path.join(a.dest, ".gitignore"), "w") as g:
    g.write("build/\n.gradle/\n.idea/\n*.iml\nout/\n")

readme = os.path.join(a.dest, "README.md")
s = open(readme).read()
s = s.replace("# GE Tycoon: RuneLite plugin\n", "# GE Tycoon: RuneLite plugin\n\n*Developed in [andrewshooman/osrs-flipper](https://github.com/andrewshooman/osrs-flipper) (`plugin/`), together with the GE Tycoon web app and the research that trained and tested the model. This repository is the plugin on its own, for the RuneLite Plugin Hub.*\n", 1)
i, j = s.find("GE Tycoon isn't on the Plugin Hub."), s.find("To build `ge-tycoon.jar`")
if i >= 0 and j > i:
    s = s[:i] + s[j:]
s = s.replace("(`tools/make-fixtures.js`)", "(`plugin/tools/make-fixtures.js` in osrs-flipper)")
open(readme, "w").write(s)

run(["git", "add", "-A"], a.dest)
if not run(["git", "status", "--porcelain"], a.dest):
    print("standalone repo already up to date")
else:
    run(["git", "-c", "user.name=Claude", "-c", "user.email=noreply@anthropic.com", "commit", "-q", "-m",
         f"GE Tycoon {version} (osrs-flipper {src_sha})\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01A3vV444mNgsoCAuk71Y3xs"], a.dest)
commit = run(["git", "rev-parse", "HEAD"], a.dest)
print("standalone", version, commit)
if a.push:
    run(["git", "push", "-q", "origin", "HEAD:main"], a.dest)

if a.hub:
    entry = os.path.join(a.hub, "plugins", "ge-tycoon")
    open(entry, "w").write(f"repository=https://github.com/andrewshooman/ge-tycoon.git\ncommit={commit}\n")
    run(["git", "add", entry], a.hub)
    old = os.path.join(a.hub, "plugins", "flip-planner")  # the entry's name before the rename to GE Tycoon
    if os.path.exists(old):
        run(["git", "rm", "-q", old], a.hub)
    if run(["git", "status", "--porcelain"], a.hub):
        run(["git", "-c", "user.name=Claude", "-c", "user.email=noreply@anthropic.com", "commit", "-q", "-m",
             f"Update GE Tycoon to {version}\n\nCo-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>\nClaude-Session: https://claude.ai/code/session_01A3vV444mNgsoCAuk71Y3xs"], a.hub)
        if a.push:
            # The Plugin Hub PR (runelite/plugin-hub#18515) comes from the fork's branch
            # "flip-planner"; renaming the branch would detach the PR, so it keeps that name.
            run(["git", "push", "-q", "origin", "HEAD:flip-planner"], a.hub)
    print("hub entry ->", commit)
