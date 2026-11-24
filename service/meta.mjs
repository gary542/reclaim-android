import { createHmac, timingSafeEqual } from 'node:crypto';

export function signatureOK(raw, header, secret) {
  if (
    !secret ||
    typeof header !== 'string' ||
    !/^sha256=[0-9a-f]{64}$/.test(header)
  )
    return false;
  return timingSafeEqual(
    createHmac('sha256', secret).update(raw).digest(),
    Buffer.from(header.slice(7), 'hex')
  );
}
const list = (v) =>
  new Set(
    (v || '')
      .split(',')
      .map((x) => x.trim())
      .filter(Boolean)
  );

export function settings(env) {
  const mode = env.MESSAGING_MODE || 'off';
  if (!['off', 'test', 'live'].includes(mode))
    throw new Error('Use off, test or live for MESSAGING_MODE.');
  const common = {
    mode,
    business: (env.BUSINESS_NAME || 'Reclaim').trim(),
    version: env.GRAPH_VERSION || '',
    appSecret: env.META_APP_SECRET || '',
    verify: env.WEBHOOK_VERIFY_TOKEN || '',
  };
  const channel = (prefix) => ({
    id: env[prefix + '_SENDER_ID'] || '',
    token: env[prefix + '_ACCESS_TOKEN'] || '',
    template: env[prefix + '_TEMPLATE'] || '',
    language: env[prefix + '_LANGUAGE'] || 'en_GB',
    allow: list(env[prefix + '_TEST_RECIPIENTS']),
  });
  return { ...common, whatsapp: channel('WA'), messenger: channel('PAGE') };
}
export function enabled(config, channel) {
  const c = config[channel];
  return !!(
    config.mode !== 'off' &&
    /^v\d+\.\d+$/.test(config.version) &&
    config.appSecret &&
    config.verify &&
    /^\d+$/.test(c.id) &&
    c.token &&
    c.template &&
    (config.mode !== 'test' || c.allow.size)
  );
}
export function eligible(c, b) {
  return (
    enabled(c, b.channel) &&
    (c.mode !== 'test' || c[b.channel].allow.has(b.recipient))
  );
}
export function templatePayload(config, b) {
  const c = config[b.channel];
  const when = new Intl.DateTimeFormat('en-GB', {
    weekday: 'short',
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    timeZone: b.zone,
    timeZoneName: 'short',
  }).format(b.startsAt);
  const template = {
    name: c.template,
    language: { code: c.language },
    components: [
      {
        type: 'body',
        parameters: [b.name, config.business, when].map((text) => ({
          type: 'text',
          text,
        })),
      },
    ],
  };
  if (b.channel === 'whatsapp')
    return {
      messaging_product: 'whatsapp',
      to: b.recipient.slice(1),
      type: 'template',
      template,
    };
  return {
    recipient: { id: b.recipient },
    messaging_type: 'UTILITY',
    message: { template },
  };
}
export async function deliver(config, b, fetcher = fetch) {
  if (!eligible(config, b)) throw new Error('Channel unavailable');
  const c = config[b.channel];
  const res = await fetcher(
    'https://graph.facebook.com/' + config.version + '/' + c.id + '/messages',
    {
      method: 'POST',
      redirect: 'error',
      signal: AbortSignal.timeout(15_000),
      headers: {
        Authorization: 'Bearer ' + c.token,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(templatePayload(config, b)),
    }
  );
  if (!res.ok) throw new Error('Provider rejected request');
  const result = await res.json();
  const id =
    b.channel === 'whatsapp' ? result.messages?.[0]?.id : result.message_id;
  if (typeof id !== 'string' || !id)
    throw new Error('Missing provider receipt');
  return id;
}

function phone(value) {
  const number = '+' + String(value || '').replace(/^\+/, '');
  return /^\+[1-9]\d{7,14}$/.test(number) ? number : null;
}
function scoped(value) {
  return typeof value === 'string' && /^[a-zA-Z0-9._-]{2,128}$/.test(value)
    ? value
    : null;
}
export function parseEvents(body, config) {
  const result = [];
  for (const entry of body.entry || []) {
    if (body.object === 'whatsapp_business_account') {
      for (const change of entry.changes || []) {
        const v = change.value;
        if (
          change.field !== 'messages' ||
          !config.whatsapp.id ||
          v?.metadata?.phone_number_id !== config.whatsapp.id
        )
          continue;
        for (const m of v.messages || []) {
          const sender = phone(m.from);
          const userId = scoped(m.from_user_id);
          if (!sender && !userId) continue;
          result.push({
            id: m.id,
            channel: 'whatsapp',
            sender,
            userId,
            at: Number(m.timestamp) * 1000,
            contextId: m.context?.id,
            text:
              m.text?.body ||
              m.button?.text ||
              m.interactive?.button_reply?.title ||
              '',
          });
        }
        for (const s of v.statuses || []) {
          if (
            ['sent', 'delivered', 'read'].includes(s.status) &&
            s.id &&
            scoped(s.recipient_user_id)
          )
            result.push({
              channel: 'whatsapp',
              bindingId: s.id,
              userId: scoped(s.recipient_user_id),
              sender: phone(s.recipient_id),
            });
          if (s.status === 'failed' && s.id)
            result.push({
              id: 'failed:' + s.id,
              channel: 'whatsapp',
              failedId: s.id,
            });
        }
      }
    } else if (
      body.object === 'page' &&
      config.messenger.id &&
      entry.id === config.messenger.id
    ) {
      for (const e of entry.messaging || []) {
        if (
          e.recipient?.id !== config.messenger.id ||
          e.message?.is_echo ||
          !/^\d+$/.test(e.sender?.id || '')
        )
          continue;
        const m = e.message;
        if (m?.mid || e.postback?.mid)
          result.push({
            id: m?.mid || e.postback.mid,
            channel: 'messenger',
            sender: e.sender.id,
            at: Number(e.timestamp),
            contextId: m?.reply_to?.mid,
            text: m?.text || e.postback?.title || '',
          });
      }
    }
  }
  return result;
}
