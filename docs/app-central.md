# App central para qualquer pessoa — passo a passo do dono

Objetivo: um único Game Catalog em **https://vprodc83.github.io/** que qualquer pessoa usa só com a conta Google. Os usuários não precisam de GitHub nem de Google Cloud.

| Quem | O que precisa |
| --- | --- |
| **Usuário** | Conta Google e celular com Chrome. Opcional: chave grátis do Gemini, colada no app, para identificar por foto. |
| **Você (dono)** | Um repositório `VPRODC83.github.io`, um projeto no Google Cloud verificado e, depois, a conta da Play Store. |

Os dados de cada usuário continuam na conta Google **dele**. O app pede só a permissão `drive.file`: acesso apenas à planilha que a pessoa escolher no seletor do Google Drive e à pasta de fotos que o app cria. Essa permissão é *não sensível*, por isso basta a **verificação de marca**, gratuita e rápida.

---

## Parte 1 — Criar o site (5 min)

1. Confirme que **gamecatalog-instalador** está marcado como modelo: **Settings → General → Template repository** ✔.
2. Abra <https://github.com/new?template_name=gamecatalog-instalador&template_owner=VPRODC83>:
   - **Repository name:** `VPRODC83.github.io`. Tem que ser exatamente o seu usuário + `.github.io`; é o que coloca o app na raiz do endereço.
   - **Public** → **Create repository**.
3. No repositório novo: **Settings → Pages → Source: GitHub Actions**.

## Parte 2 — Google Cloud (15 min)

Use um projeto **só para o app central**, por exemplo “Game Catalog”. Pode ser o mesmo que você talvez já tenha criado para testes, desde que siga os passos abaixo.

1. **Projeto:** [console.cloud.google.com/projectcreate](https://console.cloud.google.com/projectcreate) → nome `Game Catalog` → **Criar** → selecione o projeto no alto.
   Anote o **Número do projeto**: aparece no **Painel** (*Dashboard*), no cartão “Informações do projeto”. São só dígitos.
2. **APIs** (clique em **Ativar** em cada uma):
   - [Google Sheets API](https://console.cloud.google.com/apis/library/sheets.googleapis.com)
   - [Google Drive API](https://console.cloud.google.com/apis/library/drive.googleapis.com)
   - [Google Picker API](https://console.cloud.google.com/apis/library/picker.googleapis.com)
3. **Google Auth Platform** ([console.cloud.google.com/auth/overview](https://console.cloud.google.com/auth/overview)) → **Primeiros passos**:
   - Nome do app: **Game Catalog**; e-mail de suporte: o seu.
   - Público-alvo: **Externo**; dados de contato: o seu e-mail → **Criar**.
4. **Branding** (menu da esquerda):
   - **Página inicial do aplicativo:** `https://vprodc83.github.io/`
   - **Link da Política de Privacidade:** `https://vprodc83.github.io/privacidade.html`
   - **Link dos Termos de Serviço:** `https://vprodc83.github.io/termos.html`
   - **Domínios autorizados:** `vprodc83.github.io`
   - **Logo:** opcional. Se quiser, envie o ícone de 120×120 do app.
   - **Salvar.**
5. **Acesso a dados** (*Data access*) → **Adicionar ou remover escopos** → marque **só** `.../auth/drive.file` → **Atualizar** → **Salvar**.
6. **Clientes** → **Criar cliente**:
   - **Aplicativo da Web**, nome `Game Catalog web`.
   - **Origens JavaScript autorizadas:** `https://vprodc83.github.io`.
   - **Criar** e copie o **ID do cliente**.
7. **Chave de API** (para o seletor do Drive): **APIs e serviços → Credenciais → Criar credenciais → Chave de API**. Depois clique na chave para editar:
   - **Restrições de aplicativo:** *Sites* (referenciadores HTTP) → `https://vprodc83.github.io/*`
   - **Restrições de API:** *Restringir chave* → **Google Picker API**
   - **Salvar** e copie a chave (`AIza…`).

   Essa chave fica visível no site, o que é normal para o seletor do Drive. As restrições impedem que ela seja usada fora do seu endereço ou para outra coisa.
8. **Público-alvo → Usuários de teste:** adicione o seu e-mail, para testar antes da verificação.

## Parte 3 — Instalar (2 min)

No repositório `VPRODC83.github.io`: **Actions → Instalar ou atualizar o app → Run workflow**, preencha os três campos e confirme:
- **Client ID do Google:** o do passo 2.6.
- **Chave de API:** a do passo 2.7.
- **Número do projeto:** o do passo 2.1.

Quando ficar verde ✅, abra **https://vprodc83.github.io/** no celular, entre com a sua conta, toque em **Escolher a minha planilha** e selecione a JOGOS.

## Parte 4 — Provar ao Google que o endereço é seu (5 min)

1. Abra [search.google.com/search-console](https://search.google.com/search-console) com **a mesma conta** dona do projeto no Google Cloud.
2. **Adicionar propriedade → Prefixo do URL** → `https://vprodc83.github.io/` → **Continuar**.
3. Escolha **Arquivo HTML** e baixe o arquivo (`googleXXXXXXXX.html`).
4. No repositório `VPRODC83.github.io`: abra a pasta **extra** → **Add file → Upload files** → envie o arquivo → **Commit changes**.
5. Espere o workflow ficar verde (1–2 min) e clique em **Verificar** no Search Console.

## Parte 5 — Liberar para todos

1. **Google Auth Platform → Público-alvo → Publicar app** (sai de “Teste” para “Em produção”).
2. **Central de verificação** (*Verification center*) → **Verificar marca** (*Verify branding*). A análise automática leva minutos; se for para análise manual, 2–3 dias úteis.

   Como o app usa só `drive.file`, não é preciso vídeo nem a verificação demorada de escopos sensíveis.
3. Enquanto a marca não estiver verificada, o Google pode mostrar o endereço no lugar do nome “Game Catalog” na tela de login.

> **Se o Google recusar `vprodc83.github.io` como domínio autorizado** (passo 2.4) ou na verificação: será preciso um domínio próprio, por exemplo `gamecatalog.com.br` (~R$ 40/ano), apontado para o GitHub Pages. Me avise que eu ajusto tudo.

## Parte 6 — Divulgar

Mensagem pronta para mandar aos colecionadores:

> 🎮 **Game Catalog** — organize sua coleção de videogames no celular, de graça.
> 1. Abra **https://vprodc83.github.io/** no Chrome do celular.
> 2. Toque nos três pontinhos ⋮ → **Instalar app**.
> 3. Toque em **Entrar com Google** e escolha a sua planilha da coleção (ou comece uma nova).
> 4. Para identificar jogos pela foto, o app mostra como criar uma chave grátis do Google em 1 minuto.

## Depois: Play Store

Com o site verificado e funcionando, os próximos passos são:
1. Criar a conta de desenvolvedor (US$ 25) e fazer a verificação de identidade.
2. Gerar o pacote Android (Trusted Web Activity, que abre o mesmo site dentro do app) e o arquivo `.well-known/assetlinks.json`, que vai na pasta **extra**.
3. Fazer o teste fechado com 12 testadores por 14 dias.
4. Publicar.

As atualizações continuam saindo daqui: o app da loja abre o site, então cada versão nova chega a todos sem passar pela revisão da loja.
