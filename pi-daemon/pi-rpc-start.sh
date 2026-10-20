#!/data/data/com.termux/files/usr/bin/bash
# Sobe o daemon RPC do Pi em background (usado manualmente e pelo app Nebula).
set -euo pipefail

HOME_DIR="${HOME}"
RUNTIME_DIR="${NEBULA_PI_RUNTIME_DIR:-$HOME_DIR/.cache/nebula-pi}"
DAEMON="$HOME_DIR/work/nebula-pi/daemon/pi-rpc-daemon.mjs"

# O node do pi-node e build glibc linux-arm64 e NAO roda no Termux (bionic):
# execve falha com ENOENT e o sh segue para o proximo PATH. O que funciona de
# verdade e o nodejs do pkg (Termux). Por isso testamos a execucao de verdade.
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
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
PROBE="$HOME_DIR/work/nebula-pi/daemon/pi-probe.mjs"

# ja esta rodando?
if [ -f "$RUNTIME_DIR/port" ] && [ -f "$RUNTIME_DIR/daemon.pid" ]; then
  pid="$(cat "$RUNTIME_DIR/daemon.pid" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    echo "daemon ja rodando (pid $pid, porta $(cat "$RUNTIME_DIR/port"))"
    exit 0
  fi
fi

# pid file sumiu mas o daemon responde? (sobrescrito por outra instancia)
if "$NODE_BIN" "$PROBE" "$PORT" >/dev/null 2>&1; then
  echo "daemon ja rodando na porta $PORT (pid file ausente)"
  exit 0
fi

setsid nohup "$NODE_BIN" "$DAEMON" >>"$RUNTIME_DIR/daemon.log" 2>&1 &
disown || true

# espera o port file aparecer
for _ in $(seq 1 40); do
  if [ -f "$RUNTIME_DIR/port" ] && [ -f "$RUNTIME_DIR/daemon.pid" ]; then
    echo "daemon up em 127.0.0.1:$(cat "$RUNTIME_DIR/port") (pid $(cat "$RUNTIME_DIR/daemon.pid"))"
    exit 0
  fi
  if "$NODE_BIN" "$PROBE" "$PORT" >/dev/null 2>&1; then
    echo "daemon up em 127.0.0.1:$PORT"
    exit 0
  fi
  sleep 0.25
done

echo "daemon nao subiu, veja $RUNTIME_DIR/daemon.log" >&2
exit 1
