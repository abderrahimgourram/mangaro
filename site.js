const menu=document.querySelector('.menu');const nav=document.querySelector('#nav');menu?.addEventListener('click',()=>{const open=menu.getAttribute('aria-expanded')!=='true';menu.setAttribute('aria-expanded',String(open));nav.classList.toggle('open',open)});
const observer=new IntersectionObserver(entries=>entries.forEach(e=>{if(e.isIntersecting){e.target.classList.add('visible');observer.unobserve(e.target)}}),{threshold:.08});document.querySelectorAll('.reveal').forEach(e=>observer.observe(e));
document.querySelector('#copy-hash')?.addEventListener('click',async e=>{const button=e.currentTarget;try{await navigator.clipboard.writeText(document.querySelector('#sha').textContent.trim());button.textContent='تم نسخ SHA-256'}catch{button.textContent='حدد البصمة وانسخها يدويًا'}setTimeout(()=>button.textContent='نسخ SHA-256',2500)});
// A single RAF per pointer movement; disabled on phones and for reduced motion.
if(matchMedia('(pointer: fine)').matches&&!matchMedia('(prefers-reduced-motion: reduce)').matches){const hero=document.querySelector('.hero');let frame=0;hero?.addEventListener('pointermove',event=>{if(frame)return;frame=requestAnimationFrame(()=>{const box=hero.getBoundingClientRect();hero.style.setProperty('--light-x',`${(event.clientX-box.left)/box.width*100}%`);hero.style.setProperty('--light-y',`${(event.clientY-box.top)/box.height*100}%`);frame=0})},{passive:true})}

// One isolated advertising opportunity on /download. The APK link is never gated.
const downloadAd=document.querySelector('#download-ad');
if(downloadAd){
  const frame=document.createElement('iframe');
  frame.title='إعلان';
  // An opaque origin isolates ad scripts from the website DOM; top navigation is forbidden.
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
        // Geometry only, never a claim that a cross-origin creative actually rendered.
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

// A voluntary offer opens at most once per tab session, only in this click handler.
const downloadButtons=[...document.querySelectorAll('[data-download-flow]')];
if(downloadButtons.length){
  const sessionKey='mangaro-download-offer-opened';
  let opened=false;
  let storageAvailable=true;
  try{opened=sessionStorage.getItem(sessionKey)==='1'}catch{storageAvailable=false}
  const offer=(()=>{
    try{
      const url=new URL(downloadButtons[0].dataset.offerUrl||'');
      return ['http:','https:'].includes(url.protocol)&&!url.username&&!url.password?url.href:null;
    }catch{return null}
  })();
  const continueDownload=()=>{
    for(const button of downloadButtons){
      button.href=button.dataset.apkUrl;
      button.download='Mangaro-release.apk';
      button.textContent='متابعة التحميل';
      button.removeAttribute('title');
    }
    document.querySelectorAll('[data-download-offer-notice]').forEach(note=>{note.hidden=true});
  };
  if(opened)continueDownload();
  if(!offer||!storageAvailable){
    document.querySelectorAll('[data-download-offer-notice]').forEach(note=>{note.hidden=true});
  }
  for(const button of downloadButtons){
    button.addEventListener('click',event=>{
      // Preserve native modified-click behavior; it never opens an offer automatically.
      if(event.button!==0||event.ctrlKey||event.metaKey||event.shiftKey||event.altKey)return;
      if(opened||!offer||!storageAvailable)return;
      // Persist before opening: rapid taps, reloads and other CTAs cannot repeat the offer.
      try{sessionStorage.setItem(sessionKey,'1')}catch{
        storageAvailable=false;
        return;
      }
      opened=true;
      event.preventDefault();
      continueDownload();
      try{window.open(offer,'_blank','noopener,noreferrer')}catch{}
      // A blocked popup never prevents the now-visible official APK action.
    });
  }
}
