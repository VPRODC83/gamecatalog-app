"""Catálogo de nomes dos consoles modernos (melhoria 22), tirado do Wikidata (dados livres, CC0).

O libretro (catálogo principal) quase não tem PS4/PS5/Switch/Xbox One/Series. Este script pergunta ao
Wikidata quais jogos saíram em cada um e grava assets/modern.json.gz no mesmo formato do catalog.json.

python tools/build_modern.py android/app/src/main/assets/modern.json.gz
"""
import csv, gzip, io, json, os, re, sys, time, urllib.parse, urllib.request

# código usado no app → (item do Wikidata, nome para mostrar)
PLATAFORMAS = {
    "@PS5": ("Q63184502", "PlayStation 5"),
    "@PS4": ("Q5014725", "PlayStation 4"),
    "@NSW2": ("Q122761124", "Nintendo Switch 2"),
    "@NSW": ("Q19610114", "Nintendo Switch"),
    "@XSX": ("Q98973368", "Xbox Series X|S"),
    "@XONE": ("Q13361286", "Xbox One"),
}
UA = "GameCatalog/1.0 (+https://github.com/VPRODC83/gamecatalog-app)"
# 1º a lista de jogos (consulta leve); 2º os nomes, em lotes, para o servidor não cortar a resposta no meio.
# Muitos itens só têm o rótulo "mul" (vale para todas as línguas); o título do artigo em inglês é o último recurso.
Q_IDS = """SELECT ?g (MIN(?d) AS ?date) WHERE {
  ?g wdt:P400 wd:%s ; wdt:P31/wdt:P279* wd:Q7889 .
  OPTIONAL { ?g wdt:P577 ?d }
} GROUP BY ?g"""
Q_NOMES = """SELECT ?g (SAMPLE(?en0) AS ?en) (SAMPLE(?mul0) AS ?mul) (SAMPLE(?pt0) AS ?pt) (SAMPLE(?wp0) AS ?wp) WHERE {
  VALUES ?g { %s }
  OPTIONAL { ?g rdfs:label ?en0 FILTER(LANG(?en0)="en") }
  OPTIONAL { ?g rdfs:label ?mul0 FILTER(LANG(?mul0)="mul") }
  OPTIONAL { ?g rdfs:label ?pt0 FILTER(LANG(?pt0)="pt-br") }
  OPTIONAL { ?a schema:about ?g ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?wp0 }
} GROUP BY ?g"""


def sparql(q):
    data = urllib.parse.urlencode({"query": q}).encode()
    for tent in range(5):
        try:
            req = urllib.request.Request("https://query.wikidata.org/sparql", data=data,
                                         headers={"Accept": "text/csv", "User-Agent": UA, "Content-Type": "application/x-www-form-urlencoded"})
            with urllib.request.urlopen(req, timeout=120) as r:
                txt = r.read().decode("utf8")
            if "java.util.concurrent.TimeoutException" in txt or "QueryTimeoutException" in txt:
                raise RuntimeError("tempo esgotado no servidor")
            return list(csv.DictReader(io.StringIO(txt)))
        except Exception as e:
            print("  tentativa", tent + 1, "falhou:", e, file=sys.stderr); time.sleep(10 * (tent + 1))
    raise SystemExit("Wikidata não respondeu")


def ask(qid):
    ids = sparql(Q_IDS % qid)
    datas = {r["g"].rsplit("/", 1)[-1]: (r.get("date") or "")[:10] for r in ids}
    qs = sorted(datas)
    rows = []
    for i in range(0, len(qs), 250):
        lote = sparql(Q_NOMES % " ".join("wd:" + q for q in qs[i:i + 250]))
        if len(lote) < len(qs[i:i + 250]) * 0.95:
            raise SystemExit("resposta incompleta do Wikidata (%d de %d)" % (len(lote), len(qs[i:i + 250])))
        for r in lote:
            r["date"] = datas.get(r["g"].rsplit("/", 1)[-1], "")
        rows += lote
        time.sleep(0.5)
    return rows


def main(out):
    repos = []
    for code, (qid, nome) in PLATAFORMAS.items():
        rows = ask(qid)
        best = {}
        for r in rows:
            d = (r.get("date") or "")[:10]
            wp = re.sub(r"\s*\((?:[^)]*video game[^)]*|[^)]*\d{4}[^)]*)\)$", "", (r.get("wp") or "").strip())
            main = (r.get("en") or "").strip() or (r.get("mul") or "").strip() or wp
            for n in (main, r.get("pt"), wp if wp != main else None):
                n = (n or "").strip()
                if not n or re.fullmatch(r"Q\d+", n): continue
                k = n.lower()
                if k not in best or (d and (not best[k][1] or d < best[k][1])): best[k] = (n, d)
        items = sorted(best.values(), key=lambda x: x[0].lower())
        repos.append({"repo": code, "nome": nome, "sha": "", "names": [n for n, _ in items], "datas": [d for _, d in items]})
        print(code, nome, len(items), "nomes", file=sys.stderr)
        time.sleep(2)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wt", encoding="utf8", compresslevel=9) as f:
        json.dump({"fonte": "Wikidata (CC0)", "repos": repos}, f, ensure_ascii=False, separators=(",", ":"))
    print(out, sum(len(r["names"]) for r in repos), "nomes", os.path.getsize(out) // 1024, "KB", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])
