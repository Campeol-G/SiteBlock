# Perfis de bloqueio + daemon de expiração

Data: 2026-10-15

## Intenção

Duas coisas que a conversa com o usuário pediu, ambas sobre a dor de uso
diário do SiteBlock:

1. **Perfis.** Montar o mesmo conjunto de bloqueios toda vez é tedioso. O
   usuário quer montar uma vez, dar um nome, e carregar depois.
2. **Expiração automática.** Hoje um bloqueio só sai do `/etc/hosts` quando
   algum comando `siteblock` roda. Se ninguém abrir o terminal, o prazo vence
   e o site continua bloqueado. O usuário considera isso um defeito.

O quinto item é consequência do primeiro: desbloquear 40 domínios um a um é
invável, então o mesmo perfil precisa servir para travar e para destravar.

## Goals

- `profile save <nome>` guarda o setup atual como uma receita reutilizável.
- `profile apply <nome>` recarrega a receita sem nunca encurtar um bloqueio
  que já existe.
- `profile unblock <nome>` solta todos os domínios do perfil de uma vez, com
  a mesma resistência que o `unblock` individual tem hoje.
- Um bloqueio temporário expira sozinho, sem depender de alguém abrir o
  terminal, e volta a valer depois de reiniciar a máquina.
- O daemon sobe junto com o computador nos três SOs suportados (Linux,
  macOS, Windows).

## Non-goals

- Suporte a curinga/subdomínio (`tiktok.com` cobrindo `vm.tiktok.com`).
  Continua listando domínios um a um, como já é hoje.
- Bloqueio por processo/app (só por domínio, via hosts).
- Interface gráfica. O projeto é CLI; permanece CLI.
- Sincronização de perfis entre máquinas.
- "Modo Paradeiro" ou qualquer trava que impeça o usuário de desligar a
  máquina ou editar o `/etc/hosts` à mão. Ver Anti-goals.

## Anti-goals

Explicitamente **não** é objetivo deste trabalho:

- **Fazer do SiteBlock resistente a quem quer burlar.** Ele bloqueia por
  arquivo hosts, que o dono da máquina pode editar com `sudo`, e o daemon é
  um processo que pode ser morto. endurecer isso exigiria driver de kernel
  ou extensão de navegador — fora de escopo. O que o projeto garante é o
  inverso: que o usuário **não precisa** lembrar de rodar comando nenhum, e
  que não consiga sair por descuido.
- **Um jeito rápido de burlar em massa.** É por isso que `profile unblock`
  exige confirmação digitada (ver abaixo), e não é detalhe de implementação.

## Restrições

Derivam do código existente, não são escolhas:

- Java 26, Maven multi-módulo (`core`, `dns`, `app`), picocli, Jackson.
- O estado de bloqueio é `~/.sitelock/blocks.json`, com escrita atômica e
  lock entre processos (`BlockStore`).
- O `expiresAt` é um `Instant` absoluto de relógio. **Bloqueio já sobrevive a
  reboot** — o que falta é a limpeza automática, não a persistência.
- Editar `/etc/hosts` exige root nos três SOs. Logo o daemon roda como root.
- O padrão existente de daemon (`DnsStartCommand` + `__run-daemon`) já resolve
  fork de processo, PID file, liveness e log. O novo daemon deve copiar esse
  desenho, não inventar outro.
- Sob `sudo`, `user.home` vira `/root`; `SiteLockPaths` resolve a home real
  via `SUDO_USER`. Todo caminho novo precisa passar por lá.

---

# Parte 1 — Perfis

## Modelo de dados

Novo arquivo `~/.sitelock/profiles.json`, ao lado do `blocks.json`.

```json
{
  "version": 1,
  "profiles": {
    "trabalho": {
      "createdAt": "2026-10-15T14:02:11Z",
      "updatedAt": "2026-10-15T14:02:11Z",
      "entries": {
        "youtube.com":    { "durationMillis": 28800000 },
        "tiktok.com":     { "durationMillis": null },
        "instagram.com":  { "durationMillis": 3600000 }
      }
    }
  }
}
```

Decisões de modelagem:

- **`durationMillis` em vez de string ISO de duração.** `Duration` no Jackson
  serializa como segundos decimais ("28800.000000000") e é ambíguo de ler. Um
  `long` em milissegundos é inequívoco,timezone-free, e basta para a única
  conta que o projeto faz (comparar durações).
- **`null` = permanente**, espelhando `BlockEntry.expiresAt == null`.
- **Guarda-se a duração, não o instante absoluto.** Um perfil é uma receita,
  não um histórico. Guardar o `expiresAt` de quando foi salvo faria um perfil
  de 6 meses ser aplicado como "já vencido".
- **`version`** presente desde já, porque o formato vai evoluir e o
  `BlockStore` tem um padrão bem estabelecido de lidar com JSON ruim
  (`CorruptedStateException`, nada é apagado).

O que o snapshot guarda é o **prazo restante no momento do save**, não o
original. Se o usuário salvou o perfil quando o `youtube` tinha 8h, o perfil
guarda 8h.

## Comandos

```
siteblock profile save <nome>        # snapshot dos bloqueios ativos
siteblock profile list               # nomes + nº de domínios
siteblock profile show <nome>        # detalhe, com durações
siteblock profile delete <nome>      # pede confirmação
siteblock profile apply <nome>       # aplica a receita (merge por máximo)
siteblock profile unblock <nome>     # destrava tudo que estiver no perfil
```

`siteblock profile` sem subcomando mostra o `usage` do picocli (exit 2), como
o `SiteLockApplication.call()` já faz na raiz.

### `profile save`

- Lê o `blocks.json` **após** o `sweepExpired()` normal, então só entra no
  perfil o que está de fato ativo agora. Bloqueios expirados que ficaram
  pendentes não entram.
- Se o nome já existe, **recusa** e aponta `--force`. Sobrescrever uma receita
  sem querer é perder trabalho; `--force` continua existindo para quem quer.
- Valida/sanitiza o nome: não vazio, sem espaço, sem caractere de controle,
  razoavelmente curto (sugestão: até 64 chars). O nome vira chave de objeto
  JSON e aparece em path de log — não é lugar para `../../`.

### `profile apply` — a regra

Esta é a parte central, e a regra é: **o perfil só pode aumentar o prazo,
nunca encurtar.** Formalmente, trata "permanente" como infinito e o resultado
é `max(quanto resta agora, o que o perfil oferece)`.

| Estado atual no `blocks.json` | Perfil oferece | Ação | `Action` interno |
|---|---|---|---|
| ausente | 8h | bloqueia por 8h a partir de agora | `ADDED` |
| ausente | permanente | bloqueia permanente | `ADDED` |
| temporário, restam 30min | 8h | passa a 8h | `RAISED` |
| temporário, restam 10h | 8h | intocado | `KEPT` |
| temporário, restam 8h | 8h | intocado | `KEPT` |
| temporário, restam 30min | permanente | passa a permanente | `RAISED` |
| permanente | 8h | intocado | `KEPT` |
| permanente | permanente | intocado | `KEPT` |

Três consequências que valem a pena registrar, porque são o que torna a
operação segura de repetir:

- **Aplicar o mesmo perfil N vezes é idempotente.** A segunda aplicação só
  produz `KEPT`.
- **Aplicar um perfil mais fraco sobre um setup mais forte não faz estrago.**
  O caso "perfil de 2h sobre algo que já tem 8h" não encurta nada.
- **Um `KEPT` não é um no-op interno.** O `BlockEntry` precisa ficar
  *byte-idêntico* ao que já estava: mesmo `createdAt`, mesmo `expiresAt`.
  Reescrever o mapa inteiro do zero faria um bloqueio de 10h virar "10h a
  partir de agora", que é exatamente o encurtamento que a regra proíbe.

Domínio **expirado** no momento do apply não é caso especial: o
`sweepExpired()` roda antes e já o removeu, então ele cai na linha "ausente".

Saída do comando, no espírito do `status` que já existe:

```
Perfil 'trabalho' aplicado.
  youtube.com    — 8h (novo)
  tiktok.com     — já bloqueado, permanente (mantido)
  instagram.com  — 1h30m -> 8h (aumentado)
  reddit.com     — já bloqueado, 10h restantes, perfil offeria 8h (mantido)
  14 domínio(s) no perfil, 3 alterados, 11 mantidos.
```

### `profile unblock`

Mesma resistência do `unblock` de hoje, sem exceção:

- Roda `sweepExpired()` primeiro.
- **Mantém o passo de `--force`.** Sem ele, recusa tudo e sai com 3, exatamente
  como `UnblockCommand.java:99` faz. Desbloquear antes do prazo é operação de
  duas etapas em toda ferramenta, e quebrar isso só aqui cria um atalho que
  não existe em `unblock` individual. A flag é uma palavra; a dor que se quer
  remover são as 40 confirmações, não a flag.
- Lista os domínios do perfil hoje bloqueados, **todos**: os temporários
  ativos com o tempo restante de cada um, e os permanentes marcados como
  `permanente`. Mostra o total somado de tempo que está sendo descartado.
- Se a lista estiver vazia, libera sem perguntar nada.
- Se não estiver, uma confirmação digitada **antes de desbloquear qualquer
  um**. Não é uma confirmação por domínio como o `unblock` faz
  (`UnblockCommand.java:125`): 40 prompts de "SIM" são exatamente a atrito
  que esta feature existe para remover.
- Cancelar **não desfaz nada**: o comando não toca em domínio algum.
- Sair com o código 3 que o `unblock` já usa para "cancelado", para que
  scripts possam distinguir.

**Os permanentes entram na confirmação** — decisão do usuário, e uma
melhoria sobre o `unblock` individual, que os solta em silêncio
(`isTemporaryAndActive` é falso para eles, `UnblockCommand.java:103`).

A assimetria é proposital e vale um comentário no código. Um bloqueio
permanente é o compromisso **mais forte** que a pessoa assumiu, não o mais
fraco: é o que se faz quando se quer fechar a porta de vez. Se o
`profile unblock` os soltasse em silêncio, seria o caminho mais curto para
descartar uma trava ilimitada sem digitar uma tecla — exatamente o atalho que
o `--force` desta seção existe para não criar. O `unblock` individual fica
como está, por não ser escopo mudá-lo.

O `unblock` individual continua como está. Nada dele muda.

---

# Parte 2 — Daemon de expiração

## O problema, com o mecanismo exato

`expiresAt` é absoluto e está no disco, então o bloqueio sobrevive a reboot.
O que não existe é o **gatilho** da limpeza: `sweepExpired()` só é chamado no
início de cada comando (`BlockCommand.java:95`, `StatusCommand.java:43`,
`UnblockCommand.java:82`). Sem comando, a linha do `/etc/hosts` fica lá
para sempre. O README já admite isso em "Limites".

Resultado hoje: você bloqueia 4h, usa o PC 5h, o prazo venceu há uma hora e
o site segue preso até você rodar `siteblock status`. O comportamento é
fail-closed, o que é defensável, mas é surpreendente e não é o que a pessoa
prometeu quando digitou `--for 4h`.

## Comandos

```
siteblock daemon start [--interval <s>]   # sobe agora, em background
siteblock daemon stop                    # encerra
siteblock daemon status                  # pid, autostart ligado?, último sweep
siteblock daemon enable                  # instala e liga o autostart
siteblock daemon disable                 # remove o autostart
siteblock daemon __run-daemon            # interno, oculta
```

### O laço

`__run-daemon` é foreground, e o `daemon start` forka ele exatamente como o
`DnsStartCommand` forka `__run-daemon` (`DnsStartCommand.java:199`). PID file
em `~/.sitelock/daemon.json`, log em `~/.sitelock/daemon.log`.

Iteração:

1. `sweepExpired()`. Se removeu algo, logar uma linha por domínio.
2. Recarregar o estado e ver qual é o `expiresAt` ativo mais próximo.
3. Dormir `clamp(próximaExpiração - agora, 1s, --interval)`.
4. Se **não houver** nenhum temporário ativo, dormir 5 min em vez de 1 s.

O passo 4 é o que evita um daemon que fica polindo o disco a cada segundo
durante a madrugada. O passo 3 com teto de 1 s (`--interval`, padrão 60 s)
garante que um bloqueio criado por outro terminal é notado em no máximo um
minuto, sem precisar de watcher de arquivo.

`--once` faz um único sweep e sai. Serve para depurar e para teste.

Robustez, na ordem do que importa:

- Uma iteração que lança exceção **não mata o daemon**. Loga e segue para a
  próxima. O `CorruptedStateException` é o caso provável (usuário editou o
  JSON na mão); ele não deve virar um daemon que não renasce.
- Duas instâncias não podem rodar. Reaproveitar o padrão de PID + liveness do
  `DnsDaemonState`, com um lock de processo por baixo para cobrir a corrida
  entre o `start` e o boot.
- No `stop`, um `SweepResult` final roda antes de sair, para não deixar
  expirado para trás num shutdown.
- Shutdown hook, como o servidor DNS faz (`DnsRunDaemonCommand.java:62`).

### O que o daemon registra, e quem escreve

Seguindo o precedente do DNS (`DnsStartCommand.java:145`), quem escreve o
estado inicial é o **pai**, e o filho só apaga no final.

`~/.sitelock/daemon.json`:

| Campo | Quem escreve | Quando |
|---|---|---|
| `pid` | pai, no `daemon start` | uma vez |
| `startedAt` | pai | uma vez |
| `stateDir` cravado | pai | uma vez |
| `lastSweepAt` | **filho** | só quando o sweep removeu algo, ou no boot |

`lastSweepAt` ser reescrito a cada iteração seria uma escrita de root em
diretório do usuário a cada 60 s, o dia inteiro, só para atualizar um campo que
ninguém lê com essa frequência. Escrever **somente quando o sweep removeu
domínio** limita o tráfego ao que é raro por definição, e `status` mostra
exatamente o que a pessoa quer saber: "quando foi a última vez que alguma
coisa expirou sozinha".

**"Autostart ligado?"** sem elevação: no Linux/macOS é teste de existência do
artefato (`/etc/systemd/system/sitelock-sweeper.service`,
`/Library/LaunchDaemons/com.sitelock.sweeper.plist`); no Windows, `schtasks
/query`. Nenhum dos três precisa de root para *consultar*, então `daemon
status` roda sem `sudo`, igual ao `dns status` hoje.

**Trava de instância única:** `daemon.lock` com `FileLock` retido pelo filho
durante a vida inteira. Um `kill -9` **não** órfãoa o lock — o SO libera
`FileLock` na morte do processo, é a propriedade que o torna adequado aqui,
ao contrário de um lock por PID que sobreviveria ao processo. O PID file
continua existindo só para o `status` mostrar e para o pai detectar filho já
morto, como o `DnsDaemonState` faz.

### O que o daemon **não** faz

Ele não adiciona nada ao `/etc/hosts`. Só remove. `sweepExpired()` é a única
coisa que ele chama. Se alguém jogar `block` dentro dele, um `profile apply`
errado passa a parecer um bloqueio válido.

## Autostart

Instalação privilegiada, porque o daemon precisa escrever no `/etc/hosts`.
É uma escolha do usuário, já validada: o uso normal do SiteBlock já pede
root, então esse passo não é uma novidade na curva.

| SO | Mecanismo | Comando de instalação |
|---|---|---|
| Linux | unit em `/etc/systemd/system/sitelock-sweeper.service` | `systemctl enable --now` |
| macOS | plist em `/Library/LaunchDaemons/com.sitelock.sweeper.plist` | `launchctl bootstrap system` |
| Windows | Task Scheduler, `onlogon`, `/rl highest` | `schtasks /create /sc ONLOGON /rl HIGHEST /tn sitelock-sweeper` |

No Windows o disparo é **no login**, não no boot — decisão do usuário.
`ONSTART /ru SYSTEM` seria "iniciar com o computador" ao pé da letra, mas
troca a identidade do processo: `user.home` passa a ser o profile do SYSTEM,
todo arquivo criado no `stateDir` nasce root, e o
`SiteLockPaths.chownToRealUserIfSudo` não socorre (ele se apoia em
`SUDO_USER`, que não existe nesse contexto), passando a exigir devolução de
posse explícita. `ONLOGON` com `HIGHEST` dá o privilégio de root que o daemon
precisa sem trazer esse custo, e é o análogo mais próximo do unit de sistema
que os outros dois SOs obrigatoriamente são.

O que se perde é o caso "máquina ligada, ninguém logado". Não importa: sem
sessão aberta não há navegador para estar bloqueado.

Por que raiz e não usuário: unit de usuário e LaunchAgent rodam sem
privilégio, e sem privilégio o daemon não consegue tirar nada do
`/etc/hosts`. Daí a decisão.

`daemon disable` reverte cada um dos três e remove o artefato.

### Os caminhos absoluteos precisam ser cravados no artefato

**Este é o ponto que faz a feature funcionar ou não, e é fácil de errar.**

`SiteLockPaths.getRealUserHome()` (`SiteLockPaths.java:110`) só recupera a home
do usuário real a partir de `SUDO_USER`. Um unit de **sistema** (sem `User=`),
uma LaunchDaemon e um `schtasks /ru SYSTEM` rodam como root **sem
`SUDO_USER`**. Nessas condições `user.home` é `/root` ou
`C:\Windows\System32\config\systemprofile`, e o daemon leria um `blocks.json`
que não é o mesmo que o do `sudo siteblock block`. Resultado: a feature
inteira é um no-op **silencioso** — o daemon roda, faz sweep, não acha
nada, e ninguém percebe.

Portanto o artefato gerado **não pode depender de `user.home` em momento
nenhum**. O `daemon enable` resolve os caminhos no instante da instalação e
os embute no ExecStart/`ProgramArguments`/`/tr`:

```
-Dsitelock.stateDir=/home/<user>/.sitelock
-Dsitelock.hostsFile=/etc/hosts
```

O gancho já existe: `SiteLockPaths.getStateDir()` e `getHostsPath()` leem
`System.getProperty` **antes** de qualquer outra coisa
(`SiteLockPaths.java:30` e `:255`). Nada em `SiteLockPaths` precisa mudar.

E o mesmo caminho tem que ser passado ao `daemon start` manual, senão `start`
e `enable` leem arquivos diferentes.

Isso vale, por extensão, para `os hosts.lock`/`blocks.json.lock`: eles são
resolvidos por `resolveSibling` a partir do state file
(`BlockStore.java:39`, `CliSupport.java:46`), então cravar o `stateDir` já
resolve os dois de brinde.

Teste obrigatório: um `daemon start` disparado com `SUDO_USER` limpo e
`user.home` apontando para um diretório vazio tem que operar sobre o
`stateDir` cravado, não sobre o `user.home`. É exatamente o bug acima, e ele
não aparece em nenhum teste feito à mão.

### Detalhes que vão quebrar se ninguém pensar neles

**Caminho absoluto do jar.** Um unit do systemd não tem o seu `PATH`, não tem
o `sudo` da sua sessão, e não passa pelo `run.sh`. A unit gerada precisa
embutir o caminho absoluto do `java` e do jar/classepath no momento do
`enable`.

Atenção: **a propriedade `siteblock.jar` não existe no projeto hoje.** Só
existe `siteblock.bin`, setado em `bin/siteblock:33`, e `run.sh`/`run.bat` não
passam `-D` nenhum. Então não há como "usar a propriedade que já existe". As
opções, e a escolha:

1. Passar a definir `-Dsiteblock.jar=<abs>` em `bin/siteblock`, `run.sh` e
   `run.bat`, e adicionar `--jar <caminho>` no `daemon enable`. É a solução
   real, mas toca três arquivos de launcher.
2. Cair para `java.class.path`, que é o que `DnsStartCommand.buildChildCommand`
   já faz (`DnsStartCommand.java:203`). Válido **apenas** se `enable` e
   `start` vierem do mesmo lugar — o que costuma ser verdade (mesmo `bin/`),
   mas não é garantido.

Faz **as duas**: propriedade nova nos launchers, com o classpath como
fallback. O `--jar` cobre o caso de instalação manual.

**Jar em diretório gravável pelo usuário = escalonamento de privilégio.** O
daemon vai rodar como root e executar código que o usuário pode trocar. No
install documentado isso é verdade por construção — o jar vive em
`~/Projects/.../target/` e o wrapper é um symlink em `~/.local/bin` — então
um portão rígido **quebraria o caminho de instalação no primeiro dia**.
Consequência: **aviso no `enable` e no `start`, nunca recusa**, e sem flag
`--allow-insecure-path` (não há portão para burlar). O alvo aqui é o
usuário distraído, não o atacante; quem quer burlar já edita o `/etc/hosts`
com `sudo`, que é uma linha a mais.

**systemd ausente.** Em container, WSL, ou distro sem systemd, `daemon
enable` não tem o que escrever. Deve detectar (`/run/systemd/system` não
existe) e imprimir as instruções manuais, em vez de fingir que funcionou.
Idem `launchctl` ausente no macOS.

**Elevação do próprio `enable`.** Se `daemon enable` rodar sem root, ele
detecta (`user.name != root`, ou o parent não é gravável) e diz o comando
para repetir com `sudo` — reusando o `PermissionAdvisor` que já existe.

### Descoberta pelo usuário

`daemon` não é obrigatório para o SiteBlock funcionar. Mas:

- `block --for` quando não há daemon rodando nem autostart ligado **avisa**:
  "o prazo só vai ser aplicado quando você rodar outro comando do siteblock;
  rode `sudo siteblock daemon enable` para isso acontecer sozinho."
- `profile apply` avisa igual, quando o perfil criou algum temporário. É o
  mesmo furo pelo caminho novo, e a pessoa não deve descobrir isso só
  quando o site travar.
- `status` mostra uma linha sobre o daemon quando existe temporário ativo.

Definições, para não virar ruído:

- **Gatilho:** avisa quando **não há daemon vivo** *ou* **não há autostart
  instalado**. Os dois casos são defeito do ponto de vista do usuário, e
  mesmo com autostart instalado o daemon pode estar naquele boot atrasado.
- **Canal: stdout**, com o prefixo `AVISO:`, igual ao que `StatusCommand`
  já usa (`StatusCommand.java:55`). Erro continua indo para stderr; isto não é
  erro, é informação.
- **`status` só fala quando há temporário ativo.** Block permanente não tem
  prazo para vencer, então não há o que avisar.

O aviso é o que fecha o ciclo: sem ele, a feature continua invisível e o
comportamento antigo permanece o que a pessoa vai achar que é normal.

---

# Parte 3 — Operação em lote no hosts

Isto não é uma feature; é um requisito de correção, e é o achado mais
importante da leitura do código.

`HostsFileManager.blockInFile` faz, **por domínio**: `ensureLockParent`,
`channel.lock()`, `readHosts`, e depois `modifyHosts` com **outro** lock,
`readHosts` de novo, comparação de concorrência e escrita atômica. Para um
perfil de 40 domínios:

- 40 ciclos de read + lock + escrita de `/etc/hosts`.
- A verificação otimista de `modifyHosts` compara o conteúdo contra o que foi
  lido milissegundos antes (`HostsFileManager.java:319`). Se **qualquer** um
  dos 40 passos achar o arquivo mudado, ele lança. O `apply` morre no
  domínio *k*, com os *k-1* anteriores já aplicados — estado parcial, e o
  usuário recebeu um erro depois de metade do trabalho ter sido feito.

O padrão por domínio do `BlockCommand` continua correto lá, onde a lista é
tipicamente de 1 a 3 domínios. Para lote, ele vira o problema.

Portanto: **adicionar operação em lote**, `blockInFile(hosts, backup, lock,
List<String>)` e `unblockInFile(..., List<String>)`, com uma leitura, uma
transformação composta e uma escrita, tudo sob **um** lock. A
implementação reaproveita `addDomain`/`removeDomain` em cima do conteúdo já
lido; o trabalho novo é a casca (lock único, uma escrita).

O caminho por domínio continua existindo — as assinaturas de um argumento
delegam para a de lote com `List.of(domain)`. Comportamento idêntico, um só
código.

### O lote precisa passar pela interface `SiteBlocker`

Um `default` no meio do caminho seria exatamente o tipo de coisa que
funciona nos testes e falha na CLI, porque `CliSupport.newService`
(`CliSupport.java:42`) entrega um `SiteBlocker` — e é por ele que os comandos
alcançam o hosts. Os overrides `--hosts-file`/`--state-file` (que os testes
usam) só funcionam se o lote passar por essa mesma costura.

Então a mudança de API em `sitelock-core` é: `blockAll`/`unblockAll` no
`SiteBlocker`, implementados em `HostsFileBlocker` delegando aos métodos de
lote do `HostsFileManager`. Sem default — a interface tem uma única
implementação, e forçar o compilador a achar as duas é mais barato que caçar
um `UnsupportedOperationException` em produção.

### E `sweepExpired()` também deve usar o lote

`BlockService.sweepExpired()` hoje faz `blocker.unblock(domain)` **por
domínio expirado** (`BlockService.java:56`). O daemon chama exatamente esse
método a cada iteração. Se 40 domínios vencem juntos, são 40 passes de
lock + read + escrita atômica do `/etc/hosts` — o mesmo custo que motivou
esta seção, agora no caminho que mais importa.

Trocar o laço interno por **uma** chamada `blocker.unblockAll(expirados)`.
Não muda assinatura, não muda resultado observável, e é a mudança de melhor
relação custo/benefício do documento inteiro: sem ela, o daemon fica lento
justamente no pior dia.

### Ordem estado-vs-hosts e a janela de kill

O estado vai primeiro, e o hosts depois — mesma ordem do `BlockCommand`
(`BlockCommand.java:118` antes de `:130`). O inverso tem um pior modo de
falha: entrada no hosts sem registro no estado significa um domínio preso
para sempre, porque o `sweepExpired()` varre o JSON, não o hosts.

A ordem escolhida tem a janela oposta: se o processo for **morto** entre os
dois passos, o `blocks.json` diz "bloqueado" e o hosts não foi escrito, então
o domínio está de fato livre. `status` vai dizer que está bloqueado. É
fail-open e silencioso.

Com o lote, `profile apply` faz: `sweepExpired` → merge em memória → **um**
`save` do `blocks.json` → **um** `blockAll` no hosts. Se o lote falhar, reverte
o `blocks.json` inteiro e reporta a falha. É o mesmo padrão de rollback que o
`BlockCommand` já faz por domínio, aplicado uma vez.

Aceito, porque matar o processo no meio de uma janela de milissegundos é
raro, e fail-open é melhor que fail-closed aqui. **Mas** a limpeza tem como
fechar a janela, e fica registrado como melhoria se sobrar escopo: fazer o
`sweepExpired()` também.remove do hosts os domínios que estão no arquivo e
**não** estão no estado. Isso é uma reconciliação em uma direção só, é
seguro, e transforma "morto no meio" em "corrigido na próxima volta".

---

# Testes

O projeto tem suíte JUnit 5 e suíte roda no CI em Linux **e** Windows
(`.github`), com TDD esperado.

## Núcleo (sem I/O de sistema, `Clock` injetável — o padrão já usado)

- `ProfileMergeTest` — a tabela inteira da regra, incluindo o `KEPT` que
  preserva `createdAt` e `expiresAt` byte-idênticos, e a idempotência de
  aplicar duas vezes.
- `ProfileStoreTest` — round-trip, arquivo ausente, JSON corrompido
  (`CorruptedStateException` sem apagar nada), nome inválido.
- `HostsFileManagerBulkTest` — lote bate com o resultado de N chamadas
  individuais, ordem das linhas, idempotência, bloco vazio remove marcadores.
- `SweeperScheduleTest` — dado o estado, qual é o próximo intervalo de sono.

## CLI

- `profile save/list/show/delete/apply/unblock` com `--hosts-file` e
  `--state-file` apontando para temp, como `CliCommandsTest` já faz.
- Confirmação do unlock em massa: sem `--force` recusa e devolve 3; entrada
  errada **não** desbloqueia nada; `SIM` desbloqueia; domínios já expirados
  passam sem prompt.

## Autostart — testar o artefato, não o agendador

O CI roda em Linux e Windows, nunca em macOS (`schtasks` e `launchctl` não
existem nos outros). Testar o *agendador* seria impossível; testar o
**artefato gerado** é possível, é puro, e é onde o bug do §"caminhos
cravados" mora.

- `AutostartArtifactTest`, um caso por `OsType`: gera o unit, o plist e o
  comando do `schtasks` a partir de um `Path` e `java.class.path` conhecidos, e
  afirma que o texto gerado **contém** o `stateDir` e o `hostsFile`
  absolutos. É uma asserção de substring sobre uma string — e é
  exatamente ela que pega o daemon apontando para `/root`.
- `disable` é simétrico a `enable`: para os três, o `disable` remove o que o
  `enable` criou, sem root quando possível.
- A chamada ao sistema (`systemctl`, `launchctl`, `schtasks`) entra atrás de
  um runner injetável, para o teste não precisar do binário real.

## Manual, documentado no README

- `daemon start` → `block --for 30s` → esperar → verificar que a linha sumiu
  do hosts **sem rodar nenhum comando**.
- `daemon enable` → reboot → confirmar que o processo subiu e que
  `daemon status` aponta para o `stateDir` certo.

---

# Decisões minhas (o usuário não escolheu, podiam mudar sem friction)

| # | Decisão | Por quê |
|---|---|---|
| 1 | `durationMillis` numérico em vez de string ISO | inequívoco, sem timezone, sem o formato decimal do Jackson |
| 2 | Guardar a **duração** no perfil, não o `expiresAt` | perfil é receita, não histórico; data salva de meses atrás é inútil |
| 3 | Perfil-permanente sobre temporário **vira permanente** | simetria do `max`; era o único canto não coberto pela fala do usuário |
| 4 | `profile save` recusa sobrescrever sem `--force` | perder a receita sem querer é o pior erro possível aqui |
| 5 | Confirmação do unlock em massa é **uma**, não uma por domínio | 40 prompts são o atrito que a feature remove |
| 6 | `daemon` no singular, com `enable`/`disable` | vocabulário de systemd, que é o que metade dos usuários já conhece |
| 7 | Sono adaptativo, teto padrão 60 s, ocioso 5 min | barato e mesmo assim reage a `block` de outro terminal em ≤ 1 min |
| 8 | Assinaturas de hosts de 1 argumento delegam para a de lote | um só código, comportamento idêntico |
| 9 | Caminho inseguro é **aviso**, nunca recusa, sem flag de bypass | o install documentado é gravável pelo usuário; um portão rígido quebraria o dia 1 |
| 10 | `-Dsiteblock.jar` nos três launchers, com classpath como fallback | a propriedade não existe hoje; sem ela o autostart não tem o que executar |
| 11 | `sweepExpired()` migra para o lote junto | é o mesmo custo que motivou a Parte 3, no caminho que o daemon mais exercita |
| 12 | `lastSweepAt` só é escrito quando o sweep removeu algo | evita escrita de root em diretório do usuário a cada 60 s para nada |
| 13 | Lock de instância única é `FileLock`, não PID | `kill -9` não órfãoa um `FileLock`; um lock por PID sobreviveria ao processo |
| 14 | "autostart ligado?" por teste de existência / `schtasks /query` | `daemon status` deve rodar sem `sudo`, como `dns status` já roda |
| 15 | `profile save` com zero bloqueios ativos **erra**, não cria perfil vazio | perfil vazio não faz nada, e criá-lo em silêncio esconde o erro |
| 16 | `reset --yes` **não** apaga `profiles.json` | perfil é receita, não é estado de bloqueio |
| 17 | Aviso de daemon em stdout, prefixo `AVISO:` | segue o que `StatusCommand` já faz; não é erro, logo não vai para stderr |
| 18 | Reconciliação estado↔hosts como **melhoria**, não agora | é o item mais defensável da fila, e não é necessário para o resto |
| 19 | Windows dispara no login (`ONLOGON`/`HIGHEST`), não no boot | **usuário** — privilege de root sem o custo de posse do profile SYSTEM |
| 20 | Permanentes entram na confirmação do `profile unblock` | **usuário** — é o compromisso mais forte, não o mais fraco; soltá-lo calado seria o atalho que o `--force` existe para impedir |

# Aberto para o builder

- Como exatamente classificar um nome de perfil (regex, ou lista de
  caracteres proibidos).
- Se `profile` merece `--json` para saída legível por script.
- Se vale um `siteblock profile rename` ou se re-salvar resolve.
- Como reportar progresso de 40+ domínios sem poluir o stdout.
- Se os 4 últimos minutos de texto do §"caminhos cravados" viram um teste de
  integração ou se a asserção de substring do `AutostartArtifactTest` basta.