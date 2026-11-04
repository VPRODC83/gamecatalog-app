# Game Catalog — aplicativo Android

App gratuito para controlar uma coleção de videogames numa planilha do Google Sheets, compartilhável com a família:
identificar jogos pela foto (no próprio celular ou com o Google Lens), ver capas, controlar procurados e compras e consultar preços no Brasil e no mundo.

- **Baixar:** [gamecatalog.apk da última versão](https://github.com/VPRODC83/gamecatalog-app/releases/latest/download/gamecatalog.apk) · site: https://vprodc83.github.io/
- **Guia do dono (Google Cloud, assinatura, Play Store):** [docs/android.md](docs/android.md)
- **Melhorias pedidas e instaladas:** [docs/melhorias.md](docs/melhorias.md)

Só o dono (VPRODC83) escreve neste repositório.

## Estrutura

| Pasta | O que é |
| --- | --- |
| `android/` | O app (Kotlin + Jetpack Compose). Testes em `android/app/src/test`. |
| `tools/build_libretro.py`, `tools/build_catalog.py` | Geram, na hora da versão, o catálogo de nomes de jogos e capas embutido no app. |
| `tools/i18n/` | Traduções (14 idiomas): `keys.txt` + uma lista por idioma; `build.py` gera `android/app/src/main/assets/i18n/`. |
| `docs/site/` | Página de download, política de privacidade e termos publicados em https://vprodc83.github.io/ (o repositório do site copia esta pasta). |

## Publicar uma versão nova

**É automático.** Todo push no `main` que mude `android/`, `tools/` ou o workflow vira uma versão: o workflow
**Publicar versão** monta e assina o APK (chave criptografada em `android/app/release.keystore.enc` + segredo
`ANDROID_KEY_PASSWORD`), gera o atestado de proveniência e cria a release com `gamecatalog.apk` e `gamecatalog.apk.sha256`.

| No título do commit (primeira linha) | Efeito |
| --- | --- |
| *(nada)* | sobe o último número: `v0.7.0` → `v0.7.1` |
| `[minor]` | sobe o do meio: `v0.7.1` → `v0.8.0` |
| `[major]` | sobe o primeiro: `v0.8.0` → `v1.0.0` |
| `[sem versao]` | não publica |

Também dá para rodar à mão em **Actions → Publicar versão → Run workflow**. O workflow confere `github.actor == 'VPRODC83'`
e o nome do repositório, e todas as Actions estão presas a um commit (SHA).

Para conferir que um APK saiu daqui: `gh attestation verify gamecatalog.apk --repo VPRODC83/gamecatalog-app`.

## Desenvolvimento

```
cd android
python ../tools/build_libretro.py /tmp/libretro && python ../tools/build_catalog.py /tmp/libretro app/src/main/assets/catalog.json.gz
./gradlew testDebugUnitTest assembleDebug
```

O teste `ScreensTest` (Robolectric) salva imagens das telas em `android/app/build/screens/`.
