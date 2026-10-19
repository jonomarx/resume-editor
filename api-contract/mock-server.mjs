// Starts a Prism mock server from openapi.yaml; responses come from the spec's examples.
// Usage: pnpm mock            (port 4010, or MOCK_PORT)
// Prism enforces the cookie security scheme: protected calls need a `SESSION` cookie (any value), e.g. Cookie: SESSION=mock.
// Add a `Prefer: code=409` header to get a documented non-default response.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const port = process.env.MOCK_PORT ?? '4010';
const prism = path.join(here, 'node_modules', '.bin', 'prism');

export function startMock() {
  return spawn(prism, ['mock', '--port', port, '--host', '127.0.0.1', path.join(here, 'openapi.yaml')], {
    stdio: 'inherit',
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const child = startMock();
  for (const sig of ['SIGINT', 'SIGTERM']) process.on(sig, () => child.kill(sig));
  child.on('exit', (code) => process.exit(code ?? 0));
}
