#!/data/data/com.termux/files/usr/bin/bash
# Starts the Pi RPC daemon in the background (used manually and by the Nebula app).
set -euo pipefail

HOME_DIR="${HOME}"
RUNTIME_DIR="${NEBULA_PI_RUNTIME_DIR:-$HOME_DIR/.cache/nebula-pi}"
# Daemon versioned in the repo; accepts both mirrors (repo and dev dir).
if [ -n "${NEBULA_PI_DAEMON:-}" ]; then
  DAEMON="$NEBULA_PI_DAEMON"
elif [ -f "$HOME_DIR/work/NewTermux/pi-daemon/pi-rpc-daemon.mjs" ]; then
  DAEMON="$HOME_DIR/work/NewTermux/pi-daemon/pi-rpc-daemon.mjs"
else
  DAEMON="$HOME_DIR/work/nebula-pi/daemon/pi-rpc-daemon.mjs"
fi

# Keeps the process alive when the app goes to the background (Android kills it easily).
# Does not fail if termux-wake-lock is missing.
termux-wake-lock 2>/dev/null || true

# The pi-node node is a glibc linux-arm64 build and does NOT run on Termux (bionic):
# execve fails with ENOENT and sh moves to the next PATH entry. What actually
# works is the pkg nodejs (Termux). That is why we test real execution.
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"

# Without LD_PRELOAD the shebangs with /usr/bin/env and /bin/sh do not resolve on
# Android (the .so rewrites those paths to $PREFIX). It happens when the
# daemon is started without the Termux env (boot, adb, another UID): the symptom is
# o loop `pi saiu code=126` / `node_modules/.bin/pi: not found` e o app fica
# in "starting daemon on my own". Re-exports here for the daemon and children.
if [ -z "${LD_PRELOAD:-}" ] && [ -f "$PREFIX_DIR/lib/libtermux-exec-ld-preload.so" ]; then
  export LD_PRELOAD="$PREFIX_DIR/lib/libtermux-exec-ld-preload.so"
fi

find_node() {
  for candidate in \
    "$PREFIX_DIR/bin/node" \
    "$HOME_DIR/.local/share/pi-node/current/bin/node" \
    "$(command -v node 2>/dev/null || true)"; do
    [ -n "$candidate" ] || continue
    [ -x "$candidate" ] || continue
    if "$candidate" -e 'process.exit(0)' >/dev/null 2>&1; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

NODE_BIN="$(find_node || true)"
if [ -z "$NODE_BIN" ]; then
  echo "nenhum node executavel encontrado (pkg install nodejs)" >&2
  exit 1
fi
echo "node: $NODE_BIN ($("$NODE_BIN" -v))"

mkdir -p "$RUNTIME_DIR"

PORT="${NEBULA_PI_PORT:-37877}"
SCRIPT_DIR="$(cd "$(dirname "$0")" 2>/dev/null && pwd)"
if [ -f "$SCRIPT_DIR/pi-probe.mjs" ]; then
  PROBE="$SCRIPT_DIR/pi-probe.mjs"
elif [ -f "$HOME_DIR/work/NewTermux/pi-daemon/pi-probe.mjs" ]; then
  PROBE="$HOME_DIR/work/NewTermux/pi-daemon/pi-probe.mjs"
else
  PROBE="$HOME_DIR/work/nebula-pi/daemon/pi-probe.mjs"
fi

# already running?
if [ -f "$RUNTIME_DIR/port" ] && [ -f "$RUNTIME_DIR/daemon.pid" ]; then
  pid="$(cat "$RUNTIME_DIR/daemon.pid" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "daemon already running (pid $pid, port $(cat "$RUNTIME_DIR/port"))"
    exit 0
  fi
fi

# pid file gone but the daemon answers? (overwritten by another instance)
if "$NODE_BIN" "$PROBE" "$PORT" >/dev/null 2>&1; then
  echo "daemon already running on port $PORT (pid file missing)"
  exit 0
fi

setsid nohup "$NODE_BIN" "$DAEMON" >>"$RUNTIME_DIR/daemon.log" 2>&1 &
disown || true

# espera o port file aparecer
for _ in $(seq 1 40); do
  if [ -f "$RUNTIME_DIR/port" ] && [ -f "$RUNTIME_DIR/daemon.pid" ]; then
    echo "daemon up at 127.0.0.1:$(cat "$RUNTIME_DIR/port") (pid $(cat "$RUNTIME_DIR/daemon.pid"))"
    exit 0
  fi
  if "$NODE_BIN" "$PROBE" "$PORT" >/dev/null 2>&1; then
    echo "daemon up at 127.0.0.1:$PORT"
    exit 0
  fi
  sleep 0.25
done

echo "daemon did not start, check $RUNTIME_DIR/daemon.log" >&2
exit 1
