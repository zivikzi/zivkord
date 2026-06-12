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
    if (o.content) q.set('content', o.content);
    if (o.hasLink) q.append('has', 'link');
    if (o.hasFile) q.append('has', 'file');
    if (o.hasEmbed) q.append('has', 'embed');
    const minId = dateToSnowflake(o.afterDate, false);
    const maxId = dateToSnowflake(o.beforeDate, true);
    if (minId) q.set('min_id', minId);
    if (maxId) q.set('max_id', maxId);
    if (o.includeNsfw) q.set('include_nsfw', 'true');
    if (offset) q.set('offset', String(offset));
    return base + '?' + q.toString();
  }

  async function search(o, offset) {
    // Search can answer 202 while Discord (re)indexes; wait and retry.
    while (true) {
      if (stopFlag) throw new Error('stopped');
      const res = await api('GET', buildSearchPath(o, offset));
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
      },
      rawOpts || {}
    );

    const stats = { found: 0, deleted: 0, skipped: 0, failed: 0 };

    // short breather: pick a target once, count down to it, then re-roll
    let untilBreak = rand(25, 40);
    let sinceBreak = 0;

    // long session cooldown: rest after every `batchSize` actual deletes
    const restMs = Math.max(0, Math.round(o.batchRestMin * 60000));
    let deletedThisBatch = 0;

    try {
      await waitForToken();

      // Always pin the search to our own account. Even if something upstream
      // forgot to set this, we resolve it here so we can't target anyone else.
      if (!o.authorId) {
        const meRes = await api('GET', '/users/@me');
        if (meRes.ok) o.authorId = (await meRes.json()).id;
      }
      if (!o.authorId) throw new Error("Couldn't work out who you are. Reload Discord and retry.");

      emit('started', { dryRun: o.dryRun });

      const first = await search(o, 0);
      stats.found = first.total;
      emit('found', { total: first.total });
      if (first.total === 0) { emit('done', stats); return; }

      let offset = 0;
      let guard = 0;

      while (!stopFlag) {
        const page = await search(o, offset);
        if (page.messages.length === 0) break;

        let didSomething = false;
        for (const msg of page.messages) {
          if (stopFlag) break;
          if (!msg || !msg.id) continue;

          const undeletable =
            !DELETABLE_TYPES.has(msg.type) || (msg.pinned && !o.includePinned);
          if (undeletable) { stats.skipped++; emit('skip', { id: msg.id, stats }); continue; }

          if (o.dryRun) {
            stats.deleted++; // counts as "would delete"
            emit('would-delete', { id: msg.id, channel: msg.channel_id, stats });
            await sleep(rand(40, 120));
            didSomething = true;
            continue;
          }

          const del = await api(
            'DELETE',
            `/channels/${msg.channel_id}/messages/${msg.id}`,
            null,
            () => rand(o.minDelay, o.maxDelay) // humanized gap between deletes
          );

          let removed = false;
          if (del.status === 204) { stats.deleted++; didSomething = true; removed = true; emit('deleted', { id: msg.id, stats }); }
          else if (del.status === 404) { stats.skipped++; emit('skip', { id: msg.id, stats }); }
          else if (del.status === 403) { stats.failed++; emit('fail', { id: msg.id, code: 403, stats }); }
          else { stats.failed++; emit('fail', { id: msg.id, code: del.status, stats }); }

          // Short human-like breather: keeps the rhythm from looking robotic.
          if (++sinceBreak >= untilBreak) {
            sinceBreak = 0;
            untilBreak = rand(25, 40);
            const brk = rand(4000, 9000);
            emit('breather', { ms: brk });
            await sleep(brk);
          }

          // Long session cooldown: after N real deletes, rest for a while so
          // the per-hour volume stays in human territory.
          if (removed && o.batchSize > 0 && restMs > 0) {
            if (++deletedThisBatch >= o.batchSize) {
              deletedThisBatch = 0;
              emit('cooldown', { ms: restMs, deleted: stats.deleted });
              const until = Date.now() + restMs;
              while (Date.now() < until && !stopFlag) await sleep(1000);
            }
          }
        }

        // If the whole page was undeletable, step the offset past it so we
        // don't loop forever on system messages.
        if (!didSomething) {
          offset += page.messages.length;
          if (offset > 5000) break; // Discord's search offset ceiling
          guard++;
          if (guard > 400) break;
        } else {
          offset = 0; // deletions shift results; re-search from the top
        }
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

  window.__ZIVKORD__ = { run, stop, context, version: '1.0.0' };
  emit('ready', { version: '1.0.0' });
})();
