import test from 'node:test';
import assert from 'node:assert/strict';
import { createSupportHandlers, SUPPORT_EMAIL } from '../lib/support-service.js';

function fixture({ password = 'test-password', sendMail } = {}) {
  let time = Date.UTC(2026, 9, 7, 19);
  const settings = () => ({ secret: 'unit-test-secret-never-deployed-0123456789', password });
  const sent = [];
  const deliver = sendMail || (async mail => { sent.push(mail); return { accepted: [SUPPORT_EMAIL] }; });
  return { handlers: createSupportHandlers({ settings, sendMail: deliver, now: () => time }), sent,
    advance: ms => { time += ms; }, settings };
}

async function submission(f, overrides = {}, options = {}) {
  const { token } = await (await f.handlers.get()).json();
  return new Request('https://mangaro-web.vercel.app/api/support', {
    method: 'POST',
    headers: { Origin: 'https://mangaro-web.vercel.app', 'Content-Type': 'application/json', ...options.headers },
    body: JSON.stringify({ email: 'reader@example.org', topic: 'app', message: 'وصف واضح للمشكلة التي واجهتني', website: '', token, ...overrides }),
  });
}

test('accepted mail gets a ticket; recipient fixed, reply-to and reference included', async () => {
  const f = fixture();
  const result = await f.handlers.post(await submission(f, { to: 'unwanted@example.org' }));
  assert.equal(result.status, 201);
  const body = await result.json();
  assert.match(body.ticket, /^MNG-20261007-[A-F0-9]{12}$/);
  assert.equal(f.sent.length, 1);
  assert.equal(f.sent[0].to, SUPPORT_EMAIL);
  assert.equal(f.sent[0].from.address, SUPPORT_EMAIL);
  assert.equal(f.sent[0].replyTo.address, 'reader@example.org');
  assert.ok(f.sent[0].text.includes(body.ticket));
  assert.ok(f.sent[0].text.includes('reader@example.org'));
});

test('invalid fields, email header injection, topics and honeypot never send', async () => {
  const f = fixture();
  for (const input of [{ email: '' }, { email: 'not-an-email' }, { email: 'a@example.org\r\nBcc:b@example.org' },
    { message: '' }, { message: 'x'.repeat(4001) }, { topic: '__proto__' }, { topic: ['app'] }, { website: 'spam' }]) {
    assert.equal((await f.handlers.post(await submission(f, input))).status, 400);
  }
  assert.equal(f.sent.length, 0);
});

test('cross-origin form posts and non-JSON are rejected', async () => {
  const f = fixture();
  assert.equal((await f.handlers.post(await submission(f, {}, { headers: { Origin: 'https://other.example.org' } }))).status, 403);
  assert.equal((await f.handlers.post(await submission(f, {}, { headers: { 'Content-Type': 'text/plain' } }))).status, 415);
  assert.equal(f.sent.length, 0);
});

test('forged, expired and future form tokens are rejected', async () => {
  const f = fixture();
  assert.equal((await f.handlers.post(await submission(f, { token: 'forged' }))).status, 400);
  const expired = await submission(f);
  f.advance(3600001);
  assert.equal((await f.handlers.post(expired)).status, 400);
  const future = await submission(f);
  f.advance(-1000);
  assert.equal((await f.handlers.post(future)).status, 400);
  assert.equal(f.sent.length, 0);
});

test('payload limit applies even without Content-Length', async () => {
  const f = fixture();
  assert.equal((await f.handlers.post(await submission(f, { message: 'x'.repeat(21000) }))).status, 413);
  assert.equal(f.sent.length, 0);
});

test('cooldown survives another function instance and expires', async () => {
  const f = fixture();
  const result = await f.handlers.post(await submission(f));
  const cookie = result.headers.get('Set-Cookie').split(';')[0];
  assert.match(result.headers.get('Set-Cookie'), /HttpOnly; Secure; SameSite=Strict/);
  const another = createSupportHandlers({ settings: f.settings, sendMail: async () => { throw new Error('should not send'); }, now: () => Date.UTC(2026, 9, 7, 19) });
  const limited = await another.post(await submission(f, {}, { headers: { Cookie: cookie } }));
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get('Retry-After'), '60');
  f.advance(60001);
  assert.equal((await f.handlers.post(await submission(f, {}, { headers: { Cookie: cookie } }))).status, 201);
  assert.equal(f.sent.length, 2);
});

test('missing SMTP configuration never reports a successful ticket', async () => {
  const f = fixture({ password: '' });
  const result = await f.handlers.post(await submission(f));
  assert.equal(result.status, 503);
  assert.equal((await result.json()).ticket, undefined);
  assert.equal(f.sent.length, 0);
});

test('SMTP failure never reports success or exposes credentials/request data', async () => {
  const f = fixture({ sendMail: async () => { throw Object.assign(new Error('test-password private SMTP detail'), { code: 'EAUTH' }); } });
  const result = await f.handlers.post(await submission(f));
  assert.equal(result.status, 502);
  const text = await result.text();
  assert.ok(!text.includes('test-password'));
  assert.ok(!text.includes('reader@example.org'));
  assert.ok(!text.includes('ticket'));
});

test('a rejected recipient never gets a ticket', async () => {
  const f = fixture({ sendMail: async () => ({ accepted: [], rejected: [SUPPORT_EMAIL] }) });
  const result = await f.handlers.post(await submission(f));
  assert.equal(result.status, 502);
  assert.equal((await result.json()).ticket, undefined);
});
