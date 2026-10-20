#!/usr/bin/env node
/**
 * Nebula Pi RPC daemon
 *
 * Expoe o Pi (modo RPC, protocolo JSONL) atraves de um socket TCP local.
 * O app Android (Nebula) conecta nesse socket e fala o mesmo protocolo
 * documentado em pi docs/rpc.md.
 *
 * Por que TCP e nao unix domain socket: o fork do Termux tem minSdkVersion=21
 * e java.net.UnixDomainSocketAddress so existe a partir da API 26.
 *
 * Por que daemon e nao Pi dentro do APK: empacotar Node no Android nao rola
 * (nodejs-mobile e Node 12, o Pi exige 18+). Rodando aqui o Pi usa o ambiente
 * Termux de verdade: mesmo bash, mesmas ferramentas, mesmos arquivos.
 *
 * Env:
 *   NEBULA_PI_BIN      caminho do executavel do Pi (default ~/.pi/agent/bin/pi)
 *   NEBULA_PI_CWD      working directory do Pi (default $HOME)
 *   NEBULA_PI_ARGS     argumentos extras (default: --mode rpc)
 *   NEBULA_PI_PORT     porta fixa (default: 37877)
 *   NEBULA_PI_RUNTIME_DIR  dir do port file/pid/log (default ~/.cache/nebula-pi)
 *   NEBULA_PI_AUTORESTART=1  reinicia o Pi se ele morrer (default 1)
 *
 * A porta e FIXA de proposito: o app Android pode ser o flavor demo
 * (com.termux.demo), que roda com outro UID e nao enxerga o port file do
 * Termux real. TCP em localhost funciona entre UIDs diferentes, entao a
 * porta fixa deixa o app achar o daemon sem compartilhar nenhum arquivo.
 */

import net from 'node:net';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';

const HOME = os.homedir();
const RUNTIME_DIR = process.env.NEBULA_PI_RUNTIME_DIR || path.join(HOME, '.cache', 'nebula-pi');
const PORT_FILE = path.join(RUNTIME_DIR, 'port');
const PID_FILE = path.join(RUNTIME_DIR, 'daemon.pid');
const LOG_FILE = path.join(RUNTIME_DIR, 'daemon.log');
const HOST = '127.0.0.1';

const PI_BIN = process.env.NEBULA_PI_BIN || path.join(HOME, '.pi', 'agent', 'bin', 'pi');
const PI_CWD = process.env.NEBULA_PI_CWD || HOME;
const PI_ARGS = (process.env.NEBULA_PI_ARGS || '--mode rpc').split(/\s+/).filter(Boolean);
const SESSION_DIR = process.env.NEBULA_PI_SESSION_DIR || path.join(HOME, '.pi', 'agent', 'sessions');
const WANT_PORT = Number(process.env.NEBULA_PI_PORT || 37877);
const AUTORESTART = process.env.NEBULA_PI_AUTORESTART !== '0';
const TERMUX_PREFIX = process.env.TERMUX_PREFIX || '/data/data/com.termux/files/usr';

fs.mkdirSync(RUNTIME_DIR, { recursive: true });

function log(...args) {
  const line = `[${new Date().toISOString()}] ${args.join(' ')}\n`;
  try {
    fs.appendFileSync(LOG_FILE, line);
  } catch {}
  if (process.env.NEBULA_PI_VERBOSE) process.stderr.write(line);
}

const CHILD_ENV = {
  ...process.env,
  HOME,
  TERM: 'xterm-256color',
  COLORTERM: 'truecolor',
  LANG: process.env.LANG || 'en_US.UTF-8',
  PATH: [
    // bin do pkg (nodejs do Termux) vem primeiro: o node do pi-node e build
    // glibc linux-arm64 e nao executa no bionic do Android.
    path.join(TERMUX_PREFIX, 'bin'),
    path.join(HOME, '.pi', 'agent', 'bin'),
    path.join(HOME, 'bin'),
    path.join(HOME, '.local', 'share', 'pi-node', 'current', 'bin'),
    '/usr/bin',
    '/bin',
  ].join(':'),
};

/** @type {import('node:child_process').ChildProcessWithoutNullStreams | null} */
let pi = null;
let restarting = false;
const clients = new Set();

// ---------------------------------------------------------------- pi process

function startPi() {
  log('iniciando pi:', PI_BIN, PI_ARGS.join(' '), 'cwd=' + PI_CWD);
  pi = spawn(PI_BIN, PI_ARGS, {
    cwd: PI_CWD,
    env: CHILD_ENV,
    stdio: ['pipe', 'pipe', 'pipe'],
  });

  pi.stdout.on('data', (chunk) => {
    // bytes crus: o cliente faz o split por LF (protocolo e JSONL)
    for (const socket of clients) {
      if (!socket.destroyed) socket.write(chunk);
    }
  });

  pi.stderr.on('data', (chunk) => {
    log('pi stderr:', chunk.toString('utf8').trimEnd());
  });

  pi.on('error', (err) => {
    log('pi erro de spawn:', err.message);
  });

  pi.on('exit', (code, signal) => {
    log('pi saiu code=' + code + ' signal=' + signal);
    pi = null;
    if (restarting) return;
    if (!AUTORESTART) {
      shutdown(0);
      return;
    }
    restarting = true;
    setTimeout(() => {
      restarting = false;
      log('reiniciando pi...');
      startPi();
    }, 2000);
  });
}

// ------------------------------------------------- comandos proprios (sessoes)

/**
 * O Pi nao expoe listar/apagar sessoes no RPC, entao o daemon responde esses
 * comandos antes de encaminhar para o pi. Resposta no mesmo formato do RPC.
 */
function handleNebulaCommand(socket, line) {
  let rec;
  try {
    rec = JSON.parse(line);
  } catch {
    return false;
  }
  const type = rec.type;
  if (typeof type !== 'string' || !type.startsWith('nebula_')) return false;

  const respond = (success, data, error) => {
    const out = { id: rec.id, type: 'response', command: type, success };
    if (data) out.data = data;
    if (error) out.error = error;
    try {
      socket.write(JSON.stringify(out) + '\n');
    } catch (e) {
      log('erro ao responder', type, e.message);
    }
  };

  try {
    switch (type) {
      case 'nebula_sessions':
        respond(true, { sessions: listSessions() });
        return true;
      case 'nebula_delete_session': {
        const deleted = deleteSession(rec.path);
        respond(true, { deleted });
        return true;
      }
      case 'nebula_rename_session': {
        const ok = renameSession(rec.path, rec.name);
        respond(true, { renamed: ok });
        return true;
      }
      case 'nebula_memory':
        respond(true, { memories: listMemories() });
        return true;
      case 'nebula_memory_save': {
        const saved = saveMemory(rec.text, rec.file || 'MEMORY.md');
        respond(true, { saved });
        return true;
      }
      default:
        respond(false, null, 'comando desconhecido: ' + type);
        return true;
    }
  } catch (e) {
    log('erro no comando', type, e.message);
    respond(false, null, e.message);
    return true;
  }
}

function listSessions() {
  const out = [];
  let dirs = [];
  try {
    dirs = fs.readdirSync(SESSION_DIR).map((d) => path.join(SESSION_DIR, d));
  } catch {
    return out;
  }
  for (const dir of dirs) {
    let files = [];
    try {
      if (!fs.statSync(dir).isDirectory()) continue;
      files = fs.readdirSync(dir).filter((f) => f.endsWith('.jsonl'));
    } catch {
      continue;
    }
    for (const f of files) {
      const file = path.join(dir, f);
      try {
        const st = fs.statSync(file);
        const raw = fs.readFileSync(file, 'utf8');
        const lines = raw.split('\n');
        let id = '';
        let timestamp = '';
        let cwd = '';
        let title = '';
        let messages = 0;
        let userMessages = 0;
        for (const line of lines) {
          if (!line.trim()) continue;
          let d;
          try {
            d = JSON.parse(line);
          } catch {
            continue;
          }
          if (d.type === 'session') {
            id = d.id || '';
            timestamp = d.timestamp || '';
            cwd = d.cwd || '';
          } else if (d.type === 'message') {
            messages++;
            const m = d.message || {};
            if (m.role === 'user') {
              userMessages++;
              if (!title) {
                const c = m.content;
                let text = '';
                if (typeof c === 'string') text = c;
                else if (Array.isArray(c)) {
                  text = c
                    .filter((x) => x && x.type === 'text')
                    .map((x) => x.text || '')
                    .join(' ');
                }
                title = text.replace(/\s+/g, ' ').trim().slice(0, 120);
              }
            }
          }
        }
        out.push({
          id: id || path.basename(f, '.jsonl'),
          title,
          file,
          modified: st.mtimeMs,
          size: st.size,
          messages,
          userMessages,
          cwd,
          timestamp,
        });
      } catch (e) {
        log('erro ao ler sessao', file, e.message);
      }
    }
  }
  out.sort((a, b) => b.modified - a.modified);
  return out;
}

/** Apaga um arquivo de sessao, so dentro do diretorio de sessoes. */
function deleteSession(file) {
  if (!file) return false;
  const resolved = path.resolve(file);
  const root = path.resolve(SESSION_DIR);
  if (!resolved.startsWith(root + path.sep)) {
    log('recusando apagar fora do dir de sessoes:', resolved);
    return false;
  }
  try {
    fs.unlinkSync(resolved);
    log('sessao apagada:', resolved);
    return true;
  } catch (e) {
    log('erro ao apagar sessao:', resolved, e.message);
    return false;
  }
}

function renameSession(file, name) {
  if (!file || !name) return false;
  const resolved = path.resolve(file);
  const root = path.resolve(SESSION_DIR);
  if (!resolved.startsWith(root + path.sep)) return false;
  const safe = String(name).replace(/[^A-Za-z0-9 ._-]/g, '_').slice(0, 60).trim();
  if (!safe) return false;
  try {
    // renomeia o arquivo e o id interno da sessao (primeira linha)
    const lines = fs.readFileSync(resolved, 'utf8').split('\n');
    if (lines.length && lines[0].includes('"type":"session"')) {
      const head = JSON.parse(lines[0]);
      head.name = safe;
      lines[0] = JSON.stringify(head);
      fs.writeFileSync(resolved, lines.join('\n'));
    }
    const dir = path.dirname(resolved);
    const base = path.basename(resolved, '.jsonl');
    const target = path.join(dir, base + '-' + safe.replace(/\s+/g, '_') + '.jsonl');
    fs.renameSync(resolved, target);
    log('sessao renomeada:', target);
    return true;
  } catch (e) {
    log('erro ao renomear sessao:', e.message);
    return false;
  }
}

// ------------------------------------------------------------------ memória

/**
 * Memórias do Hermes (extensão pi-hermes-memory).
 *
 * O app Android (mesmo no flavor demo, que roda com outro UID) não consegue
 * ler ~/.pi/agent/pi-hermes-memory direto, então o daemon lê e devolve.
 *
 * Formato dos arquivos: entradas separadas por uma linha só com "§", e cada
 * entrada termina com um comentário <!-- created=..., last=... -->.
 */
const MEMORY_DIR = process.env.NEBULA_PI_MEMORY_DIR || path.join(HOME, '.pi', 'agent', 'pi-hermes-memory');
const MEMORY_FILES = ['MEMORY.md', 'USER.md', 'failures.md'];

function parseMemoryFile(file) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch {
    return [];
  }
  const out = [];
  for (const chunk of raw.split(/^§\s*$/m)) {
    const text = chunk.trim();
    if (!text) continue;
    let created = '';
    let last = '';
    const meta = text.match(/<!--\s*created=([^,]*),\s*last=([^>]*?)\s*-->\s*$/);
    let body = text;
    if (meta) {
      created = (meta[1] || '').trim();
      last = (meta[2] || '').trim();
      body = text.slice(0, meta.index).trim();
    }
    if (!body) continue;
    out.push({ file: path.basename(file), text: body, created, last });
  }
  return out;
}

function listMemories() {
  const out = [];
  for (const name of MEMORY_FILES) {
    out.push(...parseMemoryFile(path.join(MEMORY_DIR, name)));
  }
  return out;
}

/** Acrescenta uma memória nova no arquivo indicado. */
function saveMemory(text, file) {
  if (!text) return false;
  const name = MEMORY_FILES.includes(file) ? file : 'MEMORY.md';
  const target = path.join(MEMORY_DIR, name);
  const stamp = new Date().toISOString().slice(0, 10);
  const entry = `${text.trim()} <!-- created=${stamp}, last=${stamp} -->\n§\n`;
  try {
    fs.mkdirSync(MEMORY_DIR, { recursive: true });
    const current = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : '';
    const next = current && !current.endsWith('\n') ? current + '\n' + entry : current + entry;
    fs.writeFileSync(target, next);
    log('memoria salva em', name);
    return true;
  } catch (e) {
    log('erro ao salvar memoria:', e.message);
    return false;
  }
}

// ------------------------------------------------------------------ servidor

const server = net.createServer((socket) => {
  log('cliente conectado de', socket.remoteAddress + ':' + socket.remotePort);
  socket.setNoDelay(true);
  clients.add(socket);

  socket.on('data', (chunk) => {
    socket._nebulaBuf = (socket._nebulaBuf || '') + chunk.toString('utf8');
    let idx;
    while ((idx = socket._nebulaBuf.indexOf('\n')) >= 0) {
      const line = socket._nebulaBuf.slice(0, idx).replace(/\r$/, '');
      socket._nebulaBuf = socket._nebulaBuf.slice(idx + 1);
      if (!line.trim()) continue;
      // Comandos proprios do daemon (sessoes) sao respondidos aqui.
      if (handleNebulaCommand(socket, line)) continue;
      if (!pi || !pi.stdin.writable) {
        log('comando recebido sem pi rodando, descartando');
        continue;
      }
      // log curto do que chegou, pra conferir que o app mandou o prompt
      log('<- cliente:', line.slice(0, 160).replace(/\n/g, ' '));
      pi.stdin.write(line + '\n');
    }
  });

  const bye = () => {
    clients.delete(socket);
    log('cliente desconectado, restam', clients.size);
  };
  socket.on('close', bye);
  socket.on('end', bye);
  socket.on('error', (err) => {
    log('socket erro:', err.message);
    bye();
  });
});

server.on('error', async (err) => {
  if (err.code === 'EADDRINUSE') {
    // Ja existe um daemon nosso rodando? Se sim, nao e erro.
    if (await probeExisting()) {
      log('ja existe um daemon rodando na porta ' + WANT_PORT + ', encerrando');
      // NAO remove o port/pid file: sao do daemon que continua rodando.
      process.exit(0);
    }
    log('porta ' + WANT_PORT + ' ocupada por outro processo, Giving up');
  }
  log('server erro:', err.message);
  shutdown(1);
});

/** Tenta falar com o protocolo na porta: se responder, ja ha um daemon nosso. */
async function probeExisting() {
  return new Promise((resolve) => {
    const sock = net.createConnection({ host: HOST, port: WANT_PORT });
    let buf = '';
    const done = (ok) => {
      sock.destroy();
      resolve(ok);
    };
    const timer = setTimeout(() => done(false), 1500);
    sock.on('connect', () => sock.write(JSON.stringify({ id: 'probe', type: 'get_state' }) + '\n'));
    sock.on('data', (d) => {
      buf += d.toString('utf8');
      if (buf.includes('"command":"get_state"')) {
        clearTimeout(timer);
        done(true);
      }
    });
    sock.on('error', () => {
      clearTimeout(timer);
      done(false);
    });
    sock.on('close', () => {
      clearTimeout(timer);
      done(false);
    });
  });
}

function shutdown(code) {
  log('encerrando daemon');
  try {
    if (pi && !pi.killed) pi.kill('SIGTERM');
  } catch {}
  try {
    fs.unlinkSync(PORT_FILE);
  } catch {}
  try {
    fs.unlinkSync(PID_FILE);
  } catch {}
  server.close(() => process.exit(code));
  setTimeout(() => process.exit(code), 1500);
}

process.on('SIGTERM', () => shutdown(0));
process.on('SIGINT', () => shutdown(0));
process.on('uncaughtException', (err) => {
  log('uncaught:', err.stack || err.message);
});

server.listen(WANT_PORT, HOST, () => {
  const port = server.address().port;
  fs.writeFileSync(PORT_FILE, String(port));
  fs.writeFileSync(PID_FILE, String(process.pid));
  log('daemon ouvindo em ' + HOST + ':' + port + ' (port file: ' + PORT_FILE + ')');
  startPi();
});
