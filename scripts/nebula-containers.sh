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
#   gui setup <name> [--profile minimal|xfce]
#                                 Install the graphical stack inside the container.
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

# `am` and `pm` live in /system/bin; the app starts us with PATH=$PREFIX/bin only.
export PATH="$PREFIX_DIR/bin:/system/bin:/system/xbin${PATH:+:$PATH}"

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


# proot chdirs into the container dir, so the rootfs shows up as "."; the container directory
# still appears in the --bind arguments of the proot command line.
container_running() {
  local dir
  dir="$(container_dir "$1")"
  [ -n "$dir" ] && pgrep -f -- "$dir/" >/dev/null 2>&1
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
  case "$1:$2" in
    apk:minimal) printf 'openbox xterm xsetroot ttf-dejavu dbus-x11\n' ;;
    apk:xfce) printf 'xfce4 xfce4-terminal xsetroot ttf-dejavu font-noto adwaita-icon-theme xrdb dbus-x11\n' ;;
    apt:minimal) printf 'openbox xterm x11-xserver-utils fonts-dejavu dbus-x11\n' ;;
    apt:xfce) printf 'xfce4 xfce4-terminal x11-xserver-utils fonts-dejavu adwaita-icon-theme dbus-x11\n' ;;
    pacman:minimal) printf 'openbox xterm xorg-xsetroot ttf-dejavu\n' ;;
    pacman:xfce) printf 'xfce4 xfce4-terminal xorg-xsetroot ttf-dejavu adwaita-icon-theme\n' ;;
    dnf:minimal) printf 'openbox xterm xorg-x11-server-utils dejavu-sans-fonts\n' ;;
    dnf:xfce) printf 'xfce4-session xfwm4 xfce4-panel xfce4-terminal thunar adwaita-icon-theme xorg-x11-server-utils dejavu-sans-fonts\n' ;;
    *) return 1 ;;
  esac
}

# Runs inside the container: reports what kind of desktop it can offer.
desktop_probe() {
  printf '%s\n' 'PATH=/usr/sbin:/usr/bin:/sbin:/bin; if command -v startxfce4 >/dev/null 2>&1; then echo xfce; elif command -v openbox-session >/dev/null 2>&1 || command -v openbox >/dev/null 2>&1; then echo minimal; else echo none; fi'
}

# GTK loads images through glycin, which sandboxes each loader with bubblewrap. proot cannot create
# those namespaces, so every icon load fails and the panel dies. A shim that drops the sandbox flags
# and runs the payload is enough for a desktop inside a phone.
read -r -d '' BWRAP_SHIM <<'SHIM' || true
#!/bin/sh
# proot cannot create the namespaces bwrap needs, so run the payload unsandboxed.
while [ $# -gt 0 ]; do
  case "$1" in
    --) shift; break ;;
    --setenv) shift 3 ;;
    --unsetenv) shift 2 ;;
    --bind|--ro-bind|--dev-bind|--bind-try|--ro-bind-try|--dev-bind-try|--file|--symlink|--ro-overlay|--tmp-overlay)
      shift 3 ;;
    --chdir|--tmpfs|--dev|--proc|--mqueue|--dir|--seccomp|--add-seccomp-fd|--userns|--userns-block-fd|--cap-add|--cap-drop|--uid|--gid|--hostname|--perms|--size|--chmod)
      shift 2 ;;
    --*) shift ;;
    *) break ;;
  esac
done
exec "$@"
SHIM

install_bwrap_shim() {
  local name="$1" encoded
  encoded="$(printf '%s' "$BWRAP_SHIM" | base64 | tr -d '\n')"
  proot-distro login "$name" -- sh -c "mkdir -p /usr/local/bin && echo '$encoded' | base64 -d > /usr/local/bin/bwrap && chmod 755 /usr/local/bin/bwrap" \
    >/dev/null 2>&1
}

cmd_gui_setup() {
  local name="${1:-}" profile="xfce" a
  [ -n "$name" ] || die "usage: nebula-containers gui setup <name> [--profile minimal|xfce]" 2
  shift || true
  while [ $# -gt 0 ]; do
    case "$1" in
      --profile) profile="${2:-xfce}"; shift 2 ;;
      --profile=*) profile="${1#*=}"; shift ;;
      *) die "unknown option: $1" 2 ;;
    esac
  done
  case "$profile" in minimal | xfce) ;; *) die "unknown profile: $profile (minimal|xfce)" 2 ;; esac

  container_exists "$name" || die "no such container: $name"
  container_complete "$name" || die "container $name is incomplete, reinstall it first"

  local family pkgs
  family="$(container_family "$name")"
  pkgs="$(desktop_packages "$family" "$profile")" || die "unsupported distro family for $name (need apk/apt/pacman/dnf)"

  err "installing the $profile desktop in $name ($family)..."
  case "$family" in
    apk) proot-distro login "$name" -- sh -c "apk add --no-cache $pkgs" ;;
    apt) proot-distro login "$name" -- sh -c "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pkgs" ;;
    pacman) proot-distro login "$name" -- sh -c "pacman -Sy --noconfirm --needed $pkgs" ;;
    dnf) proot-distro login "$name" -- sh -c "dnf install -y $pkgs" ;;
  esac || die "desktop install failed"

  local detected
  install_bwrap_shim "$name"
  detected="$(desktop_profile_of "$name")"
  [ "$detected" != none ] || die "installation finished but no desktop was found inside $name"

  printf 'Desktop (%s) ready in %s. Start it with: nebula-containers gui start %s\n' \
    "$detected" "$name" "$name"
}

# One container login that reports which desktop flavour is installed.
desktop_profile_of() {
  local name="$1" out
  out="$(proot-distro login "$name" -- sh -c "$(desktop_probe)" 2>/dev/null | tail -1)"
  case "$out" in
    xfce | minimal) printf '%s\n' "$out" ;;
    *) printf 'none\n' ;;
  esac
}

# A socket left behind by a crashed server passes a bare [ -S ] test, so probe the display.
cmd_gui_status() {
  local name="${1:-}" json=0 a
  [ -n "$name" ] || die "usage: nebula-containers gui status <name>" 2
  shift || true
  for a in "$@"; do [ "$a" = "--json" ] && json=1; done

  local ready=false family='unknown' profile='none'
  if container_exists "$name" && container_complete "$name"; then
    family="$(container_family "$name")"
    profile="$(desktop_profile_of "$name")"
    [ "$profile" != none ] && ready=true
  fi

  if [ "$json" -eq 1 ]; then
    printf '{"name":%s,"family":%s,"ready":%s,"profile":%s}\n' \
      "$(json_str "$name")" "$(json_str "$family")" "$(json_bool "$ready")" "$(json_str "$profile")"
  else
    printf '%s: desktop %s (%s, %s)\n' "$name" \
      "$( [ "$ready" = true ] && echo ready || echo 'not installed')" "$profile" "$family"
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

# proot does not isolate PIDs, so a window manager from a previous session keeps the WM slot on
# the display and the new desktop refuses to start. Clear the clients, keep the X server.
clean_x_clients() {
  local proc
  for proc in openbox xfwm4 xfce4-session xfce4-panel xfsettingsd xfce4-notifyd tint2 xterm \
    pcmanfm lxterminal st wrapper-2.0; do
    pkill -x "$proc" >/dev/null 2>&1
  done
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
  clean_x_clients
  sleep 1

  local cmd
  case "$app" in
    # XFCE when present (panel, menu, file manager), otherwise a bare openbox + fullscreen xterm.
    desktop) cmd='xsetroot -solid "#12121a" 2>/dev/null
if command -v startxfce4 >/dev/null 2>&1; then
  exec dbus-run-session -- startxfce4
fi
openbox-session >/dev/null 2>&1 &
sleep 1
xterm -fullscreen -fa "Monospace:pixelsize=44" -bg "#12121a" -fg "#e6e6e6" -title Nebula
pkill -x openbox >/dev/null 2>&1' ;;
    chromium) cmd='xsetroot -solid "#12121a" 2>/dev/null; exec chromium --no-sandbox --disable-dev-shm-usage --start-maximized' ;;
    terminal) cmd='exec xterm -fa "Monospace:pixelsize=44" -bg "#12121a" -fg "#e6e6e6" -title Nebula' ;;
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
  clean_x_clients
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
