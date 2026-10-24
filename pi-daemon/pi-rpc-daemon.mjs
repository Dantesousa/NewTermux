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
 *   NEBULA_PI_BIN      caminho do executavel do Pi (override absoluto;
 *                        default ~/.pi/agent/bin/pi)
 *   NEBULA_PI_ENGINE   "pi" ou "pi-bolt" (vale se NEBULA_PI_BIN nao setado)
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
const TERMUX_PREFIX = process.env.TERMUX_PREFIX || '/data/data/com.termux/files/usr';

const PI_CWD = process.env.NEBULA_PI_CWD || HOME;

// ---- engine: Pi (Node) ou Pi-Bolt (nativo) ----
//
// Ordem de resolucao (boot): NEBULA_PI_BIN absoluto > NEBULA_PI_ENGINE
// ("pi"|"pi-bolt") > arquivo runtime/engine > padrao (pi).
// O arquivo runtime/engine e escrito pelo comando nebula_set_engine, entao a
// escolha sobrevive a reinicios do daemon. NEBULA_PI_BIN continua valendo
// como override absoluto (compatibilidade com o comportamento anterior).
//
// As duas engines leem e escrevem o MESMO ~/.pi/agent (config, auth,
// sessoes, memorias): trocar de engine nunca apaga dado, so comeca uma
// sessao nova no processo novo. A sessao anterior continua listada em
// nebula_sessions e pode ser reaberta em qualquer engine.
const ENGINE_FILE = path.join(RUNTIME_DIR, 'engine');
const ENGINES = {
  pi: {
    id: 'pi',
    name: 'Pi (Node)',
    bin: path.join(HOME, '.pi', 'agent', 'bin', 'pi'),
  },
  'pi-bolt': {
    id: 'pi-bolt',
    name: 'Pi-Bolt (nativo)',
    bin: path.join(TERMUX_PREFIX, 'bin', 'pi-bolt'),
  },
};

function resolveEngineId() {
  const fromEnv = String(process.env.NEBULA_PI_ENGINE || '').trim();
  if (fromEnv === 'pi' || fromEnv === 'pi-bolt') return fromEnv;
  try {
    const saved = fs.readFileSync(ENGINE_FILE, 'utf8').trim();
    if (saved === 'pi' || saved === 'pi-bolt') return saved;
  } catch {}
  return 'pi';
}

let engineId = resolveEngineId();
let PI_BIN = ENGINES[engineId].bin;
// Override absoluto (comportamento anterior): usa o binario indicado.
// A troca em runtime (nebula_set_engine) limpa esse override no processo.
if (process.env.NEBULA_PI_BIN) PI_BIN = process.env.NEBULA_PI_BIN;
const PI_ARGS = (process.env.NEBULA_PI_ARGS || '--mode rpc').split(/\s+/).filter(Boolean);
const SESSION_DIR = process.env.NEBULA_PI_SESSION_DIR || path.join(HOME, '.pi', 'agent', 'sessions');
const WANT_PORT = Number(process.env.NEBULA_PI_PORT || 37877);
const AUTORESTART = process.env.NEBULA_PI_AUTORESTART !== '0';
// Mesmo motivo do start script: sem o preload do Termux os filhos nao
// conseguem executar shebangs (/usr/bin/env, /bin/sh) e o pi morre com
// exit 126 em loop. Garante aqui tambem caso o daemon tenha sido herdado
// sem env (o start script ja exporta, isto e segunda barreira).
const TERMUX_PRELOAD = path.join(TERMUX_PREFIX, 'lib', 'libtermux-exec-ld-preload.so');
const CHILD_LD_PRELOAD = process.env.LD_PRELOAD
  || (fs.existsSync(TERMUX_PRELOAD) ? TERMUX_PRELOAD : undefined);

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
  ...(CHILD_LD_PRELOAD ? { LD_PRELOAD: CHILD_LD_PRELOAD } : {}),
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
let switchingEngine = false;
const clients = new Set();

// ---------------------------------------------------------------- pi process

function startPi() {
  log('iniciando pi [' + engineId + ']:', PI_BIN, PI_ARGS.join(' '), 'cwd=' + PI_CWD);
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
    // Troca de engine: o pi atual foi morto de proposito, sobe o novo
    // com o binario novo (o socket TCP com o app continua de pe).
    if (switchingEngine) {
      switchingEngine = false;
      log('reiniciando pi com a nova engine [' + engineId + ']...');
      startPi();
      return;
    }
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
      case 'nebula_engines':
        respond(true, { engines: engineStatus(), current: currentEngine() });
        return true;
      case 'nebula_set_engine': {
        const r = setEngine(rec.engine);
        if (r.ok) respond(true, { engine: r.engine });
        else respond(false, null, r.error);
        return true;
      }
      case 'nebula_providers':
        respond(true, { providers: providerStatus() });
        return true;
      case 'nebula_provider_save': {
        const r = saveProviderKey(rec.provider, rec.key);
        if (r.ok) respond(true, { saved: true });
        else respond(false, null, r.error);
        return true;
      }
      case 'nebula_provider_delete': {
        const ok = deleteProvider(rec.provider);
        respond(true, { deleted: ok });
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

// ------------------------------------------------- engine (pi x pi-bolt)

/** Engine efetiva: 'custom' quando NEBULA_PI_BIN aponta pra fora da lista. */
function currentEngine() {
  if (process.env.NEBULA_PI_BIN && process.env.NEBULA_PI_BIN !== ENGINES[engineId].bin) {
    return 'custom';
  }
  return engineId;
}

function engineStatus() {
  return Object.values(ENGINES).map((e) => {
    let available = false;
    try {
      fs.accessSync(e.bin, fs.constants.X_OK);
      available = true;
    } catch {}
    return { id: e.id, name: e.name, bin: e.bin, available };
  });
}

/**
 * Troca a engine em runtime: persiste a escolha, mata o pi atual e o
 * exit handler sobe o novo com o binario novo. O socket TCP continua de
 * pe, entao o app nao precisa reconectar — mas a sessao ativa zera
 * (o processo novo comeca vazio; o arquivo da sessao anterior continua
 * no disco e pode ser reaberto via switch_session).
 */
function setEngine(id) {
  if (id !== 'pi' && id !== 'pi-bolt') {
    return { ok: false, error: 'engine desconhecida: ' + id };
  }
  const bin = ENGINES[id].bin;
  try {
    fs.accessSync(bin, fs.constants.X_OK);
  } catch {
    return { ok: false, error: 'binario nao encontrado ou nao executavel: ' + bin };
  }
  engineId = id;
  PI_BIN = bin;
  delete process.env.NEBULA_PI_BIN;
  try {
    fs.writeFileSync(ENGINE_FILE, id + '\n');
  } catch (e) {
    log('nao consegui persistir engine:', e.message);
  }
  if (pi && !pi.killed) {
    switchingEngine = true;
    log('trocando engine para', id, '- matando pi atual');
    try {
      pi.kill('SIGTERM');
    } catch (e) {
      log('falha ao matar pi:', e.message);
      switchingEngine = false;
      startPi();
    }
  } else {
    startPi();
  }
  return { ok: true, engine: id };
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

// ------------------------------------------------- provedores (auth)
// Catalogo espelhado do Pi 1.3.0 (pi-ai/dist/env-api-keys.js + providers/).
// featured=true vai pro topo no app (OpenCode em destaque, pedido do Dante).
const AUTH_FILE = path.join(HOME, '.pi', 'agent', 'auth.json');
const PROVIDERS = [
  { id: 'opencode-go', name: 'OpenCode Go', env: 'OPENCODE_API_KEY', featured: true },
  { id: 'opencode', name: 'OpenCode Zen', env: 'OPENCODE_API_KEY', featured: true },
  { id: 'anthropic', name: 'Anthropic', env: 'ANTHROPIC_API_KEY' },
  { id: 'openai', name: 'OpenAI', env: 'OPENAI_API_KEY' },
  { id: 'openai-codex', name: 'OpenAI Codex (OAuth)', env: 'OPENAI_API_KEY' },
  { id: 'google', name: 'Google Gemini', env: 'GEMINI_API_KEY' },
  { id: 'deepseek', name: 'DeepSeek', env: 'DEEPSEEK_API_KEY' },
  { id: 'openrouter', name: 'OpenRouter', env: 'OPENROUTER_API_KEY' },
  { id: 'xai', name: 'xAI (Grok)', env: 'XAI_API_KEY' },
  { id: 'groq', name: 'Groq', env: 'GROQ_API_KEY' },
  { id: 'cerebras', name: 'Cerebras', env: 'CEREBRAS_API_KEY' },
  { id: 'mistral', name: 'Mistral', env: 'MISTRAL_API_KEY' },
  { id: 'moonshotai', name: 'Moonshot AI', env: 'MOONSHOT_API_KEY' },
  { id: 'moonshotai-cn', name: 'Moonshot AI (CN)', env: 'MOONSHOT_API_KEY' },
  { id: 'kimi-coding', name: 'Kimi for Coding', env: 'KIMI_API_KEY' },
  { id: 'qwen-token-plan', name: 'Qwen Token Plan', env: 'QWEN_TOKEN_PLAN_API_KEY' },
  { id: 'qwen-token-plan-cn', name: 'Qwen (CN)', env: 'QWEN_TOKEN_PLAN_CN_API_KEY' },
  { id: 'qwen-token-plan-individual', name: 'Qwen Individual', env: 'QWEN_TOKEN_PLAN_API_KEY' },
  { id: 'xiaomi', name: 'Xiaomi MiMo', env: 'XIAOMI_API_KEY' },
  { id: 'xiaomi-token-plan-cn', name: 'MiMo (CN)', env: 'XIAOMI_TOKEN_PLAN_CN_API_KEY' },
  { id: 'xiaomi-token-plan-ams', name: 'MiMo (Amsterdam)', env: 'XIAOMI_TOKEN_PLAN_AMS_API_KEY' },
  { id: 'xiaomi-token-plan-sgp', name: 'MiMo (Singapore)', env: 'XIAOMI_TOKEN_PLAN_SGP_API_KEY' },
  { id: 'zai', name: 'ZAI Coding Plan', env: 'ZAI_API_KEY' },
  { id: 'zai-coding-cn', name: 'ZAI (CN)', env: 'ZAI_CODING_CN_API_KEY' },
  { id: 'ant-ling', name: 'Ant Ling', env: 'ANT_LING_API_KEY' },
  { id: 'nvidia', name: 'NVIDIA NIM', env: 'NVIDIA_API_KEY' },
  { id: 'vercel-ai-gateway', name: 'Vercel AI Gateway', env: 'AI_GATEWAY_API_KEY' },
  { id: 'radius', name: 'Radius', env: 'RADIUS_API_KEY' },
  { id: 'typesafe', name: 'TypeSafe', env: 'TYPESAFE_API_KEY' },
  { id: 'huggingface', name: 'Hugging Face', env: 'HF_TOKEN' },
  { id: 'fireworks', name: 'Fireworks', env: 'FIREWORKS_API_KEY' },
  { id: 'together', name: 'Together AI', env: 'TOGETHER_API_KEY' },
  { id: 'baseten', name: 'Baseten', env: 'BASETEN_API_KEY' },
  { id: 'meta', name: 'Meta', env: 'META_API_KEY' },
  { id: 'minimax', name: 'MiniMax', env: 'MINIMAX_API_KEY' },
  { id: 'minimax-cn', name: 'MiniMax (CN)', env: 'MINIMAX_CN_API_KEY' },
  { id: 'azure', name: 'Azure OpenAI', env: 'AZURE_OPENAI_API_KEY' },
  { id: 'github-copilot', name: 'GitHub Copilot', env: 'COPILOT_GITHUB_TOKEN' },
  { id: 'amazon-bedrock', name: 'Amazon Bedrock', env: 'AWS_BEARER_TOKEN_BEDROCK' },
  { id: 'google-vertex', name: 'Google Vertex', env: 'GOOGLE_CLOUD_API_KEY' },
  { id: 'cloudflare-workers-ai', name: 'Cloudflare Workers AI', env: 'CLOUDFLARE_API_KEY' },
  { id: 'cloudflare-ai-gateway', name: 'Cloudflare Gateway', env: 'CLOUDFLARE_API_KEY' },
];

function readAuth() {
  try { return JSON.parse(fs.readFileSync(AUTH_FILE, 'utf8')); }
  catch { return {}; }
}

function providerStatus() {
  const auth = readAuth();
  return PROVIDERS.map((p) => {
    const hasKey = !!(auth[p.id] && auth[p.id].key);
    const envSet = !!process.env[p.env];
    return { id: p.id, name: p.name, env: p.env, featured: !!p.featured, configured: hasKey || envSet, viaEnv: !hasKey && envSet };
  });
}

function saveProviderKey(provider, key) {
  if (!PROVIDERS.some((p) => p.id === provider)) return { ok: false, error: 'provedor desconhecido: ' + provider };
  const k = String(key || '').trim();
  if (k.length < 4) return { ok: false, error: 'chave muito curta' };
  try {
    const auth = readAuth();
    auth[provider] = { type: 'api_key', key: k };
    fs.mkdirSync(path.dirname(AUTH_FILE), { recursive: true });
    fs.writeFileSync(AUTH_FILE, JSON.stringify(auth, null, 2) + '\n', { mode: 0o600 });
    log('provedor configurado:', provider);
    return { ok: true };
  } catch (e) { return { ok: false, error: e.message }; }
}

function deleteProvider(provider) {
  try {
    const auth = readAuth();
    if (auth[provider]) { delete auth[provider]; fs.writeFileSync(AUTH_FILE, JSON.stringify(auth, null, 2) + '\n', { mode: 0o600 }); log('provedor removido:', provider); return true; }
    return false;
  } catch (e) { log('erro ao remover provedor:', e.message); return false; }
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
