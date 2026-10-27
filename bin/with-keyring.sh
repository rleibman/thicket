#!/usr/bin/env bash
# Runs a command with a Secret Service of its own: a private D-Bus session bus, and a
# gnome-keyring whose login keyring is created and unlocked with a throwaway password.
#
#   bin/with-keyring.sh sbt --error checkLinux
#
# For CI, which has no desktop session, and safe on a desktop: the daemon keeps its keyring
# and its control socket in a temporary directory, never in the user's own, and the command
# reaches it only through the private bus. The password must not be empty: with an empty one,
# gnome-keyring makes no default collection, and the first store waits for a prompt that never
# comes. Needs dbus-run-session and gnome-keyring-daemon.
set -euo pipefail
home=$(mktemp -d)
trap 'rm -rf "$home"' EXIT
mkdir -m 700 "$home/run"
dbus-run-session -- bash -c '
  printf "thicket-ci" |
    XDG_RUNTIME_DIR="$0/run" XDG_DATA_HOME="$0/data" \
      gnome-keyring-daemon --unlock --components=secrets --daemonize >/dev/null
  exec "$@"' "$home" "$@"
