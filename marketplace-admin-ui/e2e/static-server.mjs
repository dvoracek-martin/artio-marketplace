import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, resolve, sep } from 'node:path';

const root = resolve('dist/browser');
const contentTypes = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.woff2', 'font/woff2'],
]);

createServer(async (request, response) => {
  const pathname = new URL(request.url ?? '/', 'http://localhost').pathname;
  if (pathname !== '/dev-admin' && !pathname.startsWith('/dev-admin/')) {
    response.writeHead(404).end();
    return;
  }

  const relativePath = decodeURIComponent(pathname.slice('/dev-admin'.length)).replace(/^\/+/, '');
  const candidate = resolve(root, relativePath || 'index.html');
  if (candidate !== root && !candidate.startsWith(`${root}${sep}`)) {
    response.writeHead(400).end();
    return;
  }

  try {
    const file = await stat(candidate);
    if (!file.isFile()) throw new Error('not a file');
    response.writeHead(200, { 'Content-Type': contentTypes.get(extname(candidate)) ?? 'application/octet-stream' });
    response.end(await readFile(candidate));
  } catch {
    response.writeHead(404).end();
  }
}).listen(4173, '127.0.0.1');
