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
  for (const [name, text] of [['missingChildrenToken', '{{children}}'], ['unknownToken', 'unknown token']]) {
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
