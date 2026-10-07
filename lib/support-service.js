import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';

export const SUPPORT_EMAIL = 'mangaroapp@gmail.com';
export const SUPPORT_TOPICS = Object.freeze({
  app: 'مشكلة في التطبيق',
  reading: 'مشكلة في القراءة',
  downloads: 'مشكلة في التنزيل',
  account: 'الحساب',
  suggestion: 'اقتراح',
  other: 'موضوع آخر',
});

const MAX_BYTES = 20000;
const COOLDOWN_SECONDS = 60;
const COOKIE_NAME = 'mangaro_support_wait';
const PUBLIC_ORIGIN = 'https://mangaro-web.vercel.app';

const response = (body, status = 200, headers = {}) => Response.json(body, {
  status,
  headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', ...headers },
});

function sign(payload, secret) {
  const data = Buffer.from(JSON.stringify(payload)).toString('base64url');
  return `${data}.${createHmac('sha256', secret).update(data).digest('base64url')}`;
}

function verify(value, secret, kind) {
  if (typeof value !== 'string' || value.length > 1024) return null;
  const [data, signature, extra] = value.split('.');
  if (!data || !signature || extra || !/^[\w-]+$/.test(data + signature)) return null;
  const expected = createHmac('sha256', secret).update(data).digest();
  const actual = Buffer.from(signature, 'base64url');
  if (actual.length !== expected.length || !timingSafeEqual(actual, expected)) return null;
  try {
    const payload = JSON.parse(Buffer.from(data, 'base64url').toString());
    return payload.kind === kind ? payload : null;
  } catch { return null; }
}

async function readBody(request) {
  if (Number(request.headers.get('content-length')) > MAX_BYTES) throw new Error('PAYLOAD_TOO_LARGE');
  if (!request.body) throw new Error('INVALID_BODY');
  const reader = request.body.getReader();
  const chunks = [];
  let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BYTES) {
        await reader.cancel();
        throw new Error('PAYLOAD_TOO_LARGE');
      }
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } finally { reader.releaseLock(); }
}

// No support request, email address or description is retained in process memory.
// Signed form tokens, a honeypot and a signed cooldown cookie provide basic spam protection.
// The cookie is per browser, not a distributed/IP rate limit.
export function createSupportHandlers({ settings, sendMail, now = Date.now }) {
  async function get() {
    const { secret } = settings();
    if (!secret || secret.length < 32) return response({ error: 'الخدمة غير متاحة الآن. حاول لاحقًا.' }, 503);
    return response({ token: sign({ kind: 'form', issuedAt: now(), nonce: randomBytes(12).toString('hex') }, secret) });
  }

  async function post(request) {
    const config = settings();
    const origins = [PUBLIC_ORIGIN];
    if (config.deploymentOrigin) origins.push(config.deploymentOrigin);
    const url = new URL(request.url);
    if (config.local && ['localhost', '127.0.0.1'].includes(url.hostname)) origins.push(url.origin);
    if (!origins.includes(request.headers.get('origin')) || request.headers.get('sec-fetch-site') === 'cross-site') {
      return response({ error: 'أرسل الطلب من صفحة خدمة العملاء.' }, 403);
    }
    if (request.headers.get('content-type')?.split(';')[0].trim().toLowerCase() !== 'application/json') {
      return response({ error: 'تعذّر قراءة الطلب.' }, 415);
    }
    if (!config.secret || config.secret.length < 32) return response({ error: 'الخدمة غير متاحة الآن. حاول لاحقًا.' }, 503);
    let body;
    try { body = await readBody(request); }
    catch (error) { return response({ error: 'تعذّر قراءة الطلب. راجع الحقول وحاول مجددًا.' }, error.message === 'PAYLOAD_TOO_LARGE' ? 413 : 400); }
    if (!body || typeof body !== 'object' || Array.isArray(body)) return response({ error: 'راجع الحقول المطلوبة.' }, 400);
    const email = typeof body.email === 'string' ? body.email.trim() : '';
    const message = typeof body.message === 'string' ? body.message.trim() : '';
    if (body.website || email.length > 254 || /[\x00-\x1f\x7f]/.test(email) || !/^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/u.test(email) ||
        typeof body.topic !== 'string' || !Object.hasOwn(SUPPORT_TOPICS, body.topic) || message.length < 10 || message.length > 4000 || /[\x00-\x08\x0b\x0c\x0e-\x1f]/.test(message)) {
      return response({ error: 'تحقق من البريد والموضوع، واكتب وصفًا من 10 إلى 4000 حرف.' }, 400);
    }
    const timestamp = now();
    const token = verify(body.token, config.secret, 'form');
    if (!token || !Number.isSafeInteger(token.issuedAt) || timestamp - token.issuedAt < 0 || timestamp - token.issuedAt > 3600000) {
      return response({ error: 'أعد المحاولة لتجديد نموذج الطلب.' }, 400);
    }
    const cookie = (request.headers.get('cookie') || '').split(';').map(value => value.trim()).find(value => value.startsWith(`${COOKIE_NAME}=`));
    const cooldown = verify(cookie?.slice(COOKIE_NAME.length + 1), config.secret, 'cooldown');
    if (cooldown && Number.isSafeInteger(cooldown.until) && cooldown.until > timestamp) {
      const retryAfter = Math.ceil((cooldown.until - timestamp) / 1000);
      return response({ error: 'وصل طلبك السابق. انتظر دقيقة قبل إرسال طلب آخر.' }, 429, { 'Retry-After': String(retryAfter) });
    }
    if (!config.password || (config.user && config.user !== SUPPORT_EMAIL)) return response({ error: 'تعذّر إرسال الطلب الآن. حاول لاحقًا.' }, 503);
    const date = new Date(timestamp).toISOString().slice(0, 10).replaceAll('-', '');
    const ticket = `MNG-${date}-${randomBytes(6).toString('hex').toUpperCase()}`;
    try {
      const delivered = await sendMail({
        from: { name: 'Mangaro Support', address: SUPPORT_EMAIL },
        to: SUPPORT_EMAIL,
        replyTo: { address: email },
        subject: `[${ticket}] ${SUPPORT_TOPICS[body.topic]}`,
        text: `رقم الطلب: ${ticket}\nالبريد الإلكتروني: ${email}\nالموضوع: ${SUPPORT_TOPICS[body.topic]}\n\nوصف المشكلة:\n${message}\n`,
      }, config.password);
      if (!delivered.accepted?.some(address => String(address).toLowerCase() === SUPPORT_EMAIL)) throw new Error('RECIPIENT_NOT_ACCEPTED');
    } catch (error) {
      // Never log request contents, SMTP credentials or the raw SMTP error.
      console.error('SUPPORT_MAIL_FAILED', ['EAUTH', 'ETIMEDOUT', 'ECONNECTION', 'EENVELOPE', 'EMESSAGE'].includes(error.code) ? error.code : 'SEND_FAILED');
      return response({ error: 'لم نتمكن من إرسال الطلب. احتفظ بوصفك وحاول مجددًا.' }, 502);
    }
    const wait = sign({ kind: 'cooldown', until: timestamp + COOLDOWN_SECONDS * 1000 }, config.secret);
    return response({ ok: true, ticket, message: 'تم استلام طلبك. احتفظ برقم الطلب للمتابعة.' }, 201, {
      'Set-Cookie': `${COOKIE_NAME}=${wait}; Max-Age=${COOLDOWN_SECONDS}; Path=/api/support; HttpOnly; Secure; SameSite=Strict`,
    });
  }
  return { get, post };
}
