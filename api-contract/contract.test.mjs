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

const json = { 'content-type': 'application/json', cookie: 'SESSION=mock' };

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

test('unauthenticated mock request without the session cookie is rejected', async () => {
  const res = await fetch(`${base}/api/me`);
  assert.equal(res.status, 401);
});
