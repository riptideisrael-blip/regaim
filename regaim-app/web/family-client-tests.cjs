/* Offline client-contract tests: simulated HTTP, no live requests or messages. */
const fs = require('fs');
const vm = require('vm');
const assert = require('assert/strict');
const html = fs.readFileSync(__dirname + '/index.html','utf8');
let source = html.split('<script>')[1].split('</script>')[0];
source = source.slice(0, source.indexOf('  if (!me && code) askName();')) +
  'globalThis.familyTest = { load, react, comment, signedUrl, openConversation };\n})();';
class Element {
  setAttribute(){} removeAttribute(){} querySelector(){return null;} querySelectorAll(){return [];} contains(){return false;} focus(){}
  constructor() { this.children=[]; this.style={}; this.dataset={}; this.value=''; this.textContent=''; this.innerHTML=''; this.classList={add(){},remove(){},contains(){return false;}}; }
  appendChild(e) { this.children.push(e); return e; }
  append(...items) { this.children.push(...items); }
  replaceChildren(...items) { this.children=items; }
  focus() {}
}
function environment({ oldCode=false, oldServer=false, denyAuth=false }={}) {
  const elements = new Map();
  const requests = [];
  const store = new Map([['m_name','שם דינמי']]);
  const base='https://fixture.supabase.co';
  const server=Buffer.from(base+'|sb_publishable_fixture_only').toString('base64url');
  const get=(id)=>{if(!elements.has(id))elements.set(id,new Element());return elements.get(id);};
  const response=(value,ok=true,status=200)=>({ok,status,json:async()=>value,text:async()=>JSON.stringify(value)});
  const context={
    URLSearchParams, console, Date, Map, Set, Error, Promise,
    atob:(text)=>Buffer.from(text,'base64').toString('binary'),
    location:{search:'?c='+(oldCode?'ABC123':'1'.repeat(32))+'&s='+server},
    localStorage:{getItem:k=>store.get(k)||null,setItem:(k,v)=>store.set(k,v)},
    document:{getElementById:get,createElement:()=>new Element(),visibilityState:'visible',addEventListener(){},activeElement:null,querySelectorAll:()=>[]},navigator:{userAgent:'test'},
    setTimeout:()=>1,clearTimeout:()=>{},setInterval:()=>1,
    fetch:async(url,options={})=>{
      requests.push({url,...options});
      if(url.includes('/auth/v1/signup')) return denyAuth ? response({},false,403) : response({access_token:'ACCESS_FIXTURE',refresh_token:'REFRESH_FIXTURE',expires_in:3600,user:{id:'user-a'}});
      assert.equal(options.headers.Authorization,'Bearer ACCESS_FIXTURE','all data/media requests require Auth bearer');
      if(url.includes('/rpc/family_circle_version'))return response({version:oldServer?2:3});
      if(url.includes('/rpc/redeem_family_invite'))return response([{id:'circle-a',name:'משפחה דינמית',code:'1'.repeat(32)}]);
      if(url.includes('/posts?'))return response([{id:'post-a',circle_id:'circle-a',kind:'photo',author:'שם דינמי',author_id:'user-a',title:'תמונה משפחתית',video:'circle-a/user-a/photo.jpg',cover:null,created_at:'2026-10-06T08:00:00Z'}]);
      if(url.includes('/storage/v1/object/sign/'))return response({signedURL:'/object/sign/moments/circle-a/user-a/photo.jpg?token=fixture'});
      return response([]);
    }
  };
  vm.createContext(context);vm.runInContext(source,context);
  return {api:context.familyTest,requests,get,base};
}
(async()=>{
  let env=environment({oldCode:true});
  await assert.rejects(env.api.load(),/הקישור הישן/);
  assert.equal(env.requests.length,0,'old codes fail before Auth or data calls');
  env=environment({denyAuth:true});
  await assert.rejects(env.api.load(),/^(?!.*Anonymous)(?=.*ממי ששלח).*$/s);
  assert.equal(env.requests.length,1,'disabled Auth does not fall back to public REST');
  env=environment({oldServer:true});
  await assert.rejects(env.api.load(),/^(?!.*v3)(?=.*בעדכון).*$/s);
  assert(!env.requests.some(r=>r.url.includes('/posts?')),'old server does not load insecure posts');
  env=environment();
  await env.api.load();
  assert(env.requests.some(r=>r.url.includes('/rpc/redeem_family_invite')),'invite must become authenticated membership');
  assert(env.requests.some(r=>r.url.includes('/object/sign/moments/')),'private photos require signed URL');
  assert(!env.requests.some(r=>r.url.includes('/object/public/')),'no public-media fallback');
  await env.api.react('post-a','❤️',false);
  let request=env.requests.find(r=>r.url.endsWith('/reactions')&&r.method==='POST');
  assert.equal(JSON.parse(request.body).author_id,'user-a');
  await env.api.comment('post-a','תגובה אמיתית');
  request=env.requests.find(r=>r.url.endsWith('/comments')&&r.method==='POST');
  assert.equal(JSON.parse(request.body).author_id,'user-a');
  await env.api.openConversation('post-a','כותרת');
  assert(env.requests.some(r=>r.url.includes('/family_messages?')&&r.url.includes('post_id=eq.post-a')),'memory conversation is scoped to post');
  env.get('chatInput').value='מי מגיע בשבת?';
  await env.get('chatSend').onclick();
  request=env.requests.find(r=>r.url.endsWith('/family_messages')&&r.method==='POST');
  assert.equal(JSON.parse(request.body).circle_id,'circle-a');
  assert.equal(JSON.parse(request.body).post_id,'post-a');
  assert.equal(JSON.parse(request.body).author_id,'user-a');
  await assert.rejects(env.api.signedUrl('https://foreign.example/photo.jpg'),/אינו שייך/);
  // Accessibility regressions: the feed isn't a live region (not read again every refresh), the refresh waits while
  // someone types / watches / is in a window, typed comments survive it, and parents see no developer words.
  const page=require('fs').readFileSync(require('path').join(__dirname,'index.html'),'utf8');
  assert(!/<main id="feed"[^>]*aria-live/.test(page),'feed is not aria-live');
  assert(/function busy\(\)/.test(page)&&/busy\(\)\) return;/.test(page),'refresh skips while busy');
  assert(/drafts\[p\.id\]/.test(page),'comment drafts survive a refresh');
  assert(/dataset\.key/.test(page)&&/back\.focus\(\)/.test(page),'focus comes back after a reaction');
  assert(!/Anonymous Sign-ins|עדכון האבטחה v3|Supabase/.test(page.replace(/<script>[\s\S]*?const SUPABASE[^\n]*\n/,'')),'no developer words for families');
  console.log('PASS: 10 offline Auth/invite/media/reaction/comment/conversation contracts. No live service contacted.');
})().catch(error=>{console.error(error);process.exitCode=1;});
