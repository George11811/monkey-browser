// ==UserScript==
// @name         Hello Monkey Browser
// @namespace    monkey.browser.samples
// @version      2.0
// @description  示例脚本：用 GM 存储统计访问次数，并用 GM_addStyle 注入样式
// @match        https://www.baidu.com/*
// @grant        GM_getValue
// @grant        GM_setValue
// @grant        GM_addStyle
// @run-at       document-end
// ==/UserScript==

// GM_getValue 是同步的：读的是本次注入时嵌入的存储快照。
var visits = GM_getValue('visits', 0) + 1;
GM_setValue('visits', visits);

GM_addStyle(
    '#rm-hello-banner{position:fixed;left:0;right:0;top:0;z-index:2147483647;' +
    'background:#e91e63;color:#fff;font:13px sans-serif;padding:6px 10px;text-align:center;}'
);

var banner = document.createElement('div');
banner.id = 'rm-hello-banner';
banner.textContent = 'Monkey Browser 注入成功 · 本脚本已在百度打开 ' + visits + ' 次（GM 存储）';
document.body.appendChild(banner);

console.log('[Monkey Browser] visits=' + visits);
