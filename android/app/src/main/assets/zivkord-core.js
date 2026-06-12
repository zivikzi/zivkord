// Runs inside the logged-in discord.com tab. Searches your messages, deletes
// them, behaves itself around the rate limits.
//
// The token is grabbed off a request Discord already makes and kept in this
// closure only. It never gets logged, sent anywhere, or written to disk. Read
// the fetch hook below if you don't believe me. Everything talks to
// discord.com/api and nothing else (the window blocks the rest anyway).
//
// Talks back to the UI by console.log'ing lines that start with ZIVKORD::.
(function () {
  if (window.__ZIVKORD__) return; // idempotent

  const API = 'https://discord.com/api/v9';
  const DISCORD_EPOCH = 1420070400000n;

  // grab the token off a header Discord sends, keep it here, tell no one
  let authToken = null;
  function rememberToken(value) {
    if (value && !authToken) authToken = value;
  }
  const origFetch = window.fetch;
  window.fetch = function (input, init) {
    try {
      const h = (init && init.headers) || (input && input.headers);
      if (h) {
        const auth = h.get ? h.get('Authorization') : h['Authorization'] || h['authorization'];
        if (auth) rememberToken(auth);
      }
    } catch (_) {}
    return origFetch.apply(this, arguments);
  };
  const origSet = XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.setRequestHeader = function (k, v) {
    if (k && k.toLowerCase() === 'authorization') rememberToken(v);
    return origSet.apply(this, arguments);
  };

  function waitForToken(timeoutMs = 15000) {
    return new Promise((resolve, reject) => {
      if (authToken) return resolve(authToken);
      const started = Date.now();
      const t = setInterval(() => {
        if (authToken) { clearInterval(t); resolve(authToken); }
        else if (Date.now() - started > timeoutMs) {
          clearInterval(t);
          reject(new Error('Could not read your session token. Click around Discord once, then retry.'));
        }
      }, 250);
    });
  }

  // ---- small helpers ---------------------------------------------------------
  const emit = (type, data) => console.log('ZIVKORD::' + JSON.stringify({ type, ...data }));
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  const rand = (min, max) => Math.floor(min + Math.random() * (max - min));

  function dateToSnowflake(dateStr, ceil) {
    if (!dateStr) return null;
    const ms = new Date(dateStr).getTime();
    if (isNaN(ms)) return null;
    let snow = (BigInt(ms) - DISCORD_EPOCH) << 22n;
    if (ceil) snow += (1n << 22n) - 1n;
    return snow.toString();
  }

  // Deletable message types: 0 = default, 19 = reply. Everything else is a
  // system message we are not allowed to delete, so we skip it.
  const DELETABLE_TYPES = new Set([0, 19]);

  let stopFlag = false;

  // ---- authenticated request with full rate-limit etiquette ------------------
  async function api(method, path, body, pacing) {
    while (true) {
      if (stopFlag) throw new Error('stopped');
      const res = await origFetch(API + path, {
        method,
        headers: {
          Authorization: authToken,
          'Content-Type': 'application/json',
        },
        body: body ? JSON.stringify(body) : undefined,
      });

      // 429: back off exactly as long as Discord asks, plus a little jitter.
      if (res.status === 429) {
        let retryAfter = 1;
        try {
          const j = await res.clone().json();
          retryAfter = j.retry_after || 1;
          if (j.global) emit('ratelimited', { scope: 'global', seconds: retryAfter });
          else emit('ratelimited', { scope: 'route', seconds: retryAfter });
        } catch (_) {}
        await sleep(retryAfter * 1000 + rand(250, 750));
        continue;
      }

      // Be polite: if this route says we're out of calls, wait for the reset.
      const remaining = res.headers.get('x-ratelimit-remaining');
      const resetAfter = res.headers.get('x-ratelimit-reset-after');
      if (remaining === '0' && resetAfter) {
        await sleep(parseFloat(resetAfter) * 1000 + rand(100, 400));
      } else if (pacing) {
        await sleep(pacing());
      }
      return res;
    }
  }

  function buildSearchPath(o, offset) {
    const base = o.guildId
      ? `/guilds/${o.guildId}/messages/search`
      : `/channels/${o.channelId}/messages/search`;
    const q = new URLSearchParams();
    if (o.guildId && o.channelId) q.set('channel_id', o.channelId);
    if (o.authorId) q.set('author_id', o.authorId);
    // NOTE: content / has-link / has-file / has-embed are all matched on-device
    // against the real message object below, NOT via Discord's search index,
    // which is word-based/unreliable (it misses "txt" inside "43534txt7798").
    // Only the cheap, reliable filters (author, date range, nsfw scope) go to the
    // search query.
    const minId = dateToSnowflake(o.afterDate, false);
    const maxId = dateToSnowflake(o.beforeDate, true);
    if (minId) q.set('min_id', minId);
    if (maxId) q.set('max_id', maxId);
    if (o.includeNsfw) q.set('include_nsfw', 'true');
    // When limiting to the most recent N, sort newest-first so "last X" is right.
    if (o.limit) { q.set('sort_by', 'timestamp'); q.set('sort_order', 'desc'); }
    if (offset) q.set('offset', String(offset));
    return base + '?' + q.toString();
  }

  async function search(o, offset) {
    // Search can answer 202 while Discord (re)indexes; wait and retry.
    while (true) {
      if (stopFlag) throw new Error('stopped');
      // Pace every API request - searches included - with the SAME humanized
      // delay deletion uses, so a scan respects the exact same rate limits as a
      // real delete run and goes through the same 429 backoff. (The per-message
      // counting in the dry run stays fast; it makes no API calls.)
      const res = await api('GET', buildSearchPath(o, offset), null,
        () => rand(o.minDelay, o.maxDelay));
      if (res.status === 202) {
        let wait = 2;
        try { wait = (await res.json()).retry_after || 2; } catch (_) {}
        emit('indexing', { seconds: wait });
        await sleep(wait * 1000);
        continue;
      }
      if (!res.ok) throw new Error('search failed: HTTP ' + res.status);
      const data = await res.json();
      // messages comes back as an array of arrays; the hit is the first item.
      const flat = (data.messages || []).map((group) => group.find((m) => m.hit) || group[0]);
      return { total: data.total_results || 0, messages: flat };
    }
  }

  // ---- main run loop ---------------------------------------------------------
  async function run(rawOpts) {
    stopFlag = false;
    const o = Object.assign(
      {
        guildId: null,
        channelId: null,
        authorId: null, // UI locks this to the current user by default
        content: '',
        hasLink: false,
        hasFile: false,
        hasEmbed: false,
        beforeDate: '',
        afterDate: '',
        includeNsfw: false,
        includePinned: false,
        dryRun: false,
        minDelay: 900,
        maxDelay: 1800,
        searchDelay: 1200,
        batchSize: 0,      // delete this many, then rest. 0 = off.
        batchRestMin: 0,   // minutes to rest between batches
        limit: 0,          // only act on the most recent N messages. 0 = all.
      },
      rawOpts || {}
    );

    const stats = { found: 0, deleted: 0, skipped: 0, failed: 0 };
    const needle = (o.content || '').trim().toLowerCase();

    const minId = dateToSnowflake(o.afterDate, false);
    const maxId = dateToSnowflake(o.beforeDate, true);

    // Does this message pass all the on-device filters?
    function matches(msg) {
      const undeletable = !DELETABLE_TYPES.has(msg.type) || (msg.pinned && !o.includePinned);
      if (undeletable) { stats.skipped++; return false; }
      if (needle && !(msg.content && msg.content.toLowerCase().includes(needle))) return false;
      if (o.hasLink && !(msg.content && /(https?:\/\/|www\.)/i.test(msg.content))) return false;
      if (o.hasFile && !(msg.attachments && msg.attachments.length)) return false;
      if (o.hasEmbed && !(msg.embeds && msg.embeds.length)) return false;
      return true;
    }

    // For a single channel or DM, read the real message history (newest first).
    // This sees messages immediately - no search-index lag, no 5000 ceiling - and
    // is the reliable path for the common "clean this channel/DM" case.
    async function collectFromChannel(channelId) {
      const queue = [];
      let before = null;
      while (!stopFlag) {
        let path = '/channels/' + channelId + '/messages?limit=100';
        if (before) path += '&before=' + before;
        const res = await api('GET', path, null, () => rand(o.minDelay, o.maxDelay));
        if (res.status === 403) break; // can't read this channel
        if (!res.ok) throw new Error('history failed: HTTP ' + res.status);
        const msgs = await res.json();
        if (!Array.isArray(msgs) || msgs.length === 0) break;
        for (const msg of msgs) {
          before = msg.id; // oldest seen so far -> next page cursor
          if (!msg.author || msg.author.id !== o.authorId) continue; // only mine
          if (maxId && BigInt(msg.id) > BigInt(maxId)) continue;      // newer than 'before' cutoff
          if (minId && BigInt(msg.id) < BigInt(minId)) return queue;  // older than 'after' cutoff -> done
          if (!matches(msg)) continue;
          queue.push({ id: msg.id, channel_id: channelId });
          if (o.limit && (stats.deleted + queue.length) >= o.limit) return queue;
        }
      }
      return queue;
    }

    // Whole-server scope: there's no single history to page, so fall back to
    // Discord's search across channels (subject to its index lag + 5000 ceiling).
    async function collectFromSearch() {
      const queue = [];
      let offset = 0;
      while (!stopFlag) {
        const page = await search(o, offset);
        if (page.messages.length === 0) break;
        for (const msg of page.messages) {
          if (!msg || !msg.id) continue;
          if (!matches(msg)) continue;
          queue.push({ id: msg.id, channel_id: msg.channel_id });
          if (o.limit && (stats.deleted + queue.length) >= o.limit) return queue;
        }
        offset += page.messages.length;
        if (offset >= 5000) break; // Discord's search offset ceiling
      }
      return queue;
    }

    // Channel/DM scope -> reliable history read; whole-server -> search.
    async function collect() {
      return o.channelId ? collectFromChannel(o.channelId) : collectFromSearch();
    }

    try {
      await waitForToken();

      // Always pin the search to our own account so we can't touch anyone else.
      if (!o.authorId) {
        const meRes = await api('GET', '/users/@me');
        if (meRes.ok) o.authorId = (await meRes.json()).id;
      }
      if (!o.authorId) throw new Error("Couldn't work out who you are. Reload Discord and retry.");

      emit('started', { dryRun: o.dryRun });

      // Dry run: collect once, report how many would go.
      if (o.dryRun) {
        const queue = await collect();
        stats.found = queue.length;
        stats.deleted = queue.length;
        emit('found', { total: queue.length });
        emit(stopFlag ? 'stopped' : 'done', stats);
        return;
      }

      // Real delete: collect a batch, delete it, repeat. Re-collecting after a
      // batch is how we get past Discord's 5000 search-offset ceiling on big
      // runs; we stop when a pass turns up nothing we can actually remove.
      let untilBreak = rand(25, 40);
      let sinceBreak = 0;
      const restMs = Math.max(0, Math.round(o.batchRestMin * 60000));
      let deletedThisBatch = 0;
      let firstPass = true;

      while (!stopFlag) {
        const queue = await collect();
        if (firstPass) { stats.found = queue.length; emit('found', { total: queue.length }); firstPass = false; }
        if (queue.length === 0) break;

        let removedThisPass = 0;
        for (const m of queue) {
          if (stopFlag) break;
          if (o.limit && stats.deleted >= o.limit) break;

          const del = await api('DELETE', `/channels/${m.channel_id}/messages/${m.id}`,
            null, () => rand(o.minDelay, o.maxDelay));

          if (del.status === 204) { stats.deleted++; removedThisPass++; emit('deleted', { id: m.id, stats }); }
          else if (del.status === 404) { stats.skipped++; emit('skip', { id: m.id, stats }); }
          else if (del.status === 403) { stats.failed++; emit('fail', { id: m.id, code: 403, stats }); }
          else { stats.failed++; emit('fail', { id: m.id, code: del.status, stats }); }

          if (++sinceBreak >= untilBreak) {
            sinceBreak = 0;
            untilBreak = rand(25, 40);
            const brk = rand(4000, 9000);
            emit('breather', { ms: brk });
            await sleep(brk);
          }

          if (del.status === 204 && o.batchSize > 0 && restMs > 0) {
            if (++deletedThisBatch >= o.batchSize) {
              deletedThisBatch = 0;
              emit('cooldown', { ms: restMs, deleted: stats.deleted });
              const until = Date.now() + restMs;
              while (Date.now() < until && !stopFlag) await sleep(1000);
            }
          }
        }

        if (o.limit && stats.deleted >= o.limit) break;
        if (removedThisPass === 0) break; // nothing left we can delete
      }

      emit(stopFlag ? 'stopped' : 'done', stats);
    } catch (err) {
      if (err && err.message === 'stopped') emit('stopped', stats);
      else emit('error', { message: (err && err.message) || String(err), stats });
    }
  }

  function stop() { stopFlag = true; }

  // Read-only helper for the UI to label the current context.
  function context() {
    const m = location.pathname.match(/channels\/(@me|\d+)(?:\/(\d+))?/);
    if (!m) return { guildId: null, channelId: null, isDM: false };
    return {
      guildId: m[1] === '@me' ? null : m[1],
      channelId: m[2] || null,
      isDM: m[1] === '@me',
    };
  }

  // Fetch the user's servers and DMs so a picker can list them. Emits ONLY
  // non-sensitive metadata (names + channel/guild ids) - never the token. The
  // token stays in this closure; the host UI only ever sees what you already
  // see on screen.
  async function listTargets() {
    try {
      await waitForToken();
      const targets = [];

      const gRes = await api('GET', '/users/@me/guilds');
      const guilds = gRes.ok ? await gRes.json() : [];
      (guilds || []).forEach((g) => targets.push({ kind: 'guild', id: g.id, name: g.name }));

      const cRes = await api('GET', '/users/@me/channels');
      const chans = cRes.ok ? await cRes.json() : [];
      (chans || []).forEach((c) => {
        let name = c.name;
        if (!name && c.recipients && c.recipients.length) {
          name = c.recipients.map((r) => r.username || r.global_name || 'unknown').join(', ');
        }
        targets.push({ kind: 'dm', id: c.id, name: name || 'Direct Message' });
      });

      emit('targets', { targets });
    } catch (err) {
      emit('error', { message: (err && err.message) || String(err) });
    }
  }

  // List the text channels of a guild so the user can narrow a server-wide
  // delete to one channel. Emits only names + ids (no token).
  async function listChannels(guildId) {
    try {
      await waitForToken();
      const res = await api('GET', '/guilds/' + guildId + '/channels');
      const chans = res.ok ? await res.json() : [];
      const out = [];
      (chans || []).forEach((c) => {
        // 0 = text, 5 = announcement. These are the ones with deletable messages.
        if (c.type === 0 || c.type === 5) out.push({ id: c.id, name: c.name, position: c.position || 0 });
      });
      out.sort((a, b) => a.position - b.position);
      emit('channels', { guildId, channels: out });
    } catch (err) {
      emit('error', { message: (err && err.message) || String(err) });
    }
  }

  window.__ZIVKORD__ = { run, stop, context, listTargets, listChannels, version: '1.0.0' };
  emit('ready', { version: '1.0.0' });
})();
