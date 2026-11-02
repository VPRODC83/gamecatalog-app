"""Envolve os textos de interface (literais Kotlin) em tr("…", args) para tradução.

Uso: python i18n_wrap.py arquivo.kt [--dry]
"""
import re, sys, json

SKIP_PREFIX = ('http', 'https', '%', '#', '/', 'application/', 'image/', 'text/', 'dd/', 'yyyy')
KEEP_RAW = {"US$", "Game Catalog", "VPRODC83", "Claude Opus 5.5", "Google Lens", "PriceCharting"}


def scan_string(src, i):
    """src[i] == '"' (não raw). Devolve (fim_exclusivo, partes) onde partes = [('txt', s) | ('expr', e)]."""
    assert src[i] == '"'
    j = i + 1
    parts = []
    buf = ''
    while True:
        c = src[j]
        if c == '\\':
            buf += src[j:j + 2]; j += 2; continue
        if c == '"':
            if buf: parts.append(('txt', buf))
            return j + 1, parts
        if c == '$' and j + 1 < len(src) and src[j + 1] == '{':
            if buf: parts.append(('txt', buf)); buf = ''
            k = j + 2; depth = 1
            while depth:
                ch = src[k]
                if ch == '"':
                    if src[k:k + 3] == '"""':
                        k = src.index('"""', k + 3) + 3; continue
                    k, _ = scan_string(src, k); continue
                if ch == '{': depth += 1
                elif ch == '}': depth -= 1
                k += 1
            parts.append(('expr', src[j + 2:k - 1])); j = k; continue
        if c == '$' and j + 1 < len(src) and (src[j + 1].isalpha() or src[j + 1] == '_'):
            if buf: parts.append(('txt', buf)); buf = ''
            m = re.match(r'[A-Za-z_][A-Za-z0-9_]*', src[j + 1:])
            parts.append(('expr', m.group(0))); j += 1 + len(m.group(0)); continue
        buf += c; j += 1


def is_ui(parts, before):
    txt = ''.join(p[1] for p in parts if p[0] == 'txt')
    if not txt.strip():
        return False
    if re.search(r'(tr|header|Regex|@Suppress|@OptIn|getSystemService|setPackage|startsWith|endsWith|contains|optString|optInt|optDouble|put|has|equals|matches|toRegex)\($', before.rstrip()[-30:].rstrip()):
        return False
    if before.rstrip().endswith('tr(') or before.rstrip().endswith('@Suppress('):
        return False
    t = txt.strip()
    if t in KEEP_RAW: return False
    if t.startswith(SKIP_PREFIX): return False
    if '\\\\' in txt or '[^' in txt or '\\s' in txt: return False
    if re.fullmatch(r'[A-Z0-9_ |!:./-]+', t) and t != 'REGIÃO': return False  # códigos e palavras da planilha
    if ' ' in t or re.match(r'[A-ZÀ-Ú‹«©“]', t) or re.search(r'[À-ú]', t):
        return True
    return False


def wrap(parts):
    key = ''; args = []
    for kind, v in parts:
        if kind == 'txt': key += v.replace('{', '{{').replace('}', '}}')
        else: key += '{%d}' % len(args); args.append(v.strip())
    lit = '"' + key + '"'
    return 'tr(' + lit + ''.join(', ' + a for a in args) + ')', key


def process(src):
    out = []; i = 0; keys = []
    in_line_comment = False
    while i < len(src):
        c = src[i]
        if src.startswith('//', i):
            e = src.find('\n', i); e = len(src) if e < 0 else e
            out.append(src[i:e]); i = e; continue
        if src.startswith('/*', i):
            e = src.index('*/', i) + 2; out.append(src[i:e]); i = e; continue
        if src.startswith('"""', i):
            e = src.index('"""', i + 3) + 3; out.append(src[i:e]); i = e; continue
        if c == "'":
            m = re.match(r"'(\\.|[^'\\])'", src[i:])
            if m: out.append(m.group(0)); i += len(m.group(0)); continue
        if c == '"':
            e, parts = scan_string(src, i)
            before = ''.join(out)[-60:]
            line_start = ''.join(out).rsplit('\n', 1)[-1]
            if 'enum class' in line_start or line_start.strip().startswith(('import', 'package', 'const val')) or not is_ui(parts, before):
                out.append(src[i:e])
            else:
                w, k = wrap(parts); out.append(w); keys.append(k)
            i = e; continue
        out.append(c); i += 1
    return ''.join(out), keys


if __name__ == '__main__':
    allkeys = []
    for p in sys.argv[1:]:
        if p.startswith('--'): continue
        s = open(p).read()
        s = re.sub(r'\b((?:[\w!]+\.)*lista\.titulo|\bt\.titulo|\btab\.titulo)\b', r'tr(\1)', s) if 'Splash' not in p else s
        s = s.replace('tr(tr(', 'tr(')
        new, keys = process(s)
        allkeys += keys
        if '--dry' not in sys.argv: open(p, 'w').write(new)
    print(json.dumps(sorted(set(allkeys)), ensure_ascii=False, indent=0))
