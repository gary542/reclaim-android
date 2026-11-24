import { DatabaseSync } from 'node:sqlite';
import { createHash, randomBytes } from 'node:crypto';

export const hour = 3_600_000;
export const uuidPattern =
  /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const pending = new Set(['scheduled', 'awaiting']);
export const fail = (message, status = 400) =>
  Object.assign(new Error(message), { status });

function shortText(value, max) {
  return (
    typeof value === 'string' &&
    value.trim().length > 0 &&
    value.trim().length <= max &&
    !/[\u0000-\u001f\u007f]/u.test(value)
  );
}

export class Store {
  constructor(path) {
    this.db = new DatabaseSync(path);
    this.db.exec(`
      PRAGMA journal_mode=WAL;
      PRAGMA foreign_keys=ON;
      PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS bookings (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, service TEXT NOT NULL, channel TEXT NOT NULL,
        recipient TEXT, startsAt INTEGER NOT NULL, zone TEXT NOT NULL, consentAt INTEGER NOT NULL,
        createHash TEXT NOT NULL, linkCode TEXT UNIQUE, status TEXT NOT NULL DEFAULT 'scheduled',
        stage INTEGER NOT NULL DEFAULT 0, review INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '',
        revision INTEGER NOT NULL DEFAULT 1, lastReply INTEGER NOT NULL DEFAULT 0);
      CREATE INDEX IF NOT EXISTS bookings_contact ON bookings(channel,recipient,startsAt);
      CREATE TABLE IF NOT EXISTS sends (
        booking TEXT NOT NULL REFERENCES bookings(id), stage INTEGER NOT NULL, state TEXT NOT NULL,
        started INTEGER NOT NULL, providerId TEXT UNIQUE, PRIMARY KEY(booking,stage));
      CREATE TABLE IF NOT EXISTS events (id TEXT PRIMARY KEY, observed INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS wa_ids (scoped TEXT PRIMARY KEY, phone TEXT);
      CREATE TABLE IF NOT EXISTS wa_waiting (id TEXT PRIMARY KEY, scoped TEXT NOT NULL,
        at INTEGER NOT NULL, reference TEXT, answer TEXT NOT NULL, observed INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS optouts (channel TEXT, recipient TEXT, PRIMARY KEY(channel,recipient));
    `);
  }
  q(sql) {
    return this.db.prepare(sql);
  }
  tx(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const result = fn();
      this.db.exec('COMMIT');
      return result;
    } catch (e) {
      this.db.exec('ROLLBACK');
      throw e;
    }
  }
  get(id) {
    return this.q('SELECT * FROM bookings WHERE id=?').get(id);
  }
  list() {
    return this.q('SELECT * FROM bookings ORDER BY startsAt,id').all();
  }
  public(b) {
    return {
      id: b.id,
      name: b.name,
      service: b.service,
      channel: b.channel,
      startsAt: b.startsAt,
      zone: b.zone,
      status: b.status,
      stage: b.stage,
      review: !!b.review,
      note: b.note,
      revision: b.revision,
      linked: !!b.recipient,
      linkCode: b.linkCode || '',
    };
  }
  create(id, input, now) {
    const { name, service, channel, startsAt, zone, consent } = input;
    let recipient =
      channel === 'whatsapp'
        ? String(input.recipient ?? '').replace(/[\s()-]/g, '')
        : '';
    if (
      !uuidPattern.test(id) ||
      !shortText(name, 60) ||
      !shortText(service, 80) ||
      !['whatsapp', 'messenger'].includes(channel) ||
      !Number.isSafeInteger(startsAt) ||
      !shortText(zone, 80) ||
      consent !== true
    )
      throw fail('Check the booking details and permission to send.');
    if (channel === 'whatsapp' && !/^\+[1-9]\d{7,14}$/.test(recipient))
      throw fail('Enter the WhatsApp number with its country code.');
    try {
      new Intl.DateTimeFormat('en-GB', { timeZone: zone });
    } catch {
      throw fail('Choose a valid time zone.');
    }
    const hash = createHash('sha256')
      .update(
        JSON.stringify([
          name.trim(),
          service.trim(),
          channel,
          recipient,
          startsAt,
          zone,
        ])
      )
      .digest('hex');
    return this.tx(() => {
      const existing = this.get(id);
      if (existing) {
        if (hash !== existing.createHash)
          throw fail('This booking ID already belongs to another draft.', 409);
        return existing;
      }
      if (startsAt <= now || startsAt > now + 730 * 24 * hour)
        throw fail('Choose a future appointment within two years.');
      if (recipient && this.optedOut(channel, recipient))
        throw fail('This customer opted out of messages on this channel.', 409);
      const code =
        channel === 'messenger'
          ? randomBytes(6).toString('hex').toUpperCase()
          : null;
      this.q(
        'INSERT INTO bookings(id,name,service,channel,recipient,startsAt,zone,consentAt,createHash,linkCode) VALUES(?,?,?,?,?,?,?,?,?,?)'
      ).run(
        id,
        name.trim(),
        service.trim(),
        channel,
        recipient || null,
        startsAt,
        zone,
        now,
        hash,
        code
      );
      return this.get(id);
    });
  }
  flag(id, note) {
    this.q(
      'UPDATE bookings SET review=1,note=?,revision=revision+1 WHERE id=? AND (review=0 OR note!=?)'
    ).run(note, id, note);
  }
  optedOut(channel, recipient) {
    return !!this.q(
      'SELECT 1 FROM optouts WHERE channel=? AND recipient=?'
    ).get(channel, recipient);
  }
  action(id, action, revision, now) {
    return this.tx(() => {
      const b = this.get(id);
      if (!b) throw fail('Booking was not found.', 404);
      const target = {
        confirm: 'confirmed',
        release: 'released',
        refill: 'refilled',
        cancel: 'cancelled',
      }[action];
      if (!target) throw fail('Unknown booking action.');
      if (target === b.status && !b.review) return b;
      if (b.revision !== revision)
        throw fail(
          'This booking changed. Refresh it before trying again.',
          409
        );
      const valid =
        (target === 'refilled' && b.status === 'released') ||
        (target === 'confirmed' && b.status === 'released' && b.review === 1) ||
        (['cancelled', 'released', 'confirmed'].includes(target) &&
          ['scheduled', 'awaiting', 'confirmed'].includes(b.status));
      if (!valid || b.startsAt <= now)
        throw fail('That action is unavailable for this booking.', 409);
      this.q(
        "UPDATE bookings SET status=?,review=0,note='',linkCode=NULL,revision=revision+1 WHERE id=?"
      ).run(target, id);
      return this.get(id);
    });
  }
  claim(id, now, allowed) {
    return this.tx(() => {
      const b = this.get(id);
      if (
        !b ||
        !pending.has(b.status) ||
        b.review ||
        b.startsAt <= now ||
        b.startsAt - now > 48 * hour
      )
        return;
      if (!b.recipient) return; // Customer can still link their own Messenger conversation.
      if (!allowed(b) || this.optedOut(b.channel, b.recipient)) {
        this.flag(
          id,
          'Delivery needs review. Check channel setup, test recipients and messaging permission.'
        );
        return;
      }
      const previous = this.q(
        'SELECT * FROM sends WHERE booking=? ORDER BY stage DESC LIMIT 1'
      ).get(id);
      if (previous && previous.state !== 'accepted') {
        if (
          previous.state === 'sending' &&
          now - previous.started >= 5 * 60_000
        )
          this.flag(
            id,
            'Delivery was interrupted. Check the channel history before contacting the customer.'
          );
        return;
      }
      if (b.stage && b.startsAt - now <= 12 * hour) {
        this.flag(
          id,
          'No reply yet. Check with the customer before releasing this slot.'
        );
        return;
      }
      if (
        b.stage &&
        (b.stage >= 2 ||
          b.startsAt - now > 24 * hour ||
          now - previous.started < 12 * hour)
      )
        return;
      const stage = b.stage + 1;
      this.q(
        "INSERT INTO sends(booking,stage,state,started) VALUES(?,?,'sending',?)"
      ).run(id, stage, now);
      return { b, stage };
    });
  }
  finish(id, stage, providerId) {
    this.tx(() => {
      if (!providerId) {
        this.q(
          "UPDATE sends SET state='uncertain' WHERE booking=? AND stage=?"
        ).run(id, stage);
        if (pending.has(this.get(id).status))
          this.flag(
            id,
            'Reminder delivery could not be verified. Check the channel history.'
          );
        return;
      }
      this.q(
        "UPDATE sends SET state='accepted',providerId=? WHERE booking=? AND stage=?"
      ).run(providerId, id, stage);
      this.q(
        "UPDATE bookings SET stage=?,status=CASE WHEN status='scheduled' THEN 'awaiting' ELSE status END,revision=revision+1 WHERE id=?"
      ).run(stage, id);
    });
  }
  receive(event, now) {
    if (!event.id) return;
    this.tx(() => {
      if (
        !this.q('INSERT OR IGNORE INTO events VALUES(?,?)').run(
          event.channel + ':' + event.id,
          now
        ).changes
      )
        return;
      // Both channels can call back before their send request has returned its message ID.
      // A transient response lets Meta retry the same event after this transaction rolls back.
      const reference = event.contextId || event.failedId;
      const known =
        reference &&
        this.q('SELECT 1 FROM sends WHERE providerId=?').get(reference);
      const immediate = /^(stop|unsubscribe|link\s)/i.test(
        String(event.text || '').trim()
      );
      if (
        !known &&
        !immediate &&
        this.q(
          `SELECT 1 FROM sends s JOIN bookings b ON b.id=s.booking
        WHERE s.state='sending' AND s.started>=? AND b.channel=? AND (? IS NULL OR b.recipient=?) LIMIT 1`
        ).get(
          now - 5 * 60_000,
          event.channel,
          event.sender || null,
          event.sender || null
        )
      ) {
        throw fail('The outbound receipt is pending. Retry this event.', 503);
      }
      if (event.failedId) {
        const row = this.q('SELECT booking FROM sends WHERE providerId=?').get(
          event.failedId
        );
        if (row && pending.has(this.get(row.booking).status))
          this.flag(
            row.booking,
            'The channel reported a delivery failure. Check this booking.'
          );
        return;
      }
      const { channel, sender } = event;
      if (!sender) return;
      const msg = String(event.text || '').trim();
      const who = this.q(
        "SELECT * FROM bookings WHERE channel=? AND recipient=? AND startsAt>? AND status NOT IN ('cancelled','refilled') ORDER BY startsAt"
      ).all(channel, sender, now);
      if (/^(stop|unsubscribe)$/i.test(msg)) {
        this.q('INSERT OR IGNORE INTO optouts VALUES(?,?)').run(
          channel,
          sender
        );
        who
          .filter((b) => pending.has(b.status))
          .forEach((b) =>
            this.flag(
              b.id,
              'Customer opted out. Contact them separately if needed.'
            )
          );
        return;
      }
      if (this.optedOut(channel, sender)) return;
      const link = /^LINK\s+([A-F\d]{12})$/i.exec(msg);
      if (link && channel === 'messenger') {
        this.q(
          "UPDATE bookings SET recipient=?,linkCode=NULL,revision=revision+1 WHERE channel='messenger' AND linkCode=? AND recipient IS NULL AND status='scheduled' AND startsAt>?"
        ).run(sender, link[1].toUpperCase(), now);
        return;
      }
      let candidates;
      if (event.contextId) {
        const send = this.q('SELECT booking FROM sends WHERE providerId=?').get(
          event.contextId
        );
        candidates = who.filter((b) => b.id === send?.booking);
      } else {
        // Closed/released or recent past appointments can still be the intended subject of an unthreaded reply.
        candidates = this.q(
          `SELECT DISTINCT b.* FROM bookings b JOIN sends s ON s.booking=b.id
          WHERE b.channel=? AND b.recipient=? AND s.state='accepted' AND b.startsAt>? AND s.started<=?`
        ).all(channel, sender, now - 7 * 24 * hour, (event.at || now) + 999);
      }
      if (candidates.length !== 1) {
        candidates
          .filter((b) => pending.has(b.status) && b.startsAt > now)
          .forEach((b) =>
            this.flag(
              b.id,
              'Several reminders match this conversation. Check which appointment the customer meant.'
            )
          );
        return;
      }
      const b = candidates[0];
      if (
        b.startsAt <= now ||
        b.consentAt > (event.at || now) + 999 ||
        b.lastReply > (event.at || now) ||
        ['cancelled', 'refilled'].includes(b.status)
      )
        return;
      if (!/^(yes|no)$/i.test(msg)) {
        if (pending.has(b.status))
          this.flag(
            b.id,
            'A customer message needs a personal reply. Open the channel conversation.'
          );
        return;
      }
      const status = msg.toLowerCase() === 'yes' ? 'confirmed' : 'released';
      if (b.status === 'released') {
        if (status === 'confirmed')
          this.flag(
            b.id,
            'The customer replied YES after releasing this slot. Check availability with them.'
          );
        return;
      }
      this.q(
        "UPDATE bookings SET status=?,review=0,note='',lastReply=?,revision=revision+1 WHERE id=?"
      ).run(status, event.at || now, b.id);
    });
  }
}
