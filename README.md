# SiteBlock

Bloqueia sites na sua máquina. Sem extensão, sem app instalado, sem conta.
Você digita o domínio, ele escreve no arquivo de hosts do sistema — o mesmo
mecanismo que a escola e o trabalho usam para travar site na lancha.

Funciona em Linux, macOS e Windows.

## O que ele faz

```bash
sudo siteblock block tiktok.com --for 4h   # bloqueia por 4 horas
siteblock status                            # o que está bloqueado e por quanto tempo
sudo siteblock unblock tiktok.com          # solta
```

Sem `--for`, o bloqueio não tem prazo: fica até você tirar.

Também tem um servidor DNS opcional que estende o bloqueio para os outros
aparelhos da mesma rede — celular, tablet, console. Está em `siteblock dns`.

## Requisitos

- **Java 26 ou mais novo**. É o requisito mais incomum do projeto: o código
  usa recursos do Java 26 e não compila em versões anteriores. A CI usa
  Temurin 26.
- Maven 3.8+ só para compilar. Para rodar, não precisa.
- Linux, macOS ou Windows. Em Windows, abra o terminal como Administrador.

## Instalar

```bash
git clone https://github.com/Campeol-G/SiteBlock.git
cd SiteBlock
mvn package
```

O executável sai em `sitelock-app/target/siteblock-*.jar`.

## Rodar

Coloque o comando no terminal:

```bash
ln -sf "$PWD/bin/siteblock" ~/.local/bin/siteblock
```

Depois use direto:

```bash
sudo siteblock block tiktok.com --for 1h
siteblock status
```

Sem instalar nada no PATH, use os scripts, que encontram o jar sozinhos:

```bash
./run.sh block tiktok.com --for 1h    # Linux e macOS
run.bat block tiktok.com --for 1h     # Windows
```

Se o jar estiver em outro lugar, aponte com `SITEBLOCK_JAR=/caminho/siteblock.jar`.

## Comandos

```bash
# Bloquear. Vários domínios de uma vez:
sudo ./run.sh block tiktok.com vm.tiktok.com --for 30m
sudo ./run.sh block twitter.com               # sem --for = sem prazo

# Ver o que está bloqueado e quanto tempo resta
./run.sh status

# Desbloquear. Se ainda tiver prazo, pede --force e confirmação digitada:
sudo ./run.sh unblock tiktok.com
sudo ./run.sh unblock tiktok.com --force

# Apagar o histórico de bloqueios (não mexe no hosts)
./run.sh reset --yes

# Servidor DNS para a rede local
sudo ./run.sh dns start      # bloqueia também no celular/tablet/console
./run.sh dns status
sudo ./run.sh dns stop

# Conjuntos de domínios com nome
sudo ./run.sh profile save trabalho      # guarda o que está bloqueado agora
./run.sh profile list
./run.sh profile apply trabalho         # recarrega sem nunca encurtar o prazo
sudo ./run.sh profile unblock trabalho --force

# Editar a receita de um perfil que já existe
./run.sh profile trabalho add tiktok.com            # entra permanente
./run.sh profile trabalho add tiktok.com --for 4h   # entra com 4h de prazo
./run.sh profile trabalho remove tiktok.com          # só sai da receita

# Daemon de expiração (o prazo vence sozinho)
sudo ./run.sh daemon enable             # instala para subir a cada boot
./run.sh daemon status                  # responde sem sudo
sudo ./run.sh daemon start              # sobe agora, em background
sudo ./run.sh daemon stop
sudo ./run.sh daemon disable
```

Durações aceitas: `30m`, `2h`, `1h30m`, `1d`, `1d2h30m`. Só minúsculo, sem
espaço.

### Por que precisa de sudo

Editar o arquivo de hosts exige permissão de administrador nos três sistemas.
Só `block`, `unblock` e `dns start` pedem. `status` e `reset` não.

## Onde ficam as coisas

Em `~/.sitelock/`:

| Arquivo | O que é |
|---|---|
| `blocks.json` | o que está bloqueado e até quando |
| `hosts.backup` | cópia do hosts original, criada uma vez |
| `dns-server.json` | estado do servidor DNS |
| `profiles.json` | os conjuntos de domínios salvos com nome |
| `daemon.json` | o daemon de expiração está rodando (pid, último sweep) |

O arquivo de hosts do sistema só é alterado no bloco delimitado por
marcadores, entre uma linha original sua e o resto do arquivo. Nunca
sobrescreve o que já estava lá.

## Limites

Coisas que **não** funcionam, e é bom saber antes:

- **Não bloqueia por app.** Bloqueia por domínio. Se o tiktok abre pelo `app.net`,
  passa.
- **Não cobre subdomínio.** `tiktok.com` não cobre `vm.tiktok.com`. Liste os
  subdomínios que você usa.
- **App com DNS próprio ou IP fixo fura.** Alguns apps (principalmente Android
  e Electron) resolvem por conta própria e ignoram o hosts.
- **Modo anônimo, extensões e alguns navegadores** podem ignorar o hosts.
- **Expirado só sai sozinho se o daemon estiver ligado.** Com
  `sudo siteblock daemon enable` instalado, o prazo vence sem você abrir o
  terminal — inclusive depois de reiniciar. Sem ele, o site só é liberado
  quando você roda outro comando do siteblock (`status` já resolve), e o
  `block --for` avisa na hora que o daemon não está cobrindo.
- **Não é à prova de você mesmo.** Você é dono da máquina e pode editar o
  `/etc/hosts` com `sudo`. O siteblock é uma trava de HPV contra distraído, não
  de segurança.
- **Precisa de Java 26.** Em máquina com Java 17, 21 ou 25, não roda.

## Como funciona

O SiteBlock escreve no arquivo de hosts do sistema:

```
# SITELOCK-START
127.0.0.1 tiktok.com
127.0.0.1 www.tiktok.com
::1 tiktok.com
::1 www.tiktok.com
# SITELOCK-END
```

Ele reescreve só esse bloco. O que está acima e abaixo, nunca toca. O
`hosts.backup` é a cópia do arquivo inteiro antes da primeira alteração, caso
você queira desfazer tudo na mão.

Quando você bloqueia, o prazo é gravado como um horário exato no `blocks.json`,
não como "4 horas a partir de agora". Por isso o bloqueio sobrevive a
reiniciar ou desligar o computador: se você bloqueou 4 horas, usou 2 horas e
desligou, quando voltar ainda restam 1h30m.

O `daemon` existe para fechar o outro lado dessa mesma conta: sozinho, ele
acorda, vê o que venceu e tira do `/etc/hosts`. Ele **só remove** — bloquear
continua sendo coisa do `block` e do `profile apply` — e escreve o resultado em
`daemon.json` para o `daemon status` mostrar quando foi a última vez que algo
expirou por conta própria.

Os `perfis` guardam um conjunto de domínios com nome para não remontar a
mesma lista todo dia. O que fica gravado é o **prazo restante** no momento do
save, nunca o horário original: um perfil é uma receita, não um histórico.
Aplicar um perfil **só aumenta** prazo, nunca encurta — repetir `profile apply`
várias vezes no dia é seguro.

`profile add` e `profile remove` editam a receita de um perfil que já existe,
sem passar pelo `block`: o `add` grava o domínio com o prazo pedido (ou
permanente, sem `--for`) e o `remove` tira o domínio da receita. Nenhum dos
dois toca no `/etc/hosts` — o que está bloqueado continua bloqueado, e o
`remove` avisa quando o domínio que saiu da receita continua preso, porque o
`profile unblock` só alcança o que ainda está no perfil.

Para instalar o autostart (o passo recomendado depois de instalar):

```bash
sudo siteblock daemon enable     # Linux: unit systemd · macOS: LaunchDaemon · Windows: tarefa
siteblock daemon status          # sem sudo: responde se está ligado
```

O daemon roda como root, porque precisa escrever no `/etc/hosts`. Ele grava os
caminhos no artefato de inicialização e devolve a posse dos arquivos que escreve
para o seu usuário, para que `status` funcione sem elevação.

O `daemon enable` também **instala uma cópia do jar** num diretório do root
(`/opt/sitelock` no Linux, `/usr/local/lib/sitelock` no macOS,
`%ProgramData%\SiteBlock` no Windows) e aponta a inicialização para lá. Não
aponta para o jar do build porque `mvn clean` apaga o `target/` — e uma
inicialização apontando para o nada entra em loop de crash, e o prazo dos
bloqueios para de ser cumprido sem nenhuma mensagem que explique o motivo.

Depois de recompilar, rode o `enable` de novo para atualizar a cópia:

```bash
mvn package
sudo siteblock daemon enable
```

Se a cópia sumir, o `daemon status` diz isso explicitamente e diz como
consertar.

O modo DNS é separado: ele roda um servidor que responde no seu IP, e os
aparelhos da rede precisam apontar para ele. Cobre mais aparelhos do que o
hosts, ao custo de configurar cada dispositivo.

## Desenvolvimento

```bash
mvn test          # testes (roda em Linux e Windows no CI)
mvn package       # gera o jar
```

Três módulos Maven:

- `sitelock-core` — estado, arquivo de hosts, domínios
- `sitelock-dns` — servidor DNS local
- `sitelock-app` — a CLI