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
- Lista os domínios do perfil que estão **temporários e ativos**, com o tempo
  restante de cada um.
- Se a lista estiver vazia, libera sem perguntar nada (não há nada a
  arriscar).
- Se não estiver, exige confirmação digitada **antes de desbloquear
  qualquer um**, mostrando a lista e o total de tempo que está sendo
  descartado. Não é uma confirmação por domínio como o `unblock` faz — 40
  prompts de "SIM" são exatamente a atrito que esta feature existe para
  remover. É uma confirmação para a operação inteira.
- Cancelar **não desfaz nada**: o comando não toca em domínio algum, nem
  nos que já estariam expirados naturalmente.
- Sair com o código 3 que o `unblock` já usa para "cancelado", para que
  scripts possam distinguir.

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
| Windows | Task Scheduler, trigger `onlogon`, `/rl highest` | `schtasks /create` |

Por que raiz e não usuário: unit de usuário e LaunchAgent rodam sem
privilégio, e sem privilégio o daemon não consegue tirar nada do
`/etc/hosts`. Daí a decisão.

`daemon disable` reverte cada um dos três e remove o artefato.

### Detalhes que vão quebrar se ninguém pensar neles

**Caminho absoluto do jar.** Um unit do systemd não tem o seu `PATH`, não tem
o `sudo` da sua sessão, e não passa pelo `run.sh`. A unit gerada precisa
embutir o caminho absoluto do `java` e do jar/classepath no momento do
`enable`. Se a propriedade `siteblock.jar` não estiver definida, cai para
`java.class.path` — que é o que o `DnsStartCommand.buildChildCommand` já faz
(`DnsStartCommand.java:203`), e que é válido só se o `enable` e o `start`
vierem do mesmo lugar. Override manual: `daemon enable --jar <caminho>`.

**Jar em diretório gravável pelo usuário = escalonamento de privilégio.** O
daemon vai rodar como root e executar código que o usuário pode trocar. Se o
jar (ou o diretório dele) for gravável por não-root, o `enable` deve
**avisar**, e o `start` deveria recusar sem `--allow-insecure-path`. Isso não
é UID nem nome de usuário: o ponto é que o risco nasce quando se troca
"eu digito sudo e veja o que estou executando" por "o sistema faz por mim".

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
- `status` mostra uma linha sobre o daemon quando existe temporário ativo,
  pelo mesmo motivo.

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

Com isso, `profile apply` faz: `sweepExpired` → merge em memória → **um**
`save` do `blocks.json` → **um** `blockInFile` em lote. Se o batch do hosts
falhar, reverte o `blocks.json` inteiro e reporta a falha. É o mesmo padrão
de rollback que o `BlockCommand` já faz por domínio, aplicado uma vez.

### Ordem estado-vs-hosts e a janela de kill

O estado vai primeiro, e o hosts depois — mesma ordem do `BlockCommand`
(`BlockCommand.java:118` antes de `:130`). O inverso tem um pior modo de
falha: entrada no hosts sem registro no estado significa um domínio preso
para sempre, porque o `sweepExpired()` varre o JSON, não o hosts.

A ordem escolhida tem a janela oposta: se o processo for **morto** entre os
dois passos, o `blocks.json` diz "bloqueado" e o hosts não foi escrito, então
o domínio está de fato livre. `status` vai dizer que está bloqueado. É
fail-open e silencioso.

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
- Confirmação do unlock em massa: entrada errada **não** desbloqueia nada;
  `SIM` desbloqueia; domínios já expirados passam sem prompt.

## Integração (manual, documentado no README)

- `daemon start` → `block --for 30s` → esperar → verificar que a linha sumiu
  do hosts **sem rodar nenhum comando**.
- `daemon enable` → reboot → confirmar que o processo subiu.

---

# Decisões minhas (o usuário não escolheu, podiam mudar sem friction)

| # | Decisão | Por quê |
|---|---|---|
| 1 | `durationMillis` numérico em vez de string ISO | inequívoco, sem timezone, sem o formato decimal do Jackson |
| 2 | Guardar a **duração** no perfil, não o `expiresAt` | perfil é receita, não histórico; data salva de meses atrás é inútil |
| 3 | Perfil-permanente sobre temporário **vira permanente** | simetria do `max`; era o único canto não coberto pela fala do usuário |
| 4 | `profile save` recusa sobrescrever sem `--force` | perder a receita sem querer é o pior erro possível aqui |
| 5 | Confirmação do unlock em massa é **uma**, não uma por domínio | 40 prompts são a atrito que a feature remove |
| 6 | `daemon` no singular, com `enable`/`disable` | vocabulário de systemd, que é o que metade dos usuários já conhece |
| 7 | Sono adaptativo, teto padrão 60 s, ocioso 5 min | barato e mesmo assim reage a `block` de outro terminal em ≤ 1 min |
| 8 | Assinaturas de hosts de 1 argumento delegam para a de lote | um só código, comportamento idêntico |
| 9 | Aviso (não bloqueio) se o jar é gravável por não-root | avisar é o suficiente; o alvo é descuido, não atacante |
| 10 | Reconciliação estado↔hosts como **melhoria**, não agora | é o item mais defensável da fila, e não é necessário para o resto |

# Aberto para o builder

- Como exatamente classificar um nome de perfil (regex, ou lista de
  caracteres proibidos).
- Se `profile` merece `--json` para saída legível por script.
- Se vale um `siteblock profile rename` ou se re-salvar resolve.
- Como reportar progresso de 40+ domínios sem poluir o stdout.