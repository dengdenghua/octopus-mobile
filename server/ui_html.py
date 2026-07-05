"""
内嵌的前端 HTML 页面(管理后台 + 远程控制台)。
从 app.py 拆出以保持主后端文件清爽;这些是零依赖的纯静态单页。
"""

REMOTE_CONSOLE_HTML = r"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Octopus 远程控制台</title>
<style>
*{box-sizing:border-box}body{margin:0;min-height:100vh;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#172033;background:linear-gradient(145deg,#eef4ff,#f7fbff 46%,#eef8f1)}
.wrap{max-width:1100px;margin:0 auto;padding:28px 16px 40px}.top{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;margin-bottom:18px}
h1{margin:0;font-size:30px;letter-spacing:0}.sub{margin:6px 0 0;color:#63708a;font-size:14px}.grid{display:grid;grid-template-columns:360px 1fr;gap:16px}
.card{background:rgba(255,255,255,.72);border:1px solid rgba(255,255,255,.8);border-radius:22px;padding:18px;box-shadow:0 18px 48px rgba(61,83,123,.13);backdrop-filter:blur(18px)}
.title{font-weight:800;margin-bottom:12px}.field{display:flex;flex-direction:column;gap:6px;margin-bottom:12px}label{font-size:12px;color:#63708a;font-weight:700}
input,textarea{width:100%;border:1px solid rgba(90,107,134,.18);background:rgba(255,255,255,.78);border-radius:14px;padding:11px 12px;font-size:14px;outline:none;color:#172033}
textarea{min-height:110px;resize:vertical;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.row{display:flex;gap:8px;flex-wrap:wrap}.btn{border:0;border-radius:14px;padding:10px 14px;font-weight:800;cursor:pointer;color:white;background:linear-gradient(135deg,#4f6df5,#35b87a);box-shadow:0 12px 26px rgba(79,109,245,.2)}
.btn.secondary{color:#22345a;background:rgba(255,255,255,.82);border:1px solid rgba(90,107,134,.14);box-shadow:none}.btn.danger{background:linear-gradient(135deg,#e34f61,#ff826a)}
.device{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px;border-radius:16px;background:rgba(255,255,255,.54);border:1px solid rgba(90,107,134,.1);margin-bottom:8px}
.name{font-weight:800}.meta{font-size:12px;color:#63708a;margin-top:3px}.pill{font-size:12px;font-weight:800;border-radius:999px;padding:5px 8px;background:#edf2ff;color:#4f6df5}.pill.on{background:#e8f8ee;color:#159354}.pill.off{background:#f4f0ef;color:#8a6a60}
.log{height:360px;overflow:auto;background:#10131c;color:#dce6f7;border-radius:18px;padding:12px;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:pre-wrap}
.code{font-size:34px;font-weight:900;letter-spacing:8px;color:#22345a}.hint{font-size:12px;color:#63708a;line-height:1.6}.hidden{display:none}
@media(max-width:820px){.top{display:block}.grid{grid-template-columns:1fr}.wrap{padding:20px 12px}.log{height:260px}}
</style>
</head>
<body><div class="wrap">
  <div class="top"><div><h1>Octopus 远程控制台</h1><p class="sub">通过官网域名配对手机,授权后控制在线设备。</p></div><button class="btn secondary" onclick="refreshDevices()">刷新设备</button></div>
  <div class="grid">
    <section class="card">
      <div class="title">账号与配对</div>
      <div class="field"><label>登录 token</label><input id="token" placeholder="粘贴网页登录返回的 JWT"></div>
      <div class="row"><button class="btn" onclick="saveToken()">保存 token</button><button class="btn secondary" onclick="clearToken()">清除</button></div>
      <hr style="border:0;border-top:1px solid rgba(90,107,134,.12);margin:16px 0">
      <div class="field"><label>设备备注</label><input id="pairName" placeholder="例如: 我的安卓手机"></div>
      <div class="row"><button class="btn" onclick="startPair()">生成配对码</button></div>
      <div id="pairBox" class="hidden" style="margin-top:14px"><div class="hint">在手机 App 中输入该配对码,5 分钟内有效。</div><div class="code" id="pairCode"></div></div>
      <hr style="border:0;border-top:1px solid rgba(90,107,134,.12);margin:16px 0">
      <div class="title">设备</div>
      <div id="devices"></div>
    </section>
    <section class="card">
      <div class="title">控制会话</div>
      <div class="hint" id="sessionHint">选择一台在线设备后连接。</div>
      <div class="row" style="margin:12px 0"><button class="btn" onclick="connectSelected()">优先直连</button><button class="btn secondary" onclick="connectRelay()">服务器中转</button><button class="btn secondary" onclick="disconnect()">断开</button><button class="btn danger" onclick="revokeSelected()">撤销授权</button></div>
      <div class="field"><label>发送 JSON 指令</label><textarea id="payload">{"type":"control","action":"home"}</textarea></div>
      <div class="row"><button class="btn" onclick="sendPayload()">发送</button><button class="btn secondary" onclick="quick('back')">返回</button><button class="btn secondary" onclick="quick('home')">主屏</button><button class="btn secondary" onclick="quick('recent')">最近</button></div>
      <div style="height:12px"></div><div class="log" id="log"></div>
    </section>
  </div>
</div>
<script>
const $=s=>document.querySelector(s);let selected="",ws=null,devices={};
$("#token").value=localStorage.octoRemoteToken||"";
function tok(){return $("#token").value.trim()}function auth(){return {"Authorization":"Bearer "+tok(),"Content-Type":"application/json"}}
function log(x){const el=$("#log");el.textContent+=((typeof x==="string")?x:JSON.stringify(x,null,2))+"\n";el.scrollTop=el.scrollHeight}
function saveToken(){localStorage.octoRemoteToken=tok();refreshDevices()}function clearToken(){localStorage.removeItem("octoRemoteToken");$("#token").value="";$("#devices").innerHTML=""}
async function api(path,opt={}){const r=await fetch(path,{...opt,headers:{...auth(),...(opt.headers||{})}});if(!r.ok)throw new Error(await r.text());return r.json()}
async function startPair(){try{const d=await api("/remote/pair/start",{method:"POST",body:JSON.stringify({deviceName:$("#pairName").value})});$("#pairCode").textContent=d.code;$("#pairBox").classList.remove("hidden");log("配对码已生成: "+d.code)}catch(e){log("生成失败: "+e.message)}}
async function refreshDevices(){try{const d=await api("/remote/devices");devices={};(d.items||[]).forEach(x=>devices[x.deviceId]=x);$("#devices").innerHTML=(d.items||[]).map(x=>`<div class="device" onclick="selectDevice('${x.deviceId}')"><div><div class="name">${esc(x.deviceName)}</div><div class="meta">${esc(x.deviceId)} · ${x.revoked?"已撤销":"最后在线 "+new Date(x.lastSeen).toLocaleString()}${x.directControlAvailable?" · 局域网直连可用":""}</div></div><span class="pill ${x.online?"on":"off"}">${x.directControlAvailable?"直连":(x.online?"在线":"离线")}</span></div>`).join("")||"<div class='hint'>暂无设备,先生成配对码。</div>"}catch(e){log("设备加载失败: "+e.message)}}
function selectDevice(id){selected=id;const d=devices[id]||{};$("#sessionHint").textContent="已选择: "+(d.deviceName||id)+(d.directControlAvailable?" · 将优先打开局域网直连控制台":" · 可用服务器中转")}
function wsUrl(){const p=location.protocol==="https:"?"wss:":"ws:";return p+"//"+location.host+"/remote/console/ws?device_id="+encodeURIComponent(selected)+"&token="+encodeURIComponent(tok())}
function connectSelected(){if(!selected){log("请先选择设备");return}const d=devices[selected]||{};if(d.lanConsoleUrl){log("正在打开局域网直连控制台: "+d.lanConsoleUrl);window.open(d.lanConsoleUrl,"_blank","noopener");return}connectRelay()}
function connectRelay(){if(!selected){log("请先选择设备");return}disconnect();ws=new WebSocket(wsUrl());ws.onopen=()=>log("服务器中转已连接");ws.onmessage=e=>{try{const msg=JSON.parse(e.data);log(msg);if(msg.type==="device_status"&&msg.deviceId){devices[msg.deviceId]={...(devices[msg.deviceId]||{}),...msg,directControlAvailable:!!msg.lanConsoleUrl}}}catch(_){log(e.data)}};ws.onclose=e=>log("连接关闭: "+e.code+" "+e.reason);ws.onerror=()=>log("连接异常")}
function disconnect(){if(ws){ws.close(1000,"console disconnect");ws=null}}
function sendPayload(){if(!ws||ws.readyState!==1){log("尚未连接设备");return}try{const msg=JSON.parse($("#payload").value);msg.id=msg.id||Date.now().toString(36);ws.send(JSON.stringify(msg));log({sent:msg})}catch(e){log("JSON 格式错误: "+e.message)}}
function quick(action){$("#payload").value=JSON.stringify({type:"control",action},null,2);sendPayload()}
async function revokeSelected(){if(!selected){log("请先选择设备");return}try{await api("/remote/devices/"+encodeURIComponent(selected)+"/revoke",{method:"POST",body:"{}"});log("已撤销授权");refreshDevices()}catch(e){log("撤销失败: "+e.message)}}
function esc(s){return String(s||"").replace(/[&<>"']/g,m=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[m]))}
refreshDevices();
</script></body></html>"""

ADMIN_HTML = r"""<!doctype html>
<html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Octopus 管理后台</title>
<style>
:root{--bg:#0f1115;--card:#171a21;--line:#262b36;--fg:#e6e9ef;--mut:#8b93a7;--brand:#6c5ce7;--ok:#21c08b;--warn:#e0a106;--bad:#e0533d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.5 -apple-system,Segoe UI,Roboto,"PingFang SC",sans-serif}
a{color:var(--brand)}.wrap{max-width:1180px;margin:0 auto;padding:20px}
.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
button{background:var(--brand);color:#fff;border:0;border-radius:8px;padding:7px 12px;cursor:pointer;font-size:13px}
button.ghost{background:transparent;border:1px solid var(--line);color:var(--fg)}
button.sm{padding:4px 9px;font-size:12px}
input{background:#0b0d11;border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:8px 10px;font-size:14px}
h1{font-size:18px;margin:0}.mut{color:var(--mut)}.mono{font-family:ui-monospace,Menlo,monospace}
.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;margin:16px 0}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:14px}
.card .k{color:var(--mut);font-size:12px}.card .v{font-size:22px;font-weight:700;margin-top:4px}
.tabs{display:flex;gap:6px;margin:18px 0 10px}.tabs button{background:transparent;border:1px solid var(--line);color:var(--mut)}
.tabs button.on{background:var(--brand);color:#fff;border-color:var(--brand)}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--mut);font-weight:600}tr:hover td{background:#12151c}
.pill{display:inline-block;padding:1px 8px;border-radius:99px;font-size:11px}
.pill.ok{background:rgba(33,192,139,.15);color:var(--ok)}.pill.no{background:#20242e;color:var(--mut)}
.pill.bad{background:rgba(224,83,61,.15);color:var(--bad)}
#login{max-width:360px;margin:12vh auto;text-align:center}
#login .card{padding:24px}#login input{width:100%;margin:12px 0}#login button{width:100%}
.err{color:var(--bad);margin-top:8px;min-height:18px}.hide{display:none}
.actbar button{margin-right:6px;margin-bottom:4px}
</style></head><body>

<div id="login">
  <div class="card">
    <h1>Octopus 管理后台</h1>
    <p class="mut">输入管理口令(服务器 .env 的 ADMIN_TOKEN)</p>
    <input id="tokIn" type="password" placeholder="管理口令" autocomplete="off">
    <button onclick="doLogin()">进入</button>
    <div class="err" id="loginErr"></div>
  </div>
</div>

<div id="app" class="wrap hide">
  <div class="row" style="justify-content:space-between">
    <h1>Octopus 管理后台</h1>
    <div class="row"><button class="ghost sm" onclick="refresh()">刷新</button>
      <button class="ghost sm" onclick="logout()">退出</button></div>
  </div>
  <div class="cards" id="stats"></div>
  <div class="tabs">
    <button class="on" data-tab="users" onclick="tab('users')">用户</button>
    <button data-tab="usage" onclick="tab('usage')">用量</button>
    <button data-tab="orders" onclick="tab('orders')">订单</button>
    <button data-tab="ai" onclick="tab('ai')">AI 分析</button>
    <button data-tab="profit" onclick="tab('profit')">盈利/经营</button>
    <button data-tab="logs" onclick="tab('logs')">操作审计</button>
  </div>
  <div id="bar" class="row" style="margin-bottom:10px"></div>
  <div id="view"></div>
</div>

<script>
const SS="octo_admin"; let curTab="users";
const $=s=>document.querySelector(s);
const tok=()=>sessionStorage.getItem(SS)||"";
const esc=s=>String(s==null?"":s).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const dt=ms=>ms?new Date(Number(ms)).toLocaleString():"";
const money=fen=>"¥"+(Number(fen||0)/100).toFixed(2);
async function api(path,opts){
  opts=opts||{}; opts.headers=Object.assign({"X-Admin-Token":tok()},opts.headers||{});
  const r=await fetch(path,opts);
  if(r.status===401||r.status===503){logout(await detail(r));throw new Error("auth");}
  if(!r.ok) throw new Error(await detail(r));
  const ct=r.headers.get("content-type")||""; return ct.includes("json")?r.json():r.text();
}
async function detail(r){try{return (await r.json()).detail||("HTTP "+r.status)}catch(e){return "HTTP "+r.status}}
function doLogin(){const v=$("#tokIn").value.trim();if(!v)return;sessionStorage.setItem(SS,v);
  $("#loginErr").textContent="";api("/admin/api/stats").then(()=>{enter()}).catch(e=>{$("#loginErr").textContent=e.message;});}
function enter(){$("#login").classList.add("hide");$("#app").classList.remove("hide");refresh();}
function logout(msg){sessionStorage.removeItem(SS);$("#app").classList.add("hide");$("#login").classList.remove("hide");
  if(msg)$("#loginErr").textContent=msg;}
function refresh(){loadStats();tab(curTab);}
function tab(t){curTab=t;document.querySelectorAll(".tabs button").forEach(b=>b.classList.toggle("on",b.dataset.tab===t));
  $("#bar").innerHTML=""; ({users:loadUsers,usage:loadUsage,orders:loadOrders,ai:loadAI,profit:loadProfit,logs:loadLogs}[t])();}

async function loadStats(){
  try{const s=await api("/admin/api/stats");
  const cards=[["用户",s.users],["总积分余额",s.totalCredits],["免费已发",s.freeGranted],
    ["有效会员",s.members],["封禁",s.banned],["邀请兑换",s.invited],
    ["订单(已付)",s.orders+" / "+s.paidOrders],["收入",money(s.revenueFen)],
    ["调用次数",s.calls],["消耗积分",s.creditsSpent],["输入tok",s.tokensIn],["输出tok",s.tokensOut],
    ["生图次数",s.imageCalls],["生视频次数",s.videoCalls],["媒体消耗积分",s.mediaSpent]];
  $("#stats").innerHTML=cards.map(([k,v])=>`<div class="card"><div class="k">${k}</div><div class="v">${esc(v)}</div></div>`).join("");
  }catch(e){if(e.message!=="auth")$("#stats").innerHTML=`<div class="card">加载失败:${esc(e.message)}</div>`;}
}

let usersListenerBound=false;
async function loadUsers(q){
  $("#bar").innerHTML=`<input id="uq" placeholder="搜索 邮箱/手机/uid/邀请码" value="${esc(q||"")}" style="width:280px">
    <button class="sm" onclick="loadUsers($('#uq').value)">搜索</button>`;
  $("#uq").addEventListener("keydown",e=>{if(e.key==="Enter")loadUsers(e.target.value);});
  try{const d=await api("/admin/api/users?limit=200&q="+encodeURIComponent(q||""));
  const rows=d.items.map(u=>`<tr data-uid="${esc(u.userId)}">
    <td><div>${esc(u.email||u.mobile||"-")}</div><div class="mut mono" style="font-size:11px">${esc(u.userId)}</div></td>
    <td><b>${u.credits}</b><div class="mut" style="font-size:11px">免:${u.freeGranted}</div></td>
    <td><b>${(u.spent||0)+(u.mediaSpent||0)}</b><div class="mut" style="font-size:11px">聊天 ${u.spent||0} · 媒体 ${u.mediaSpent||0}</div></td>
    <td>图 <b>${u.imageCalls||0}</b><div class="mut" style="font-size:11px">视 ${u.videoCalls||0}</div></td>
    <td>${u.memberActive?`<span class="pill ok">会员</span><div class="mut" style="font-size:11px">${dt(u.memberExpireAt)}</div>`:'<span class="pill no">非会员</span>'}</td>
    <td>${u.banned?'<span class="pill bad">封禁</span>':'<span class="pill ok">正常</span>'}</td>
    <td class="mono">${esc(u.inviteCode||"-")}${u.invitedBy?`<div class="mut" style="font-size:11px">←${esc(u.invitedBy)}</div>`:""}</td>
    <td class="mut" style="font-size:12px">${dt(u.createdAt)}</td>
    <td class="actbar">
      <button class="sm" data-act="cr">积分±</button>
      <button class="sm ghost" data-act="mem">会员</button>
      <button class="sm ghost" data-act="ban">${u.banned?"解封":"封禁"}</button>
    </td></tr>`).join("");
  $("#view").innerHTML=`<div class="mut" style="margin-bottom:6px">共 ${d.total} 人</div>
    <table><thead><tr><th>账号</th><th>积分</th><th>累计消耗</th><th>生图/视频</th><th>会员</th><th>状态</th><th>邀请</th><th>注册</th><th>操作</th></tr></thead><tbody>${rows}</tbody></table>`;
  if(!usersListenerBound){usersListenerBound=true;
    $("#view").addEventListener("click",onUserAction);}
  $("#view").onclick=onUserAction;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}
async function onUserAction(ev){
  const btn=ev.target.closest("button[data-act]"); if(!btn)return;
  const uid=btn.closest("tr").dataset.uid; const act=btn.dataset.act;
  try{
    if(act==="cr"){const d=prompt("加/减积分(正数=加,负数=减),例如 +500 或 -100");if(d==null)return;
      const delta=parseInt(d,10);if(!delta){alert("请输入非零整数");return;}
      const reason=prompt("备注(可空)")||"";
      const r=await api(`/admin/api/users/${encodeURIComponent(uid)}/credits`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({delta,reason})});
      alert("新余额:"+r.balance);}
    else if(act==="mem"){const d=prompt("会员天数(正=延长,负=减少),例如 30 或 -30");if(d==null)return;
      const days=parseInt(d,10);if(isNaN(days)){alert("请输入整数");return;}
      const reason=prompt("备注(可空)")||"";
      const r=await api(`/admin/api/users/${encodeURIComponent(uid)}/membership`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({days,reason})});
      alert(r.memberActive?("会员至 "+dt(r.memberExpireAt)):"已设为非会员");}
    else if(act==="ban"){const isBan=btn.textContent==="封禁";
      if(!confirm(isBan?"确认封禁该账号?其令牌将立即失效":"确认解封?"))return;
      const reason=prompt("备注(可空)")||"";
      await api(`/admin/api/users/${encodeURIComponent(uid)}/ban`,{method:"POST",
        headers:{"Content-Type":"application/json"},body:JSON.stringify({banned:isBan,reason})});}
    loadStats();loadUsers($("#uq")?$("#uq").value:"");
  }catch(e){if(e.message!=="auth")alert("失败:"+e.message);}
}

async function loadUsage(){
  $("#bar").innerHTML=`<button class="sm" onclick="dlCsv()">导出 CSV</button>`;
  try{const d=await api("/admin/api/usage?limit=300");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>账号</th><th>模型</th><th>输入</th><th>输出</th><th>扣分</th></tr></thead><tbody>${
    d.items.map(r=>`<tr><td class="mut" style="font-size:12px">${dt(r.ts)}</td>
      <td>${esc(r.email||r.mobile||r.user_id)}</td><td class="mono">${esc(r.model)}</td>
      <td>${r.tokens_in}</td><td>${r.tokens_out}</td><td><b>${r.credits}</b></td></tr>`).join("")||
      '<tr><td colspan=6 class="mut">暂无用量</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}
async function dlCsv(){try{const txt=await api("/admin/api/usage.csv");
  const blob=new Blob([txt],{type:"text/csv"});const a=document.createElement("a");
  a.href=URL.createObjectURL(blob);a.download="usage.csv";a.click();URL.revokeObjectURL(a.href);
  }catch(e){if(e.message!=="auth")alert("导出失败:"+e.message);}}

async function loadOrders(){
  try{const d=await api("/admin/api/orders?limit=200");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>订单号</th><th>账号</th><th>商品</th><th>金额</th><th>状态</th></tr></thead><tbody>${
    d.items.map(o=>`<tr><td class="mut" style="font-size:12px">${dt(o.created_at)}</td>
      <td class="mono" style="font-size:12px">${esc(o.order_no)}</td><td>${esc(o.email||o.mobile||o.user_id)}</td>
      <td>${esc(o.goods_id)}</td><td>${money(o.amount_fen)}</td>
      <td>${o.status==="PAID"?'<span class="pill ok">已付</span>':'<span class="pill no">'+esc(o.status)+'</span>'}</td></tr>`).join("")||
      '<tr><td colspan=6 class="mut">暂无订单</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}

async function loadLogs(){
  try{const d=await api("/admin/api/logs?limit=300");
  $("#view").innerHTML=`<table><thead><tr><th>时间</th><th>操作</th><th>目标</th><th>详情</th></tr></thead><tbody>${
    d.items.map(l=>`<tr><td class="mut" style="font-size:12px">${dt(l.ts)}</td><td class="mono">${esc(l.action)}</td>
      <td class="mono" style="font-size:12px">${esc(l.target_user)}</td><td>${esc(l.detail)}</td></tr>`).join("")||
      '<tr><td colspan=4 class="mut">暂无操作记录</td></tr>'}</tbody></table>`;
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}

function mdLite(s){return esc(s)
  .replace(/^\s*###?#?\s*(.*)$/gm,'<h4 style="margin:12px 0 4px">$1</h4>')
  .replace(/^\s*##\s*(.*)$/gm,'<h3 style="margin:14px 0 6px">$1</h3>')
  .replace(/^\s*#\s*(.*)$/gm,'<h3 style="margin:14px 0 6px">$1</h3>')
  .replace(/\*\*(.+?)\*\*/g,'<b>$1</b>')
  .replace(/^\s*[-*]\s+(.*)$/gm,'<li>$1</li>')
  .replace(/(<li>[\s\S]*?<\/li>)/g,'<ul style="margin:4px 0 4px 4px">$1</ul>')
  .replace(/\n{2,}/g,'<br><br>').replace(/\n/g,'<br>');}
async function loadAI(){
  $("#bar").innerHTML=`<button class="sm" id="genAI">⚡ 生成分析</button>
    <span class="mut">基于行为元数据(平台不存聊天内容);AI 叙述由 qwen 生成</span>`;
  $("#view").innerHTML=`<div class="mut">点「生成分析」开始 —— 结构化分群即时可用;AI 叙述需服务端配置 qwen。</div>`;
  $("#genAI").onclick=async function(){
    this.disabled=true;$("#view").innerHTML=`<div class="mut">分析中…(LLM 生成约数秒)</div>`;
    try{const d=await api("/admin/api/ai-analysis",{method:"POST"});
      const seg=Object.entries(d.segments).map(([k,v])=>`<div class="card"><div class="k">${esc(k)}</div><div class="v">${v}</div></div>`).join("");
      const tbl=(title,arr,fmt)=>arr&&arr.length?`<h3 style="margin:16px 0 6px">${title}</h3><table><tbody>${arr.map(fmt).join("")}</tbody></table>`:"";
      const conv=tbl("🟢 转化重点(重度免费用户)",d.convert,u=>`<tr><td>${esc(u.email)}</td><td>图${u.img}/视${u.vid}</td><td>累计消耗 ${u.totalSpent}</td><td class="mut">非会员</td></tr>`);
      const vip=tbl("💎 高价值",d.vip,u=>`<tr><td>${esc(u.email)}</td><td>${u.member?'<span class="pill ok">会员</span>':'<span class="pill no">非会员</span>'}</td><td>累计消耗 ${u.totalSpent}</td><td>余额 ${u.balance}</td></tr>`);
      const churn=tbl("⚠️ 流失预警",d.churn,u=>`<tr><td>${esc(u.email)}</td><td>${u.daysIdle!=null?u.daysIdle+' 天未活跃':''}</td><td>余额 ${u.balance}</td></tr>`);
      const tot=(d.tagDist||[]).reduce((s,t)=>s+t.n,0)||1;
      const dist=(d.tagDist&&d.tagDist.length)
        ?`<h3 style="margin:16px 0 6px">📊 用户主要用来做啥(意图标签)</h3><table><tbody>${d.tagDist.map(t=>`<tr><td style="width:84px">${esc(t.tag)}</td><td style="width:260px"><div style="background:var(--brand);height:11px;border-radius:6px;width:${Math.max(6,Math.round(t.n/tot*240))}px;display:inline-block"></div></td><td class="mut">${t.n} · ${Math.round(t.n/tot*100)}%</td></tr>`).join("")}</tbody></table>`
        :`<h3 style="margin:16px 0 6px">📊 用户主要用来做啥(意图标签)</h3><div class="card mut">暂无意图标签 —— 接入 qwen 的盒子上线后,用户聊天会自动打标签累积,这里就能看用途分布。</div>`;
      const ai=d.llm.available
        ?`<div class="card" style="margin-top:10px;line-height:1.7">${mdLite(d.llm.report)}</div>`
        :`<div class="card" style="margin-top:10px;border-color:var(--warn)"><b>AI 叙述未生成</b><div class="mut" style="margin-top:6px">${esc(d.llm.note||"")}</div></div>`;
      $("#view").innerHTML=`<div class="mut" style="margin-bottom:8px">分群概览(共 ${d.users} 用户)</div><div class="cards">${seg}</div>${dist}${conv}${vip}${churn}<h3 style="margin:18px 0 6px">🤖 AI 经营分析</h3>${ai}`;
    }catch(e){if(e.message!=="auth")$("#view").innerHTML=`<div class="card" style="border-color:var(--bad)">分析失败:${esc(e.message)}</div>`;}
  };
}

let _profitBase=null;
const fmtY=v=>"¥"+Number(v||0).toLocaleString(undefined,{maximumFractionDigits:2});
function profitCalc(){
  if(!_profitBase)return;
  const N=+$("#pN").value||0,c=+$("#pC").value||0,r=+$("#pR").value||0,p=+$("#pP").value||0;
  const k=$("#pBasis").value==='full'?_profitBase.costPerCreditFull:0;
  const rev=N*(r/100)*p, cost=N*c*k, profit=rev-cost;
  const beR=(N*p>0)?(cost/(N*p)*100):0;
  $("#pOut").innerHTML=`<div class="cards">
    <div class="card"><div class="k">月收入(估)</div><div class="v">${fmtY(rev)}</div></div>
    <div class="card"><div class="k">月上游成本</div><div class="v">${fmtY(cost)}</div></div>
    <div class="card"><div class="k">月利润</div><div class="v" style="color:${profit>=0?'var(--ok)':'var(--bad)'}">${fmtY(profit)}</div></div>
    <div class="card"><div class="k">盈亏平衡转化率</div><div class="v">${beR<0.1?beR.toFixed(2):beR.toFixed(1)}%</div></div>
  </div><div class="mut" style="margin-top:6px">口径:${$("#pBasis").value==='full'?'满负荷真实上游价':'免费额度(上游≈0)'} · 上游成本 ¥${k.toFixed(5)}/积分</div>`;
}
async function loadProfit(){
  $("#bar").innerHTML=`<button class="sm" id="genProfitAI">⚡ AI 经营诊断</button> <span class="mut">财务为确定性计算·即时;AI 诊断由 qwen 生成</span>`;
  $("#view").innerHTML=`<div class="mut">加载中…</div>`;
  try{
    const s=await api("/admin/api/profit"); _profitBase=s;
    const mar=s.grossMarginFull==null?'—(无收入)':s.grossMarginFull+'%';
    const l1=`<div class="mut" style="margin:4px 0 8px">L1 · 财务 / 单位经济快照</div><div class="cards">
      <div class="card"><div class="k">收入(实付)</div><div class="v">${fmtY(s.revenue)}</div><div class="mut" style="font-size:11px">${s.paidOrders} 单</div></div>
      <div class="card"><div class="k">上游成本·满负荷</div><div class="v">${fmtY(s.costFull)}</div><div class="mut" style="font-size:11px">聊${fmtY(s.chatCostFull)}·媒${fmtY(s.mediaCostFull)}</div></div>
      <div class="card"><div class="k">上游成本·当前</div><div class="v">${fmtY(s.costNow)}</div><div class="mut" style="font-size:11px">${s.freeQuota?'吃免费额度':'已计真实价'}</div></div>
      <div class="card"><div class="k">净盈亏(满负荷)</div><div class="v" style="color:${s.grossFull>=0?'var(--ok)':'var(--bad)'}">${fmtY(s.grossFull)}</div></div>
      <div class="card"><div class="k">毛利率(满负荷)</div><div class="v">${mar}</div></div>
      <div class="card"><div class="k">已消耗积分价值</div><div class="v">${fmtY(s.servedValue)}</div><div class="mut" style="font-size:11px">${s.creditsSpent} 积分</div></div>
      <div class="card"><div class="k">未消耗积分·潜在负债</div><div class="v">${fmtY(s.outstandingLiability)}</div><div class="mut" style="font-size:11px">${s.outstandingCredits} 积分</div></div>
      <div class="card"><div class="k">活跃/会员/总</div><div class="v">${s.activeUsers}/${s.members}/${s.users}</div></div>
      <div class="card"><div class="k">人均成本(满负荷)</div><div class="v">${fmtY(s.costPerActiveFull)}</div></div>
    </div>`;
    const defC=Math.max(1,Math.round((s.creditsSpent||0)/(s.activeUsers||1)));
    const l2=`<div class="mut" style="margin:18px 0 8px">L2 · 盈亏平衡 / What-if(改数字实时算)</div>
      <div class="row" style="gap:14px;flex-wrap:wrap;align-items:flex-end">
        <label class="mut" style="font-size:12px">活跃用户<br><input id="pN" type="number" value="${s.activeUsers||1000}" style="width:96px"></label>
        <label class="mut" style="font-size:12px">人均月消耗积分<br><input id="pC" type="number" value="${defC}" style="width:90px"></label>
        <label class="mut" style="font-size:12px">付费转化率%<br><input id="pR" type="number" value="5" style="width:70px"></label>
        <label class="mut" style="font-size:12px">会员单价¥<br><input id="pP" type="number" value="30" style="width:76px"></label>
        <label class="mut" style="font-size:12px">成本口径<br><select id="pBasis" style="background:#0b0d11;border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:8px"><option value="full">满负荷真实价</option><option value="free">免费额度(≈0)</option></select></label>
      </div><div id="pOut" style="margin-top:12px"></div>`;
    const l3=`<div class="mut" style="margin:18px 0 8px">L3 · AI 经营顾问</div><div id="profitAI" class="mut">点上方「⚡ AI 经营诊断」生成(需服务端配置 qwen)。</div>`;
    $("#view").innerHTML=l1+l2+l3;
    ["pN","pC","pR","pP","pBasis"].forEach(id=>$("#"+id).addEventListener("input",profitCalc));
    profitCalc();
    $("#genProfitAI").onclick=async function(){
      this.disabled=true;$("#profitAI").innerHTML=`<div class="mut">分析中…(LLM 生成约数秒)</div>`;
      try{const d=await api("/admin/api/profit-advisor",{method:"POST"});
        $("#profitAI").innerHTML=d.llm.available
          ?`<div class="card" style="line-height:1.7">${mdLite(d.llm.report)}</div>`
          :`<div class="card" style="border-color:var(--warn)"><b>AI 诊断未生成</b><div class="mut" style="margin-top:6px">${esc(d.llm.note||"")}</div></div>`;
      }catch(e){if(e.message!=="auth")$("#profitAI").innerHTML=`<div class="card" style="border-color:var(--bad)">失败:${esc(e.message)}</div>`;}
      this.disabled=false;
    };
  }catch(e){if(e.message!=="auth")$("#view").innerHTML=esc(e.message);}
}

if(tok())api("/admin/api/stats").then(enter).catch(()=>logout());
$("#tokIn").addEventListener("keydown",e=>{if(e.key==="Enter")doLogin();});
</script></body></html>"""
