"""Gera o catálogo de nomes embutido no app Android a partir dos índices do libretro.

python tools/build_catalog.py <pasta com os .json de build_libretro.py> android/app/src/main/assets/catalog.json.gz
"""
import gzip
import json
import os
import sys


def main(src, out):
    index = json.load(open(os.path.join(src, "index.json"), encoding="utf8"))
    repos = []
    for it in index:
        d = json.load(open(os.path.join(src, it["repo"] + ".json"), encoding="utf8"))
        repos.append({"repo": it["repo"], "nome": it["nome"], "sha": d["sha"], "names": d["boxarts"]})
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wt", encoding="utf8", compresslevel=9) as f:
        json.dump({"repos": repos}, f, ensure_ascii=False, separators=(",", ":"))
    print(out, sum(len(r["names"]) for r in repos), "nomes", os.path.getsize(out) // 1024, "KB")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
