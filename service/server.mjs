import { createServer } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { isAbsolute } from 'node:path';
import { Store, fail, uuidPattern, hour } from './store.mjs';
import {
  settings,
  enabled,
  eligible,
  deliver,
  signatureOK,
  parseEvents,
} from './meta.mjs';

function same(a, b) {
  return (
    !!a &&
    !!b &&
    timingSafeEqual(
      createHash('sha256').update(a).digest(),
      createHash('sha256').update(b).digest()
    )
  );
}
async function rawBody(req, max) {
  const chunks = [];
  let length = 0;
  for await (const chunk of req) {
    length += chunk.length;
    if (length > max) throw fail('Request is too large.', 413);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}
function json(buf) {
  try {
    const obj = JSON.parse(buf.toString('utf8'));
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) throw Error();
    return obj;
  } catch {
    throw fail('Expected a JSON object.');
  }
}
export function acceptEvent(store, config, event, now) {
  if (config.mode === 'off') return;
  const allowed = (sender) =>
    config.mode !== 'test' || config[event.channel].allow.has(sender);
  if (event.channel !== 'whatsapp' || !event.userId) {
    if (event.failedId || allowed(event.sender)) store.receive(event, now);
    return;
  }
  // Only authenticated provider fields can link an opaque WhatsApp ID to the customer's number.
  let phone = event.sender;
  if (event.bindingId) {
    const booking = store
      .q(
        `SELECT b.recipient FROM bookings b JOIN sends s ON b.id=s.booking
      WHERE b.channel='whatsapp' AND s.providerId=?`
      )
      .get(event.bindingId);
    if (!booking) {
      if (
        store
          .q("SELECT 1 FROM sends WHERE state='sending' AND started>? LIMIT 1")
          .get(now - 5 * 60_000)
      )
        throw fail('The outbound receipt is pending. Retry this event.', 503);
      return;
    }
    if (phone && phone !== booking.recipient) return;
    phone = booking.recipient;
  }
  if (phone) {
    if (!allowed(phone)) return;
    store
      .q(
        `INSERT INTO wa_ids VALUES(?,?) ON CONFLICT(scoped) DO UPDATE
      SET phone=CASE WHEN wa_ids.phone=excluded.phone THEN excluded.phone ELSE NULL END`
      )
      .run(event.userId, phone);
  }
  const mapped = store
    .q('SELECT phone FROM wa_ids WHERE scoped=?')
    .get(event.userId);
  if (!mapped?.phone) {
    if (
      !event.bindingId &&
      !phone &&
      !mapped &&
      event.id &&
      store
        .q(
          "SELECT 1 FROM bookings WHERE channel='whatsapp' AND startsAt>? AND status IN ('scheduled','awaiting','confirmed') LIMIT 1"
        )
        .get(now)
    ) {
      // A reply may arrive before the delivery status supplies the identity mapping.
      const word = String(event.text || '')
        .trim()
        .toUpperCase();
      store
        .q('INSERT OR IGNORE INTO wa_waiting VALUES(?,?,?,?,?,?)')
        .run(
          event.id,
          event.userId,
          event.at || now,
          event.contextId || null,
          ['YES', 'NO', 'STOP', 'UNSUBSCRIBE'].includes(word) ? word : '?',
          now
        );
    }
    if (phone && !event.bindingId)
      store.receive({ ...event, sender: phone }, now);
    return;
  }
  if (!allowed(mapped.phone)) return;
  const held = store
    .q('SELECT * FROM wa_waiting WHERE scoped=? ORDER BY at,observed,id')
    .all(event.userId);
  for (const item of held) {
    if (item.observed > now - 48 * hour)
      store.receive(
        {
          id: item.id,
          channel: 'whatsapp',
          sender: mapped.phone,
          at: item.at,
          contextId: item.reference,
          text: item.answer,
        },
        now
      );
    store.q('DELETE FROM wa_waiting WHERE id=?').run(item.id);
  }
  if (!event.bindingId) store.receive({ ...event, sender: mapped.phone }, now);
}
export async function tick(store, config, now = Date.now(), send = deliver) {
  store.q('DELETE FROM wa_waiting WHERE observed<?').run(now - 48 * hour);
  if (config.mode === 'off') return;
  const due = store
    .q(
      "SELECT id FROM bookings WHERE status IN ('scheduled','awaiting') AND review=0 AND startsAt>? AND startsAt<=? ORDER BY startsAt"
    )
    .all(now, now + 48 * hour);
  for (const { id } of due) {
    const job = store.claim(id, now, (b) => eligible(config, b));
    if (!job) continue;
    let receipt;
    try {
      receipt = await send(config, job.b);
    } catch {
      /* Unknown delivery is held for owner review. */
    }
    store.finish(id, job.stage, receipt);
  }
}
export function service(store, config, tokenHash, clock = Date.now) {
  if (!/^[0-9a-f]{64}$/.test(tokenHash || ''))
    throw Error('Provide a SHA-256 hex DEVICE_TOKEN_SHA256.');
  return createServer(async (req, res) => {
    function reply(status, value, plain = false) {
      res.writeHead(status, {
        'Content-Type': plain
          ? 'text/plain; charset=utf-8'
          : 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(plain ? String(value) : JSON.stringify(value));
    }
    try {
      const u = new URL(req.url, 'http://localhost');
      const method = req.method;
      if (u.pathname === '/health' && method === 'GET') {
        store.q('SELECT 1').get();
        return reply(200, { status: 'ok' });
      }
      if (u.pathname === '/webhooks/meta' && method === 'GET') {
        if (
          u.searchParams.get('hub.mode') !== 'subscribe' ||
          !same(u.searchParams.get('hub.verify_token'), config.verify)
        )
          throw fail('Verification failed.', 403);
        return reply(200, u.searchParams.get('hub.challenge') || '', true);
      }
      if (u.pathname === '/webhooks/meta' && method === 'POST') {
        const raw = await rawBody(req, 1024 * 1024);
        if (
          !signatureOK(
            raw,
            req.headers['x-hub-signature-256'],
            config.appSecret
          )
        )
          throw fail('Invalid signature.', 403);
        const body = json(raw);
        for (const e of parseEvents(body, config))
          acceptEvent(store, config, e, clock());
        return reply(200, { received: true });
      }
      if (!u.pathname.startsWith('/v1/')) throw fail('Unknown endpoint.', 404);
      const token = /^Bearer ([A-Za-z0-9_-]{32,160})$/.exec(
        req.headers.authorization || ''
      )?.[1];
      if (
        !token ||
        !timingSafeEqual(
          createHash('sha256').update(token).digest(),
          Buffer.from(tokenHash, 'hex')
        )
      )
        throw fail('Check the device access key.', 401);
      if (u.pathname === '/v1/workspace' && method === 'GET') {
        return reply(200, {
          protocol: 1,
          business: config.business,
          mode: config.mode,
          channels: {
            whatsapp: enabled(config, 'whatsapp'),
            messenger: enabled(config, 'messenger'),
          },
          appointments: store.list().map((b) => store.public(b)),
        });
      }
      const path = /^\/v1\/appointments\/([^/]+)(\/actions)?$/.exec(u.pathname);
      if (!path || !uuidPattern.test(path[1]))
        throw fail('Unknown appointment endpoint.', 404);
      if (method === 'PUT' && !path[2]) {
        const data = json(await rawBody(req, 8192));
        const existing = store.get(path[1]);
        if (
          !existing &&
          !enabled(
            config,
            data.channel === 'messenger' ? 'messenger' : 'whatsapp'
          )
        )
          throw fail('This channel is not connected for reminders.', 409);
        if (!existing && !['whatsapp', 'messenger'].includes(data.channel))
          throw fail('Choose a supported channel.');
        if (
          !existing &&
          data.channel === 'whatsapp' &&
          config.mode === 'test' &&
          !config.whatsapp.allow.has(
            String(data.recipient || '').replace(/[\s()-]/g, '')
          )
        )
          throw fail('Use a verified test recipient.', 422);
        return reply(200, {
          appointment: store.public(store.create(path[1], data, clock())),
        });
      }
      if (method === 'POST' && path[2]) {
        const data = json(await rawBody(req, 8192));
        return reply(200, {
          appointment: store.public(
            store.action(path[1], data.action, data.revision, clock())
          ),
        });
      }
      throw fail('Method unavailable.', 405);
    } catch (e) {
      if (!res.headersSent)
        reply(e.status || 500, {
          error: e.status
            ? e.message
            : 'The service could not complete the request.',
        });
    }
  });
}
if (
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const path = process.env.DATABASE_PATH;
  if (!path || !isAbsolute(path))
    throw Error('Set DATABASE_PATH to an absolute path on persistent storage.');
  const config = settings(process.env);
  const store = new Store(path);
  const http = service(store, config, process.env.DEVICE_TOKEN_SHA256);
  http.requestTimeout = 25_000;
  http.headersTimeout = 15_000;
  http.listen(
    Number(process.env.PORT || 3000),
    process.env.BIND_ADDRESS || '127.0.0.1'
  );
  let running = false;
  async function run() {
    if (running) return;
    running = true;
    try {
      await tick(store, config);
    } catch {
      console.error(
        'Reminder cycle failed. Check database and service health.'
      );
    } finally {
      running = false;
    }
  }
  const interval = setInterval(run, 60_000);
  run();
  process.once('SIGTERM', () => {
    clearInterval(interval);
    http.close();
  });
}
