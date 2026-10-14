// Monkey Browser —— gm-api.js 运行时回归校验
//
// 为什么需要它：gm-api.js 是注入到页面里的 GM 运行时，Kotlin 单测覆盖不到这段 JS。
// 而它一旦改坏，症状是"脚本在页面上静默失效"——很难从插件侧发现。典型回归：
//   responseType 不再透传 / abort 不发出去 / 句柄字段改名 / 未授权的 API 意外泄漏。
//
// 做法：用 Node 的 vm 造一个最小页面沙箱（window + console + 少量 Web API），
// 把 gm-api.js 加载进去，然后直接驱动它并断言回传消息与回调。
//
// 用法：
//   node tools/check-gm-runtime.mjs                      # 校验默认路径的 gm-api.js
//   node tools/check-gm-runtime.mjs path/to/gm-api.js    # 校验指定文件
//
// 退出码：0 = 全部通过；1 = 有失败。

import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = process.argv[2]
  ? path.resolve(process.argv[2])
  : path.resolve(here, '..', 'src', 'main', 'resources', 'js', 'gm-api.js');

const results = [];
let failed = 0;

function check(name, ok, extra) {
  if (!ok) failed += 1;
  results.push((ok ? 'PASS  ' : 'FAIL  ') + name + (extra === undefined ? '' : '   [' + extra + ']'));
}

function section(title) {
  results.push('');
  results.push('== ' + title + ' ==');
}

/** Kotlin 注入前会替换这些占位符；这里补上，让文件成为可执行的运行时。 */
function loadRuntime(grants) {
  let src = fs.readFileSync(runtimePath, 'utf8');
  src = src
    .replace(/__RM_SCRIPT_ID__/g, 'script1')
    .replace(/__RM_FRAME_ID__/g, 'frame1')
    .replace(/__RM_TOKEN__/g, 'tok1')
    .replace(/__RM_SNAPSHOT__/g, '{}')
    .replace(/__RM_GRANTS__/g, JSON.stringify(grants))
    .replace(/__RM_INFO__/g, '{}')
    .replace(/__RM_RESOURCES__/g, '{}')
    .replace(/__RM_RESOURCE_URLS__/g, '{}');

  const posts = [];
  const sandbox = {
    console: { log: (m) => posts.push(String(m)) },
    setTimeout, clearTimeout, setInterval, clearInterval,
    JSON, Math, Date, Object, Array, String, Number, Boolean, Error, RegExp, Promise,
    encodeURIComponent, decodeURIComponent, atob,
    Blob: class Blob { constructor(parts) { this.parts = parts; } },
    Uint8Array, ArrayBuffer,
    document: {
      createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, remove() {}, textContent: '' }),
      head: { appendChild() {} },
      documentElement: { appendChild() {} },
      body: { appendChild() {} },
      querySelector: () => null,
      querySelectorAll: () => []
    }
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(src, sandbox);
  return { sandbox, posts };
}

/**
 * 从 __RM_GM__... 消息里取字段（URL 解码）。
 *
 * 注意必须先剥掉协议前缀：第一段长这样 '__RM_GM__scriptId=script1'，
 * 不剥掉就匹配不到 scriptId（这一点最初把校验脚本自己写挂了）。
 */
function field(message, key) {
  if (!message) return null;
  const prefix = '__RM_GM__';
  let text = String(message);
  if (text.indexOf(prefix) === 0) text = text.slice(prefix.length);
  const parts = text.split('&');
  for (const part of parts) {
    if (part.indexOf(key + '=') === 0) return decodeURIComponent(part.slice(key.length + 1));
  }
  return null;
}

function lastPostOf(posts, op) {
  for (let i = posts.length - 1; i >= 0; i -= 1) {
    if (field(posts[i], 'op') === op) return posts[i];
  }
  return null;
}

const ALL_GRANTS = [
  'GM_xmlhttpRequest', 'GM.xmlHttpRequest',
  'GM_download', 'GM.download',
  'GM_openInTab', 'GM.openInTab'
];

// ── 授权裁剪 ────────────────────────────────────────────────────────────────

section('授权裁剪（@grant 白名单）');
{
  const { sandbox } = loadRuntime([]);
  check('未授权时 GM_getValue 未定义', typeof sandbox.GM_getValue === 'undefined', typeof sandbox.GM_getValue);
  check('未授权时 GM_xmlhttpRequest 未定义', typeof sandbox.GM_xmlhttpRequest === 'undefined', typeof sandbox.GM_xmlhttpRequest);
  check('未授权时 GM_openInTab 未定义', typeof sandbox.GM_openInTab === 'undefined', typeof sandbox.GM_openInTab);
  check('未授权时 GM_download 未定义', typeof sandbox.GM_download === 'undefined', typeof sandbox.GM_download);
}
{
  const { sandbox } = loadRuntime(ALL_GRANTS);
  check('授权后 GM_xmlhttpRequest 可用', typeof sandbox.GM_xmlhttpRequest === 'function');
  check('授权后 GM_openInTab 可用', typeof sandbox.GM_openInTab === 'function');
  check('授权后 GM_download 可用', typeof sandbox.GM_download === 'function');
}

// ── 回传协议 ────────────────────────────────────────────────────────────────

section('回传协议');
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  sandbox.GM_xmlhttpRequest({ url: 'https://example.com/' });
  const msg = lastPostOf(posts, 'xhr');
  check('回传消息带 ops 前缀', String(msg).indexOf('__RM_GM__') === 0, String(msg).slice(0, 30));
  check('带 scriptId', field(msg, 'scriptId') === 'script1');
  check('带 frameId', field(msg, 'frameId') === 'frame1');
  check('带 token', field(msg, 'token') === 'tok1');
}

// ── GM_xmlhttpRequest ───────────────────────────────────────────────────────

section('GM_xmlhttpRequest');
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  let loaded = null;
  const progress = [];
  posts.length = 0;
  const handle = sandbox.GM_xmlhttpRequest({
    url: 'https://example.com/bin',
    method: 'post',
    responseType: 'arraybuffer',
    timeout: 5000,
    headers: { 'X-Test': 'v' },
    onprogress: (e) => progress.push(e),
    onload: (r) => { loaded = r; }
  });
  const req = lastPostOf(posts, 'xhr');
  check('发出 op=xhr', !!req);
  check('method 被大写', field(req, 'method') === 'POST', field(req, 'method'));
  check('responseType 透传', field(req, 'responseType') === 'arraybuffer');
  check('timeout 透传', field(req, 'timeout') === '5000');
  check('自定义 header 透传', field(req, 'header.X-Test') === 'v');
  check('返回句柄带 abort()', !!(handle && typeof handle.abort === 'function'));

  const id = field(req, 'id');
  sandbox.window.__rmDeliver(JSON.stringify({ id, progress: true, loaded: 100, total: 400 }));
  check('onprogress 被调用', progress.length === 1);
  check('进度 readyState=3', progress[0] && progress[0].readyState === 3);
  check('进度 loaded/total 正确', !!(progress[0] && progress[0].loaded === 100 && progress[0].total === 400));
  check('进度 lengthComputable=true', !!(progress[0] && progress[0].lengthComputable));

  sandbox.window.__rmDeliver(JSON.stringify({
    id, status: 200, responseBase64: Buffer.from([1, 2, 3]).toString('base64'),
    responseHeaders: 'x: y', finalUrl: 'https://example.com/bin'
  }));
  check('onload 被调用', !!loaded);
  const tag = loaded && Object.prototype.toString.call(loaded.response);
  check('arraybuffer 得到 ArrayBuffer', tag === '[object ArrayBuffer]', tag);
  const bytes = loaded ? new Uint8Array(loaded.response) : [];
  check('二进制内容正确 (1,2,3)', bytes.length === 3 && bytes[0] === 1 && bytes[1] === 2 && bytes[2] === 3, Array.from(bytes).join(','));
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  let json = null;
  posts.length = 0;
  sandbox.GM_xmlhttpRequest({ url: 'https://example.com/j', responseType: 'json', onload: (r) => { json = r; } });
  sandbox.window.__rmDeliver(JSON.stringify({ id: field(lastPostOf(posts, 'xhr'), 'id'), status: 200, responseText: '{"a":1}' }));
  check('json 模式 response 已解析', !!(json && json.response && json.response.a === 1));
  check('json 模式保留原始 responseText', !!(json && json.responseText === '{"a":1}'));
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  let blob = null;
  posts.length = 0;
  sandbox.GM_xmlhttpRequest({ url: 'https://example.com/b', responseType: 'blob', onload: (r) => { blob = r; } });
  sandbox.window.__rmDeliver(JSON.stringify({ id: field(lastPostOf(posts, 'xhr'), 'id'), status: 200, responseBase64: Buffer.from('hi').toString('base64') }));
  check('blob 模式得到 Blob', !!(blob && blob.response && blob.response.constructor.name === 'Blob'), blob && blob.response && blob.response.constructor.name);
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  let text = null, errored = null;
  posts.length = 0;
  sandbox.GM_xmlhttpRequest({ url: 'https://example.com/t', onload: (r) => { text = r; }, onerror: (r) => { errored = r; } });
  const id = field(lastPostOf(posts, 'xhr'), 'id');
  sandbox.window.__rmDeliver(JSON.stringify({ id, status: 200, responseText: 'hello' }));
  check('默认 responseType 给 responseText', !!(text && text.responseText === 'hello'));
  check('默认 responseType 的 response 是字符串', !!(text && text.response === 'hello'));

  posts.length = 0;
  sandbox.GM_xmlhttpRequest({ url: 'https://example.com/e', onerror: (r) => { errored = r; } });
  const id2 = field(lastPostOf(posts, 'xhr'), 'id');
  sandbox.window.__rmDeliver(JSON.stringify({ id: id2, error: 'boom', timeout: false }));
  check('error 走 onerror', !!errored);
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  const handle = sandbox.GM_xmlhttpRequest({ url: 'https://example.com/a', onload: () => {}, onerror: () => {} });
  const id = field(lastPostOf(posts, 'xhr'), 'id');
  handle.abort();
  const abort = lastPostOf(posts, 'abort');
  check('abort 发出 op=abort', !!abort);
  check('abort 带同一个 id', field(abort, 'id') === id, field(abort, 'id') + ' vs ' + id);
}
{
  const { sandbox } = loadRuntime(ALL_GRANTS);
  let threw = false;
  try { sandbox.GM_xmlhttpRequest({ url: 'https://example.com/d', responseType: 'document' }); } catch (e) { threw = true; }
  check('responseType=document 明确抛错', threw);
}

section('GM.xmlHttpRequest（Promise 风格）');
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  const promise = sandbox.GM.xmlHttpRequest({ url: 'https://example.com/p' });
  check('返回 Promise', !!(promise && typeof promise.then === 'function'));
  check('Promise 上带 abort()', typeof promise.abort === 'function');
}

// ── GM_download ─────────────────────────────────────────────────────────────

section('GM_download');
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  let loaded = null;
  posts.length = 0;
  const handle = sandbox.GM_download(
    { url: 'https://example.com/a.zip', name: 'pkg.zip', headers: { 'X-A': '1' } },
    (r) => { loaded = r; },
    () => {}
  );
  const req = lastPostOf(posts, 'download');
  check('发出 op=download', !!req);
  check('name 透传', field(req, 'name') === 'pkg.zip');
  check('header 透传', field(req, 'header.X-A') === '1');
  check('返回 abort 句柄', !!(handle && typeof handle.abort === 'function'));

  sandbox.window.__rmDeliver(JSON.stringify({
    id: field(req, 'id'), status: 200, path: 'C:/tmp/pkg.zip', name: 'pkg.zip', finalUrl: 'https://example.com/a.zip'
  }));
  check('onload 收到 path', !!(loaded && loaded.path === 'C:/tmp/pkg.zip'));
  check('onload 收到 name', !!(loaded && loaded.name === 'pkg.zip'));
  check('onload 收到 status', !!(loaded && loaded.status === 200));
}
{
  const { sandbox } = loadRuntime(ALL_GRANTS);
  const promise = sandbox.GM.download({ url: 'https://example.com/b.bin' });
  check('GM.download 返回 Promise', !!(promise && typeof promise.then === 'function'));
  check('GM.download Promise 带 abort()', typeof promise.abort === 'function');
}

// ── GM_openInTab ────────────────────────────────────────────────────────────

section('GM_openInTab');
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  const handle = sandbox.GM_openInTab('https://example.com/new', { active: false });
  const req = lastPostOf(posts, 'openTab');
  check('发出 op=openTab', !!req);
  check('url 透传', field(req, 'url') === 'https://example.com/new');
  check('active=false 透传', field(req, 'active') === 'false');
  check('句柄字段齐全', typeof handle.close === 'function' && handle.closed === false && handle.onclose === null);

  sandbox.window.__rmDeliver(JSON.stringify({ id: field(req, 'id'), tabId: 7 }));
  let closed = false;
  handle.onclose = () => { closed = true; };
  handle.close();
  const closeMsg = lastPostOf(posts, 'closeTab');
  check('close() 发出 op=closeTab', !!closeMsg);
  check('closeTab 的 tabId 正确', field(closeMsg, 'tabId') === '7');
  check('close() 触发 onclose', closed);
  check('close() 后 closed=true', handle.closed === true);
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  const handle = sandbox.GM_openInTab('https://example.com/early');
  const req = lastPostOf(posts, 'openTab');
  handle.close();
  check('id 未到时 close 不发 closeTab', lastPostOf(posts, 'closeTab') === null);
  sandbox.window.__rmDeliver(JSON.stringify({ id: field(req, 'id'), tabId: 9 }));
  const closeMsg = lastPostOf(posts, 'closeTab');
  check('id 到达后补发 closeTab', !!closeMsg && field(closeMsg, 'tabId') === '9');
}
{
  const { sandbox, posts } = loadRuntime(ALL_GRANTS);
  posts.length = 0;
  sandbox.GM_openInTab('https://example.com/default');
  check('active 默认 true', field(lastPostOf(posts, 'openTab'), 'active') === 'true');
  const promise = sandbox.GM.openInTab('https://example.com/promise');
  check('GM.openInTab 返回 Promise', !!(promise && typeof promise.then === 'function'));
}

// ── 结果 ───────────────────────────────────────────────────────────────────

console.log(results.join('\n'));
console.log('');
console.log('gm-api.js: ' + runtimePath);
console.log('总计 ' + (results.filter((r) => r.indexOf('PASS') === 0).length + failed) + ' 项，失败 ' + failed + ' 项');
process.exit(failed === 0 ? 0 : 1);
