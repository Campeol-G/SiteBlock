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
sudo ./run.sh dns start --allow-from 192.168.1.50,10.8.0.0/24   # libera outras origens
./run.sh dns status
sudo ./run.sh dns stop
sudo ./run.sh dns stop --force  # encerra antes do prazo, com confirmação

# Conjuntos de domínios com nome
sudo ./run.sh save profile trabalho      # guarda o que está bloqueado agora
./run.sh profile list
./run.sh profile trabalho apply             # recarrega sem nunca encurtar o prazo
sudo ./run.sh profile trabalho unblock --force
./run.sh delete profile trabalho         # apaga a receita (pede confirmação)

# Criar vazio, sem bloquear nada agora
./run.sh create profile trabalho

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
espaço. O limite é 1.000 anos; acima disso o comando recusa.

### Códigos de saída

Todo comando devolve um código, para quem quiser montar script em cima:

| Código | Quando |
|---|---|
| `0` | deu certo |
| `1` | erro (não conseguiu ler ou gravar o arquivo, perfil que não existe) |
| `2` | uso errado (falta argumento, valor inválido, comando desconhecido) |
| `3` | recusado ou cancelado (recusou parar antes do prazo, cancelou a confirmação) |

### Por que precisa de sudo

Editar o arquivo de hosts exige permissão de administrador nos três sistemas.
`block`, `unblock`, `profile <nome> apply`, `profile <nome> unblock` e `dns start` pedem.
`status`, `reset`, `profile list` e `daemon status` não.

Os comandos de daemon (`enable`, `start`, `stop`, `disable`) mexem no sistema
— autostart, serviço, processo do root — e também pedem elevação.

## Onde ficam as coisas

Em `~/.sitelock/`:

| Arquivo | O que é |
|---|---|
| `blocks.json` | o que está bloqueado e até quando |
| `blocks.json.lock` | lock: serializa processos que mexem no estado ao mesmo tempo |
| `hosts.backup` | cópia do hosts original, criada uma vez |
| `hosts.lock` | lock: serializa as escritas no arquivo de hosts |
| `dns-server.json` | estado do servidor DNS |
| `dns-server.start.lock` | lock: impede dois `dns start` ao mesmo tempo |
| `dns-server.log` | log do servidor DNS (consultas, upstream, decisões) |
| `dns-server.log.1` | log anterior, quando o atual passa de 5 MiB |
| `profiles.json` | os conjuntos de domínios salvos com nome |
| `profiles.json.lock` | lock: serializa as escritas dos perfis |
| `daemon.json` | o daemon de expiração está rodando (pid, último sweep) |
| `daemon.lock` | lock: garante um daemon só, mesmo com dois `daemon start` |
| `daemon.log` | log do daemon de expiração (o que ele liberou, erros) |

O arquivo de hosts do sistema só é alterado no bloco delimitado por
marcadores, entre uma linha original sua e o resto do arquivo. Nunca
sobrescreve o que já estava lá.

## Limites

Coisas que **não** funcionam, e é bom saber antes:

- **Não bloqueia por app.** Bloqueia por domínio. Se o tiktok abre pelo `app.net`,
  passa.
- **No modo hosts, não cobre subdomínio.** `tiktok.com` cobre `tiktok.com` e
  `www.tiktok.com`, mas não `vm.tiktok.com`. Liste os subdomínios que você
  usa. No modo DNS a cobertura é outra: o servidor responde por todos os
  subdomínios de um domínio bloqueado, sem precisar listar um por um.
- **O modo DNS escuta em todas as interfaces.** O servidor fica em
  `0.0.0.0:53` e repassa para o DNS de cima o que não está bloqueado. É assim
  que ele alcança a rede local — e é assim que ele vira um resolver aberto se
  o roteador deixar a porta 53 passar para a internet. **Não exponha a porta
  53 para fora da sua rede.**
- **O servidor DNS recusa quem não está na rede local.** As origens de fora são
  ignoradas em silêncio, e o `--allow-from` (no `dns start`) é quem libera
  mais alguma.
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
continua sendo coisa do `block` e do `profile <nome> apply` — e escreve o resultado em
`daemon.json` para o `daemon status` mostrar quando foi a última vez que algo
expirou por conta própria.

Os `perfis` guardam um conjunto de domínios com nome para não remontar a
mesma lista todo dia. O que fica gravado é o **prazo restante** no momento do
save, nunca o horário original: um perfil é uma receita, não um histórico.
Aplicar um perfil **só aumenta** prazo, nunca encurta — repetir `profile <nome> apply`
várias vezes no dia é seguro.

`profile <nome> add` e `profile <nome> remove` editam a receita de um perfil que já existe,
sem passar pelo `block`: o `add` grava o domínio com o prazo pedido (ou
permanente, sem `--for`) e o `remove` tira o domínio da receita. Nenhum dos
dois toca no `/etc/hosts` — o que está bloqueado continua bloqueado, e o
`remove` avisa quando o domínio que saiu da receita continua preso, porque o
`profile <nome> unblock` só alcança o que ainda está no perfil.

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

O servidor atende **UDP e TCP na mesma porta**. O TCP não é enfeite: quando a
resposta não cabe num pacote UDP, o servidor devolve `TC=1` e o cliente refaz
a mesma consulta por TCP (RFC 7766). Sem o listener TCP, a resolução falha
justamente nas respostas grandes. E se o upstream também responder truncado, o
SiteBlock refaz a consulta por TCP antes de entregar a resposta.

Quem pode consultar o servidor é definido na hora de subir. Por padrão, só
quem está na rede local (loopback, link-local, faixas RFC1918, IPv6 ULA e os
próprios endereços da máquina); o resto é recusado em silêncio. Para liberar
mais alguma origem — um celular em outra rede, um servidor da empresa — use o
`--allow-from` com IP ou CIDR, separado por vírgula:

```bash
sudo siteblock dns start --allow-from 192.168.1.50,10.8.0.0/24
```

A lista é validada antes do servidor subir: erro de digitação aparece na hora,
e não depois.

## Desenvolvimento

```bash
mvn test          # testes (roda em Linux, macOS e Windows no CI)
mvn package       # gera o jar
```

O repositório traz um `./mvnw` (e `mvnw.cmd` no Windows) com o Maven 3.9.16
fixado: é o que a CI usa, e evita o "funciona na minha máquina" de versão de
Maven e de plugin.

Três módulos Maven:

- `sitelock-core` — estado, arquivo de hosts, domínios
- `sitelock-dns` — servidor DNS local
- `sitelock-app` — a CLI