# SiteBlock

CLI simples em Java para bloquear sites na sua máquina editando o arquivo hosts. Tem também um servidor DNS local opcional para estender o bloqueio a outros aparelhos da mesma rede.

## Requisitos

- Java 26+ (`java -version`)
- Maven 3.8+ só para compilar (`mvn -version`)

## Baixar e compilar

```bash
git clone https://github.com/Campeol-G/SiteBlock.git
cd SiteBlock
mvn package
```

O executável sai em `sitelock-app/target/siteblock-*.jar`.

## Rodar

Para ter o comando `siteblock` no terminal, crie um link no `PATH`:

```bash
ln -sf "$PWD/bin/siteblock" ~/.local/bin/siteblock
```

Depois use direto:

```bash
sudo siteblock block tiktok.com --for 1h
siteblock status
```

Alternativas sem instalar (`run.sh`/`run.bat` acham o jar sozinhos, ou passe o caminho em `SITEBLOCK_JAR`):

```bash
./run.sh block tiktok.com --for 1h
./run.sh status
```

No Windows:

```bat
run.bat block tiktok.com --for 1h
run.bat status
```

Ou direto:

```bash
java -jar sitelock-app/target/siteblock-*.jar status
```

Mexer no hosts e ouvir na porta 53 exige permissão alta. No Linux/macOS use `sudo` para `block`, `unblock` e `dns start` na porta padrão. Dá para testar o DNS sem sudo com `--port 5300`.

## Comandos

```bash
# Bloquear (temporário ou permanente)
sudo ./run.sh block tiktok.com vm.tiktok.com --for 30m
sudo ./run.sh block youtube.com --for 1h30m
sudo ./run.sh block twitter.com

# Ver o que está bloqueado
./run.sh status

# Desbloquear (se ainda tiver prazo, pede --force + confirmação digitada)
sudo ./run.sh unblock tiktok.com
sudo ./run.sh unblock tiktok.com --force

# Apagar o estado e começar do zero (não mexe no hosts)
./run.sh reset --yes

# DNS para a rede local
sudo ./run.sh dns start
./run.sh dns status
sudo ./run.sh dns stop
```

Durações aceitas: `30m`, `2h`, `1h30m`, `1d`. Sem `--for`, o bloqueio fica até `unblock`.

## Onde fica o estado

- Bloqueios: `~/.sitelock/blocks.json`
- Backup do hosts original: `~/.sitelock/hosts.backup` (criado uma vez)
- Estado do DNS: `~/.sitelock/dns-server.json`

## Limites honestos

- Bloqueio por hosts não tem curinga: `tiktok.com` não cobre `vm.tiktok.com`, liste os subdomínios.
- App que usa DNS próprio ou IP fixo fura o bloqueio.
- Expirado só sai do hosts quando algum comando roda; sem agendador, não há limpeza sozinha.
