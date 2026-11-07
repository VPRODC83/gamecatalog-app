# Aplicativo Android: o que o dono precisa fazer

> A versão web (app no navegador e instalador) foi descontinuada em 03/11/2026. O site https://vprodc83.github.io/ agora só tem a página de download, a política de privacidade e os termos, publicados a partir de `docs/site/`.

O aplicativo Android (pasta `android/`) é montado e assinado automaticamente pelo GitHub
a cada versão nova, junto com o site. Ele **não usa Gemini nem nenhuma IA na nuvem**:

- **Identificação:** o celular lê o texto da etiqueta/caixa (ML Kit, sem internet) e compara com
  o catálogo de nomes do libretro embutido no app. Funciona com vários cartuchos na mesma foto.
  Se não reconhecer, o botão **Perguntar ao Google Lens** abre o Lens com a foto; ao copiar o nome
  lá e voltar, o app oferece o nome copiado. Também há a busca por nome (ou parte dele).
- **Planilha:** login com a conta Google do celular (a mesma da Play Store). O app lê e grava a
  planilha do Google Sheets da coleção: várias pessoas podem usar a mesma planilha (compartilhada
  como Editor). Fica uma cópia no celular para abrir rápido e ver a coleção sem internet.
- **Preços:** links das lojas do Brasil (Mercado Livre, OLX, Shopee, Enjoei) e valores do
  PriceCharting convertidos pelo dólar do dia, guardados na aba PRECOS.
- **Cópia de segurança:** Ajustes → Exportar .xlsx.

A configuração das abas e colunas fica na própria planilha (aba APP_CONFIG), e vale para todos os celulares que usam a mesma planilha.

## Passo 1 — Senha da chave de assinatura (uma vez só)

O APK é assinado com uma chave guardada **criptografada** no repositório
(`android/app/release.keystore.enc`). Só o GitHub Actions, com a senha, consegue usá-la.

1. Abra https://github.com/VPRODC83/gamecatalog-app/settings/secrets/actions
2. **New repository secret**
3. Name: `ANDROID_KEY_PASSWORD`
4. Secret: a senha que o Claude enviou na conversa
5. **Add secret**

Guarde essa senha também num lugar seguro (gerenciador de senhas). Sem ela não dá para publicar
atualizações que o Android aceite por cima da versão instalada.

## Passo 2 — Permitir o login no Android (Google Cloud, uma vez só)

Use o projeto do Google Cloud do Game Catalog (o mesmo já configurado).

1. https://console.cloud.google.com/apis/library/sheets.googleapis.com → **Ativar**
   (se já estiver ativada, aparece "Gerenciar").
2. https://console.cloud.google.com/auth/clients → **Criar cliente**
   - Tipo de aplicativo: **Android**
   - Nome: `Game Catalog Android`
   - Nome do pacote: `io.github.vprodc83.gamecatalog`
   - Impressão digital do certificado SHA-1:
     `D0:2C:49:33:C4:DE:2C:7B:58:5A:F2:A0:3F:D9:84:33:F7:35:9A:B5`
   - **Criar**
3. https://console.cloud.google.com/auth/scopes → **Adicionar ou remover escopos** → marque
   `.../auth/spreadsheets` (Google Sheets API, "Ver, editar, criar e excluir todas as suas
   planilhas") → **Atualizar** → **Salvar**.
4. https://console.cloud.google.com/auth/audience → em **Usuários de teste**, **Add users** e
   inclua o seu e-mail e o da sua esposa (e de quem mais for testar; até 100).

Enquanto o app estiver "Em teste", só os usuários de teste conseguem entrar, e o Google mostra a
tela "O Google não verificou este app" → **Continuar**. Isso é normal nesta fase.

## Passo 3 — Instalar no celular

1. No celular, abra https://github.com/VPRODC83/gamecatalog-app/releases/latest
2. Toque em **gamecatalog.apk** para baixar.
3. Abra o arquivo baixado. Na primeira vez o Android pede para **permitir instalar apps desta
   fonte** (o navegador): permita e volte.
4. Abra o **Game Catalog** → **Entrar com Google** → escolha a conta → permita o acesso às planilhas.
5. Cole o link da planilha (no app Google Sheets: Compartilhar → Copiar link) → **Usar esta planilha**.
   Se ela já foi configurada antes, entra direto; se não, confira as abas e colunas e salve.

Para a sua esposa: compartilhe a planilha com o e-mail dela como **Editor** e, no app, em
Ajustes → **Enviar link para alguém**. Ela copia o link e cola no app (ou, no WhatsApp, segura a
mensagem → Compartilhar → **Game Catalog**).

**Atualizações:** Ajustes → **Procurar atualização** → **Baixar** e instalar por cima.
O Play Protect pode avisar que o app é desconhecido (ainda não está na Play Store): toque em
"Instalar mesmo assim".

## Depois: tornar público e ir para a Play Store

1. **Verificação do Google** (para sair do limite de 100 usuários de teste e tirar o aviso):
   em https://console.cloud.google.com/auth/verification envie para verificação. O escopo
   `spreadsheets` é "sensível": o Google pede a página inicial (https://vprodc83.github.io/), a
   política de privacidade (já pronta), a justificativa e um vídeo mostrando o login e o uso.
   - **Justificativa sugerida:** "O app organiza a coleção de videogames do usuário numa planilha
     do Google Sheets que pode ser compartilhada entre pessoas da família. O usuário informa o link
     da planilha; o app lê e grava apenas essa planilha (coleção, procurados, compras). O escopo
     drive.file não permite abrir planilhas compartilhadas por outra pessoa a partir do link."
   - **Vídeo (1–3 min, pode ser gravado no celular):** abrir o app → Entrar com Google → tela de
     permissão mostrando o nome do app e o escopo → colar o link da planilha → mostrar a coleção →
     fotografar um cartucho → tocar em "Comprei" → mostrar a linha nova na planilha no Google Sheets.
2. **Play Store:** com a conta de desenvolvedor (taxa única de US$ 25), crie o app com o pacote
   `io.github.vprodc83.gamecatalog`. A Play Store assina com uma chave própria: depois de enviar a
   primeira versão, copie o SHA-1 da "chave de assinatura de apps" (Play Console → Integridade do app)
   e crie **mais um** cliente Android no Google Cloud com esse SHA-1. Peça ao Claude para gerar o
   formato `.aab` no workflow quando chegar nesse ponto.

## Chave do TheGamesDB (capas) — passo a passo

O TheGamesDB (https://thegamesdb.net) é um acervo comunitário de capas e dados de jogos, gratuito. Para usar a API é preciso
pedir acesso; não há cobrança nem cartão. Ele dá duas chaves:

| Chave | Para quê | Limite | Onde usar |
| --- | --- | --- | --- |
| **Pública** | Uso dentro de aplicativos | Por endereço de internet (IP): cada celular tem a sua cota mensal | **Esta vai no app** |
| Privada | Montar uma cópia dos dados num servidor | 6.000 pedidos no total, compartilhados | **Nunca** colocar no app nem no GitHub |

1. Crie a conta no **site** (não no fórum): https://thegamesdb.net/register.php — usuário, senha, e-mail (use Gmail; eles
   avisam que Hotmail/Outlook não recebe os e-mails deles) e o código da imagem. Confirme pelo e-mail recebido.
2. Entre em https://thegamesdb.net com essa conta. No **rodapé** da página, clique em **API Access Request**, preencha
   o formulário (pode usar o texto abaixo) e envie. A mesma conta passa a valer no fórum.
3. Espere a aprovação (é manual; pode levar alguns dias).
4. Com a conta aprovada, abra https://api.thegamesdb.net/key.php (logado) e copie a **Public API Key**.
5. No GitHub, em https://github.com/VPRODC83/gamecatalog-app/settings/secrets/actions → **New repository secret**:
   Name `TGDB_API_KEY`, Secret = a chave pública. A próxima versão do app já sai usando o TheGamesDB.

Texto sugerido para o pedido (em inglês):

> **Subject:** API key request — Game Catalog (free, open-source Android collection manager)
>
> Hi! I'd like to request API access for **Game Catalog**, a free and open-source Android app for managing a personal
> video game collection stored in the user's own Google Sheets spreadsheet (source: https://github.com/VPRODC83/gamecatalog-app,
> site: https://vprodc83.github.io/). We would use the **public key** only, from the app, to look up **box art** for games
> that our primary source (libretro-thumbnails) doesn't have — mostly modern consoles and Brazilian releases.
> Usage is small: a lookup by name + platform and one images call per game without cover, cached on the device, so a
> typical user makes at most a few hundred requests per month. No data is re-hosted or resold; images are shown with
> credit to TheGamesDB. Non-commercial, no ads. Thank you!

O app mostrará "Capas: libretro-thumbnails, TheGamesDB…" em Ajustes, como crédito.
