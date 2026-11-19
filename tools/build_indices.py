"""Escolhe e baixa as imagens dos pacotes de reconhecimento por imagem (melhoria 24).

Fonte: LaunchBox Games DB (Metadata.zip, gratuito): fotos/scans de cartucho ("Cart - Front"), de disco
("Disc") e de capa ("Box - Front"), com a região. As imagens são baixadas, reduzidas e passadas ao
montador (Kotlin), que guarda só os pontos característicos; as imagens em si não vão para o app.

python tools/build_indices.py listar <pasta>          → <pasta>/listas/<acervo>.json e <pasta>/acervos.json
python tools/build_indices.py baixar <pasta> <acervo>  → <pasta>/<acervo>/img/*.jpg e <pasta>/<acervo>/lista.json
"""
import concurrent.futures as cf, io, json, os, re, sys, urllib.request, xml.etree.ElementTree as ET, zipfile
from PIL import Image

UA = "GameCatalog/1.0 (+https://github.com/VPRODC83/gamecatalog-app)"
PX = 480

# plataforma do LaunchBox → acervo usado no app (mesmos nomes do catálogo)
PLATAFORMAS = {
    "Super Nintendo Entertainment System": "Nintendo_-_Super_Nintendo_Entertainment_System",
    "Nintendo Entertainment System": "Nintendo_-_Nintendo_Entertainment_System",
    "Nintendo Famicom Disk System": "Nintendo_-_Nintendo_Entertainment_System",
    "Nintendo 64": "Nintendo_-_Nintendo_64",
    "Nintendo Game Boy": "Nintendo_-_Game_Boy",
    "Nintendo Game Boy Color": "Nintendo_-_Game_Boy_Color",
    "Nintendo Game Boy Advance": "Nintendo_-_Game_Boy_Advance",
    "Nintendo DS": "Nintendo_-_Nintendo_DS",
    "Nintendo 3DS": "Nintendo_-_Nintendo_3DS",
    "Nintendo GameCube": "Nintendo_-_GameCube",
    "Nintendo Wii": "Nintendo_-_Wii",
    "Nintendo Wii U": "Nintendo_-_Wii_U",
    "Nintendo Switch": "@NSW",
    "Sega Genesis": "Sega_-_Mega_Drive_-_Genesis",
    "Sega Master System": "Sega_-_Master_System_-_Mark_III",
    "Sega Game Gear": "Sega_-_Game_Gear",
    "Sega CD": "Sega_-_Mega-CD_-_Sega_CD",
    "Sega Saturn": "Sega_-_Saturn",
    "Sega Dreamcast": "Sega_-_Dreamcast",
    "Atari 2600": "Atari_-_2600",
    "Magnavox Odyssey 2": "Magnavox_-_Odyssey2",
    "NEC TurboGrafx-16": "NEC_-_PC_Engine_-_TurboGrafx_16",
    "SNK Neo Geo AES": "SNK_-_Neo_Geo",
    "Microsoft MSX": "Microsoft_-_MSX",
    "Sony Playstation": "Sony_-_PlayStation",
    "Sony Playstation 2": "Sony_-_PlayStation_2",
    "Sony Playstation 3": "Sony_-_PlayStation_3",
    "Sony Playstation 4": "@PS4",
    "Sony Playstation 5": "@PS5",
    "Sony PSP": "Sony_-_PlayStation_Portable",
    "Microsoft Xbox": "Microsoft_-_Xbox",
    "Microsoft Xbox 360": "Microsoft_-_Xbox_360",
}
TIPOS = {"Cart - Front": "midia", "Disc": "midia", "Box - Front": "capa"}
POR_GRUPO = 1  # uma imagem por jogo, região e tipo (cartucho/disco ou capa)


def regiao(r):
    r = (r or "").lower()
    if "brazil" in r: return "BR"
    if "japan" in r: return "JP"
    if r in ("north america", "united states", "canada", "usa"): return "US"
    if any(k in r for k in ("europe", "kingdom", "germany", "france", "spain", "italy", "netherlands", "sweden", "australia")): return "EU"
    if "korea" in r: return "KR"
    if "china" in r: return "CN"
    if "asia" in r or "hong kong" in r or "taiwan" in r: return "ASIA"
    if "world" in r: return "WORLD"
    return ""


def baixar(url, dest):
    if os.path.exists(dest): return True
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=60) as r: b = r.read()
        im = Image.open(io.BytesIO(b)).convert("RGBA")
        bg = Image.new("RGBA", im.size, (255, 255, 255, 255)); bg.alpha_composite(im)
        im = bg.convert("RGB"); im.thumbnail((PX, PX)); im.save(dest, quality=90)
        return True
    except Exception as e:
        print("  falhou", url, e, file=sys.stderr); return False


def safe(acervo): return re.sub(r"[^A-Za-z0-9_.-]", "_", acervo.replace("@", "AT_"))


def listar(work):
    os.makedirs(os.path.join(work, "listas"), exist_ok=True)
    mz = os.path.join(work, "Metadata.zip")
    if not os.path.exists(mz):
        req = urllib.request.Request("https://gamesdb.launchbox-app.com/Metadata.zip", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=600) as r, open(mz, "wb") as f: f.write(r.read())
    games, imgs = {}, {}
    with zipfile.ZipFile(mz) as z, z.open("Metadata.xml") as fx:
        for _, el in ET.iterparse(fx):
            if el.tag == "Game":
                p = el.findtext("Platform")
                if p in PLATAFORMAS: games[el.findtext("DatabaseID")] = (el.findtext("Name"), p)
                el.clear()
            elif el.tag == "GameImage":
                t = el.findtext("Type")
                if t in TIPOS: imgs.setdefault(el.findtext("DatabaseID"), []).append((t, el.findtext("Region"), el.findtext("FileName")))
                el.clear()
    por_acervo = {}
    for gid, (nome, plat) in games.items():
        acervo = PLATAFORMAS[plat]; grupos = {}
        for t, reg, fn in imgs.get(gid, []):
            k = (TIPOS[t], regiao(reg))
            if len(grupos.setdefault(k, [])) < POR_GRUPO: grupos[k].append(fn)
        for (tipo, reg), fns in grupos.items():
            for fn in fns: por_acervo.setdefault(acervo, []).append({"nome": nome, "acervo": acervo, "regiao": reg, "tipo": tipo, "url": fn})
    for acervo, lista in por_acervo.items():
        json.dump(lista, open(os.path.join(work, "listas", safe(acervo) + ".json"), "w"), ensure_ascii=False)
        print(acervo, len(lista), file=sys.stderr)
    json.dump(sorted(safe(a) for a in por_acervo), open(os.path.join(work, "acervos.json"), "w"))


def baixar_acervo(work, nome_seguro):
    lista = json.load(open(os.path.join(work, "listas", nome_seguro + ".json")))
    d = os.path.join(work, nome_seguro); os.makedirs(os.path.join(d, "img"), exist_ok=True)
    def um(x):
        arq = re.sub(r"[^A-Za-z0-9_.-]", "_", x["url"].rsplit(".", 1)[0]) + ".jpg"
        ok = baixar("https://images.launchbox-app.com/" + x["url"], os.path.join(d, "img", arq))
        return dict(x, arquivo=arq) if ok else None
    with cf.ThreadPoolExecutor(8) as ex: ok = [x for x in ex.map(um, lista) if x]
    json.dump(ok, open(os.path.join(d, "lista.json"), "w"), ensure_ascii=False)
    print(nome_seguro, len(ok), "de", len(lista), "imagens", file=sys.stderr)


if __name__ == "__main__":
    if sys.argv[1] == "listar": listar(sys.argv[2])
    else: baixar_acervo(sys.argv[2], sys.argv[3])
