#!/data/data/com.termux/files/usr/bin/bash
# Sobe o daemon RPC do Pi em background (usado manualmente e pelo app Nebula).
set -euo pipefail

HOME_DIR="${HOME}"
RUNTIME_DIR="${NEBULA_PI_RUNTIME_DIR:-$HOME_DIR/.cache/nebula-pi}"
# Daemon versionado no repo; aceita os dois espelhos (repo e dir dev).
if [ -n "${NEBULA_PI_DAEMON:-}" ]; then
  DAEMON="$NEBULA_PI_DAEMON"
elif [ -f "$HOME_DIR/work/NewTermux/pi-daemon/pi-rpc-daemon.mjs" ]; then
  DAEMON="$HOME_DIR/work/NewTermux/pi-daemon/pi-rpc-daemon.mjs"
else
  DAEMON="$HOME_DIR/work/nebula-pi/daemon/pi-rpc-daemon.mjs"
fi

# Mantem o processo vivo quando o app vai pro fundo (o Android mata facil).
# Nao falha se o termux-wake-lock nao existir.
termux-wake-lock 2>/dev/null || true

# O node do pi-node e build glibc linux-arm64 e NAO roda no Termux (bionic):
# execve falha com ENOENT e o sh segue para o proximo PATH. O que funciona de
# verdade e o nodejs do pkg (Termux). Por isso testamos a execucao de verdade.
PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"

# Sem LD_PRELOAD os shebangs com /usr/bin/env e /bin/sh nao resolvem no
# Android (o .so reescreve esses caminhos para $PREFIX). Acontece quando o
# daemon e disparado sem env do Termux (boot, adb, outro UID): o sintoma e
# o loop `pi saiu code=126` / `node_modules/.bin/pi: not found` e o app fica
# em "subindo daemon sozinho". Reexporta aqui para o daemon e os filhos.
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
