"""Gera o índice de códigos de produto (serial) → jogo usado pela identificação exata (melhoria 15b).

python tools/build_serials.py <pasta do libretro-database> android/app/src/main/assets/serials.json.gz

Lê metadat/serial/*.dat (cartuchos: SHVC-MW, SNS-MW, NUS-NSME…) e metadat/redump/*.dat (discos: SLUS-00594…).
Grava {"repos": [acervo libretro], "s": {CÓDIGO_NORMALIZADO: [índice do acervo, título]}}.
"""
import gzip, json, os, re, sys

REGION = re.compile(r"-(USA|JPN|EUR|NOE|FRA|ESP|ITA|UKV|AUS|KOR|CHN|BRA|FAH|SCN|HOL|FRG|SWE|CAN|ASI|UK)$")


def norm(s):
    return re.sub(r"[^A-Z0-9]", "", s.upper())


BAD = re.compile(r"\[|\((Beta|Proto|Demo|Sample|Arcade|Hack|Pirate|Unl|Kiosk|Rev [B-Z])", re.I)


def rank(name):
    """Prefere nomes limpos e regiões comuns (menor = melhor)."""
    r = 10 if BAD.search(name) else 0
    for i, reg in enumerate(("(USA", "(World", "(Brazil", "(Europe", "(Japan")):
        if reg in name:
            return r + i
    return r + 6


def games(path):
    txt = open(path, encoding="utf-8", errors="replace").read()
    for block in re.findall(r"^game \($(.*?)^\)$", txt, re.M | re.S):
        name = re.search(r'^\s*(?:name|comment) "([^"]+)"', block, re.M)
        for s in set(re.findall(r'serial "([^"]+)"', block)):
            if name:
                yield s, name.group(1)


def main(db, out):
    repos, idx, table = [], {}, {}
    for sub in ("serial", "redump"):
        d = os.path.join(db, "metadat", sub)
        for f in sorted(os.listdir(d)):
            if not f.endswith(".dat"):
                continue
            repo = f[:-4].replace(" ", "_")
            for serial, name in games(os.path.join(d, f)):
                for part in re.split(r"[,/]\s*", serial):
                    part = part.strip().upper()
                    if len(part) < 4:
                        continue
                    if repo not in idx:
                        idx[repo] = len(repos); repos.append(repo)
                    segs = [x for x in re.split(r"[- ]", part) if x]
                    keys = {norm(part)} | {norm("-".join(segs[:k])) for k in range(2, len(segs) + 1)}
                    for key in keys:
                        if len(key) < 4:
                            continue
                        old = table.get(key)
                        if old is None or rank(name) < rank(old[1]):
                            table[key] = [idx[repo], name]
    raw = json.dumps({"repos": repos, "s": table}, ensure_ascii=False, separators=(",", ":")).encode()
    with gzip.open(out, "wb", 9) as g:
        g.write(raw)
    print(len(repos), "acervos,", len(table), "códigos,", len(raw), "bytes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
