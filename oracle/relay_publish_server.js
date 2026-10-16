// ONYX VAULT - Relay Publish Server V2 (post-audit 2026-10-16)
//
// AUDIT FIXES:
//   CRITICAL-1: Alle eingehenden Nostr-Events kryptografisch verifiziert bevor Fanout
//               - event.id == NIP-01 canonical hash
//               - BIP-340 Schnorr Signatur gegen event.pubkey
//               - Kind-Whitelist: nur KIND_KILL (30078) + KIND_RECOVERY (30081)
//               - Content-Shape-Check: muss {target, type, timestamp} haben
//   CRITICAL-1: Rate-Limit (sliding window) pro IP + globaler Dedup via event.id (24h TTL)
//   CRITICAL-1: CORS-Policy nicht mehr '*' sondern Allowlist
//
// Nimmt POST /relay-publish, leitet via lokalen Tor an .onion Nostr-Relays
// + parallel an Clearnet-Relays.

const http = require('http');
const https = require('https');
const WebSocket = require('ws');
const { SocksProxyAgent } = require('socks-proxy-agent');
const { verifyEvent, getEventHash } = require('nostr-tools/pure');

const PORT = 3000;
const TOR_SOCKS = 'socks5h://127.0.0.1:9050';
const MEOWL_URL = 'https://relays.meowl.social/urls.json';
const CACHE_MS = 60 * 60 * 1000; // 1h relay list cache

// ─── AUDIT CRITICAL-1: Kind Whitelist ────────────────────────────────
const KIND_KILL     = 30078;
const KIND_RECOVERY = 30081;
const KIND_WHITELIST = new Set([KIND_KILL, KIND_RECOVERY]);

// ─── AUDIT CRITICAL-1: Rate-Limit (sliding window per IP) ────────────
const RATE_LIMIT_WINDOW_MS = 60_000;   // 1 min window
const RATE_LIMIT_MAX_REQ   = 10;        // 10 req/min per IP
const rateMap = new Map();              // ip -> [timestamp, timestamp, ...]

function rateLimitAllowed(ip) {
  const now = Date.now();
  let arr = rateMap.get(ip) || [];
  arr = arr.filter(t => now - t < RATE_LIMIT_WINDOW_MS);
  if (arr.length >= RATE_LIMIT_MAX_REQ) {
    rateMap.set(ip, arr);
    return false;
  }
  arr.push(now);
  rateMap.set(ip, arr);
  return true;
}

// ─── AUDIT CRITICAL-1 + NEU-3: Event-Dedup persistiert ───────────────
const EVENT_TTL_MS = 24 * 60 * 60 * 1000;
const SEEN_PATH = '/opt/onyx-relay/seen-events.json';
const seenEvents = new Map();           // eventId -> firstSeenAtMs

// NEU-3 Fix: Beim Start aus Datei laden damit Restart kein Replay-Fenster oeffnet
try {
  if (require('fs').existsSync(SEEN_PATH)) {
    const raw = JSON.parse(require('fs').readFileSync(SEEN_PATH, 'utf-8'));
    const now = Date.now();
    for (const [id, ts] of Object.entries(raw)) {
      if (now - ts < EVENT_TTL_MS) seenEvents.set(id, ts);
    }
    console.log(`[init] loaded ${seenEvents.size} seen-events from disk`);
  }
} catch (e) { console.log('[init] no seen-events to restore'); }

let _flushScheduled = false;
function scheduleFlush() {
  if (_flushScheduled) return;
  _flushScheduled = true;
  setTimeout(() => {
    _flushScheduled = false;
    try {
      const obj = Object.fromEntries(seenEvents);
      require('fs').writeFileSync(SEEN_PATH, JSON.stringify(obj));
    } catch (e) { console.log('[warn] seen-events flush failed:', e.message); }
  }, 5000);  // Debounce 5s
}

function eventSeen(eventId) {
  const now = Date.now();
  if (seenEvents.size > 10_000) {
    for (const [id, ts] of seenEvents) {
      if (now - ts > EVENT_TTL_MS) seenEvents.delete(id);
    }
  }
  if (seenEvents.has(eventId)) return true;
  seenEvents.set(eventId, now);
  scheduleFlush();  // NEU-3: Persistiere nach Insert
  return false;
}

// ─── AUDIT CRITICAL-1 + NEU-2: Strikte CORS Allowlist ───────────────
// NEU-2 Fix: Statt endsWith('.onion') konkrete Allowlist aus ENV.
// Attacker koennte sonst evil-site.onion hosten und von dort relay-publish
// per CSRF/Cross-Origin aufrufen. Nur unser eigener Hidden-Service darf.
const ALLOWED_ORIGINS = new Set([
  'https://supportonyxvault.github.io',   // Clearnet GitHub Pages
]);
// ENV: ONYX_ONION_ORIGIN=http://<unser-eigener-hidden-service>.onion
const ONYX_ONION_ORIGIN = process.env.ONYX_ONION_ORIGIN || '';
if (ONYX_ONION_ORIGIN) ALLOWED_ORIGINS.add(ONYX_ONION_ORIGIN);

function setCorsHeaders(req, res) {
  const origin = req.headers.origin;
  // Strikter Match - kein endsWith('.onion') mehr!
  if (origin && ALLOWED_ORIGINS.has(origin)) {
    res.setHeader('Access-Control-Allow-Origin', origin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Credentials', 'false');
  }
  res.setHeader('Access-Control-Allow-Methods', 'POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  res.setHeader('Access-Control-Max-Age', '600');
}

// ─── Relay-Pool (unchanged from V1) ──────────────────────────────────

const CLEARNET_RELAYS = [
  'wss://relay.damus.io', 'wss://nos.lol', 'wss://nostr.wine',
  'wss://relay.snort.social', 'wss://offchain.pub', 'wss://purplerelay.com'
];
const FALLBACK_RELAYS = [
  'wss://[REDACTED-ONION]',
  'wss://[REDACTED-ONION]',
  'wss://[REDACTED-ONION]'
];

let relayCache = { relays: FALLBACK_RELAYS, fetchedAt: 0 };

function fetchMeowlRelays() {
  return new Promise(resolve => {
    https.get(MEOWL_URL, { timeout: 15000 }, res => {
      let body = '';
      res.on('data', c => body += c);
      res.on('end', () => {
        try {
          const data = JSON.parse(body);
          const urls = (data.urls || []).filter(u => u.includes('.onion'));
          if (urls.length > 0) resolve(urls);
          else resolve(null);
        } catch (e) { resolve(null); }
      });
    }).on('error', () => resolve(null)).on('timeout', () => resolve(null));
  });
}

async function getRelays() {
  const now = Date.now();
  if (now - relayCache.fetchedAt < CACHE_MS) return relayCache.relays;
  const fresh = await fetchMeowlRelays();
  if (fresh && fresh.length >= 3) {
    relayCache = { relays: fresh.slice(0, 10), fetchedAt: now };
    console.log(`[${new Date().toISOString()}] fetched ${fresh.length} onion relays, using top 10`);
  } else {
    relayCache.fetchedAt = now;
    console.log(`[${new Date().toISOString()}] meowl fetch failed, using fallback`);
  }
  return relayCache.relays;
}

function publishClearnet(relayUrl, eventJson) {
  return new Promise(resolve => {
    try {
      const ws = new WebSocket(relayUrl, { timeout: 15000 });
      const timeout = setTimeout(() => { try { ws.close(); } catch (e) {} resolve({ relay: relayUrl, result: 'timeout', net: 'clearnet' }); }, 18000);
      ws.on('open', () => { ws.send(JSON.stringify(['EVENT', eventJson])); });
      ws.on('message', (data) => {
        try {
          const d = JSON.parse(data.toString());
          if (d[0] === 'OK') { clearTimeout(timeout); ws.close(); resolve({ relay: relayUrl, result: 'ok', msg: d[2], net: 'clearnet' }); }
        } catch (e) {}
      });
      ws.on('error', (e) => { clearTimeout(timeout); resolve({ relay: relayUrl, result: 'error', msg: (e.message || '').substring(0, 80), net: 'clearnet' }); });
      ws.on('close', () => resolve({ relay: relayUrl, result: 'closed', net: 'clearnet' }));
    } catch (e) { resolve({ relay: relayUrl, result: 'exception', msg: e.message, net: 'clearnet' }); }
  });
}

function publishToRelay(relayUrl, eventJson) {
  return new Promise(resolve => {
    try {
      const agent = new SocksProxyAgent(TOR_SOCKS);
      const ws = new WebSocket(relayUrl, { agent, timeout: 25000 });
      const timeout = setTimeout(() => { try { ws.close(); } catch (e) {} resolve({ relay: relayUrl, result: 'timeout' }); }, 30000);
      ws.on('open', () => { ws.send(JSON.stringify(['EVENT', eventJson])); });
      ws.on('message', (data) => {
        try {
          const d = JSON.parse(data.toString());
          if (d[0] === 'OK') { clearTimeout(timeout); ws.close(); resolve({ relay: relayUrl, result: 'ok', msg: d[2] }); }
        } catch (e) {}
      });
      ws.on('error', (e) => { clearTimeout(timeout); resolve({ relay: relayUrl, result: 'error', msg: (e.message || '').substring(0, 80) }); });
      ws.on('close', () => resolve({ relay: relayUrl, result: 'closed' }));
    } catch (e) { resolve({ relay: relayUrl, result: 'exception', msg: e.message }); }
  });
}

// ─── AUDIT CRITICAL-1: Event-Validator ───────────────────────────────
function validateEvent(ev) {
  // Shape check
  if (!ev || typeof ev !== 'object') return 'not an object';
  const required = ['id', 'pubkey', 'created_at', 'kind', 'tags', 'content', 'sig'];
  for (const k of required) {
    if (!(k in ev)) return `missing field: ${k}`;
  }
  if (typeof ev.id !== 'string' || ev.id.length !== 64) return 'invalid id format';
  if (typeof ev.pubkey !== 'string' || ev.pubkey.length !== 64) return 'invalid pubkey';
  if (typeof ev.sig !== 'string' || ev.sig.length !== 128) return 'invalid sig';
  if (typeof ev.created_at !== 'number') return 'created_at not number';
  if (typeof ev.kind !== 'number') return 'kind not number';
  if (!Array.isArray(ev.tags)) return 'tags not array';
  if (typeof ev.content !== 'string') return 'content not string';

  // Kind whitelist
  if (!KIND_WHITELIST.has(ev.kind)) return `kind ${ev.kind} not in whitelist`;

  // Timestamp sanity: not in future (>5min), not too old (>7d)
  const nowSec = Math.floor(Date.now() / 1000);
  const ageSec = nowSec - ev.created_at;
  if (ageSec > 7 * 24 * 3600) return `event too old (${ageSec}s)`;
  if (ageSec < -300) return `event too far in future (${-ageSec}s)`;

  // Content must be valid JSON with required fields
  let content;
  try { content = JSON.parse(ev.content); }
  catch (e) { return 'content not valid JSON'; }
  if (!content.target || !content.type || typeof content.timestamp !== 'number') {
    return 'content missing {target, type, timestamp}';
  }

  // CANONICAL ID CHECK: event.id must equal sha256(canonical NIP-01 serialization)
  const expectedId = getEventHash(ev);
  if (expectedId !== ev.id) return 'event.id does not match canonical hash';

  // BIP-340 Schnorr signature verification
  if (!verifyEvent(ev)) return 'BIP-340 signature verification failed';

  return null; // valid
}

// ─── HTTP Server ─────────────────────────────────────────────────────

const server = http.createServer(async (req, res) => {
  // NEU-1 Fix: nginx-Proxy -> wir muessen X-Real-IP/X-Forwarded-For auslesen,
  // sonst sehen wir immer 127.0.0.1. Erste IP im X-Forwarded-For = echte Client-IP.
  const xff = req.headers['x-forwarded-for'];
  const xri = req.headers['x-real-ip'];
  const ip = (xff ? xff.split(',')[0].trim() : xri) || req.socket.remoteAddress || 'unknown';
  setCorsHeaders(req, res);
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  if (req.url === '/health') {
    const relays = await getRelays();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({
      ok: true,
      relay_pool: relays.length,
      cached_at: new Date(relayCache.fetchedAt).toISOString(),
      rate_limit_active_ips: rateMap.size,
      dedup_cache_size: seenEvents.size
    }));
    return;
  }

  if (req.url === '/relays') {
    const relays = await getRelays();
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ relays }));
    return;
  }

  if (req.url === '/relay-publish' && req.method === 'POST') {
    // AUDIT CRITICAL-1: Rate-limit per IP
    if (!rateLimitAllowed(ip)) {
      console.log(`[${new Date().toISOString()}] RATE-LIMIT ip=${ip}`);
      res.writeHead(429, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'rate limit exceeded', retry_after_sec: 60 }));
      return;
    }

    let body = '';
    let bytesReceived = 0;
    const MAX_BODY = 32 * 1024; // 32 KiB hard cap
    let aborted = false;

    req.on('data', c => {
      bytesReceived += c.length;
      if (bytesReceived > MAX_BODY) {
        aborted = true;
        res.writeHead(413, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'payload too large' }));
        req.destroy();
        return;
      }
      body += c;
    });

    req.on('end', async () => {
      if (aborted) return;
      try {
        const eventJson = JSON.parse(body);

        // AUDIT CRITICAL-1: Full event verification
        const err = validateEvent(eventJson);
        if (err) {
          console.log(`[${new Date().toISOString()}] REJECT ip=${ip} reason="${err}"`);
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'event validation failed', detail: err }));
          return;
        }

        // AUDIT CRITICAL-1: Event dedup
        if (eventSeen(eventJson.id)) {
          console.log(`[${new Date().toISOString()}] DEDUP ip=${ip} id=${eventJson.id.substring(0, 16)}`);
          res.writeHead(409, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'event already seen (replay protection)' }));
          return;
        }

        const relays = await getRelays();
        console.log(`[${new Date().toISOString()}] /relay-publish VERIFIED kind=${eventJson.kind} id=${eventJson.id.substring(0, 16)} ip=${ip} fanning to ${relays.length + CLEARNET_RELAYS.length} relays`);
        const onionProms = relays.map(r => publishToRelay(r, eventJson));
        const clearProms = CLEARNET_RELAYS.map(r => publishClearnet(r, eventJson));
        const results = await Promise.all([...onionProms, ...clearProms]);
        const okCount = results.filter(r => r.result === 'ok').length;
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ published_to: okCount, total: relays.length + CLEARNET_RELAYS.length, results }));
      } catch (e) {
        console.log(`[${new Date().toISOString()}] ERROR ip=${ip} msg="${e.message}"`);
        res.writeHead(500, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'internal error' }));
      }
    });
    return;
  }

  res.writeHead(404); res.end('not found');
});

server.listen(PORT, '127.0.0.1', () => {
  console.log(`[onyx-relay-publish v2] listening 127.0.0.1:${PORT}`);
  console.log(`[init] kind whitelist: ${[...KIND_WHITELIST]}`);
  console.log(`[init] rate limit: ${RATE_LIMIT_MAX_REQ} req/${RATE_LIMIT_WINDOW_MS}ms per IP`);
  console.log(`[init] dedup TTL: ${EVENT_TTL_MS / 1000}s`);
  getRelays().then(r => console.log(`[init] relay pool: ${r.length} onion + ${CLEARNET_RELAYS.length} clearnet`));
});
