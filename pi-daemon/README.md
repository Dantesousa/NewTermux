# pi-daemon — Nebula Pi RPC daemon (Termux)

Mirrors `~/work/nebula-pi/daemon/` — the versioned source of what runs in Termux.

- `pi-rpc-daemon.mjs` — TCP socket 127.0.0.1:37877 <-> `pi --mode rpc` (JSONL).
  Daemon-owned `nebula_*` commands are answered in the daemon:
  - `nebula_sessions` / `nebula_delete_session` / `nebula_rename_session`
  - `nebula_memory` (reads `~/.pi/agent/pi-hermes-memory/{MEMORY,USER,failures}.md`,
    `§`-separated format) / `nebula_memory_save`
- `pi-rpc-start.sh` — starts it in the background (setsid/nohup), port/pid/log in `~/.cache/nebula-pi/`
- `pi-probe.mjs` — health check (`get_state`)

Usage in Termux:

```bash
pi-rpc              # starts it (~/bin/pi-rpc -> this start.sh)
pi-rpc-test         # tests it (prompt + image)
```

Sync: after editing here, copy back to `~/work/nebula-pi/daemon/` (or the other way around) — both must stay identical.
