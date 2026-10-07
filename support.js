const form = document.querySelector('#support-form');
if (form) {
  const button = form.querySelector('button[type="submit"]');
  const status = document.querySelector('#support-status');
  let busy = false;
  let token;
  const prepare = async () => {
    const response = await fetch('/api/support', { cache: 'no-store', signal: AbortSignal.timeout(12000) });
    if (!response.ok) throw new Error('تعذّر تجهيز الطلب الآن. حاول مجددًا.');
    token = (await response.json()).token;
    if (!token) throw new Error('تعذّر تجهيز الطلب الآن. حاول مجددًا.');
  };
  // Fetch only a public form token; no user fields are sent until explicit submission.
  const preparing = prepare().catch(() => { token = null; });
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy || !form.reportValidity()) return;
    busy = true;
    button.disabled = true;
    button.textContent = 'جاري إرسال الطلب...';
    status.textContent = '';
    try {
      await preparing;
      if (!token) await prepare();
      const fields = new FormData(form);
      const response = await fetch('/api/support', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: fields.get('email'), topic: fields.get('topic'), message: fields.get('message'), website: fields.get('website'), token }),
        signal: AbortSignal.timeout(28000),
      });
      const result = await response.json();
      if (!response.ok || !result.ok || !/^MNG-\d{8}-[A-F0-9]{12}$/.test(result.ticket)) {
        token = null;
        throw new Error(result.error || 'تعذّر إرسال الطلب. حاول مجددًا.');
      }
      document.querySelector('#support-ticket').textContent = result.ticket;
      document.querySelector('#support-success').hidden = false;
      form.hidden = true;
      document.querySelector('#support-success').focus();
    } catch (error) {
      status.textContent = error.name === 'TimeoutError' ? 'تعذّر تأكيد الإرسال. احتفظ بوصفك وحاول لاحقًا.' : (/[\u0600-\u06ff]/.test(error.message || '') ? error.message : 'تعذّر الاتصال. تحقق من الإنترنت وحاول مجددًا.');
    } finally {
      busy = false;
      button.disabled = false;
      button.textContent = 'إرسال الطلب';
    }
  });
}
