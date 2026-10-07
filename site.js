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
