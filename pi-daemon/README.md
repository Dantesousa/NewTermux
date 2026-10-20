# pi-daemon — daemon RPC do Nebula Pi (Termux)

Espelha `~/work/nebula-pi/daemon/` — fonte versionada do que roda no Termux.

- `pi-rpc-daemon.mjs` — socket TCP 127.0.0.1:37877 <-> `pi --mode rpc` (JSONL).
  Comandos próprios `nebula_*` respondidos no daemon:
  - `nebula_sessions` / `nebula_delete_session` / `nebula_rename_session`
  - `nebula_memory` (lê `~/.pi/agent/pi-hermes-memory/{MEMORY,USER,failures}.md`,
    formato `§`-separado) / `nebula_memory_save`
- `pi-rpc-start.sh` — sobe em background (setsid/nohup), port/pid/log em `~/.cache/nebula-pi/`
- `pi-probe.mjs` — healthcheck (`get_state`)

Uso no Termux:

```bash
pi-rpc              # sobe (~/bin/pi-rpc -> este start.sh)
pi-rpc-test         # testa (prompt + imagem)
```

Sync: após editar aqui, copiar de volta pra `~/work/nebula-pi/daemon/` (ou o inverso) — os dois precisam ficar iguais.
