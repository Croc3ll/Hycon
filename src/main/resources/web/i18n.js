// Panel translations, the way the hytaleserver.xyz site does them: each fixed piece of English text is looked up
// in lang/<code>.json, keyed by its English wording. A missing entry stays English, so new text never breaks a page.
// The panel builds its pages in many files, so instead of wrapping every string this watches the page and swaps
// text as it appears. The English is kept per node, so switching language re-translates everything in place.
'use strict';

const LANGS = {
  en: 'English', es: 'Español', de: 'Deutsch', fr: 'Français', pt: 'Português', pl: 'Polski',
  ru: 'Русский', it: 'Italiano', nl: 'Nederlands', tr: 'Türkçe',
};
const LANG_KEY = 'hycon_lang';
const ATTRS = ['placeholder', 'title', 'aria-label', 'alt'];
// Inside these the text is data or code, never panel wording.
const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'INPUT', 'CODE', 'PRE']);

// english: a text node's English. shown: what we last wrote there, so our own text is never taken for new English.
const I18N = { lang: 'en', dict: {}, english: new WeakMap(), shown: new WeakMap(), loading: 0, templates: [] };

function pickLang() {
  let saved = null;
  try { saved = localStorage.getItem(LANG_KEY); } catch (e) { /* storage blocked: use the browser's language */ }
  if (saved in LANGS) return saved;
  for (const l of navigator.languages || [navigator.language || 'en']) {
    const code = String(l).slice(0, 2).toLowerCase();
    if (code in LANGS) return code;
  }
  return 'en';
}

// Keeps the spaces around a fragment ("Log in as ", name, " with...") and translates the words between.
function tr(text) {
  if (I18N.lang === 'en' || !text) return text;
  const m = /^(\s*)([\s\S]*?)(\s*)$/.exec(text);
  const hit = I18N.dict[m[2]] || fromTemplate(m[2]);
  return hit ? m[1] + hit + m[3] : text;
}

// "Last 7 days" matches the entry "Last {0} days": the values are put back into the translation.
function fromTemplate(text) {
  for (const t of I18N.templates) {
    const got = t.match.exec(text);
    if (got) return t.out.replace(/\{(\d)\}/g, (s, i) => (got[+i + 1] !== undefined ? got[+i + 1] : s));
  }
  return null;
}

// Longest first, so "Last {0} days of {1}" wins over "Last {0}".
function compileTemplates(dict) {
  return Object.keys(dict).filter((k) => /\{\d\}/.test(k) && /[A-Za-z]{2}/.test(k.replace(/\{\d\}/g, '')))
    .sort((a, b) => b.length - a.length)
    .map((k) => {
      const order = [];
      const body = k.split(/(\{\d\})/).map((p) => {
        const slot = /^\{(\d)\}$/.exec(p);
        if (!slot) return p.replace(/[.*+?^$()|[\]\\{}]/g, '\\$&');
        order.push(+slot[1]);
        return '([\\s\\S]+?)';
      }).join('');
      // Captures come in the order they appear; map them back to their {n}.
      const re = new RegExp('^' + body + '$');
      return { match: { exec: (s) => { const g = re.exec(s); if (!g) return null;
        const byIndex = []; order.forEach((n, i) => { byIndex[n + 1] = g[i + 1]; }); return byIndex; } }, out: dict[k] };
    });
}

// Alerts, confirms and prompts aren't page text, so the watcher never sees them.
for (const name of ['alert', 'confirm', 'prompt']) {
  const native = window[name].bind(window);
  window[name] = (msg, ...rest) => native(tr(String(msg ?? '')), ...rest);
}

function translateText(node) {
  if (node.parentNode && SKIP.has(node.parentNode.nodeName)) return;
  let english = I18N.english.get(node);
  // The app changed the text since we last translated it: what it holds now is the new English.
  if (english === undefined || node.data !== I18N.shown.get(node)) english = node.data;
  if (!english.trim()) return;
  const out = tr(english);
  I18N.english.set(node, english);
  I18N.shown.set(node, out);
  if (node.data !== out) node.data = out;
}

function translateAttrs(elm) {
  for (const a of ATTRS) {
    if (!elm.hasAttribute(a)) continue;
    const key = 'data-en-' + a;
    const now = elm.getAttribute(a);
    let english = elm.getAttribute(key);
    if (english === null || now !== elm.getAttribute('data-shown-' + a)) english = now;
    const out = tr(english);
    elm.setAttribute(key, english);
    elm.setAttribute('data-shown-' + a, out);
    if (now !== out) elm.setAttribute(a, out);
  }
}

function translateTree(root) {
  if (root.nodeType === Node.TEXT_NODE) return translateText(root);
  if (root.nodeType !== Node.ELEMENT_NODE || SKIP.has(root.nodeName)) return;
  translateAttrs(root);
  const walk = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT);
  for (let n = walk.nextNode(); n; n = walk.nextNode()) {
    if (n.nodeType === Node.TEXT_NODE) translateText(n);
    else if (!SKIP.has(n.nodeName)) translateAttrs(n);
  }
}

const watcher = new MutationObserver((records) => {
  for (const r of records) {
    if (r.type === 'childList') r.addedNodes.forEach(translateTree);
    else if (r.type === 'characterData') translateText(r.target);
    else if (r.type === 'attributes') translateAttrs(r.target);
  }
});

async function setLang(code) {
  if (!(code in LANGS)) code = 'en';
  try { localStorage.setItem(LANG_KEY, code); } catch (e) { /* storage blocked: the pick lasts this visit */ }
  const ticket = ++I18N.loading;
  let dict = {};
  if (code !== 'en') {
    try { dict = await (await fetch('lang/' + code + '.json', { credentials: 'same-origin' })).json(); } catch (e) { dict = {}; }
  }
  if (ticket !== I18N.loading) return; // a later pick arrived first; it wins
  I18N.lang = code;
  I18N.dict = dict;
  I18N.templates = compileTemplates(dict);
  document.documentElement.lang = code;
  translateTree(document.body);
  dispatchEvent(new Event('hycon-lang')); // widths changed; the tab pill re-measures
}

function langPicker() {
  const sel = document.createElement('select');
  sel.id = 'lang';
  sel.setAttribute('aria-label', 'Language');
  for (const [code, name] of Object.entries(LANGS)) sel.append(new Option(name, code, false, code === I18N.lang));
  sel.addEventListener('change', () => setLang(sel.value));
  const box = document.createElement('label');
  box.className = 'langbox';
  box.title = 'Language';
  box.innerHTML = GLOBE; // our own fixed markup, never server data
  box.append(sel);
  return box;
}

// A globe, so the picker reads as "language" whatever language the page is in.
const GLOBE = '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" '
  + 'aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9'
  + 'M12 3c-2.5 2.6-3.8 5.6-3.8 9s1.3 6.4 3.8 9"/></svg>';

addEventListener('DOMContentLoaded', () => {
  I18N.lang = pickLang();
  document.querySelector('header').insertBefore(langPicker(), document.getElementById('me'));
  watcher.observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  setLang(I18N.lang);
});
