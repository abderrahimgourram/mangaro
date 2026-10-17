// Local editorial decisions, never an automatic title/cover matching service.
const opaque=/^[a-f0-9]{64}$/;
const methods=new Set(["MAINTAINER_CONFIRMED","PROVIDER_LINK","EXTERNAL_AUTHORITY"]);
function check(ok,message){if(!ok)throw Error(message);}
function fields(value,allowed){check(value&&typeof value==="object"&&!Array.isArray(value)&&Object.keys(value).every(k=>allowed.includes(k)),"حقول غير مدعومة");}
function uri(value,evidence=false){
  check(typeof value==="string"&&value.length>0&&value.length<=2048&&!/[\u0000-\u001f]/.test(value)&&!value.includes("[REDACTED]"),"رابط غير صالح");
  const u=new URL(value,"https://relative.invalid");
  check(!u.username&&!u.password&&u.protocol==="https:","لا تقبل بيانات دخول أو روابط تنفيذية");
  if(evidence)check(value.startsWith("https://")&&!u.search,"الدليل يجب أن يكون رابط HTTPS عامًا");
  for(const key of [...u.searchParams.keys(),...new URLSearchParams(u.hash.slice(1)).keys()])check(!/^(token|access_token|auth|authorization|password|secret|key|api_key|signature|session|cookie|jwt|email|user_id|code)$/i.test(key),"رابط يتضمن بيانات خاصة");
}
export function validate(doc){
  fields(doc,["schema","records"]);
  check(doc?.schema===1&&Array.isArray(doc.records)&&doc.records.length<=2048,"صيغة السجل غير مدعومة");
  const roots=new Set(),routes=new Map(),providers=new Map();
  function claim(map,key,root){check(!map.has(key)||map.get(key)===root,"طبعة مرتبطة بعملين؛ راجع التعارض");map.set(key,root);}
  for(const r of doc.records){
    fields(r,["key","title","state","editions","evidence","method","reviewer","reviewedAt"]);
    check(typeof r.key==="string"&&opaque.test(r.key)&&!roots.has(r.key),"هوية عمل مكررة أو غير صالحة");roots.add(r.key);
    check(typeof r.title==="string"&&r.title.trim()&&["PENDING","ACTIVE","REVOKED"].includes(r.state),"العنوان/الحالة غير صالحة");
    check(Array.isArray(r.editions)&&r.editions.length>=2&&r.editions.length<=32,"يلزم مرجعان مختلفان للطبعات");
    check(Array.isArray(r.evidence)&&r.evidence.length>=1&&r.evidence.length<=8,"أضف روابط الأدلة العامة");r.evidence.forEach(x=>uri(x,true));
    const seen=new Set();
    for(const e of r.editions){
      fields(e,["sourceId","url","providerId"]);
      check(typeof e.sourceId==="string"&&/^-?[1-9][0-9]*$/.test(e.sourceId),"معرّف المصدر يجب أن يبقى نصًا دقيقًا");
      const id=BigInt(e.sourceId);check(id>=-(2n**63n)&&id<2n**63n,"معرف المصدر خارج النطاق");
      uri(e.url);
      const route=e.sourceId+"\0"+e.url;check(!seen.has(route),"طبعة مكررة");seen.add(route);
      check(e.providerId==null||(typeof e.providerId==="string"&&/^[A-Za-z0-9._:-]{1,128}$/.test(e.providerId)),"معرف مزود غير صالح");
      if(r.state==="ACTIVE"){
        claim(routes,route,r.key);
        if(e.providerId!=null)claim(providers,e.sourceId+"\0"+e.providerId,r.key);
      }
    }
    if(r.state==="ACTIVE")check(methods.has(r.method)&&typeof r.reviewer==="string"&&r.reviewer.trim()&&typeof r.reviewedAt==="string"&&/^\d{4}-\d{2}-\d{2}$/.test(r.reviewedAt),"الربط النشط يتطلب مراجعة مؤرخة وصريحة");
  }
  return doc;
}
export function decision(doc,key,active,reviewer,date){
  validate(doc);
  check(typeof reviewer==="string"&&reviewer.trim(),"أدخل اسم المراجع");
  const next=JSON.parse(JSON.stringify(doc)),row=next.records.find(r=>r.key===key);
  check(row,"السجل غير موجود");
  Object.assign(row,{state:active?"ACTIVE":"REVOKED",method:"MAINTAINER_CONFIRMED",reviewer:reviewer.trim(),reviewedAt:date});
  return validate(next);
}
if(typeof document!=="undefined"){
  const get=id=>document.getElementById(id);
  let current=null;
  function guarded(action){try{action();get("problem").textContent="";}catch(e){get("problem").textContent=e.message;}}
  function render(doc){
    current=validate(doc);get("editor").value=JSON.stringify(current,null,2);get("records").replaceChildren();get("save").disabled=false;
    for(const row of current.records){
      const card=document.createElement("article"),title=document.createElement("h2");title.textContent=row.title+" — "+row.state;card.append(title);
      const refs=document.createElement("ul");
      for(const e of row.editions){const li=document.createElement("li");li.textContent=e.sourceId+" · "+e.url;refs.append(li);}
      card.append(refs);
      for(const url of row.evidence){const a=document.createElement("a");a.href=url;a.target="_blank";a.rel="noopener noreferrer";a.textContent=url;card.append(a,document.createElement("br"));}
      const label=document.createElement("label"),confirmed=document.createElement("input");confirmed.type="checkbox";label.append(confirmed,document.createTextNode("راجعت الطبعات وأؤكد أنها العمل الأصلي نفسه، وليست تكملة أو اقتباسًا مختلفًا"));card.append(label);
      const approve=document.createElement("button");approve.textContent="اعتماد الربط";approve.disabled=true;confirmed.onchange=()=>{approve.disabled=!confirmed.checked;};
      approve.onclick=()=>guarded(()=>render(decision(current,row.key,true,get("reviewer").value,new Date().toISOString().slice(0,10))));
      const revoke=document.createElement("button");revoke.textContent="إلغاء الربط مع حفظ السجل";
      revoke.onclick=()=>guarded(()=>render(decision(current,row.key,false,get("reviewer").value,new Date().toISOString().slice(0,10))));
      card.append(approve,revoke);get("records").append(card);
    }
  }
  get("file").onchange=async event=>{try{const file=event.target.files[0];check(file&&file.size<=1048576,"ملف مفقود أو كبير");render(JSON.parse(await file.text()));get("problem").textContent="";}catch(e){get("problem").textContent=e.message;}};
  get("load").onclick=()=>guarded(()=>render(JSON.parse(get("editor").value)));
  get("add").onclick=()=>guarded(()=>{
    const doc=JSON.parse(get("editor").value||'{"schema":1,"records":[]}');validate(doc);
    const bytes=crypto.getRandomValues(new Uint8Array(32));const key=Array.from(bytes,x=>x.toString(16).padStart(2,"0")).join("");
    // Deliberately incomplete. The maintainer must fill exact source references and evidence first.
    doc.records.push({key,title:"عمل للمراجعة",state:"PENDING",editions:[{sourceId:"",url:""},{sourceId:"",url:""}],evidence:[]});
    get("editor").value=JSON.stringify(doc,null,2);get("save").disabled=true;get("problem").textContent="أكمل المراجع والأدلة في JSON ثم اضغط تحقق.";
  });
  get("save").onclick=()=>guarded(()=>{
    const doc=validate(JSON.parse(get("editor").value));
    const url=URL.createObjectURL(new Blob([JSON.stringify(doc,null,2)+"\n"],{type:"application/json"}));
    const a=document.createElement("a");a.href=url;a.download="catalog_reviewed_links.json";a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
  });
}
