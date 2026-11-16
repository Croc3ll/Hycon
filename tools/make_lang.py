"""Writes the panel's language files (src/main/resources/web/lang/<code>.json) through DeepL.

    py tools/make_lang.py          every language
    py tools/make_lang.py de       one language

Same languages as the hytaleserver.xyz site. The files are keyed by the English wording, like the site's
locales/*.json. This collects every quoted English string in the panel's scripts and page, in Hycon's
Java (server messages, built-in tabs) and in the plugins that add tabs (OTHER_JAVA), so rerun it after
changing panel text. A sentence built around a value ('Last ' + days + ' days', String.format("%d kills"))
becomes a template ("Last {0} days") that i18n.js matches at runtime. {n} placeholders are kept. Translations are cached in build/lang-cache.json, so a rerun only pays for new
lines. The DeepL key comes from the DEEPL_KEY variable or the file named by DEEPL_KEY_FILE (never committed).
"""
import json, os, re, sys, time, urllib.parse, urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
WEB = os.path.join(ROOT, 'src/main/resources/web')
JAVA = os.path.join(ROOT, 'src/main/java/com/hycon')
CACHE = os.path.join(ROOT, 'build/lang-cache.json')
# Panel language -> DeepL target. Portuguese follows most Hytale players: Brazilian.
TARGETS = {'es': 'ES', 'de': 'DE', 'fr': 'FR', 'pt': 'PT-BR', 'pl': 'PL', 'ru': 'RU', 'it': 'IT', 'nl': 'NL', 'tr': 'TR'}
SCRIPTS = ['app.js', 'graph.js', 'heatmap.js', 'replay.js', 'replay3d.js', 'globe.js']
STRING = re.compile(r"'((?:[^'\\\n]|\\.)*)'|\"((?:[^\"\\\n]|\\.)*)\"")
# Plugins whose Hycon tabs and profile sections send their own wording to the panel.
OTHER_JAVA = [os.path.join(ROOT, '..', p) for p in (
    'HyPrison/HyPrisons/src/main/java/com/prisoncore/analytics/hycon', 'HyconAntiCheat/src/main/java/com/hycon/anticheat')]
JAVA_STRING = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
# A value joined into a sentence: a name, field, index or call without quotes inside.
EXPR = r'[\w.$\[\]]+(?:\([^()\'"]*\))?'
# Java lines whose text never reaches the panel: logs, in-game chat, Discord, HTTP headers, comments and annotations.
NOT_PANEL = re.compile(r'^.*(?:\.log\(|\.at\(Level|LOGGER|sendMessage|Message\.raw|Message\.translation|discord|Discord'
                       r'|setHeader|getResponseHeaders|\.add\("[A-Z][\w-]+",|^\s*(?://|\*|/\*|@)).*$', re.M)
FORMAT = re.compile(r'%[-#+0,(]*\d*(?:\.\d+)?[sdf]')
HTML_TEXT = re.compile(r'>([^<>]*[A-Za-z][^<>]*)<')
KEEP = re.compile(r'\{\w+\}|https?://\S+|/[a-z][a-z0-9_]*(?: [a-z0-9_<>]+)*')
BATCH = 40


def looks_english(s):
    """Wording a person reads, not an id, CSS class, URL, selector or level name."""
    t = re.sub(r'\{\d\}', '', s).strip()
    if len(t) < 2 or not re.search('[A-Za-z]{2}', t):
        return False
    if re.search(r'[<>={}$\\]|^[#./]|https?:|\.(js|css|json|svg|png)\b|^--|=>', t):
        return False
    if re.fullmatch(r'[A-Z0-9_]+', t) or re.fullmatch(r'[a-z0-9_:-]+', t):
        return False  # LEVEL names and lower-case ids/classes
    if re.fullmatch(r'[a-z0-9 -]+', t) and len(t.split()) <= 3 or re.search(r'\d(px|ms)\b', t):
        return False  # CSS class lists and sizes
    return t[0].isupper() or ' ' in t


def unescape(text):
    return text.replace("\\'", "'").replace('\\"', '"')


def chains(src, q):
    """Sentences glued from string literals (quote q) and values, as templates with {0}, {1}..."""
    lit = q + r'((?:[^' + q + r'\\\n]|\\.)*)' + q
    chain = re.compile(r'(?:' + EXPR + r'\s*\+\s*)?' + lit + r'(?:\s*\+\s*(?:' + lit + '|' + EXPR + '))+')
    for m in chain.finditer(src):
        out, slot = '', 0
        for text, value in re.findall(lit + '|(' + EXPR + ')', m.group(0)):
            if value:
                out += '{%d}' % slot
                slot += 1
            else:
                out += unescape(text)
        if slot:
            yield out


def java_text(src):
    src = NOT_PANEL.sub('', src)
    for m in JAVA_STRING.finditer(src):
        slot = iter(range(99))
        text = FORMAT.sub(lambda _: '{%d}' % next(slot), unescape(m.group(1))).replace('%%', '%')
        if not re.match(r'(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|PRAGMA)\b', text):
            yield text
    yield from chains(src, '"')


def collect():
    found = []
    for name in SCRIPTS:
        src = re.sub(r'^\s*//.*$', '', open(os.path.join(WEB, name), encoding='utf-8').read(), flags=re.M)
        found += [unescape(m.group(1) if m.group(1) is not None else m.group(2)) for m in STRING.finditer(src)]
        found += chains(src, "'")
    found += HTML_TEXT.findall(open(os.path.join(WEB, 'index.html'), encoding='utf-8').read())
    for root in [JAVA] + OTHER_JAVA:
        for folder, _, files in os.walk(root):
            for f in files:
                if f.endswith('.java'):
                    found += java_text(open(os.path.join(folder, f), encoding='utf-8').read())
    return sorted({t.strip() for t in found if looks_english(t)})


def deepl_key():
    key = os.environ.get('DEEPL_KEY')
    if not key and os.environ.get('DEEPL_KEY_FILE'):
        for line in open(os.environ['DEEPL_KEY_FILE'], encoding='utf-8'):
            if '=' in line and 'key' in line.split('=', 1)[0].lower():
                key = line.split('=', 1)[1].strip()
    if not key:
        sys.exit('Set DEEPL_KEY or DEEPL_KEY_FILE')
    return key


def protect(text):
    return KEEP.sub(lambda m: '<x>' + m.group(0) + '</x>', text.replace('&', '&amp;').replace('<', '&lt;'))


def unprotect(text):
    text = re.sub(r'<x>(.*?)</x>', lambda m: m.group(1), text)
    return text.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')


def call_deepl(key, texts, target):
    host = 'https://api-free.deepl.com' if key.endswith(':fx') else 'https://api.deepl.com'
    form = [('text', protect(t)) for t in texts] + [
        ('source_lang', 'EN'), ('target_lang', target), ('tag_handling', 'xml'), ('ignore_tags', 'x'),
        ('context', 'Short labels and messages in a game server admin web panel (bans, reports, players, chat).')]
    request = urllib.request.Request(host + '/v2/translate', urllib.parse.urlencode(form).encode(),
                                     {'Authorization': 'DeepL-Auth-Key ' + key})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return [unprotect(t['text']) for t in json.load(response)['translations']]
        except urllib.error.HTTPError as e:
            if e.code not in (429, 503) or attempt == 4:
                raise
            time.sleep(2 ** attempt)


def main():
    langs = sys.argv[1:] or list(TARGETS)
    texts = collect()
    cache = json.load(open(CACHE, encoding='utf-8')) if os.path.exists(CACHE) else {}
    key = None
    os.makedirs(os.path.join(WEB, 'lang'), exist_ok=True)
    for lang in langs:
        done = cache.setdefault(lang, {})
        todo = [t for t in texts if t not in done]
        if todo:
            key = key or deepl_key()
            for i in range(0, len(todo), BATCH):
                part = todo[i:i + BATCH]
                done.update(zip(part, call_deepl(key, part, TARGETS[lang])))
            os.makedirs(os.path.dirname(CACHE), exist_ok=True)
            json.dump(cache, open(CACHE, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
        # A translation that lost or added a {n} would show the wrong values: keep the English for it.
        slots = lambda s: sorted(re.findall(r'\{\d\}', s))
        out = {t: done[t] for t in texts if done[t] != t and slots(done[t]) == slots(t)}
        with open(os.path.join(WEB, 'lang', lang + '.json'), 'w', encoding='utf-8', newline='\n') as f:
            json.dump(out, f, ensure_ascii=False, indent=1, sort_keys=True)
            f.write('\n')
        print(f'{lang}: {len(out)} lines ({len(todo)} new)')


if __name__ == '__main__':
    main()
