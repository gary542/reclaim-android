import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, createHmac, randomBytes, randomUUID } from 'node:crypto';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Store, hour } from '../store.mjs';
import {
  settings,
  eligible,
  templatePayload,
  parseEvents,
  signatureOK,
  deliver,
} from '../meta.mjs';
import { tick, service, acceptEvent } from '../server.mjs';

const zero = Date.UTC(2027, 2, 26, 12);
const wa = '+447700900123',
  psid = '12345678';
const setup = () =>
  settings({
    MESSAGING_MODE: 'test',
    BUSINESS_NAME: 'Test Studio',
    GRAPH_VERSION: 'v27.0',
    META_APP_SECRET: 'fixture-secret',
    WEBHOOK_VERIFY_TOKEN: 'fixture-verify',
    WA_SENDER_ID: '444',
    WA_ACCESS_TOKEN: 'fixture',
    WA_TEMPLATE: 'reclaim_check',
    WA_TEST_RECIPIENTS: wa,
    PAGE_SENDER_ID: '555',
    PAGE_ACCESS_TOKEN: 'fixture',
    PAGE_TEMPLATE: 'reclaim_check',
    PAGE_TEST_RECIPIENTS: psid,
  });
const input = (channel = 'whatsapp', startsAt = zero + 50 * hour) => ({
  name: 'Alex Example',
  service: 'Appointment',
  channel,
  recipient: channel === 'whatsapp' ? wa : '',
  startsAt,
  zone: 'Europe/London',
  consent: true,
});
const fixture = (channel = 'whatsapp', start) => {
  const s = new Store(':memory:');
  const c = setup(),
    id = randomUUID();
  s.create(id, input(channel, start), zero);
  return { s, c, id };
};
const sender = (arr) => async (_, b) => {
  arr.push(b.id);
  return 'msg-' + arr.length;
};
const reply = (
  s,
  id,
  text,
  at,
  sender = wa,
  contextId = 'msg-1',
  channel = 'whatsapp'
) => s.receive({ id, channel, sender, text, at, contextId }, at);

test('48-hour and 24-hour reminders, silence holds the booking for the owner', async () => {
  const { s, c, id } = fixture();
  const sent = [];
  const send = sender(sent);
  for (const offset of [0, 1.99, 2, 2, 25.99, 26, 26.1, 38])
    await tick(s, c, zero + offset * hour, send);
  assert.deepEqual(sent, [id, id]);
  const b = s.get(id);
  assert.equal(b.status, 'awaiting');
  assert.equal(b.stage, 2);
  assert.equal(b.review, 1);
  assert.equal(
    templatePayload(c, b).template.components[0].parameters[2].text.includes(
      'BST'
    ),
    true
  );
});
test('YES stops further reminders and replayed events do not change state', async () => {
  const { s, c, id } = fixture();
  const sent = [];
  await tick(s, c, zero + 2 * hour, sender(sent));
  reply(s, 'in1', '  yes ', zero + 3 * hour);
  const v = s.get(id).revision;
  reply(s, 'in1', 'NO', zero + 4 * hour);
  await tick(s, c, zero + 30 * hour, sender(sent));
  assert.equal(s.get(id).status, 'confirmed');
  assert.equal(s.get(id).revision, v);
  assert.equal(sent.length, 1);
});
test('NO opens one slot, refill survives duplicate NO and late YES', async () => {
  const { s, c, id } = fixture();
  await tick(s, c, zero + 2 * hour, sender([]));
  reply(s, 'in1', 'NO', zero + 3 * hour);
  assert.equal(s.get(id).status, 'released');
  const b = s.get(id);
  s.action(id, 'refill', b.revision, zero + 4 * hour);
  s.action(id, 'refill', b.revision, zero + 4 * hour);
  reply(s, 'in2', 'YES', zero + 5 * hour);
  assert.equal(s.get(id).status, 'refilled');
});
test('late YES after release is flagged without reclaiming the slot', async () => {
  const { s, c, id } = fixture();
  await tick(s, c, zero + 2 * hour, sender([]));
  reply(s, 'n', 'NO', zero + 3 * hour);
  reply(s, 'y', 'YES', zero + 4 * hour);
  assert.equal(s.get(id).status, 'released');
  assert.equal(s.get(id).review, 1);
});
test('late booking sends once, past bookings never send', async () => {
  const { s, c, id } = fixture('whatsapp', zero + 5 * hour);
  const sent = [];
  for (const n of [0, 0.1, 2, 6])
    await tick(s, c, zero + n * hour, sender(sent));
  assert.equal(sent.length, 1);
  assert.equal(s.get(id).status, 'awaiting');
});
test('a duplicate create, retry across restart, or concurrent workers produce one reminder', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'reclaim-test-'));
  const path = join(dir, 'test.db');
  const id = randomUUID(),
    c = setup();
  let s = new Store(path);
  try {
    s.create(id, input(), zero);
    s.create(id, input(), zero);
    assert.throws(
      () => s.create(id, { ...input(), name: 'Other' }, zero),
      /already/
    );
    const sent = [];
    const other = new Store(path);
    await Promise.all([
      tick(s, c, zero + 2 * hour, sender(sent)),
      tick(other, c, zero + 2 * hour, sender(sent)),
    ]);
    other.db.close();
    s.db.close();
    s = new Store(path);
    await tick(s, c, zero + 3 * hour, sender(sent));
    assert.equal(sent.length, 1);
    assert.equal(s.get(id).stage, 1);
  } finally {
    s.db.close();
    rmSync(dir, { recursive: true, force: true });
  }
});
test('unverified sends and interrupted workers require review without replay', async () => {
  for (const crash of [true, false]) {
    const { s, c, id } = fixture();
    let attempts = 0;
    if (crash) {
      s.claim(id, zero + 2 * hour, () => true);
      attempts++;
    } else
      await tick(s, c, zero + 2 * hour, async () => {
        attempts++;
        throw Error('Network');
      });
    await tick(s, c, zero + 3 * hour, async () => {
      attempts++;
      return 'wrong';
    });
    assert.equal(attempts, 1);
    assert.equal(s.get(id).review, 1);
    assert.equal(s.get(id).status, 'scheduled');
  }
});
test('plain ambiguous or unexpected replies never release a slot', async () => {
  const { s, c, id } = fixture();
  const second = randomUUID();
  s.create(second, input('whatsapp', zero + 49 * hour), zero);
  const sent = [];
  await tick(s, c, zero + 2 * hour, sender(sent));
  s.receive(
    {
      id: 'amb',
      channel: 'whatsapp',
      text: 'no',
      sender: wa,
      at: zero + 3 * hour,
    },
    zero + 3 * hour
  );
  for (const k of [id, second]) {
    assert.equal(s.get(k).status, 'awaiting');
    assert.equal(s.get(k).review, 1);
  }
  reply(s, 'wrong-sender', 'NO', zero + 4 * hour, '+447700900222');
  assert.equal(s.get(second).status, 'awaiting');
  reply(s, 'typed', 'not sure', zero + 5 * hour);
  assert.equal(s.get(second).status, 'awaiting');
});
test('STOP suppresses subsequent sends and bookings', async () => {
  const { s, c, id } = fixture();
  const sent = [];
  await tick(s, c, zero + 2 * hour, sender(sent));
  reply(s, 'stop', 'STOP', zero + 3 * hour);
  await tick(s, c, zero + 28 * hour, sender(sent));
  assert.equal(s.get(id).review, 1);
  assert.equal(sent.length, 1);
  assert.throws(() => s.create(randomUUID(), input(), zero), /opted out/);
});
test('customer links Messenger once, then a Page-scoped reply confirms their appointment', async () => {
  const { s, c, id } = fixture('messenger');
  let sent = [];
  await tick(s, c, zero + 2 * hour, sender(sent));
  assert.equal(sent.length, 0);
  const code = s.get(id).linkCode;
  const event = (mid, txt, who = psid) => ({
    object: 'page',
    entry: [
      {
        id: '555',
        messaging: [
          {
            sender: { id: who },
            recipient: { id: '555' },
            timestamp: zero + 3 * hour,
            message: { mid, text: txt },
          },
        ],
      },
    ],
  });
  s.receive(parseEvents(event('link', 'LINK ' + code), c)[0], zero + 3 * hour);
  s.receive(
    parseEvents(event('steal', 'LINK ' + code, '789'), c)[0],
    zero + 3 * hour
  );
  assert.equal(s.get(id).recipient, psid);
  assert.equal(s.get(id).linkCode, null);
  await tick(s, c, zero + 3 * hour, sender(sent));
  s.receive(parseEvents(event('yes', 'YES'), c)[0], zero + 3 * hour);
  assert.equal(s.get(id).status, 'confirmed');
  const body = templatePayload(c, s.get(id));
  assert.deepEqual(body.recipient, { id: psid });
  assert.equal(body.messaging_type, 'UTILITY');
});
test('template adapters use the matching endpoint and require a receipt', async () => {
  for (const channel of ['whatsapp', 'messenger']) {
    const c = setup();
    const b = {
      ...input(channel),
      recipient: channel === 'whatsapp' ? wa : psid,
    };
    const id = await deliver(c, b, async (url, opt) => {
      assert.ok(url.endsWith('/' + c[channel].id + '/messages'));
      assert.equal(opt.method, 'POST');
      assert.equal(opt.redirect, 'error');
      const p = JSON.parse(opt.body);
      assert.equal(
        channel === 'whatsapp' ? p.template.name : p.message.template.name,
        'reclaim_check'
      );
      return new Response(
        JSON.stringify(
          channel === 'whatsapp'
            ? { messages: [{ id: '123' }] }
            : { message_id: '123' }
        )
      );
    });
    assert.equal(id, '123');
    await assert.rejects(deliver(c, b, async () => new Response('{}')));
  }
});
test('stale changes, nonexistent local times from malformed zone, and missing permission are rejected', () => {
  const { s, id } = fixture();
  assert.throws(() => s.action(id, 'release', 52, zero), /changed/);
  assert.throws(() =>
    s.create(randomUUID(), { ...input(), consent: false }, zero)
  );
  assert.throws(() =>
    s.create(randomUUID(), { ...input(), zone: 'Nowhere' }, zero)
  );
  assert.equal(
    eligible(settings({}), { channel: 'whatsapp', recipient: wa }),
    false
  );
});
test('owner HTTP API and signed Meta webhook complete a test-only round trip', async () => {
  const s = new Store(':memory:'),
    c = setup();
  const key = randomBytes(32).toString('hex');
  const http = service(
    s,
    c,
    createHash('sha256').update(key).digest('hex'),
    () => zero + 3 * hour
  );
  await new Promise((r) => http.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + http.address().port;
  const headers = {
    Authorization: 'Bearer ' + key,
    'Content-Type': 'application/json',
  };
  try {
    assert.equal((await fetch(base + '/v1/workspace')).status, 401);
    const url = base + '/webhooks/meta';
    assert.equal(
      (await fetch(url + '?hub.mode=subscribe&hub.verify_token=wrong')).status,
      403
    );
    assert.equal(
      await (
        await fetch(
          url +
            '?hub.mode=subscribe&hub.verify_token=fixture-verify&hub.challenge=abc'
        )
      ).text(),
      'abc'
    );
    const id = randomUUID(),
      req = { method: 'PUT', headers, body: JSON.stringify(input()) };
    assert.equal(
      (await fetch(base + '/v1/appointments/' + id, req)).status,
      200
    );
    assert.equal(
      (await fetch(base + '/v1/appointments/' + id, req)).status,
      200
    );
    assert.equal(
      (
        await fetch(base + '/v1/appointments/' + randomUUID(), {
          ...req,
          body: JSON.stringify({ ...input(), recipient: '+447700900999' }),
        })
      ).status,
      422
    );
    await tick(s, c, zero + 3 * hour, sender([]));
    const body = JSON.stringify({
      object: 'whatsapp_business_account',
      entry: [
        {
          changes: [
            {
              field: 'messages',
              value: {
                metadata: { phone_number_id: '444' },
                messages: [
                  {
                    id: 'incoming',
                    from: wa.slice(1),
                    timestamp: String((zero + 3 * hour) / 1000),
                    context: { id: 'msg-1' },
                    text: { body: 'NO' },
                  },
                ],
              },
            },
          ],
        },
      ],
    });
    assert.equal((await fetch(url, { method: 'POST', body })).status, 403);
    const sig =
      'sha256=' + createHmac('sha256', c.appSecret).update(body).digest('hex');
    assert.equal(signatureOK(Buffer.from(body + ' '), sig, c.appSecret), false);
    for (let i = 0; i < 2; i++)
      assert.equal(
        (
          await fetch(url, {
            method: 'POST',
            body,
            headers: { 'x-hub-signature-256': sig },
          })
        ).status,
        200
      );
    const obj = await (await fetch(base + '/v1/workspace', { headers })).json();
    assert.equal(obj.appointments.length, 1);
    assert.equal(obj.appointments[0].status, 'released');
    assert.equal(JSON.stringify(obj).includes(c.whatsapp.token + '"'), false);
  } finally {
    await new Promise((r) => http.close(r));
    s.db.close();
  }
});

test('a callback arriving before the outbound receipt can be retried once the receipt is saved', () => {
  const { s, id } = fixture();
  const now = zero + 3 * hour;
  const job = s.claim(id, now, () => true);
  const incoming = {
    id: 'early',
    channel: 'whatsapp',
    sender: wa,
    at: now,
    text: 'NO',
    contextId: 'msg-early',
  };
  assert.throws(
    () => s.receive(incoming, now),
    (e) => e.status === 503
  );
  s.finish(id, job.stage, 'msg-early');
  s.receive(incoming, now + 1000);
  s.receive(incoming, now + 2000);
  assert.equal(s.get(id).status, 'released');
  assert.equal(s.q('SELECT COUNT(*) AS n FROM events').get().n, 1);
});
test('a later YES on a released slot can be restored explicitly by the owner', async () => {
  const { s, c, id } = fixture();
  await tick(s, c, zero + 2 * hour, sender([]));
  reply(s, 'n', 'NO', zero + 3 * hour);
  reply(s, 'y', 'YES', zero + 4 * hour);
  const b = s.get(id);
  s.action(id, 'confirm', b.revision, zero + 5 * hour);
  assert.equal(s.get(id).status, 'confirmed');
});

test('WhatsApp resolves a hidden phone number from its signed delivery receipt when replies arrive first', async () => {
  const { s, c, id } = fixture();
  const now = zero + 3 * hour;
  await tick(s, c, now, sender([]));
  const data = (v) => ({
    object: 'whatsapp_business_account',
    entry: [
      {
        changes: [
          {
            field: 'messages',
            value: { metadata: { phone_number_id: '444' }, ...v },
          },
        ],
      },
    ],
  });
  const incoming = data({
    messages: [
      {
        id: 'no-number',
        from_user_id: 'GB.example123',
        timestamp: String(now / 1000),
        type: 'text',
        text: { body: 'NO' },
      },
    ],
  });
  const status = data({
    statuses: [
      { id: 'msg-1', status: 'delivered', recipient_user_id: 'GB.example123' },
    ],
  });
  for (const event of parseEvents(incoming, c)) acceptEvent(s, c, event, now);
  assert.equal(s.get(id).status, 'awaiting');
  for (const event of parseEvents(status, c))
    acceptEvent(s, c, event, now + 100);
  assert.equal(s.get(id).status, 'released');
  assert.equal(s.q('SELECT count(*) AS n FROM wa_waiting').get().n, 0);
  const rev = s.get(id).revision;
  for (const event of parseEvents(incoming, c))
    acceptEvent(s, c, event, now + 200);
  assert.equal(s.get(id).revision, rev);
});
