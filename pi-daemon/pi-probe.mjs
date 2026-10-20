#!/usr/bin/env node
/**
 * Verifica se existe um daemon RPC do Pi respondendo na porta.
 * uso: node pi-probe.mjs [porta]   -> exit 0 se responder, 1 se nao
 */
import net from 'node:net';

const port = Number(process.argv[2] || process.env.NEBULA_PI_PORT || 37877);
const socket = net.createConnection({ host: '127.0.0.1', port });
let buf = '';
const finish = (ok) => {
  socket.destroy();
  process.exit(ok ? 0 : 1);
};
const timer = setTimeout(() => finish(false), 2000);
socket.on('connect', () => socket.write(JSON.stringify({ id: 'probe', type: 'get_state' }) + '\n'));
socket.on('data', (d) => {
  buf += d.toString('utf8');
  if (buf.includes('"command":"get_state"')) {
    clearTimeout(timer);
    finish(true);
  }
});
socket.on('error', () => {
  clearTimeout(timer);
  finish(false);
});
