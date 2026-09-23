# SiteLock — Etapa 1 (bloqueio local via /etc/hosts)

CLI em Java 17+ para bloquear domínios na própria máquina, redirecionando para
`127.0.0.1` via `/etc/hosts`. Suporta bloqueio temporizado (`--for`) ou
permanente até `unblock` manual.

> Etapa futura (não implementada): servidor DNS para bloquear em outros
> dispositivos da rede. A arquitetura já está preparada: o bloqueio real fica
> atrás da interface `com.sitelock.blocker.SiteBlocker` (hoje só existe
> `HostsFileBlocker`); um futuro `DnsBlocker` pode ser composto sem tocar no CLI.

## Compilar

```bash
mvn package
```

Gera `target/sitelock-1.0.0.jar` (fat-jar executável).

## Rodar

Editar `/etc/hosts` exige root. Comandos que modificam (`block`, `unblock`) e a
limpeza de expirados falham com mensagem clara se não houver escrita — rode com `sudo`:

```bash
sudo java -jar target/sitelock-1.0.0.jar block tiktok.com --for 1h30m
java -jar target/sitelock-1.0.0.jar status
sudo java -jar target/sitelock-1.0.0.jar unblock tiktok.com
```

O estado fica em `~/.sitelock/blocks.json` — **do usuário real** (respeita
`SUDO_USER`, para não cair em `/root/.sitelock` sob sudo). O backup do hosts
original vai para `~/.sitelock/hosts.backup` (criado uma única vez).

## Comandos

```bash
# Bloqueio temporário (30m, 2h, 1h30m, 1d — apenas m/h/d minúsculos, colados)
# Aceita 1..N domínios por chamada (útil p/ TikTok: apex + subdomínios/CDN)
sudo java -jar target/sitelock-*.jar block tiktok.com vm.tiktok.com vt.tiktok.com --for 30m
sudo java -jar target/sitelock-*.jar block youtube.com --for 1h30m

# Bloqueio permanente (até unblock manual)
sudo java -jar target/sitelock-*.jar block twitter.com

# Re-bloquear atualiza a duração (avisa que sobrescreveu)
sudo java -jar target/sitelock-*.jar block tiktok.com --for 2h

# Listar (não precisa de sudo para listar; com sudo também limpa expirados)
java -jar target/sitelock-*.jar status

# Desbloquear (recusa se ainda houver prazo; informa quanto falta)
sudo java -jar target/sitelock-*.jar unblock tiktok.com vm.tiktok.com

# Desbloqueio antecipado: exige --force + digitar SIM (por dominio)
sudo java -jar target/sitelock-*.jar unblock tiktok.com --force

# Estado corrompido: o programa aborta sem apagar nada; para recriar:
java -jar target/sitelock-*.jar reset --yes
# (não altera o /etc/hosts — limpe o restante com unblock)
```

Sempre são bloqueadas as quatro linhas por dominio: `dominio` + `www.dominio`,
em IPv4 (`127.0.0.1`) e IPv6 (`::1`).
Entradas como `https://WWW.TikTok.com/feed` são normalizadas para `tiktok.com`
(com aviso). O `/etc/hosts` não suporta wildcard (`*.tiktok.com`), por isso
liste os subdomínios explicitamente.

> Nota (bug corrigido): versões anteriores gravavam o `/etc/hosts` com
> permissão `600` (ilegível sem sudo, bloqueio "fantasma") e criavam
> `~/.sitelock/*` como root. Agora as permissões/dono originais são
> preservadas e o estado volta a pertencer ao usuário real. Se seu `/etc/hosts`
> ficou `600`, repare uma vez: `sudo chmod 644 /etc/hosts`, e
> `sudo chown -R $USER:$USER ~/.sitelock/`.

## Expiração automática

A cada execução de qualquer comando, os bloqueios expirados são removidos do
`/etc/hosts` e do JSON antes de processar o comando pedido. Limitação: se o
usuário não rodar nada, nada é limpo — para limpeza contínua, agende chamadas
periódicas (ex.: `systemd timer` rodando `sitelock status`) — ver comentário em
`BlockService.sweepExpired()`.

## Testes

```bash
mvn test
```

Cobre: parsing de duração, validação/normalização de domínio, manipulação do
bloco de marcadores em memória e serialização do JSON.
