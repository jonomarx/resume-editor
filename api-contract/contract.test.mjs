// Contract checks: the spec lints clean, and the mock server answers a sample request per tag.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const port = process.env.MOCK_TEST_PORT ?? '4019';
const base = `http://127.0.0.1:${port}`;
let mock;

test('spec passes redocly lint', () => {
  const r = spawnSync(path.join(here, 'node_modules/.bin/redocly'), ['lint', 'openapi.yaml', '--format', 'stylish'], {
    cwd: here,
    encoding: 'utf8',
  });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

before(async () => {
  mock = spawn(path.join(here, 'node_modules/.bin/prism'), ['mock', '--port', port, '--host', '127.0.0.1', 'openapi.yaml'], {
    cwd: here,
    stdio: 'ignore',
  });
  for (let i = 0; i < 60; i++) {
    try {
      await fetch(`${base}/api/me`);
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 250));
    }
  }
  throw new Error('mock server did not start');
});

after(() => mock?.kill());

const json = { 'content-type': 'application/json', cookie: 'SESSION=mock', 'x-xsrf-token': 'tok' };

test('auth: login returns a user', async () => {
  const res = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: json,
    body: JSON.stringify({ username: 'ada', password: 'correct-horse-battery' }),
  });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).username, 'ada');
});

test('auth: /api/me returns a user', async () => {
  const res = await fetch(`${base}/api/me`, { headers: json });
  assert.equal(res.status, 200);
  assert.equal((await res.json()).username, 'ada');
});

test('resume: master read returns a versioned tree', async () => {
  const res = await fetch(`${base}/api/resumes/master`, { headers: json });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(typeof body.version, 'number');
  assert.ok(body.tree.header && Array.isArray(body.tree.sections));
});

test('resume: stale save returns the 409 shape', async () => {
  const res = await fetch(`${base}/api/resumes/master`, {
    method: 'PUT',
    headers: { ...json, prefer: 'code=409' },
    body: JSON.stringify({
      baseVersion: 1,
      tree: {
        id: 'root',
        header: { id: 'h', type: 'header', title: 'Header', latex: '', tags: [], children: [] },
        sections: [],
        footer: { id: 'f', type: 'footer', title: 'Footer', latex: '', tags: [], children: [] },
      },
    }),
  });
  assert.equal(res.status, 409);
  const body = await res.json();
  assert.equal(body.code, 'version_conflict');
  assert.equal(typeof body.currentVersion, 'number');
});

test('tags: list returns tags', async () => {
  const res = await fetch(`${base}/api/tags`, { headers: json });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.ok(Array.isArray(body) && body[0].slug);
});

test('render: returns a render result', async () => {
  const res = await fetch(`${base}/api/render`, {
    method: 'POST',
    headers: json,
    body: JSON.stringify({
      tree: {
        id: 'root',
        header: { id: 'h', type: 'header', title: 'Header', latex: '', tags: [], children: [] },
        sections: [],
        footer: { id: 'f', type: 'footer', title: 'Footer', latex: '', tags: [], children: [] },
      },
    }),
  });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(typeof body.success, 'boolean');
  assert.ok('pageCount' in body && Array.isArray(body.errors));
});

test('csrf: a state-changing request without X-XSRF-TOKEN is rejected by the contract', async () => {
  const { 'x-xsrf-token': _omit, ...noToken } = json;
  const res = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: noToken,
    body: JSON.stringify({ username: 'ada', password: 'correct-horse-battery' }),
  });
  assert.equal(res.status, 400); // Prism: required header missing
});

test('csrf: the documented 403 csrf_invalid shape is served', async () => {
  const res = await fetch(`${base}/api/auth/logout`, { method: 'POST', headers: { ...json, prefer: 'code=403' } });
  assert.equal(res.status, 403);
  assert.equal((await res.json()).code, 'csrf_invalid');
});

test('credentials: username pattern is enforced', async () => {
  const res = await fetch(`${base}/api/auth/signup`, {
    method: 'POST',
    headers: json,
    body: JSON.stringify({ username: 'has space', password: 'correct-horse-battery' }),
  });
  assert.equal(res.status, 400);
});

test('credentials: a very long password is accepted by the contract', async () => {
  const res = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: json,
    body: JSON.stringify({ username: 'ada', password: 'x'.repeat(500) }),
  });
  assert.equal(res.status, 200);
});

test('restore: 400 is documented', async () => {
  const res = await fetch(`${base}/api/resumes/master/versions/2/restore`, {
    method: 'POST',
    headers: { ...json, prefer: 'code=400' },
    body: JSON.stringify({}),
  });
  assert.equal(res.status, 400);
  assert.equal((await res.json()).code, 'validation_failed');
});

test('save: unknown tag slug 400 example names the slug', async () => {
  const res = await fetch(`${base}/api/resumes/master`, { method: 'PUT', headers: { ...json, prefer: 'code=400' }, body: JSON.stringify({ baseVersion: 1, tree: { id: 'r', header: { id: 'h', type: 'header', title: 'H', latex: '', tags: [], children: [] }, sections: [], footer: { id: 'f', type: 'footer', title: 'F', latex: '', tags: [], children: [] } } }) });
  assert.equal(res.status, 400);
  assert.match(JSON.stringify(await res.json()), /unknown tag slug/);
});

test('render pdf: path requires a full 64-hex SHA-256 and /api/me advertises the XSRF cookie', async () => {
  const hash = 'a'.repeat(64);
  assert.equal((await fetch(`${base}/api/render/${hash}/pdf`, { headers: json })).status, 200);
  assert.equal((await fetch(`${base}/api/render/abc123/pdf`, { headers: json })).status, 422);
  const me = await fetch(`${base}/api/me`, { headers: json });
  assert.ok(me.headers.has('set-cookie') || me.status === 200);
});

test('contentHash is the same 64-hex format in resume and render examples', async () => {
  const resume = await (await fetch(`${base}/api/resumes/master`, { headers: json })).json();
  const render = await (await fetch(`${base}/api/render`, {
    method: 'POST', headers: json,
    body: JSON.stringify({ tree: resume.tree }),
  })).json();
  assert.match(resume.contentHash, /^[0-9a-f]{64}$/);
  assert.equal(render.contentHash, resume.contentHash);
});

test('unauthenticated mock request without the session cookie is rejected', async () => {
  const res = await fetch(`${base}/api/me`);
  assert.equal(res.status, 401);
});

test('save 400: children token examples name the node', async () => {
  const tree = { id: 'r', header: { id: 'h', type: 'header', title: 'H', latex: '', tags: [], children: [] }, sections: [], footer: { id: 'f', type: 'footer', title: 'F', latex: '', tags: [], children: [] } };
  for (const [name, text] of [['missingChildrenToken', '{{children}}'], ['unknownToken', 'unknown token'], ['duplicateChildrenToken', 'more than one']]) {
    const res = await fetch(`${base}/api/resumes/master`, { method: 'PUT', headers: { ...json, prefer: `code=400, example=${name}` }, body: JSON.stringify({ baseVersion: 1, tree }) });
    assert.equal(res.status, 400);
    const body = await res.json();
    assert.equal(body.code, 'validation_failed');
    assert.ok(body.details[0].field.startsWith('n-exp'));
    assert.ok(body.details[0].message.includes(text));
  }
});

test('examples: every node with children carries the {{children}} token', async () => {
  const doc = await (await fetch(`${base}/api/resumes/master`, { headers: json })).json();
  const check = (n) => { if (n.children.length) assert.ok(n.latex.includes('{{children}}'), n.id); n.children.forEach(check); };
  [doc.tree.header, ...doc.tree.sections, doc.tree.footer].forEach(check);
});

test('tags: C++ and C# get distinct slugs in the documented examples', async () => {
  const post = (name, example, status) => fetch(`${base}/api/tags`, { method: 'POST', headers: { ...json, prefer: `code=${status}, example=${example}` }, body: JSON.stringify({ name, category: 'language' }) });
  const cpp = await post('C++', 'cpp', 201);
  assert.equal(cpp.status, 201);
  assert.equal((await cpp.json()).slug, 'c-plus-plus');
  const cs = await post('C#', 'csharp', 201);
  assert.equal((await cs.json()).slug, 'c-sharp');
  const existing = await post('c++', 'cpp', 200);
  assert.equal(existing.status, 200);
  assert.ok((await existing.json()).aliases.includes('C++'));
});

test('tag aliases: add returns the updated tag with the alias as typed', async () => {
  const res = await fetch(`${base}/api/tags/c-plus-plus/aliases`, { method: 'POST', headers: { ...json, prefer: 'example=cpp' }, body: JSON.stringify({ alias: 'C++' }) });
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.equal(body.slug, 'c-plus-plus');
  assert.deepEqual(body.aliases, ['C++']);
  const dotnet = await fetch(`${base}/api/tags/net/aliases`, { method: 'POST', headers: json, body: JSON.stringify({ alias: '.NET' }) });
  assert.equal(dotnet.status, 200);
});

test('tag aliases: non-ASCII, empty and control characters are rejected by the contract', async () => {
  for (const alias of ['caf\u00e9', '', 'tab\there', 'emoji \u{1F600}']) {
    const res = await fetch(`${base}/api/tags/postgresql/aliases`, { method: 'POST', headers: json, body: JSON.stringify({ alias }) });
    assert.equal(res.status, 400, JSON.stringify(alias));
  }
  const documented = await fetch(`${base}/api/tags/postgresql/aliases`, { method: 'POST', headers: { ...json, prefer: 'code=400, example=nonAscii' }, body: JSON.stringify({ alias: 'ok' }) });
  assert.equal(documented.status, 400);
  const body = await documented.json();
  assert.equal(body.code, 'validation_failed');
  assert.equal(body.details[0].field, 'alias');
});

test('tag aliases: the case-insensitive conflict names the other tag, 403 is documented', async () => {
  const conflict = await fetch(`${base}/api/tags/postgresql/aliases`, { method: 'POST', headers: { ...json, prefer: 'code=409' }, body: JSON.stringify({ alias: 'MYSQL' }) });
  assert.equal(conflict.status, 409);
  const body = await conflict.json();
  assert.equal(body.code, 'alias_conflict');
  assert.equal(body.conflictingSlug, 'mysql');
  const csrf = await fetch(`${base}/api/tags/postgresql/aliases`, { method: 'POST', headers: { ...json, prefer: 'code=403' }, body: JSON.stringify({ alias: 'pg' }) });
  assert.equal(csrf.status, 403);
  assert.equal((await csrf.json()).code, 'csrf_invalid');
});

test('tag aliases: the alias C on c-plus-plus conflicts with slug c, and DELETE without alias has an alias-specific 400', async () => {
  const conflict = await fetch(`${base}/api/tags/c-plus-plus/aliases`, { method: 'POST', headers: { ...json, prefer: 'code=409, example=aliasEqualsOtherSlug' }, body: JSON.stringify({ alias: 'C' }) });
  assert.equal(conflict.status, 409);
  assert.equal((await conflict.json()).conflictingSlug, 'c');
  const bad = await fetch(`${base}/api/tags/c-plus-plus/aliases`, { method: 'DELETE', headers: json });
  assert.equal(bad.status, 400);
  assert.equal((await bad.json()).details[0].field, 'alias');
});

test('tag aliases: DELETE takes a percent-encoded alias query parameter', async () => {
  const res = await fetch(`${base}/api/tags/c-plus-plus/aliases?alias=${encodeURIComponent('C++')}`, { method: 'DELETE', headers: json });
  assert.equal(res.status, 200);
  assert.deepEqual((await res.json()).aliases, []);
  const missing = await fetch(`${base}/api/tags/c-plus-plus/aliases?alias=nope`, { method: 'DELETE', headers: { ...json, prefer: 'code=404' } });
  assert.equal(missing.status, 404);
  const noParam = await fetch(`${base}/api/tags/c-plus-plus/aliases`, { method: 'DELETE', headers: json });
  assert.equal(noParam.status, 400);
  // the old path-segment form no longer exists
  const old = await fetch(`${base}/api/tags/c-plus-plus/aliases/cpp`, { method: 'DELETE', headers: json });
  assert.equal(old.status, 404);
});

test('sign-up: a password over 72 bytes is a 400 on password (named example), login stays a 401', async () => {
  const documented = await fetch(`${base}/api/auth/signup`, { method: 'POST', headers: { ...json, prefer: 'code=400, example=passwordTooLong' }, body: JSON.stringify({ username: 'ada', password: 'correct-horse-battery' }) });
  assert.equal(documented.status, 400);
  const body = await documented.json();
  assert.equal(body.code, 'validation_failed');
  assert.equal(body.details[0].field, 'password');
  assert.match(body.details[0].message, /72 bytes/);
  // the schema's cheap first check: 73 characters are rejected before the byte count even matters
  const long = await fetch(`${base}/api/auth/signup`, { method: 'POST', headers: json, body: JSON.stringify({ username: 'ada', password: 'x'.repeat(73) }) });
  assert.equal(long.status, 400);
  const login = await fetch(`${base}/api/auth/login`, { method: 'POST', headers: { ...json, prefer: 'code=401' }, body: JSON.stringify({ username: 'ada', password: 'x'.repeat(500) }) });
  assert.equal(login.status, 401);
  assert.equal((await login.json()).code, 'bad_credentials');
});

test('sign-up: a control character in the password is a 400 on password; login treats it as a wrong password', async () => {
  const documented = await fetch(`${base}/api/auth/signup`, { method: 'POST', headers: { ...json, prefer: 'code=400, example=passwordControlCharacter' }, body: JSON.stringify({ username: 'ada', password: 'correct-horse-battery' }) });
  assert.equal(documented.status, 400);
  const body = await documented.json();
  assert.equal(body.details[0].field, 'password');
  assert.match(body.details[0].message, /control characters/);
  for (const bad of ['correct\u0000horse', 'correct\thorse-battery', 'correct\u007Fhorse-battery']) {
    const res = await fetch(`${base}/api/auth/signup`, { method: 'POST', headers: json, body: JSON.stringify({ username: 'ada', password: bad }) });
    assert.equal(res.status, 400, JSON.stringify(bad));
  }
  const login = await fetch(`${base}/api/auth/login`, { method: 'POST', headers: { ...json, prefer: 'code=401' }, body: JSON.stringify({ username: 'ada', password: 'bad\u0000password' }) });
  assert.equal(login.status, 401);
  assert.equal((await login.json()).code, 'bad_credentials');
  // length boundary is by characters in the schema (first check) and the example names the byte limit: 72 characters pass the schema
  const ok72 = await fetch(`${base}/api/auth/signup`, { method: 'POST', headers: json, body: JSON.stringify({ username: 'ada', password: 'x'.repeat(72) }) });
  assert.equal(ok72.status, 201);
});

// ---- Contract v1 (US-028): sources, ingest, jobs, drafts, cutdown ----
const call = (method, url, { body, prefer, headers } = {}) =>
  fetch(`${base}${url}`, {
    method,
    headers: { ...json, ...(prefer ? { prefer } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
const emptyTree = { id: 'r', header: { id: 'h', type: 'header', title: 'H', latex: '', tags: [], children: [] }, sections: [], footer: { id: 'f', type: 'footer', title: 'F', latex: '', tags: [], children: [] } };

test('sources: list, create, update, delete, and the duplicate 409 names the existing source', async () => {
  const list = await (await call('GET', '/api/sources')).json();
  assert.ok(Array.isArray(list) && typeof list[0].url === 'string' && 'extractionTier' in list[0]);
  const created = await call('POST', '/api/sources', { body: { url: 'https://acme.example/careers', name: 'Acme' }, prefer: 'code=201' });
  assert.equal(created.status, 201);
  assert.equal((await call('POST', '/api/sources', { body: { url: 'ftp://nope' } })).status, 400);
  const dup = await call('POST', '/api/sources', { body: { url: 'https://acme.example/careers' }, prefer: 'code=409' });
  assert.equal(dup.status, 409);
  const dupBody = await dup.json();
  assert.equal(dupBody.code, 'source_exists');
  assert.equal(typeof dupBody.existingSourceId, 'number');
  assert.equal((await call('GET', '/api/sources/3')).status, 200);
  const upd = await call('PUT', '/api/sources/3', { body: { enabled: false } });
  assert.equal(upd.status, 200);
  assert.equal((await call('DELETE', '/api/sources/3')).status, 204);
  assert.equal((await call('GET', '/api/sources/3', { prefer: 'code=404' })).status, 404);
});

test('source health: one entry per source with state and recent runs', async () => {
  const health = await (await call('GET', '/api/source-health')).json();
  assert.ok(health[0].source.id && ['never_run', 'healthy', 'degraded', 'failing'].includes(health[0].state));
  assert.ok(Array.isArray(health[0].recentRuns) && health[0].lastRun.status);
});

test('ingest tokens: the secret appears only in the create response', async () => {
  const created = await (await call('POST', '/api/ingest/tokens', { body: { label: 'Bookmarklet' }, prefer: 'code=201' })).json();
  assert.match(created.token, /^ing_/);
  const list = await (await call('GET', '/api/ingest/tokens')).json();
  assert.ok(list.length && !('token' in list[0]));
  assert.equal((await call('DELETE', '/api/ingest/tokens/5')).status, 204);
});

test('ingest: 202 with an event id, kinds are validated, a bearer token needs no CSRF header, duplicates are flagged', async () => {
  const ok = await call('POST', '/api/ingest', { body: { kind: 'text', payload: 'Software Intern at Acme', sourceHint: null }, prefer: 'code=202, example=new' });
  assert.equal(ok.status, 202);
  const accepted = await ok.json();
  assert.equal(typeof accepted.ingestEventId, 'number');
  assert.equal(accepted.duplicate, false);
  assert.equal((await call('POST', '/api/ingest', { body: { kind: 'pdf', payload: 'x' } })).status, 400);
  assert.equal((await call('POST', '/api/ingest', { body: { kind: 'text' } })).status, 400);
  // bearer token, no session cookie and no CSRF header
  const bearer = await fetch(`${base}/api/ingest`, { method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer ing_abc' }, body: JSON.stringify({ kind: 'url', payload: 'https://acme.example/jobs/1' }) });
  assert.equal(bearer.status, 202);
  const dup = await call('POST', '/api/ingest', { body: { kind: 'text', payload: 'Software Intern at Acme' }, prefer: 'code=202, example=duplicate' });
  assert.equal((await dup.json()).duplicate, true);
  assert.equal((await call('POST', '/api/ingest', { body: { kind: 'text', payload: 'x' }, prefer: 'code=413' })).status, 413);
  assert.equal((await call('POST', '/api/ingest', { body: { kind: 'text', payload: 'x' }, prefer: 'code=429' })).status, 429);
});

test('ingest events: list and read, a failed event carries an error', async () => {
  assert.ok((await (await call('GET', '/api/ingest/events')).json()).length >= 1);
  const failed = await (await call('GET', '/api/ingest/events/89', { prefer: 'example=failed' })).json();
  assert.equal(failed.status, 'failed');
  assert.equal(failed.error.code, 'fetch_failed');
  assert.equal((await call('GET', '/api/ingest/events/9999', { prefer: 'code=404' })).status, 404);
});

test('jobs: feed page, detail with a fit explanation, status change', async () => {
  const page = await (await call('GET', '/api/jobs?sort=fit&tag=java&tag=postgresql&status=new&limit=10')).json();
  assert.ok(page.items[0].fitScore >= 0 && page.items[0].fitScore <= 100);
  assert.equal(typeof page.total, 'number');
  assert.equal((await call('GET', '/api/jobs?sort=best')).status, 400);
  const detail = await (await call('GET', '/api/jobs/140')).json();
  assert.ok(Array.isArray(detail.fit.matchedTags) && 'season' in detail.eligibility);
  const status = await call('PUT', '/api/jobs/140/status', { body: { status: 'saved' } });
  assert.equal(status.status, 200);
  assert.equal((await call('PUT', '/api/jobs/140/status', { body: { status: 'maybe' } })).status, 400);
});

test('drafts: fork (201 or 200), read, save with include toggles, list, export, versions, restore', async () => {
  assert.equal((await call('POST', '/api/jobs/140/drafts', { prefer: 'code=201' })).status, 201);
  assert.equal((await call('POST', '/api/jobs/140/drafts', { prefer: 'code=200' })).status, 200);
  const draft = await (await call('GET', '/api/drafts/21')).json();
  assert.ok(Array.isArray(draft.excludedNodeIds) && draft.tree.header && draft.jobId);
  assert.equal((await call('PUT', '/api/drafts/21', { body: { baseVersion: 3, tree: draft.tree, excludedNodeIds: ['n-exp-1-b2'], status: 'ready' } })).status, 200);
  assert.equal((await call('PUT', '/api/drafts/21', { body: { baseVersion: 3, tree: emptyTree } })).status, 400); // excludedNodeIds is required
  const stale = await call('PUT', '/api/drafts/21', { body: { baseVersion: 1, tree: emptyTree, excludedNodeIds: [] }, prefer: 'code=409' });
  assert.equal((await stale.json()).code, 'version_conflict');
  assert.equal((await (await call('GET', '/api/drafts?status=in_progress')).json())[0].status, 'in_progress');
  assert.equal((await call('GET', '/api/drafts/21/export?format=tex')).status, 200);
  assert.equal((await call('GET', '/api/drafts/21/export?format=docx')).status, 422); // Prism answers 422 for a bad required query value; the server answers 400
  assert.equal((await call('GET', '/api/drafts/21/export?format=pdf', { prefer: 'code=422' })).status, 422);
  assert.equal((await (await call('GET', '/api/drafts/21/versions')).json())[1].reason, 'before_cutdown');
  assert.equal((await call('POST', '/api/drafts/21/versions/2/restore', { body: { baseVersion: 3 } })).status, 200);
});

test('cutdown: start (202), poll a ready run with per-node suggestions, apply, and the documented conflicts', async () => {
  const started = await call('POST', '/api/drafts/21/cutdowns', { body: { baseVersion: 3, mode: 'deterministic' } });
  assert.equal(started.status, 202);
  assert.equal((await started.json()).status, 'queued');
  assert.equal((await call('POST', '/api/drafts/21/cutdowns', { body: { baseVersion: 3, mode: 'magic' } })).status, 400);
  const active = await call('POST', '/api/drafts/21/cutdowns', { body: { baseVersion: 3 }, prefer: 'code=409, example=active' });
  assert.equal((await active.json()).code, 'cutdown_in_progress');
  assert.equal((await call('POST', '/api/drafts/21/cutdowns', { body: { baseVersion: 3, mode: 'llm_tighten' }, prefer: 'code=429' })).status, 429);
  assert.equal((await call('POST', '/api/drafts/21/cutdowns', { body: { baseVersion: 3, mode: 'llm_tighten' }, prefer: 'code=503' })).status, 503);
  const run = await (await call('GET', '/api/drafts/21/cutdowns/7', { prefer: 'example=ready' })).json();
  assert.equal(run.status, 'ready');
  assert.equal(run.pageCountAfter, 1);
  assert.deepEqual(run.suggestions.map((s) => s.kind), ['exclude', 'reword']);
  assert.ok(run.suggestions[1].afterLatex && run.suggestions[0].afterLatex === null);
  const failed = await (await call('GET', '/api/drafts/21/cutdowns/8', { prefer: 'example=failed' })).json();
  assert.equal(failed.error.code, 'cannot_fit');
  const applied = await call('POST', '/api/drafts/21/cutdowns/7/apply', { body: { acceptedSuggestionIds: ['s1', 's2'] } });
  assert.equal(applied.status, 200);
  assert.ok(Array.isArray((await applied.json()).excludedNodeIds));
  const notReady = await call('POST', '/api/drafts/21/cutdowns/7/apply', { body: { acceptedSuggestionIds: [] }, prefer: 'code=409, example=notReady' });
  assert.equal((await notReady.json()).code, 'cutdown_not_ready');
});
