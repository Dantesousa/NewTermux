#!/data/data/com.termux/files/usr/bin/bash
# nebula-containers — inspect and manage proot-distro containers from Nebula.
#
# Commands:
#   list [--json]                 Containers with version, size and state.
#   total [--json]                Disk usage of containers + image cache.
#   search <query> [--json]       Query Docker Hub through proot-distro.
#   install <image> [--desktop]   Install a container (optionally with a desktop).
#   remove <name>                 Delete a container.
#   clear-cache                   Drop cached OCI layers.
#   gui setup <name>              Install the graphical stack inside the container.
#   gui status <name> [--json]    Report whether the graphical stack is ready.
#   gui start <name> [opts]       Start X11 + desktop inside the container.
#   gui stop                      Stop the X11 server and the desktop session.
#   doctor [--json]               Report on the host requirements.
#
# Options for `gui start`:
#   --app desktop|chromium|terminal   What to run inside the container (default: desktop).
#   --no-viewer                       Do not bring up the Termux:X11 Android viewer.
#
# Environment:
#   NEBULA_PD_ROOT   Override the proot-distro data dir.
#
# Exit codes: 0 ok, 1 error, 2 usage, 3 missing dependency.

set -uo pipefail

PREFIX_DIR="${PREFIX:-/data/data/com.termux/files/usr}"
PD_ROOT="${NEBULA_PD_ROOT:-$PREFIX_DIR/var/lib/proot-distro}"
RUNTIME="${TMPDIR:-$PREFIX_DIR/tmp}"
DISPLAY_NUM="${NEBULA_DISPLAY:-:0}"
VIEWER_PKG="com.termux.x11"

err() { printf '%s\n' "nebula-containers: $*" >&2; }
die() { err "$*"; exit "${2:-1}"; }

usage() { sed -n '2,/^set -/p' "$0" | sed 's/^# \{0,1\}//; /^set -/d'; }

json_str() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"; }

json_bool() { if [ "$1" = "true" ]; then printf 'true'; else printf 'false'; fi; }

# ------------------------------------------------------------------ layout

containers_dir() {
  if [ -d "$PD_ROOT/containers" ]; then
    printf '%s\n' "$PD_ROOT/containers"
  elif [ -d "$PD_ROOT/installed-rootfs" ]; then
    printf '%s\n' "$PD_ROOT/installed-rootfs"
  fi
}

cache_dir() {
  if [ -d "$PD_ROOT/cache" ]; then
    printf '%s\n' "$PD_ROOT/cache"
  elif [ -d "$PD_ROOT/dlcache" ]; then
    printf '%s\n' "$PD_ROOT/dlcache"
  fi
}

container_dir() { printf '%s/%s\n' "$(containers_dir)" "$1"; }

# v4 keeps the tree under <container>/rootfs, older releases use the dir itself.
container_rootfs() {
  local dir="$1"
  if [ -d "$dir/rootfs" ]; then
    printf '%s\n' "$dir/rootfs"
  else
    printf '%s\n' "$dir"
  fi
}

container_exists() {
  local d
  d="$(container_dir "$1")"
  [ -n "$1" ] && [ -d "$d" ] && [ "$1" != ".." ]
}

# An interrupted install leaves an almost empty rootfs behind. Symlinks inside the rootfs
# point to absolute guest paths, so test entry counts instead of resolving binaries.
container_complete() {
  local root entries
  root="$(container_rootfs "$(container_dir "$1")")"
  [ -d "$root" ] || return 1
  entries="$(find "$root" -mindepth 1 -maxdepth 1 -printf '.' 2>/dev/null | wc -c)"
  [ "${entries:-0}" -ge 5 ] || return 1
  [ -e "$root/bin" ] || [ -e "$root/usr/bin" ]
}


container_running() {
  local root
  root="$(container_rootfs "$(container_dir "$1")")"
  pgrep -f -- "$root" >/dev/null 2>&1
}

kb_of_size() { du -sk "$1" 2>/dev/null | awk '{print $1+0}'; }

human_kb() {
  local kb="${1:-0}"
  if [ "$kb" -ge 1048576 ]; then
    printf '%.1f GB' "$(awk -v k="$kb" 'BEGIN{printf "%.1f", k/1048576}')"
  elif [ "$kb" -ge 1024 ]; then
    printf '%s MB' "$(awk -v k="$kb" 'BEGIN{printf "%.0f", k/1024}')"
  else
    printf '%s KB' "$kb"
  fi
}

# Image version: OCI manifest first, then /etc/os-release inside the rootfs.
container_version() {
  local dir root manifest v
  dir="$(container_dir "$1")"
  manifest="$dir/manifest.json"
  if [ -f "$manifest" ]; then
    v="$(sed -n 's/.*"org\.opencontainers\.image\.version": *"\([^"]*\)".*/\1/p' "$manifest" | head -1)"
    [ -n "$v" ] && { printf '%s\n' "$v"; return; }
  fi
  root="$(container_rootfs "$dir")"
  if [ -r "$root/etc/os-release" ]; then
    v="$(sed -n 's/^VERSION_ID="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$root/etc/os-release" | head -1)"
    [ -n "$v" ] && { printf '%s\n' "$v"; return; }
  fi
  printf '?\n'
}

# Package manager family, used to pick the desktop install recipe.
container_family() {
  local root id
  root="$(container_rootfs "$(container_dir "$1")")"
  if [ -r "$root/etc/os-release" ]; then
    id="$(sed -n 's/^ID="\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' "$root/etc/os-release" | head -1)"
    case "$id" in
      alpine) printf 'apk\n'; return ;;
      debian | ubuntu | kali | linuxmint | raspbian) printf 'apt\n'; return ;;
      arch | archarm | manjaro | endeavouros) printf 'pacman\n'; return ;;
      fedora | rhel | centos | rocky | almalinux) printf 'dnf\n'; return ;;
    esac
  fi
  printf 'unknown\n'
}

list_containers() {
  local dir
  dir="$(containers_dir)"
  [ -n "$dir" ] || return 0
  find "$dir" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null | sort
}

# ------------------------------------------------------------------ commands

cmd_list() {
  local json=0 a
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  local names name dir size_kb version complete running family first=1
  names="$(list_containers)"

  [ "$json" -eq 1 ] && printf '{"containers":['
  if [ -z "$names" ]; then
    [ "$json" -eq 1 ] && printf ']}\n' || printf 'No containers installed.\n'
    return 0
  fi

  while IFS= read -r name; do
    [ -n "$name" ] || continue
    dir="$(container_dir "$name")"
    size_kb="$(kb_of_size "$dir")"
    version="$(container_version "$name")"
    complete=false; container_complete "$name" && complete=true
    running=false; container_running "$name" && running=true
    family="$(container_family "$name")"

    if [ "$json" -eq 1 ]; then
      [ "$first" -eq 0 ] && printf ','
      first=0
      printf '{"name":%s,"version":%s,"size_kb":%s,"complete":%s,"running":%s,"family":%s}' \
        "$(json_str "$name")" "$(json_str "$version")" "$size_kb" \
        "$(json_bool "$complete")" "$(json_bool "$running")" "$(json_str "$family")"
    else
      printf '%-16s %-12s %-10s %s%s\n' \
        "$name" "$version" "$(human_kb "$size_kb")" \
        "$( [ "$running" = true ] && printf 'running' || printf 'stopped')" \
        "$( [ "$complete" = true ] || printf ' (incomplete install)')"
    fi
  done <<< "$names"

  [ "$json" -eq 1 ] && printf ']}\n'
  return 0
}

cmd_total() {
  local json=0 a names name size_kb total=0 cache_kb free_kb
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  names="$(list_containers)"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    size_kb="$(kb_of_size "$(container_dir "$name")")"
    total=$((total + size_kb))
  done <<< "$names"

  cache_kb=0
  if [ -n "$(cache_dir)" ]; then
    cache_kb="$(kb_of_size "$(cache_dir)")"
  fi
  free_kb="$(df -k "$PD_ROOT" 2>/dev/null | awk 'NR==2{print $4+0}')"

  if [ "$json" -eq 1 ]; then
    printf '{"containers_kb":%s,"cache_kb":%s,"free_kb":%s}\n' \
      "$total" "${cache_kb:-0}" "${free_kb:-0}"
  else
    printf 'containers  %s\n' "$(human_kb "$total")"
    printf 'cache       %s\n' "$(human_kb "${cache_kb:-0}")"
    printf 'free        %s\n' "$(human_kb "${free_kb:-0}")"
  fi
}

cmd_search() {
  local query="${1:-}" json=0 a
  [ -n "$query" ] || die "usage: nebula-containers search <query>" 2
  shift || true
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  local out
  out="$(proot-distro search "$query" 2>&1)" || die "search failed: $out"

  if [ "$json" -eq 0 ]; then
    printf '%s\n' "$out"
    return 0
  fi

  # Rows look like: "NAME   DESCRIPTION   STARS   PULLS   OFFICIAL"
  printf '{"images":['
  local first=1 name desc stars pulls official
  local rows
  rows="$(printf '%s\n' "$out" | awk '
    /^NAME[ \t]/ { table = 1; next }
    !table { next }
    /^[ \t]*$/ { table = 0; next }
    {
      official = ($0 ~ /\[OK\]/) ? "true" : "false"
      sub(/ *\[OK\] *$/, "")
      if (NF < 3) next
      name = $1
      pulls = $NF
      stars = $(NF - 1)
      desc = substr($0, length(name) + 1)
      sub(/[ \t]+[^ \t]+[ \t]+[^ \t]+[ \t]*$/, "", desc)
      gsub(/^[ \t]+|[ \t]+$/, "", desc)
      printf "%s\037%s\037%s\037%s\037%s\n", name, desc, stars, pulls, official
    }
  ')"
  while IFS=$'\037' read -r name desc stars pulls official; do
    [ -n "$name" ] || continue
    [ "$first" -eq 0 ] && printf ','
    first=0
    printf '{"name":%s,"description":%s,"stars":%s,"pulls":%s,"official":%s}' \
      "$(json_str "$name")" "$(json_str "$desc")" "$(json_str "$stars")" \
      "$(json_str "$pulls")" "$(json_bool "$official")"
  done <<< "$rows"
  printf ']}\n'
}

cmd_install() {
  local image="${1:-}" with_desktop=0 a
  [ -n "$image" ] || die "usage: nebula-containers install <image> [--desktop]" 2
  shift || true
  for a in "$@"; do [ "$a" = "--desktop" ] && with_desktop=1; done

  command -v proot-distro >/dev/null || die "proot-distro is not installed (pkg install proot-distro)" 3
  err "installing $image (this can take a few minutes)..."
  proot-distro install "$image" || die "install failed"

  if [ "$with_desktop" -eq 1 ]; then
    cmd_gui setup "$image" || die "installed $image but the desktop stack failed"
  fi
}

cmd_remove() {
  local name="${1:-}"
  [ -n "$name" ] || die "usage: nebula-containers remove <name>" 2
  container_exists "$name" || die "no such container: $name"
  container_running "$name" && die "container $name is running, stop it first"
  proot-distro remove -y "$name" 2>/dev/null || proot-distro remove "$name" || die "remove failed"
}

cmd_clear_cache() {
  local dir
  dir="$(cache_dir)"
  [ -n "$dir" ] || { printf 'No image cache.\n'; return 0; }
  local before
  before="$(human_kb "$(kb_of_size "$dir")")"
  proot-distro clear-cache >/dev/null 2>&1 || rm -rf "$dir"
  mkdir -p "$dir"
  printf 'Cleared image cache (%s freed).\n' "$before"
}

# ------------------------------------------------------------------ desktop

desktop_packages() {
  case "$1" in
    apk) printf 'openbox chromium xterm font-noto ttf-dejavu dbus-x11 imagemagick\n' ;;
    apt) printf 'openbox chromium xterm fonts-dejavu dbus-x11 imagemagick\n' ;;
    pacman) printf 'openbox chromium xterm ttf-dejavu dbus imagemagick\n' ;;
    dnf) printf 'openbox chromium xterm dejavu-sans-fonts dbus-x11 ImageMagick\n' ;;
    *) return 1 ;;
  esac
}

cmd_gui_setup() {
  local name="${1:-}"
  [ -n "$name" ] || die "usage: nebula-containers gui setup <name>" 2
  container_exists "$name" || die "no such container: $name"
  container_complete "$name" || die "container $name is incomplete, reinstall it first"

  local family pkgs
  family="$(container_family "$name")"
  pkgs="$(desktop_packages "$family")" || die "unsupported distro family for $name (need apk/apt/pacman/dnf)"

  err "installing the desktop stack in $name ($family)..."
  case "$family" in
    apk) proot-distro login "$name" -- sh -c "apk add --no-cache $pkgs" ;;
    apt) proot-distro login "$name" -- sh -c "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgs" ;;
    pacman) proot-distro login "$name" -- sh -c "pacman -Sy --noconfirm --needed $pkgs" ;;
    dnf) proot-distro login "$name" -- sh -c "dnf install -y $pkgs" ;;
  esac || die "desktop install failed"

  local missing=''
  local bin
  for bin in openbox-session xterm chromium chromium-browser; do
    if proot-distro login "$name" -- sh -c "PATH=/usr/sbin:/usr/bin:/sbin:/bin command -v $bin" >/dev/null 2>&1; then
      missing=''
      break
    fi
    missing='yes'
  done
  [ -z "$missing" ] || err "warning: no window manager or terminal found inside $name"

  printf 'Desktop ready in %s. Start it with: nebula-containers gui start %s\n' "$name" "$name"
}

# A socket left behind by a crashed server passes a bare [ -S ] test, so probe the display.
cmd_gui_status() {
  local name="${1:-}" json=0 a
  [ -n "$name" ] || die "usage: nebula-containers gui status <name>" 2
  shift || true
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  local ready=false family='unknown'
  if container_exists "$name" && container_complete "$name"; then
    family="$(container_family "$name")"
    if proot-distro login "$name" -- sh -c 'PATH=/usr/sbin:/usr/bin:/sbin:/bin; { command -v openbox-session || command -v openbox; } >/dev/null 2>&1 && command -v xterm >/dev/null 2>&1' >/dev/null 2>&1; then
      ready=true
    fi
  fi

  if [ "$json" -eq 1 ]; then
    printf '{"name":%s,"family":%s,"ready":%s}\n' \
      "$(json_str "$name")" "$(json_str "$family")" "$(json_bool "$ready")"
  else
    printf '%s: desktop %s (%s)\n' "$name" \
      "$( [ "$ready" = true ] && echo ready || echo 'not installed')" "$family"
  fi
}

x11_probe() {
  local disp="${1:-$DISPLAY_NUM}"
  if command -v xdotool >/dev/null 2>&1; then
    DISPLAY="$disp" xdotool getdisplaygeometry >/dev/null 2>&1 && return 0
    return 1
  fi
  [ -S "$RUNTIME/.X11-unix/X${disp#:}" ]
}

x11_running() { x11_probe "$DISPLAY_NUM"; }

ensure_x11() {
  if x11_running; then
    return 0
  fi
  command -v termux-x11 >/dev/null || die "termux-x11 is not installed (pkg install x11-repo termux-x11-nightly)" 3
  # Drop the socket of a server that died without cleaning up after itself.
  rm -f "$RUNTIME/.X11-unix/X${DISPLAY_NUM#:}" 2>/dev/null
  (setsid nohup termux-x11 "$DISPLAY_NUM" >"$RUNTIME/x11.log" 2>&1 &)
  local i
  for i in $(seq 1 40); do
    x11_running && return 0
    sleep 0.5
  done
  die "X11 server did not start (see $RUNTIME/x11.log)"
}

ensure_viewer() {
  am start -n "$VIEWER_PKG/.MainActivity" >/dev/null 2>&1 || err "could not open the Termux:X11 viewer"
}

cmd_gui_start() {
  local name="${1:-}" app="desktop" viewer=1 a
  [ -n "$name" ] || die "usage: nebula-containers gui start <name> [--app ...]" 2
  shift || true
  while [ $# -gt 0 ]; do
    case "$1" in
      --app) app="${2:-desktop}"; shift 2 ;;
      --app=*) app="${1#*=}"; shift ;;
      --no-viewer) viewer=0; shift ;;
      *) die "unknown option: $1" 2 ;;
    esac
  done
  container_exists "$name" || die "no such container: $name"
  container_complete "$name" || die "container $name is incomplete, reinstall it first"

  ensure_x11
  [ "$viewer" -eq 1 ] && ensure_viewer

  local cmd
  case "$app" in
    desktop) cmd='openbox-session >/dev/null 2>&1 & sleep 1; exec xterm' ;;
    chromium) cmd='exec chromium --no-sandbox --disable-dev-shm-usage' ;;
    terminal) cmd='exec xterm' ;;
    *) die "unknown app: $app (desktop|chromium|terminal)" 2 ;;
  esac

  # Host binaries must not leak into the container: set the guest PATH explicitly.
  local guest_path='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'

  err "starting $app in $name on $DISPLAY_NUM"
  exec proot-distro login "$name" --shared-x11 -- \
    env -i PATH="$guest_path" HOME=/root DISPLAY="$DISPLAY_NUM" TERM=xterm-256color \
    sh -c "$cmd"
}

cmd_gui_stop() {
  pkill -f "termux-x11" 2>/dev/null && printf 'X11 server stopped.\n' || printf 'X11 server was not running.\n'
  rm -f "$RUNTIME/.X11-unix/X${DISPLAY_NUM#:}" 2>/dev/null
}

# ------------------------------------------------------------------ doctor

cmd_doctor() {
  local json=0 a
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  local pd_ok=false x11_ok=false viewer_ok=false cache_ok=false free_kb=0
  command -v proot-distro >/dev/null 2>&1 && pd_ok=true
  command -v termux-x11 >/dev/null 2>&1 && x11_ok=true
  command -v am >/dev/null 2>&1 && pm list packages 2>/dev/null | grep -q "$VIEWER_PKG" && viewer_ok=true
  [ -n "$(cache_dir)" ] && cache_ok=true
  free_kb="$(df -k "$PD_ROOT" 2>/dev/null | awk 'NR==2{print $4+0}')"

  if [ "$json" -eq 1 ]; then
    printf '{"proot_distro":%s,"termux_x11":%s,"x11_viewer":%s,"free_kb":%s}\n' \
      "$(json_bool "$pd_ok")" "$(json_bool "$x11_ok")" "$(json_bool "$viewer_ok")" "${free_kb:-0}"
    return 0
  fi
  printf 'proot-distro   %s\n' "$( [ "$pd_ok" = true ] && echo ok || echo missing)"
  printf 'termux-x11     %s\n' "$( [ "$x11_ok" = true ] && echo ok || echo missing)"
  printf 'X11 viewer     %s\n' "$( [ "$viewer_ok" = true ] && echo ok || echo missing)"
  printf 'free space     %s\n' "$(human_kb "${free_kb:-0}")"
}

# ------------------------------------------------------------------ main

main() {
  local cmd="${1:-}"
  [ $# -gt 0 ] && shift

  case "$cmd" in
    list | ls) cmd_list "$@" ;;
    total | df) cmd_total "$@" ;;
    search) cmd_search "$@" ;;
    install) cmd_install "$@" ;;
    remove | rm) cmd_remove "$@" ;;
    clear-cache) cmd_clear_cache ;;
    doctor) cmd_doctor "$@" ;;
    gui)
      local sub="${1:-}"; [ $# -gt 0 ] && shift
      case "$sub" in
        setup) cmd_gui_setup "$@" ;;
        status) cmd_gui_status "$@" ;;
        start) cmd_gui_start "$@" ;;
        stop) cmd_gui_stop ;;
        *) die "usage: nebula-containers gui setup|status|start|stop" 2 ;;
      esac
      ;;
    '' | help | -h | --help) usage ;;
    *) die "unknown command: $cmd (try: nebula-containers help)" 2 ;;
  esac
}

main "$@"
