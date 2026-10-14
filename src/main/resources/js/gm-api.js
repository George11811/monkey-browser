// Monkey Browser —— 注入到页面里的 GM API 运行时。
//
// 占位符由 Kotlin 侧在注入前替换：__RM_SCRIPT_ID__ / __RM_FRAME_ID__ / __RM_TOKEN__ /
// __RM_SNAPSHOT__ / __RM_GRANTS__ / __RM_INFO__ / __RM_RESOURCES__ / __RM_RESOURCE_URLS__。
//
// 只用 ES5 语法（不用模板字符串、箭头函数），避免在宿主页面里出兼容问题。
//
// 语义要点：
// - **按 @grant 裁剪**：只有 __RM_GRANTS__ 里列出的名字才会定义，未授权调用得到 ReferenceError（与 TM 一致）。
// - GM_getValue 是**同步**的：读的是注入时嵌入的存储快照（ADR-0005 D1）。
// - GM_setValue 先改内存快照再异步回传落盘，本页后续读取立即可见。
// - 所有需要回传 Kotlin 的操作都走 console 通道，鉴权由 Kotlin 用来源指纹完成（ADR-0007）。

var __rmScriptId = "__RM_SCRIPT_ID__";
var __rmFrameId = "__RM_FRAME_ID__";
var __rmToken = "__RM_TOKEN__";
var __rmStore = __RM_SNAPSHOT__;
var __rmGrants = __RM_GRANTS__;
var __rmInfo = __RM_INFO__;
var __rmResources = __RM_RESOURCES__;
var __rmResourceUrls = __RM_RESOURCE_URLS__;

function __rmGranted(name) {
  return __rmGrants.indexOf(name) >= 0;
}

/** 每次回传都要带上的鉴权字段；集中一处，避免各 API 各写一份。 */
function __rmAuthParts(op) {
  return [
    'scriptId=' + encodeURIComponent(__rmScriptId),
    'frameId=' + encodeURIComponent(__rmFrameId),
    'token=' + encodeURIComponent(__rmToken),
    'op=' + encodeURIComponent(op)
  ];
}

/** 把一组字段发回 Kotlin。失败（例如页面改写了 console.log）不影响脚本本身。 */
function __rmPost(op, extraParts) {
  try {
    var parts = __rmAuthParts(op).concat(extraParts || []);
    console.log('__RM_GM__' + parts.join('&'));
  } catch (e) {
    // 忽略
  }
}

function __rmSend(op, key, valueJson) {
  var parts = ['key=' + encodeURIComponent(key === null || key === undefined ? '' : String(key))];
  if (valueJson !== null && valueJson !== undefined) {
    parts.push('value=' + encodeURIComponent(valueJson));
  }
  __rmPost(op, parts);
}

// ── 存储内核：始终定义，但不直接暴露 ──

function __rmReadValue(key, defaultValue) {
  var k = String(key);
  if (Object.prototype.hasOwnProperty.call(__rmStore, k)) {
    try {
      return JSON.parse(__rmStore[k]);
    } catch (e) {
      return defaultValue;
    }
  }
  return defaultValue;
}

function __rmWriteValue(key, value) {
  var raw = JSON.stringify(value === undefined ? null : value);
  __rmStore[String(key)] = raw;
  __rmSend('set', key, raw);
}

function __rmDeleteValue(key) {
  delete __rmStore[String(key)];
  __rmSend('delete', key, null);
}

function __rmListValues() {
  var out = [];
  for (var k in __rmStore) {
    if (Object.prototype.hasOwnProperty.call(__rmStore, k)) {
      out.push(k);
    }
  }
  return out;
}

function __rmAddStyle(css) {
  var style = document.createElement('style');
  style.textContent = css;
  (document.head || document.documentElement).appendChild(style);
  return style;
}

// ── GM_xmlhttpRequest：请求走 console，响应由 Kotlin 用 executeJavaScript 回调 ──
//
// 投递入口必须挂在 window 上（Kotlin 的 executeJavaScript 只能进页面全局作用域）。
// 用「处理器数组 + 谁认得这个 id 谁处理」避免多脚本互相覆盖（ADR-0009 D4）。

var __rmPendingRequests = {};
var __rmRequestCounter = 0;

// GM_openInTab 的句柄表：tabId 由插件异步回传，因此先占位、后填值。
var __rmOpenTabHandles = {};

window.__rmDeliverHandlers = window.__rmDeliverHandlers || [];

window.__rmDeliver = function (payload) {
  var handlers = window.__rmDeliverHandlers || [];
  for (var i = 0; i < handlers.length; i++) {
    try {
      if (handlers[i](payload) === true) {
        return;
      }
    } catch (e) {
      // 单个处理器出错不应影响其它脚本
    }
  }
};

function __rmBase64ToBytes(text) {
  var binary = atob(text);
  var bytes = new Uint8Array(binary.length);
  for (var i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function __rmTryParseJson(text) {
  try {
    return JSON.parse(text);
  } catch (e) {
    return null;
  }
}

function __rmBuildResponse(data, responseType) {
  var response = {
    readyState: 4,
    status: data.status || 0,
    statusText: '',
    responseText: '',
    response: null,
    responseHeaders: data.responseHeaders || '',
    finalUrl: data.finalUrl || ''
  };
  // GM_download：插件回传落盘位置（这种响应既没有 responseText 也没有 responseBase64）。
  if (typeof data.path === 'string') {
    response.path = data.path;
    response.name = data.name || '';
  }
  if (typeof data.responseBase64 === 'string') {
    var bytes = __rmBase64ToBytes(data.responseBase64);
    // blob 优先：Tampermonkey 在 responseType='blob' 时给 Blob，arraybuffer 时给 ArrayBuffer。
    response.response = responseType === 'blob' ? new Blob([bytes]) : bytes.buffer;
    return response;
  }
  response.responseText = data.responseText || '';
  response.response = responseType === 'json'
    ? __rmTryParseJson(response.responseText)
    : response.responseText;
  return response;
}

window.__rmDeliverHandlers.push(function (payload) {
  var data = null;
  try {
    data = JSON.parse(payload);
  } catch (e) {
    return false;
  }
  if (!data || !data.id) {
    return false;
  }
  var entry = __rmPendingRequests[data.id];
  if (!entry) {
    return false;
  }

  // 进度事件不结束请求：只回调 onprogress（readyState=3）。
  if (data.progress === true) {
    try {
      if (typeof entry.details.onprogress === 'function') {
        entry.details.onprogress({
          readyState: 3,
          status: 0,
          statusText: '',
          lengthComputable: data.total > 0,
          loaded: data.loaded || 0,
          total: data.total > 0 ? data.total : 0
        });
      }
    } catch (e) {
      // 脚本自己的回调抛错不应影响页面
    }
    return true;
  }

  delete __rmPendingRequests[data.id];

  var response = __rmBuildResponse(data, entry.responseType);
  try {
    if (data.error) {
      if (data.timeout && typeof entry.details.ontimeout === 'function') {
        entry.details.ontimeout(response);
      } else if (typeof entry.details.onerror === 'function') {
        entry.details.onerror(response);
      }
    } else if (typeof entry.details.onload === 'function') {
      entry.details.onload(response);
    }
  } catch (e) {
    // 脚本自己的回调抛错不应影响页面
  }
  return true;
});

// GM_openInTab 的句柄回执：只认 tabId 字段，与上面的 xhr 处理器互不干扰
// （tabId 回执的 id 不在 __rmPendingRequests 里，上面的处理器会返回 false 落到这里）。
window.__rmDeliverHandlers.push(function (payload) {
  var data = null;
  try {
    data = JSON.parse(payload);
  } catch (e) {
    return false;
  }
  if (!data || !data.id || typeof data.tabId !== 'number') {
    return false;
  }
  var entry = __rmOpenTabHandles[data.id];
  if (!entry) {
    return false;
  }
  entry.tabId = data.tabId;
  // close() 早于 id 到达时，这里补发关闭请求，避免"关不掉"。
  if (entry.closeRequested && entry.tabId > 0) {
    __rmPost('closeTab', ['tabId=' + encodeURIComponent(String(entry.tabId))]);
  }
  return true;
});

function __rmXhrRequest(details) {
  if (!details || !details.url) {
    throw new Error('GM_xmlhttpRequest: 缺少 url');
  }
  var responseType = details.responseType ? String(details.responseType).toLowerCase() : '';
  if (responseType === 'document' || responseType === 'stream') {
    throw new Error('GM_xmlhttpRequest: 不支持的 responseType ' + responseType);
  }
  var id = 'xhr' + (++__rmRequestCounter);
  var parts = [
    'id=' + encodeURIComponent(id),
    'url=' + encodeURIComponent(String(details.url)),
    'method=' + encodeURIComponent(details.method ? String(details.method).toUpperCase() : 'GET')
  ];
  if (details.data !== undefined && details.data !== null) {
    parts.push('data=' + encodeURIComponent(String(details.data)));
  }
  if (details.timeout) {
    parts.push('timeout=' + encodeURIComponent(String(details.timeout)));
  }
  if (responseType) {
    parts.push('responseType=' + encodeURIComponent(responseType));
  }
  var headers = details.headers || {};
  for (var name in headers) {
    if (Object.prototype.hasOwnProperty.call(headers, name)) {
      parts.push('header.' + encodeURIComponent(name) + '=' + encodeURIComponent(String(headers[name])));
    }
  }
  // 形状变化：从「直接存 details」改为 {details, responseType}，投递处理器同步更新。
  __rmPendingRequests[id] = { details: details, responseType: responseType };
  __rmPost('xhr', parts);
  return {
    abort: function () {
      if (!__rmPendingRequests[id]) {
        return;
      }
      delete __rmPendingRequests[id];
      __rmPost('abort', ['id=' + encodeURIComponent(id)]);
    }
  };
}

function __rmMergeCallbacks(details, onload, onerror) {
  var merged = {};
  if (details) {
    for (var k in details) {
      if (Object.prototype.hasOwnProperty.call(details, k)) {
        merged[k] = details[k];
      }
    }
  }
  if (typeof onload === 'function') {
    merged.onload = onload;
  }
  if (typeof onerror === 'function') {
    merged.onerror = onerror;
  }
  return merged;
}

/** GM_download：交给插件下载并落盘；返回与 GM_xmlhttpRequest 同形的 abort 句柄。 */
function __rmDownload(details) {
  if (!details || !details.url) {
    throw new Error('GM_download: 缺少 url');
  }
  var id = 'dl' + (++__rmRequestCounter);
  var parts = [
    'id=' + encodeURIComponent(id),
    'url=' + encodeURIComponent(String(details.url))
  ];
  if (details.name !== undefined && details.name !== null) {
    parts.push('name=' + encodeURIComponent(String(details.name)));
  }
  var headers = details.headers || {};
  for (var name in headers) {
    if (Object.prototype.hasOwnProperty.call(headers, name)) {
      parts.push('header.' + encodeURIComponent(name) + '=' + encodeURIComponent(String(headers[name])));
    }
  }
  __rmPendingRequests[id] = { details: details, responseType: '' };
  __rmPost('download', parts);
  return {
    abort: function () {
      if (!__rmPendingRequests[id]) {
        return;
      }
      delete __rmPendingRequests[id];
      __rmPost('abort', ['id=' + encodeURIComponent(id)]);
    }
  };
}

/**
 * GM_openInTab：同步返回句柄，tabId 由插件异步回传。
 * 宿主未装配时插件回 tabId=-1，此时 close() 是安全的空操作（不会误关别的标签页）。
 */
function __rmOpenInTab(url, options) {
  var id = 'tab' + (++__rmRequestCounter);
  var active = !(options && options.active === false);
  var entry = { tabId: -1, closeRequested: false };
  __rmOpenTabHandles[id] = entry;

  var handle = {
    closed: false,
    onclose: null,
    close: function () {
      if (handle.closed) {
        return;
      }
      handle.closed = true;
      entry.closeRequested = true;
      if (entry.tabId > 0) {
        __rmPost('closeTab', ['tabId=' + encodeURIComponent(String(entry.tabId))]);
      }
      if (typeof handle.onclose === 'function') {
        try {
          handle.onclose();
        } catch (e) {
          // 脚本自己的回调抛错不应影响页面
        }
      }
    }
  };

  __rmPost('openTab', [
    'id=' + encodeURIComponent(id),
    'url=' + encodeURIComponent(String(url === undefined || url === null ? '' : url)),
    'active=' + (active ? 'true' : 'false')
  ]);
  return handle;
}

// ── 按 @grant 暴露传统同步 API ──

if (__rmGranted('GM_getValue')) {
  var GM_getValue = function (key, defaultValue) {
    return __rmReadValue(key, defaultValue);
  };
}

if (__rmGranted('GM_setValue')) {
  var GM_setValue = function (key, value) {
    __rmWriteValue(key, value);
  };
}

if (__rmGranted('GM_deleteValue')) {
  var GM_deleteValue = function (key) {
    __rmDeleteValue(key);
  };
}

if (__rmGranted('GM_listValues')) {
  var GM_listValues = function () {
    return __rmListValues();
  };
}

if (__rmGranted('GM_addStyle')) {
  var GM_addStyle = function (css) {
    return __rmAddStyle(css);
  };
}

if (__rmGranted('GM_xmlhttpRequest')) {
  var GM_xmlhttpRequest = function (details) {
    return __rmXhrRequest(details);
  };
}

if (__rmGranted('GM_info')) {
  var GM_info = __rmInfo;
}

if (__rmGranted('GM_getResourceText')) {
  var GM_getResourceText = function (name) {
    var value = __rmResources[String(name)];
    return value === undefined ? null : value;
  };
}

if (__rmGranted('GM_getResourceURL')) {
  var GM_getResourceURL = function (name) {
    var value = __rmResourceUrls[String(name)];
    return value === undefined ? null : value;
  };
}

if (__rmGranted('GM_setClipboard')) {
  var GM_setClipboard = function (data) {
    var text = String(data === null || data === undefined ? '' : data);
    __rmPost('clipboard', ['text=' + encodeURIComponent(text)]);
  };
}

// GM_notification 支持两种写法：TM 的 GM_notification(text, title) 与 GM_notification(details)。
if (__rmGranted('GM_notification')) {
  var GM_notification = function (details, title) {
    var text = '';
    var heading = '';
    if (typeof details === 'string' || typeof details === 'number') {
      text = String(details);
      heading = title === undefined || title === null ? '' : String(title);
    } else if (details) {
      text = details.text === undefined ? '' : String(details.text);
      heading = details.title === undefined ? '' : String(details.title);
    }
    __rmPost('notify', [
      'text=' + encodeURIComponent(text),
      'title=' + encodeURIComponent(heading)
    ]);
    // 说明：不支持 onclick 回调（需要把点击事件再回传 JS，属后续工作）。
  };
}

// ── 按 @grant 暴露现代 Promise 风格别名（GM.* 命名）──

var GM = {};

if (__rmGranted('GM.getValue')) {
  GM.getValue = function (key, defaultValue) {
    return Promise.resolve(__rmReadValue(key, defaultValue));
  };
}

if (__rmGranted('GM.setValue')) {
  GM.setValue = function (key, value) {
    __rmWriteValue(key, value);
    return Promise.resolve();
  };
}

if (__rmGranted('GM.deleteValue')) {
  GM.deleteValue = function (key) {
    __rmDeleteValue(key);
    return Promise.resolve();
  };
}

if (__rmGranted('GM.listValues')) {
  GM.listValues = function () {
    return Promise.resolve(__rmListValues());
  };
}

if (__rmGranted('GM.addStyle')) {
  GM.addStyle = function (css) {
    return __rmAddStyle(css);
  };
}

if (__rmGranted('GM.xmlHttpRequest')) {
  GM.xmlHttpRequest = function (details) {
    var handle = null;
    var promise = new Promise(function (resolve, reject) {
      var wrapped = {};
      for (var k in details) {
        if (Object.prototype.hasOwnProperty.call(details, k)) {
          wrapped[k] = details[k];
        }
      }
      wrapped.onload = function (response) { resolve(response); };
      wrapped.onerror = function (response) { reject(response); };
      wrapped.ontimeout = function (response) { reject(response); };
      handle = __rmXhrRequest(wrapped);
    });
    // Tampermonkey 的 GM.xmlHttpRequest 返回的 Promise 上挂着 abort()，这里保持一致。
    promise.abort = function () {
      if (handle && typeof handle.abort === 'function') {
        handle.abort();
      }
    };
    return promise;
  };
}

if (__rmGranted('GM_download')) {
  var GM_download = function (details, onload, onerror) {
    return __rmDownload(__rmMergeCallbacks(details, onload, onerror));
  };
}

if (__rmGranted('GM.download')) {
  GM.download = function (details) {
    var handle = null;
    var promise = new Promise(function (resolve, reject) {
      var wrapped = __rmMergeCallbacks(details, null, null);
      wrapped.onload = function (response) { resolve(response); };
      wrapped.onerror = function (response) { reject(response); };
      handle = __rmDownload(wrapped);
    });
    promise.abort = function () {
      if (handle && typeof handle.abort === 'function') {
        handle.abort();
      }
    };
    return promise;
  };
}

if (__rmGranted('GM_openInTab')) {
  var GM_openInTab = function (url, options) {
    return __rmOpenInTab(url, options);
  };
}

if (__rmGranted('GM.openInTab')) {
  GM.openInTab = function (url, options) {
    return Promise.resolve(__rmOpenInTab(url, options));
  };
}

if (__rmGranted('GM.getResourceText')) {
  GM.getResourceText = function (name) {
    var value = __rmResources[String(name)];
    return Promise.resolve(value === undefined ? null : value);
  };
}

if (__rmGranted('GM.getResourceUrl')) {
  GM.getResourceUrl = function (name) {
    var value = __rmResourceUrls[String(name)];
    return Promise.resolve(value === undefined ? null : value);
  };
}

if (__rmGranted('GM.setClipboard')) {
  GM.setClipboard = function (data) {
    var text = String(data === null || data === undefined ? '' : data);
    __rmPost('clipboard', ['text=' + encodeURIComponent(text)]);
    return Promise.resolve();
  };
}

if (__rmGranted('GM.notification')) {
  GM.notification = function (details, title) {
    var text = '';
    var heading = '';
    if (typeof details === 'string' || typeof details === 'number') {
      text = String(details);
      heading = title === undefined || title === null ? '' : String(title);
    } else if (details) {
      text = details.text === undefined ? '' : String(details.text);
      heading = details.title === undefined ? '' : String(details.title);
    }
    __rmPost('notify', [
      'text=' + encodeURIComponent(text),
      'title=' + encodeURIComponent(heading)
    ]);
    return Promise.resolve();
  };
}
