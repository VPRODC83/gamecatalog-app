"""Monta assets/i18n/<lang>.json a partir de keys.txt + tr/<lang>.json (lista na mesma ordem)."""
import json, re, sys, os, glob
S = os.path.dirname(os.path.abspath(__file__))
keys = [l.rstrip('\n') for l in open(os.path.join(S, 'keys.txt'), encoding='utf-8')]
out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
ok = True
for f in sorted(glob.glob(os.path.join(S, '*.json'))):
    code = os.path.basename(f)[:-5]
    vals = json.load(open(f, encoding='utf-8'))
    if isinstance(vals, dict):  # só as diferenças (ex.: en-GB): {"número": "texto"}
        pairs = {keys[int(n) - 1]: v for n, v in vals.items()}
    else:
        if len(vals) != len(keys):
            print(code, 'ERRO: tem', len(vals), 'esperado', len(keys)); ok = False; continue
        pairs = dict(zip(keys, vals))
    res = {}
    for k, v in pairs.items():
        ph_k = sorted(re.findall(r'\{\d\}', k)); ph_v = sorted(re.findall(r'\{\d\}', v))
        if ph_k != ph_v:
            print(code, 'PLACEHOLDER', repr(k), '->', repr(v)); ok = False
        lead = k[:len(k) - len(k.lstrip())]; trail = k[len(k.rstrip()):]
        res[k] = lead + v.strip() + trail
    json.dump(res, open(os.path.join(out_dir, code + '.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=0, sort_keys=True)
    print(code, len(res))
sys.exit(0 if ok else 1)
