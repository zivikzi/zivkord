const wv = document.getElementById('discord');
const conn = document.getElementById('conn');
const $ = (id) => document.getElementById(id);

let coreSource = null;
let injected = false;
let running = false;

// the four range positions -> [minDelay, maxDelay] in ms
const SPEEDS = [
  [400, 900],   // brisk (riskier)
  [900, 1800],  // normal
  [1800, 3200], // careful
  [3500, 6000], // paranoid
];
const SPEED_LABELS = ['0.4-0.9s', '0.9-1.8s', '1.8-3.2s', '3.5-6s'];

function setConn(text, cls) { conn.textContent = text; conn.className = 'pill ' + cls; }

function log(msg, cls) {
  const el = $('log');
  const line = document.createElement('div');
  if (cls) line.className = cls;
  line.textContent = msg;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

// --- talk to the engine living in the Discord tab ---------------------------
async function ensureCore() {
  if (!coreSource) coreSource = await window.zivkord.getCoreSource();
  if (!injected) {
    await wv.executeJavaScript(coreSource);
    injected = true;
  }
}

// Stop Discord from invoking WebAuthn, which is what makes Windows throw up the
// "insert your security key" passkey dialog. Runs in the page before its login
// component asks for a passkey.
const KILL_PASSKEY = `(function(){
  try { Object.defineProperty(window, 'PublicKeyCredential', { value: undefined, configurable: true }); } catch (e) {}
  try { if (navigator.credentials) navigator.credentials.get = function(){ return new Promise(function(){}); }; } catch (e) {}
})();`;
function killPasskey() { wv.executeJavaScript(KILL_PASSKEY).catch(() => {}); }

wv.addEventListener('dom-ready', () => { killPasskey(); refreshContext(); });
wv.addEventListener('did-navigate', () => { injected = false; killPasskey(); refreshContext(); });
wv.addEventListener('did-navigate-in-page', refreshContext);
wv.addEventListener('did-finish-load', () => { injected = false; killPasskey(); });

async function refreshContext() {
  try {
    const loggedIn = await wv.executeJavaScript(
      `!!document.querySelector('[class*="guilds"], [class*="privateChannels"]')`
    );
    if (!loggedIn) { setConn('log in to Discord →', 'warn'); $('ctxLine').textContent = '...'; return; }
    setConn('connected', 'ok');
    await ensureCore();
    const ctx = await wv.executeJavaScript(`window.__ZIVKORD__ && window.__ZIVKORD__.context()`);
    if (ctx && ctx.channelId) {
      $('ctxLine').textContent = ctx.isDM
        ? `current: a DM (channel ${ctx.channelId})`
        : `current: server ${ctx.guildId}, channel ${ctx.channelId}`;
    } else {
      $('ctxLine').textContent = 'open a channel or DM to target it';
    }
  } catch (_) {}
}

// --- engine -> UI events ----------------------------------------------------
wv.addEventListener('console-message', (e) => {
  if (!e.message.startsWith('ZIVKORD::')) return;
  let ev;
  try { ev = JSON.parse(e.message.slice(9)); } catch { return; }
  handleEvent(ev);
});

function handleEvent(ev) {
  const s = ev.stats;
  if (s) {
    $('cFound').textContent = ev.foundTotal ?? $('cFound').textContent;
    $('cDeleted').textContent = s.deleted;
    $('cSkipped').textContent = s.skipped;
    $('cFailed').textContent = s.failed;
    const found = parseInt($('cFound').textContent, 10) || 0;
    if (found) $('barFill').style.width = Math.min(100, (s.deleted + s.skipped + s.failed) / found * 100) + '%';
  }
  switch (ev.type) {
    case 'ready': log('engine ready (v' + ev.version + ')'); break;
    case 'started': log(ev.dryRun ? 'dry run, nothing will be deleted' : 'starting...'); break;
    case 'found': $('cFound').textContent = ev.total; log('found ' + ev.total + ' message(s)'); break;
    case 'deleted': log('deleted ' + ev.id, 'd'); break;
    case 'would-delete': log('would delete ' + ev.id, 'd'); break;
    case 'skip': log('skipped ' + ev.id); break;
    case 'fail': log('failed ' + ev.id + ' (' + ev.code + ')', 'f'); break;
    case 'ratelimited': log('rate limited, waiting ' + ev.seconds + 's', 'i'); break;
    case 'indexing': log('discord is indexing, hang on ' + ev.seconds + 's', 'i'); break;
    case 'breather': log('taking a breather (' + Math.round(ev.ms / 1000) + 's)', 'i'); break;
    case 'cooldown': log('batch done (' + ev.deleted + '), resting ' + Math.round(ev.ms / 60000) + ' min', 'i'); break;
    case 'done': finish('done, ' + s.deleted + ' gone'); break;
    case 'stopped': finish('stopped'); break;
    case 'error': log('error: ' + ev.message, 'f'); finish('error'); break;
  }
}

function finish(msg) {
  running = false;
  $('go').disabled = false;
  $('stop').disabled = true;
  log(msg);
}

// --- controls ---------------------------------------------------------------
document.querySelectorAll('input[name=target]').forEach((r) =>
  r.addEventListener('change', () => {
    $('manualIds').classList.toggle('hidden', document.querySelector('input[name=target]:checked').value !== 'manual');
  })
);

document.querySelectorAll('.collapser').forEach((h) =>
  h.addEventListener('click', () => {
    h.classList.toggle('closed');
    $(h.dataset.target).classList.toggle('open');
  })
);

$('speed').addEventListener('input', (e) => {
  $('delayLabel').textContent = SPEED_LABELS[e.target.value];
});

$('batchOn').addEventListener('change', (e) => {
  $('batchOpts').classList.toggle('hidden', !e.target.checked);
});

$('coffee').addEventListener('click', () => $('coffeeBox').classList.toggle('hidden'));
$('btcHide').addEventListener('click', () => $('coffeeBox').classList.add('hidden'));
$('btcCopy').addEventListener('click', async () => {
  const addr = $('btcAddr').textContent.trim();
  try {
    await navigator.clipboard.writeText(addr);
  } catch {
    const t = document.createElement('textarea');
    t.value = addr; document.body.appendChild(t); t.select();
    document.execCommand('copy'); t.remove();
  }
  const b = $('btcCopy');
  b.textContent = 'Copied!';
  setTimeout(() => (b.textContent = 'Copy'), 1200);
});

async function gatherOptions() {
  const manual = document.querySelector('input[name=target]:checked').value === 'manual';
  let guildId = null, channelId = null;
  if (manual) {
    guildId = $('guildId').value.trim() || null;
    channelId = $('channelId').value.trim() || null;
  } else {
    const ctx = await wv.executeJavaScript(`window.__ZIVKORD__.context()`);
    guildId = ctx.guildId; channelId = ctx.channelId;
  }
  const [minDelay, maxDelay] = SPEEDS[$('speed').value];
  return {
    guildId, channelId,
    authorId: null, // engine locks this to the logged-in user via /users/@me
    content: $('content').value.trim(),
    hasLink: $('hasLink').checked,
    hasFile: $('hasFile').checked,
    hasEmbed: $('hasEmbed').checked,
    afterDate: $('afterDate').value,
    beforeDate: $('beforeDate').value,
    includeNsfw: $('includeNsfw').checked,
    includePinned: $('includePinned').checked,
    dryRun: $('dryRun').checked,
    minDelay, maxDelay,
    batchSize: $('batchOn').checked ? Math.max(1, parseInt($('batchSize').value, 10) || 0) : 0,
    batchRestMin: $('batchOn').checked ? Math.max(1, parseFloat($('batchRest').value) || 0) : 0,
  };
}

$('go').addEventListener('click', async () => {
  if (running) return;
  try {
    await ensureCore();
    const opts = await gatherOptions();
    if (!opts.channelId && !opts.guildId) { log('open a channel or DM first', 'f'); return; }
    if (!$('dryRun').checked && !confirm('Delete your messages here? This cannot be undone.')) return;
    running = true;
    $('go').disabled = true;
    $('stop').disabled = false;
    $('barFill').style.width = '0';
    ['cFound','cDeleted','cSkipped','cFailed'].forEach((id) => $(id).textContent = '0');
    wv.executeJavaScript(`window.__ZIVKORD__.run(${JSON.stringify(opts)})`);
  } catch (e) {
    log('couldn\'t start: ' + (e && e.message || e), 'f');
    running = false;
    $('go').disabled = false;
    $('stop').disabled = true;
  }
});

$('stop').addEventListener('click', () => {
  wv.executeJavaScript(`window.__ZIVKORD__ && window.__ZIVKORD__.stop()`);
  $('stop').disabled = true;
});
