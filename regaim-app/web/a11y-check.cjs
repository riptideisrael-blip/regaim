// Accessibility and security-header check of the site's pages (a developer tool; not part of the site).
// Run: node web/a11y-check.cjs web      (needs Playwright with Chromium)
// Each page is opened at desktop and phone width with the site's own Content-Security-Policy, and checked for:
// text contrast, names of fields / buttons / links, headings order, skip link, landmarks, touch targets (44px),
// sideways scrolling at 320px, keyboard focus ring on every stop, and console errors (a blocked resource shows here).
// The family page is also checked with content and with each dialog open, against a pretend server.
const { chromium } = require('playwright');
const http = require('http'), fs = require('fs'), path = require('path');
const root = process.argv[2];
const toml = fs.readFileSync(path.join(root,'netlify.toml'),'utf8');
const csp = (toml.match(/Content-Security-Policy = "([^"]+)"/)||[])[1];
const srv = http.createServer((q,r)=>{ let f=decodeURIComponent(q.url.split('?')[0]); if(f==='/')f='/index.html';
  const p=path.join(root,f); if(!fs.existsSync(p)){r.writeHead(404);return r.end();}
  r.writeHead(200,{'Content-Type':p.endsWith('.html')?'text/html; charset=utf-8':'application/octet-stream','Content-Security-Policy':csp}); r.end(fs.readFileSync(p)); }).listen(0);
const audit = () => {
  const out=[]; const vis=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};
  const lum=c=>{const a=c.map(v=>{v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4)});return 0.2126*a[0]+0.7152*a[1]+0.0722*a[2];};
  const parse=c=>{const m=c.match(/rgba?\(([^)]+)\)/);if(!m)return null;const p=m[1].split(',').map(x=>parseFloat(x));return {rgb:p.slice(0,3),a:p.length>3?p[3]:1};};
  const bgOf=e=>{let rgb=[255,255,255],stack=[];for(let n=e;n&&n.nodeType===1;n=n.parentElement){const s=getComputedStyle(n);if(s.backgroundImage&&s.backgroundImage!=='none')return null;const c=parse(s.backgroundColor);if(c&&c.a>0){stack.push(c);if(c.a>=1)break;}}
    for(const c of stack.reverse())rgb=rgb.map((v,i)=>v*(1-c.a)+c.rgb[i]*c.a);return rgb;};
  // contrast
  const seen=new Set();
  const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
  for(let n=w.nextNode();n;n=w.nextNode()){const t=n.textContent.trim();if(t.length<2)continue;const e=n.parentElement;if(!e||!vis(e)||e.closest('script,style,[aria-hidden=true]'))continue;
    const s=getComputedStyle(e);if(e.disabled||e.closest('[disabled]'))continue;const fg=parse(s.color);const bg=bgOf(e);if(!fg||!bg)continue;const f=fg.rgb.map((v,i)=>v*fg.a+bg[i]*(1-fg.a));
    const l1=lum(f),l2=lum(bg);const cr=(Math.max(l1,l2)+0.05)/(Math.min(l1,l2)+0.05);const size=parseFloat(s.fontSize),bold=parseInt(s.fontWeight)>=700;const need=(size>=24||(size>=18.66&&bold))?3:4.5;
    if(cr<need){const k=s.color+'|'+bg.map(Math.round).join(',');if(!seen.has(k)){seen.add(k);out.push(`contrast ${cr.toFixed(2)}<${need} "${t.slice(0,28)}" ${s.color} on rgb(${bg.map(Math.round)}) ${size}px`);}}}
  // names
  for(const e of document.querySelectorAll('img'))if(!e.hasAttribute('alt')&&vis(e))out.push('img without alt '+e.src.slice(-30));
  for(const e of document.querySelectorAll('input,select,textarea')){if(e.type==='hidden'||!vis(e))continue;const lab=e.labels&&e.labels.length||e.getAttribute('aria-label')||e.getAttribute('aria-labelledby')||e.title;if(!lab)out.push('field without label #'+e.id+' '+e.type);
    if(['email','password','text','tel'].includes(e.type)&&!e.getAttribute('autocomplete')&&/mail|pass|name|phone/i.test(e.id+e.name))out.push('field without autocomplete #'+e.id);}
  for(const e of document.querySelectorAll('a,button,[role=button]')){if(!vis(e))continue;const name=(e.getAttribute('aria-label')||e.textContent||e.title||'').trim();if(!name)out.push('control without name '+e.outerHTML.slice(0,80));
    if(e.tagName==='A'&&!e.getAttribute('href'))out.push('link without href '+name.slice(0,30));}
  // ids, aria refs
  const ids={};for(const e of document.querySelectorAll('[id]'))ids[e.id]=(ids[e.id]||0)+1;for(const k in ids)if(ids[k]>1)out.push('duplicate id '+k);
  for(const e of document.querySelectorAll('[aria-labelledby],[aria-describedby],[aria-controls],label[for]'))for(const a of ['aria-labelledby','aria-describedby','aria-controls','for']){const v=e.getAttribute(a);if(v)for(const id of v.split(/\s+/))if(id&&!document.getElementById(id))out.push(`${a} points to missing #${id}`);}
  // headings
  const hs=[...document.querySelectorAll('h1,h2,h3,h4,h5,h6')].filter(vis).map(h=>+h.tagName[1]);if(hs.filter(x=>x===1).length!==1)out.push('h1 count '+hs.filter(x=>x===1).length);
  for(let i=1;i<hs.length;i++)if(hs[i]-hs[i-1]>1)out.push(`heading jump h${hs[i-1]}->h${hs[i]} (#${i})`);
  // structure
  if(!document.documentElement.lang)out.push('no lang');if(!document.documentElement.dir)out.push('no dir');if(!document.title.trim())out.push('no title');
  if(!document.querySelector('main,[role=main]'))out.push('no main landmark');
  const first=document.body.querySelector('a,button,input');if(!first||!/דילוג|skip/i.test(first.textContent))out.push('first focusable is not a skip link: '+(first?first.textContent.trim().slice(0,30):''));
  for(const e of document.querySelectorAll('[tabindex]'))if(+e.getAttribute('tabindex')>0)out.push('positive tabindex');
  for(const e of document.querySelectorAll('[role=dialog]'))if(!e.getAttribute('aria-labelledby')&&!e.getAttribute('aria-label'))out.push('dialog without name #'+e.id);
  for(const t of document.querySelectorAll('table')){if(!t.querySelector('th'))out.push('table without headers');if(!t.querySelector('caption')&&!t.getAttribute('aria-label')&&!t.getAttribute('aria-labelledby'))out.push('table without caption');for(const th of t.querySelectorAll('th'))if(!th.getAttribute('scope'))out.push('th without scope');}
  for(const e of document.querySelectorAll('[lang]'))if(e!==document.documentElement&&e.lang==='en'&&getComputedStyle(e).direction!=='ltr')out.push('english block not ltr');
  for(const v of document.querySelectorAll('video,audio'))if(!v.controls)out.push('media without controls');
  const vp=document.querySelector('meta[name=viewport]');if(vp&&/user-scalable\s*=\s*no|maximum-scale\s*=\s*1(\D|$)/.test(vp.content))out.push('zoom disabled in viewport');
  // targets
  for(const e of document.querySelectorAll('a,button,input:not([type=hidden]),select,textarea,[role=button],summary')){if(!vis(e))continue;const r=e.getBoundingClientRect();const inline=getComputedStyle(e).display==='inline'&&e.closest('p,li,td,dd,span');if(!inline&&(r.height<44||r.width<44)&&e.type!=='checkbox'&&e.type!=='radio')out.push(`small target ${Math.round(r.width)}x${Math.round(r.height)} "${(e.textContent||e.id||e.type||'').trim().slice(0,24)}"`);}
  const over=document.documentElement.scrollWidth-document.documentElement.clientWidth;if(over>1)out.push('horizontal scroll '+over+'px');
  return out;
};
(async()=>{
  const port=srv.address().port; const b=await chromium.launch();
  const pages=['index.html','privacy.html','terms.html','accessibility.html','delete-account.html','reset-password.html'];
  for (const vp of [{width:1280,height:800},{width:320,height:640}]) for (const pg of pages) {
    const ctx=await b.newContext({viewport:vp, reducedMotion:'no-preference'}); const p=await ctx.newPage(); const errs=[];
    p.on('console',m=>{ if(m.type()==='error') errs.push(m.text().slice(0,200)); }); p.on('pageerror',e=>errs.push('PAGEERR '+String(e).slice(0,160)));
    await p.goto(`http://127.0.0.1:${port}/${pg}`,{waitUntil:'load'}); await p.waitForTimeout(300);
    const res=await p.evaluate(audit);
    // keyboard: tab through, every stop must show a visible focus ring or change
    let noRing=0, stops=0; for(let i=0;i<40;i++){ await p.keyboard.press('Tab'); const r=await p.evaluate(()=>{const e=document.activeElement; if(!e||e===document.body) return null; const s=getComputedStyle(e); return {t:(e.textContent||e.id||e.tagName).trim().slice(0,20), ring: s.outlineStyle!=='none'&&parseFloat(s.outlineWidth)>=2 || s.boxShadow!=='none'}; }); if(!r) break; stops++; if(!r.ring) noRing++; }
    console.log(`== ${pg} @${vp.width}: issues=${res.length} tabStops=${stops} withoutFocusRing=${noRing} consoleErrors=${errs.length}`);
    for(const v of res.slice(0,14)) console.log('   -',v);
    for(const e of errs.slice(0,3)) console.log('   ! ',e);
    await ctx.close();
  }

  for (const vp of [{width:1280,height:800},{width:320,height:640}]) {
    const ctx=await b.newContext({viewport:vp}); const p=await ctx.newPage();
    await p.goto(`http://127.0.0.1:${port}/reset-password.html#access_token=x&type=recovery`,{waitUntil:'load'}); await p.waitForTimeout(300);
    let res=await p.evaluate(audit); console.log(`== reset-password.html FORM @${vp.width}: issues=${res.length}`); for(const v of res) console.log('   -',v);
    await p.fill('#pw1','short'); await p.click('#save'); await p.waitForTimeout(300);
    console.log('   after a short password:', JSON.stringify(await p.evaluate(()=>({invalid:document.getElementById('pw1').getAttribute('aria-invalid'),role:document.getElementById('msg').getAttribute('role'),msg:document.getElementById('msg').textContent,focus:document.activeElement.id}))));
    res=await p.evaluate(audit); for(const v of res) console.log('   -',v);
    await ctx.close();
  }
  // ---- the family page with content (mocked server) ----
  for (const vp of [{width:1280,height:800},{width:320,height:640}]) {
    const ctx=await b.newContext({viewport:vp}); const p=await ctx.newPage(); const errs=[];
    p.on('console',m=>{ if(m.type()==='error') errs.push(m.text().slice(0,200)); }); p.on('pageerror',e=>errs.push('PAGEERR '+String(e).slice(0,160)));
    const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==','base64');
    await p.route('https://acqgmcmvxijyijhgivnt.supabase.co/**', async (route)=>{
      const u=route.request().url(); const j=(v)=>route.fulfill({status:200,contentType:'application/json',headers:{'access-control-allow-origin':'*','access-control-allow-headers':'*'},body:JSON.stringify(v)});
      if(route.request().method()==='OPTIONS') return route.fulfill({status:204,headers:{'access-control-allow-origin':'*','access-control-allow-headers':'*','access-control-allow-methods':'*'}});
      if(u.includes('/auth/v1/')) return j({access_token:'A',refresh_token:'R',expires_in:3600,user:{id:'user-a'}});
      if(u.includes('/rpc/family_circle_version')) return j({version:3});
      if(u.includes('/rpc/redeem_family_invite')) return j([{id:'circle-a',name:'משפחת בדיקה',code:'1'.repeat(32)}]);
      if(u.includes('/posts?')) return j([
        {id:'post-a',circle_id:'circle-a',kind:'photo',author:'סבתא רות',author_id:'user-b',title:'יום בפארק',video:'circle-a/user-b/photo.jpg',cover:null,created_at:'2026-10-06T08:00:00Z'},
        {id:'post-b',circle_id:'circle-a',kind:'video',author:'אבא',author_id:'user-a',title:'',video:'circle-a/user-a/clip.mp4',cover:'circle-a/user-a/cover.jpg',created_at:'2026-10-07T08:00:00Z'}]);
      if(u.includes('/reactions?')) return j([{post_id:'post-a',author_id:'user-a',author:'אבא',emoji:'❤️'},{post_id:'post-a',author_id:'user-b',author:'סבתא רות',emoji:'😂'}]);
      if(u.includes('/comments?')) return j([{id:'c1',post_id:'post-a',author_id:'user-b',author:'סבתא רות',body:'איזה חמודים',created_at:'2026-10-06T09:00:00Z'}]);
      if(u.includes('/family_messages')) return j([{id:'m1',circle_id:'circle-a',author_id:'user-b',author:'סבתא רות',body:'שבת שלום',created_at:'2026-10-06T10:00:00Z'}]);
      if(u.includes('/storage/v1/object/sign/')) return j({signedURL:'/object/sign/moments/x.png?token=t'});
      if(u.includes('/object/sign/moments/')) return route.fulfill({status:200,contentType:'image/png',body:png,headers:{'access-control-allow-origin':'*'}});
      return j([]);
    });
    const server=Buffer.from('https://acqgmcmvxijyijhgivnt.supabase.co|sb_publishable_x').toString('base64url');
    await p.addInitScript(()=>{ try{ localStorage.setItem('m_name','בודק'); }catch(e){} });
    await p.goto(`http://127.0.0.1:${port}/index.html?c=${'1'.repeat(32)}&s=${server}`,{waitUntil:'load'}); await p.waitForTimeout(1500);
    const posts=await p.evaluate(()=>document.querySelectorAll('#feed article, #feed .post, #feed > *').length);
    let res=await p.evaluate(audit);
    console.log(`== index.html WITH FEED @${vp.width}: feedItems=${posts} issues=${res.length} consoleErrors=${errs.length}`);
    for(const v of res.slice(0,16)) console.log('   -',v);
    // open each sheet that has an opener button and audit again
    const openers=await p.evaluate(()=>[...document.querySelectorAll('button')].filter(b=>b.offsetParent).map(b=>b.textContent.trim().slice(0,30)));
    console.log('   buttons:',JSON.stringify(await p.evaluate(()=>[...document.querySelectorAll('#feed button')].slice(0,6).map(b=>[b.textContent.trim(),b.getAttribute('aria-label'),b.getAttribute('aria-pressed'),b.getAttribute('aria-expanded')]))).slice(0,500));
    for (const label of ['שיחה במשפחה','שתפו רגע','שינוי השם','תגובות','להגיב']) {
      const btn=p.locator('button',{hasText:label}).first(); if(!(await btn.count())) continue;
      try { await btn.click({timeout:1500}); await p.waitForTimeout(400); } catch(e){ continue; }
      res=await p.evaluate(audit);
      const focusIn=await p.evaluate(()=>{const s=document.querySelector('.sheet.open');return s? s.contains(document.activeElement):null;});
      console.log(`   after "${label}": issues=${res.length} focusInsideOpenSheet=${focusIn}`); for(const v of res.slice(0,8)) console.log('      -',v);
      await p.keyboard.press('Escape'); await p.waitForTimeout(200);
    }
    for(const e of errs.slice(0,4)) console.log('   ! ',e);
    await ctx.close();
  }
  await b.close(); srv.close();
})();
