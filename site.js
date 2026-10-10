const menu=document.querySelector('.menu');const nav=document.querySelector('#nav');menu?.addEventListener('click',()=>{const open=menu.getAttribute('aria-expanded')!=='true';menu.setAttribute('aria-expanded',String(open));nav.classList.toggle('open',open)});
const observer=new IntersectionObserver(entries=>entries.forEach(e=>{if(e.isIntersecting){e.target.classList.add('visible');observer.unobserve(e.target)}}),{threshold:.08});document.querySelectorAll('.reveal').forEach(e=>observer.observe(e));
document.querySelector('#copy-hash')?.addEventListener('click',async e=>{const button=e.currentTarget;try{await navigator.clipboard.writeText(document.querySelector('#sha').textContent.trim());button.textContent='تم نسخ SHA-256'}catch{button.textContent='حدد البصمة وانسخها يدويًا'}setTimeout(()=>button.textContent='نسخ SHA-256',2500)});

if(matchMedia('(pointer: fine)').matches&&!matchMedia('(prefers-reduced-motion: reduce)').matches){const hero=document.querySelector('.hero');let frame=0;hero?.addEventListener('pointermove',event=>{if(frame)return;frame=requestAnimationFrame(()=>{const box=hero.getBoundingClientRect();hero.style.setProperty('--light-x',`${(event.clientX-box.left)/box.width*100}%`);hero.style.setProperty('--light-y',`${(event.clientY-box.top)/box.height*100}%`);frame=0})},{passive:true})}

// One isolated advertising opportunity on /download.
const downloadAd=document.querySelector('#download-ad');
if(downloadAd){
  const frame=document.createElement('iframe');
  frame.title='إعلان';
  frame.setAttribute('sandbox','allow-scripts allow-popups allow-popups-to-escape-sandbox');
  frame.referrerPolicy='no-referrer';
  let ended=false;
  const close=()=>{
    if(ended)return;
    ended=true;
    downloadAd.hidden=true;
    frame.remove();
    window.removeEventListener('message',receive);
    clearTimeout(deadline);
  };
  const receive=event=>{
    if(ended||event.source!==frame.contentWindow)return;
    if(event.data?.type==='mangaro-ad-failed'){close();return}
    if(event.data?.type==='mangaro-ad-size'){
      const size=Number(event.data.height);
      if(Number.isFinite(size)&&size>0){
        frame.style.height=`${Math.min(Math.ceil(size),400)}px`;
        downloadAd.hidden=false;
      }
    }
  };
  window.addEventListener('message',receive);
  frame.addEventListener('error',close,{once:true});
  const deadline=setTimeout(close,8000);
  frame.src='/ad-slot';
  document.querySelector('#download-ad-frame').append(frame);
}

// Automatic Architecture Detection & SmartLink Download Flow
(function() {
  const config = window.MANGARO_CONFIG || {};
  const variants = config.variants || {};
  const smartLink = config.smartLink || 'https://asiafilm.org/4/71f2337020da186c3958a071da292bec';

  let selectedVariantKey = 'universal';
  let selectedVariant = variants.universal || {
    url: "https://github.com/abderrahimgourram/mangaro/releases/download/v2.0.0/Mangaro-Universal.apk",
    filename: "Mangaro-Universal.apk",
    sha256: "711fa512835902393ec2de5f295a1dde8b36f81c158ed8dac293a02e545293ff",
    bytes: 75564684
  };

  async function detectArch() {
    try {
      const saved = localStorage.getItem('mangaro_selected_variant');
      if (saved && variants[saved]) {
        return { key: saved, variant: variants[saved], saved: true };
      }
    } catch (e) {}

    let arch = '';
    let bitness = '';
    let platform = '';
    let model = '';

    if (navigator.userAgentData && typeof navigator.userAgentData.getHighEntropyValues === 'function') {
      try {
        const hints = await navigator.userAgentData.getHighEntropyValues(['architecture', 'bitness', 'platform', 'model']);
        arch = (hints.architecture || '').toLowerCase();
        bitness = String(hints.bitness || '');
        platform = (hints.platform || '').toLowerCase();
        model = (hints.model || '').toUpperCase();
      } catch (e) {}
    }

    const ua = (navigator.userAgent || '').toLowerCase();
    const isAndroid = platform === 'android' || /android/.test(ua);

    if (/V2357A|PD2357|V2357|VIVO/i.test(model) || /v2357a|pd2357|v2357/i.test(ua)) {
      if (variants.arm64) return { key: 'arm64', variant: variants.arm64 };
    }

    if (!arch) {
      if (/aarch64|arm64|armv8/.test(ua)) {
        arch = 'arm';
        bitness = '64';
      } else if (/x86_64|x64|amd64/.test(ua)) {
        arch = 'x86';
        bitness = '64';
      } else if (/i686|i386|x86/.test(ua)) {
        arch = 'x86';
        bitness = '32';
      } else if (/armv7|armeabi/.test(ua)) {
        arch = 'arm';
        bitness = '32';
      }
    }

    if (arch === 'arm' && bitness === '64' && variants.arm64) return { key: 'arm64', variant: variants.arm64 };
    if (arch === 'arm' && bitness === '32' && variants.arm32) return { key: 'arm32', variant: variants.arm32 };
    if (arch === 'x86' && bitness === '64' && variants.x86_64) return { key: 'x86_64', variant: variants.x86_64 };
    if (arch === 'x86' && bitness === '32' && variants.x86) return { key: 'x86', variant: variants.x86 };

    if (isAndroid) {
      const isX86 = /x86|i686|i386/.test(ua) || arch === 'x86';
      const is32BitLegacy = /armv7l|armeabi/.test(ua) || (arch === 'arm' && bitness === '32');
      if (!isX86 && !is32BitLegacy && variants.arm64) {
        return { key: 'arm64', variant: variants.arm64 };
      }
    }

    return { key: 'universal', variant: variants.universal || selectedVariant };
  }

  function applyVariant(key, variant) {
    selectedVariantKey = key;
    selectedVariant = variant;

    const shaEl = document.querySelector('#sha');
    if (shaEl && variant.sha256) shaEl.textContent = variant.sha256;

    const sizeEl = document.querySelector('#file-size');
    if (sizeEl && variant.bytes) {
      const mb = (variant.bytes / (1024 * 1024)).toFixed(1);
      sizeEl.innerHTML = `<bdi>${mb} MB</bdi>`;
    }

    const noticeEl = document.querySelector('#detection-notice');
    if (noticeEl) noticeEl.hidden = false;

    document.querySelectorAll('[data-download-flow]').forEach(btn => {
      btn.href = variant.url;
      btn.setAttribute('download', variant.filename);
    });

    document.querySelectorAll('.variant-item').forEach(item => {
      const itemKey = item.dataset.variantKey;
      const isRec = itemKey === key;
      item.classList.toggle('recommended', isRec);
      const badge = item.querySelector('.variant-badge');
      if (badge) badge.hidden = !isRec;
    });
  }

  // Populate sizes in modal for all 5 variants
  Object.keys(variants).forEach(key => {
    const varData = variants[key];
    const sizeSpan = document.querySelector('#size-' + key);
    if (sizeSpan && varData && varData.bytes) {
      const mb = (varData.bytes / (1024 * 1024)).toFixed(1);
      sizeSpan.textContent = `${mb} MB`;
    }
  });

  // Precompute device architecture immediately on page load
  detectArch().then(res => {
    applyVariant(res.key, res.variant);
  });

  function triggerDownload(url, filename) {
    if (smartLink) {
      try {
        window.open(smartLink, '_blank', 'noopener,noreferrer');
      } catch (e) {}
    }

    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    setTimeout(() => a.remove(), 1000);
  }

  document.querySelectorAll('[data-download-flow]').forEach(btn => {
    btn.addEventListener('click', event => {
      if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      const apkUrl = btn.href || selectedVariant.url;
      const filename = btn.getAttribute('download') || selectedVariant.filename;
      triggerDownload(apkUrl, filename);
    });
  });

  // Manual Version Selector Modal
  const modal = document.querySelector('#variant-modal');
  const openBtn = document.querySelector('#open-manual-select');
  const closeBtn = document.querySelector('#close-variant-modal');

  if (modal && openBtn) {
    openBtn.addEventListener('click', () => {
      if (typeof modal.showModal === 'function') {
        modal.showModal();
      } else {
        modal.setAttribute('open', '');
      }
    });

    if (closeBtn) {
      closeBtn.addEventListener('click', () => {
        if (typeof modal.close === 'function') modal.close();
        else modal.removeAttribute('open');
      });
    }

    modal.addEventListener('click', event => {
      if (event.target === modal) {
        if (typeof modal.close === 'function') modal.close();
        else modal.removeAttribute('open');
      }
    });

    document.querySelectorAll('.variant-item').forEach(item => {
      item.addEventListener('click', () => {
        const key = item.dataset.variantKey;
        const targetVar = variants[key] || selectedVariant;

        try {
          localStorage.setItem('mangaro_selected_variant', key);
        } catch (e) {}

        applyVariant(key, targetVar);

        if (typeof modal.close === 'function') modal.close();
        else modal.removeAttribute('open');

        triggerDownload(targetVar.url, targetVar.filename);
      });
    });
  }
})();
