"""Gera os índices de nomes de capas do libretro-thumbnails usados pelo app.

python tools/build_libretro.py dist/data/libretro

Para cada console, clona só a árvore do repositório (sem baixar imagens) e grava
<repo>.json = {"sha": commit, "boxarts": [nomes]}. O app monta o endereço da imagem
em raw.githubusercontent.com com esse commit, então a capa mostrada é sempre a mesma.
"""
import json
import os
import subprocess
import sys
import tempfile

REPOS = {
    "Atari_-_2600": "Atari 2600", "Magnavox_-_Odyssey2": "Odyssey² / Videopac", "Microsoft_-_MSX": "MSX",
    "Microsoft_-_Xbox": "Xbox", "Microsoft_-_Xbox_360": "Xbox 360", "NEC_-_PC_Engine_-_TurboGrafx_16": "PC Engine / TurboGrafx-16",
    "Nintendo_-_Game_Boy": "Game Boy", "Nintendo_-_Game_Boy_Advance": "Game Boy Advance", "Nintendo_-_Game_Boy_Color": "Game Boy Color",
    "Nintendo_-_GameCube": "GameCube", "Nintendo_-_Nintendo_3DS": "Nintendo 3DS", "Nintendo_-_Nintendo_64": "Nintendo 64",
    "Nintendo_-_Nintendo_DS": "Nintendo DS", "Nintendo_-_Nintendo_Entertainment_System": "NES / Famicom",
    "Nintendo_-_Super_Nintendo_Entertainment_System": "Super Nintendo / Super Famicom", "Nintendo_-_Wii": "Wii", "Nintendo_-_Wii_U": "Wii U",
    "SNK_-_Neo_Geo": "Neo Geo", "Sega_-_Dreamcast": "Dreamcast", "Sega_-_Game_Gear": "Game Gear", "Sega_-_Master_System_-_Mark_III": "Master System",
    "Sega_-_Mega-CD_-_Sega_CD": "Sega CD / Mega-CD", "Sega_-_Mega_Drive_-_Genesis": "Mega Drive / Genesis", "Sega_-_Saturn": "Saturn",
    "Sony_-_PlayStation": "PlayStation", "Sony_-_PlayStation_2": "PlayStation 2", "Sony_-_PlayStation_3": "PlayStation 3",
    "Sony_-_PlayStation_4": "PlayStation 4", "Sony_-_PlayStation_Portable": "PSP",
}


def main(out):
    os.makedirs(out, exist_ok=True)
    index = []
    with tempfile.TemporaryDirectory() as tmp:
        for repo, nome in REPOS.items():
            d = os.path.join(tmp, repo)
            r = subprocess.run(["git", "clone", "-q", "--filter=blob:none", "--no-checkout", "--depth", "1",
                                f"https://github.com/libretro-thumbnails/{repo}", d])
            if r.returncode:
                print("falhou", repo, flush=True)
                continue
            sha = subprocess.run(["git", "-C", d, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
            names = subprocess.run(["git", "-C", d, "ls-tree", "-r", "--name-only", "HEAD", "--", "Named_Boxarts"],
                                   capture_output=True, text=True, check=True).stdout.splitlines()
            names = sorted(x[len("Named_Boxarts/"):-4] for x in names if x.endswith(".png"))
            json.dump({"sha": sha, "boxarts": names}, open(os.path.join(out, repo + ".json"), "w", encoding="utf8"),
                      ensure_ascii=False, separators=(",", ":"))
            index.append({"repo": repo, "nome": nome, "n": len(names)})
            print(repo, len(names), flush=True)
    index.sort(key=lambda x: x["nome"])
    json.dump(index, open(os.path.join(out, "index.json"), "w", encoding="utf8"), ensure_ascii=False, indent=0)


if __name__ == "__main__":
    main(sys.argv[1])
