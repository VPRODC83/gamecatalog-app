# Game Catalog — código do app e canal de versões

App gratuito para controlar uma coleção de videogames numa planilha do Google: identificar jogos pela foto, ver capas, controlar a lista de compras e consultar preços no Brasil e no mundo.

> **Quer usar o app?** Não use este repositório. Siga o guia de instalação em
> **[VPRODC83/gamecatalog-instalador](https://github.com/VPRODC83/gamecatalog-instalador#readme)**.
> Cada pessoa instala uma cópia na própria conta do GitHub, com a própria conta Google.

Este repositório é a **fonte oficial das atualizações**. Só o dono (VPRODC83) escreve nele. Cada versão publicada aqui é verificada pelo instalador de cada usuário antes de entrar no ar.

## Como funciona

| Parte | Onde fica | Quem controla |
| --- | --- | --- |
| Código do app | Este repositório (`app/`) | Só o dono |
| Versões | Releases deste repositório (`app.zip` + atestado de proveniência) | Só o dono, pelo workflow `Publicar versão` |
| App de cada pessoa | GitHub Pages do repositório da pessoa, criado a partir do modelo `gamecatalog-instalador` | A pessoa |
| Dados | Planilha e pasta do Google Drive da própria pessoa | A pessoa |
| Chaves do Gemini e do Tavily | Só no navegador do aparelho da pessoa | A pessoa |

O app não tem servidor próprio. Ele roda no navegador e fala direto com:
- **Google Sheets e Google Drive**, com o login Google da pessoa (escopos `spreadsheets` e `drive.file`). O `drive.file` só vê os arquivos que o próprio app criou: a pasta de fotos.
- **Gemini**, com a chave gratuita da pessoa: identificação por foto e leitura dos anúncios.
- **Tavily**, opcional, com a chave gratuita da pessoa: busca de preços. Sem ela, o app usa a busca do Google dentro do Gemini.
- **libretro-thumbnails** e **Wikipédia**: capas.

## Segurança das atualizações

1. O workflow [`release.yml`](.github/workflows/release.yml) só roda para tags `v*` criadas pelo dono, e só neste repositório.
2. Ele monta o `app.zip` e gera um **atestado de proveniência** assinado (GitHub Artifact Attestations / Sigstore). O atestado prova que o arquivo saiu deste workflow, deste repositório e deste commit.
3. O instalador de cada usuário baixa o `app.zip`, confere a soma SHA-256 e roda `gh attestation verify --repo VPRODC83/gamecatalog-app --signer-workflow VPRODC83/gamecatalog-app/.github/workflows/release.yml`. Se a verificação falhar, nada é publicado.
4. Todas as GitHub Actions usadas estão presas a um commit (SHA), não a uma etiqueta que pode mudar.

### Proteções recomendadas neste repositório (Settings)

- **Rules → Rulesets → New branch ruleset** para `main`: bloquear exclusão e force push, exigir pull request (só você aprova).
- **Rules → Rulesets → New tag ruleset** para `v*`: restringir criação, atualização e exclusão (bypass só para você).
- **Actions → General → Fork pull request workflows**: exigir aprovação para todos os colaboradores externos.
- Na sua conta: autenticação em dois fatores ligada.

## Publicar uma versão nova

1. Faça as mudanças em `app/` no `main`.
2. Crie a tag. No GitHub: **Releases → Draft a new release → Choose a tag → digite `v1.2.3` → Create new tag → Publish release**. Ou, pelo terminal: `git tag v1.2.3 && git push origin v1.2.3`.
3. O workflow **Publicar versão** monta o `app.zip`, gera o atestado e anexa tudo à release.
4. Os instaladores dos usuários pegam a versão nova na rotina diária, ou na hora, se a pessoa rodar o workflow à mão.

Use versão semântica: `vX.Y.Z`. Mudanças que exijam ação do usuário (novos escopos, nova configuração) pedem uma nota clara na release.

## Desenvolvimento

- `app/index.html`, `app/app.js`: o app (HTML, CSS e JavaScript puros, sem build).
- `app/sw.js`, `app/manifest.webmanifest`, `app/icons/`: app instalável (PWA).
- `tools/build_libretro.py`: gera `data/libretro/*.json`, os índices de nomes das capas, na hora da release.

Para testar localmente, sirva uma pasta com `app/`, `data/libretro/` (gerado pelo script), um `config.json` com `{"google_client_id": "..."}` e um `version.json`. O Client ID precisa ter `http://localhost:PORTA` entre as origens JavaScript autorizadas.
