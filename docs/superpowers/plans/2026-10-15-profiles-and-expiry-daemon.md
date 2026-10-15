# Perfis de bloqueio + daemon de expiração — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer `siteblock` desbloquear sozinho quando o prazo vence (daemon que sobe no boot) e dar a ele conjuntos de domínios salvos com nome (`profile save/apply/unblock`).

**Architecture:** Duas features que dividem a mesma base. (1) Uma camada de operação **em lote** no arquivo de hosts: hoje cada domínio custa um lock + uma escrita atômica, o que quebra um `apply` de 40 domínios no meio. A camada em lote faz uma leitura, uma transformação composta e uma escrita, sob um lock só, e a assinatura de um domínio passa a delegar para ela. (2) Sobre essa base, os perfis (`~/.sitelock/profiles.json`, um snapshot de durações restantes com uma regra de `max` que só aumenta prazo) e o daemon de expiração (um laço que só chama `sweepExpired()`, com sono adaptativo, PID file e lock de instância única, instalado no boot por systemd/launchd/Task Scheduler).

**Tech Stack:** Java 26, Maven multi-módulo (`core`, `dns`, `app`), picocli, Jackson, JUnit 5. Sem dependência nova.

**Spec:** `docs/superpowers/specs/2026-10-15-profiles-and-expiry-daemon-design.md`

## Global Constraints

- **Java 26.** O CI usa Temurin 26; o código não compila em versões anteriores.
- **Sem dependência nova.** Só o que já está no `pom.xml` dos três módulos.
- **Português no código e na saída.** Nomes de classe/método em inglês (padrão do projeto); mensagens de usuário em português.
- **O daemon precisa de root** (escreve no `/etc/hosts`). `daemon status` **não** pode precisar de root.
- **Nenhum teste toca `/etc/hosts` real, nem porta 53, nem pede senha.** Todo teste usa `@TempDir` e `--hosts-file`/`--state-file`. `mvn test` roda inteiro sem privilégio.
- **O CI roda em Linux e Windows, nunca em macOS.** `launchctl` não existe nos outros: o caminho do macOS é testado pela **string gerada**, não pelo agendador.
- **Caminhos absolutos cravados.** O artefato de autostart não pode depender de `user.home` nem de `SUDO_USER` em momento algum; `enable` resolve e embute `-Dsitelock.stateDir=` e `-Dsitelock.hostsFile=` no ExecStart/ProgramArguments/`/tr`.
- **Comandos que escrevem no estado, estado primeiro e hosts depois**, com rollback em falha — o padrão de `BlockCommand.java:116-146`.
- **`sweepExpired()` é chamado no início de todo comando da CLI** que toca bloqueio (`BlockCommand.java:95`, `StatusCommand.java:43`, `UnblockCommand.java:82`) e **no início de toda iteração do daemon**.
- **Códigos de saída já em uso:** 0 sucesso, 1 erro, 2 uso incorreto, 3 cancelado/recusado (`UnblockCommand.java:113`).
- **`CorruptedStateException` nunca apaga arquivo.** O padrão de `BlockStore` vale para o `ProfileStore`.
- **Cada commit é verde sozinho.** `mvn -B test` passa entre um e outro.

## Deviations da spec (achadas pela revisão do documento)

Quatro pontos em que este plano **não** faz o que a spec diz, porque do jeito que ela diz a feature não funciona. Todas estão detalhadas na task correspondente:

1. **O próprio daemon escreve o `daemon.json`.** A spec dá a escrita do `pid` para o pai do `daemon start`, mas o autostart não tem pai: o systemd/launchd/schtasks executa `__run-daemon` direto. Do jeito da spec, depois de um boot com autostart não existe `pid` — `daemon status` diria "parado" com o processo rodando, `daemon stop` não teria o que matar, e o aviso de "não há daemon vivo" dispararia numa máquina configurada corretamente.
2. **O daemon faz flush do cache DNS depois de um sweep que removeu algo.** A spec diz que `sweepExpired()` é a única coisa que ele chama. Mas todo outro caminho que mexe no hosts chama `CliSupport.flushDnsBestEffort()`; sem isso a linha sai do `/etc/hosts`, o daemon registra o sweep, e o navegador continua resolvendo do cache — a feature fica invisível justamente no caso que ela existe para atender.
3. **O artefato de autostart embute o nome do usuário e o daemon devolve a posse dos arquivos.** Um unit de sistema e uma LaunchDaemon rodam como root sem `SUDO_USER`, então `chownToRealUserIfSudo` não socorre e o `blocks.json` nasce do root. A spec promete duas vezes que `daemon status` roda sem `sudo`; sem devolver a posse, essa promessa quebra no primeiro sweep.
4. **`profile unblock` sem `--force` recusa qualquer domínio ativo, inclusive permanente.** A spec diz as duas coisas ("sem `--force`, recusa tudo" e "os permanentes entram na confirmação"), e são incompatíveis: com o gate só nos temporários, um perfil só de permanentes sairia sem `--force` e sem confirmação — exatamente o atalho que o `--force` existe para impedir. A leitura que fecha o contraditório: `--force` é o gate, a confirmação digitada é a camada de dentro, e ela inclui os permanentes.

Correções factuais na spec (o documento já está errado sobre o código; o plano usa as referências certas):

- `SiteLockPaths.java` tem 131 linhas, não 255: `getHostsPath()` está em `:54`, e a leitura da property em `:55`.
- `AVISO:` (maiúsculo) já existe em `DnsStartCommand.java:193`; `StatusCommand.java:55` e `BlockCommand.java:64` usam `Aviso:`. Este plano usa **`AVISO:`**, como a decisão 17 da spec manda — é uma escolha deliberada, não um deslize: são mensagens de outra classe (falta de configuração, não estado).
- `BlockService.java:56` não é a chamada de `unblock`; o laço abre em `:58` e a chamada está em `:60`.
- O custo por domínio do `blockInFile` é pior do que a spec estima: **três** locks e três leituras (`:290` e `:291` chamam `readHostsLocked`, e `modifyHosts` lê de novo), não duas. O `unblockInFile` são duas. A conclusão — lote é necessário — não muda.
- `sweepExpired()` não é chamado por todo comando: `reset`, os cinco subcomandos de `dns` e a raiz não chamam, e o `status` só chama quando o hosts é gravável.

## Review Focus

Cinco condições que a spec implica e que nenhum teste existente exercita — a ordem é a de quem mais morde primeiro:

1. **Prazo vence com o PC desligado.** O bloqueio sobrevive ao reboot (o `expiresAt` é absoluto), então o pior caso éexactly este: o usuário desliga, volta 3 horas depois, e o site segue preso porque nada rodou. É o motivo da feature existir — coberto pelo teste `pinnedStateDirIsUsedWhenSudoUserIsAbsent` da Task 10.
2. **Duas instâncias do daemon ao mesmo tempo** (`sudo siteblock daemon start` com o autostart já ativo): dois processos escrevendo `/etc/hosts` ao mesmo tempo. Coberto por `secondAcquireFailsWhileHeld` (Task 9) e `startRefusesWhenDaemonAlreadyRunning` (Task 10).
3. **`blocks.json` corrompido com o daemon no ar.** A spec exige que a exceção não mate o daemon — se matar, o usuário nunca mais recebe expirações e não recebe nenhum aviso. Coberto por `iterationSurvivesCorruptedState` (Task 10).
4. **Falha no meio do `profile apply`** deixa estado parcial: 39 domínios bloqueados e o estado JSON divergindo do `/etc/hosts`. Coberto por `rollbackWhenBlockAllFails` (Task 6).
5. **Máquina sem systemd** (container, WSL, distro enxuta): `daemon enable` não tem onde escrever e não pode fingir que funcionou. Coberto por `linuxWithoutSystemdPrintsManualInstructions` (Task 12).

---

## File Structure

### `sitelock-core` — `com.sitelock.core`

| Arquivo | Responsabilidade |
|---|---|
| `HostsFileManager.java` (mod) | ganha as assinaturas de lote; as de um domínio delegam |
| `SiteBlocker.java` (mod) | `blockAll`/`unblockAll` na interface, sem `default` |
| `HostsFileBlocker.java` (mod) | implementa as duas, delegando ao `HostsFileManager` |
| `BlockService.java` (mod) | `sweepExpired()` destrava em uma chamada |
| `ProfileEntry.java` (novo) | um domínio no perfil: `durationMillis` (`null` = permanente) |
| `Profile.java` (novo) | um perfil: `createdAt`, `updatedAt`, `entries` |
| `ProfileDocument.java` (novo) | raiz do `profiles.json`: `version` + mapa de perfis |
| `ProfileNames.java` (novo) | valida nome de perfil (`^[A-Za-z0-9_-]{1,64}$`) |
| `ProfileStore.java` (novo) | persistência atômica com lock, no padrão do `BlockStore` |
| `ProfileMerge.java` (novo) | a regra do `max`: decisão por domínio, puro |
| `SweeperSchedule.java` (novo) | quanto o daemon dorme, puro |
| `ExpirationDaemonState.java` (novo) | `daemon.json`: pid, início, stateDir, último sweep |
| `ExpirationDaemonLock.java` (novo) | `daemon.lock` com `FileLock` retido pela vida do processo |
| `CommandRunner.java` (novo) | `int run(List<String>)`, extraído de `DnsCacheFlusher.CommandRunner` |
| `AutostartPaths.java` (novo) | os dados que o artefato precisa, já resolvidos e absolutos |
| `AutostartArtifact.java` (novo) | **gera o texto** do unit, do plist e do comando `schtasks` — puro |
| `AutostartInstaller.java` (novo) | escreve/remove o artefato e chama o agendador |

### `sitelock-app` — `com.sitelock.cli`

| Arquivo | Responsabilidade |
|---|---|
| `SiteLockApplication.java` (mod) | registra `ProfileCommand` e `DaemonCommand` |
| `CliSupport.java` (mod) | `resolveProfilesFile`, `warnIfNoDaemon`, caminho do jar |
| `ProfileCommand.java` (novo) | pai `profile`; sem subcomando mostra uso (2) |
| `ProfileSaveCommand.java`, `ProfileListCommand.java`, `ProfileShowCommand.java`, `ProfileDeleteCommand.java` (novos) | o `--force` do save, a listagem, o detalhe, a remoção com confirmação |
| `ProfileApplyCommand.java` (novo) | merge + 1 `save` + 1 `blockAll` + rollback |
| `ProfileUnblockCommand.java` (novo) | destrava o perfil inteiro com **uma** confirmação |
| `DaemonCommand.java` (novo) | pai `daemon`; sem subcomando mostra uso (2) |
| `DaemonStartCommand.java`, `DaemonStopCommand.java`, `DaemonStatusCommand.java` (novos) | o ciclo de vida |
| `DaemonEnableCommand.java`, `DaemonDisableCommand.java` (novos) | instalação no boot |
| `DaemonRunCommand.java` (novo) | `__run-daemon`, oculto: o laço de expiração |
| `BlockCommand.java` (mod), `StatusCommand.java` (mod) | o aviso de daemon ausente |

### Launchers e docs

`bin/siteblock`, `run.sh`, `run.bat` (mod) · `README.md` (mod)

---

### Task 1: operações em lote no arquivo de hosts

**Files:**
- Modify: `sitelock-core/src/main/java/com/sitelock/core/HostsFileManager.java:287-299`
- Test: `sitelock-core/src/test/java/com/sitelock/core/HostsFileManagerBulkTest.java`

**Interfaces:**
- Consumes: os privados `addDomain(String, String)` e `removeDomain(String, String)`, `readHostsLocked(Path, Path)`, `modifyHosts(Path, Path, Path, String, String)` — todos já existentes nesta classe.
- Produces:
  - `public static void blockInFile(Path hostsPath, Path backupPath, Path lockFile, List<String> domains) throws IOException`
  - `public static void unblockInFile(Path hostsPath, Path backupPath, Path lockFile, List<String> domains) throws IOException`
  - as assinaturas de um domínio (`…, String domain`) passam a existir como delegações: `blockInFile(h, b, l, List.of(domain))`.

- [ ] **Step 1: Escreva o teste que falha**

Crie `HostsFileManagerBulkTest` com `@TempDir Path tmp`, escrevendo `127.0.0.1 localhost\n` em `tmp/hosts` e usando `tmp/hosts.backup` e `tmp/hosts.lock`. Casos:

- `bulkBlockMatchesIndividualCalls()` — bloqueia `List.of("tiktok.com","youtube.com")` num arquivo e um por vez em outro; os dois conteúdos são `assertEquals`.
- `bulkUnblockRemovesEveryDomain()` — bloqueia em lote, desbloqueia em lote, e o resultado é `assertEquals` ao conteúdo original; o bloco `# SITELOCK-START`/`# SITELOCK-END` some.
- `bulkIsIdempotent()` — bloqueia a mesma lista duas vezes; a segunda não duplica nenhuma linha (conte as ocorrências de `127.0.0.1 tiktok.com` e espere 1).
- `emptyListLeavesFileUntouched()` — `blockInFile(..., List.of())` e `unblockInFile(..., List.of())` não lançam e o conteúdo é byte-idêntico ao anterior.
- `singleArgumentOverloadMatchesBatch()` — `blockInFile(h, b, l, "tiktok.com")` produz arquivo `assertEquals` ao de `blockInFile(h, b, l, List.of("tiktok.com"))`.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=HostsFileManagerBulkTest`
Expected: erro de compilação — `List` não corresponde a nenhum `blockInFile` existente.

- [ ] **Step 3: Implemente as duas assinaturas em `HostsFileManager`**

As de lote fazem **uma** leitura e **uma** escrita: uma chamada a `readHostsLocked`, um `for` aplicando `addDomain`/`removeDomain` sobre o resultado, e uma chamada a `modifyHosts` com o conteúdo transformado e o original. As assinaturas de um domínio delegam com `List.of(domain)`. Não mude `modifyHosts`, `readHostsLocked` nem `addDomain`/`removeDomain`.

- [ ] **Step 4: Rode o teste e veja passar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=HostsFileManagerBulkTest`
Expected: PASS, 5 testes.

- [ ] **Step 5: Rode a suíte inteira**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS (a assinatura de um domínio é a mesma de antes, então `HostsFileManagerTest` segue verde sem alteração).

- [ ] **Step 6: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/HostsFileManager.java sitelock-core/src/test/java/com/sitelock/core/HostsFileManagerBulkTest.java
git commit -m "Núcleo: operações em lote no arquivo de hosts"
```

---

### Task 2: `sweepExpired()` destrava em lote

**Files:**
- Modify: `sitelock-core/src/main/java/com/sitelock/blocker/SiteBlocker.java`
- Modify: `sitelock-core/src/main/java/com/sitelock/blocker/HostsFileBlocker.java`
- Modify: `sitelock-core/src/main/java/com/sitelock/core/BlockService.java:56-73`
- Test: `sitelock-core/src/test/java/com/sitelock/core/BlockServiceSweepTest.java`

**Interfaces:**
- Consumes: `blockInFile`/`unblockInFile` de `List` (Task 1).
- Produces:
  - `SiteBlocker.blockAll(List<String> domains) throws IOException`
  - `SiteBlocker.unblockAll(List<String> domains) throws IOException`
  - `HostsFileBlocker` implementa as duas.

- [ ] **Step 1: Escreva o teste que falha**

Crie `BlockServiceSweepTest`. Um `RecordingBlocker implements SiteBlocker` interno conta chamadas: `List<List<String>> unblockAllCalls` e `int unblockCalls`. Monte o `BlockService` com `new BlockStore(stateFile)`, o blocker, `Clock.fixed(now, ZoneOffset.UTC)` e um `hosts` gravável em `@TempDir`. Casos:

- `sweepRemovesEveryExpiredDomainInOneCall()` — 3 expirados + 1 permanente no estado; depois do sweep, `unblockAllCalls` tem **1** elemento com os 3 domínios, `unblockCalls == 0`, e o `blocks.json` relido contém só o permanente.
- `sweepKeepsPermanentsUntouched()` — estado só com permanentes; `unblockAllCalls` fica vazio.
- `sweepIsSkippedWhenHostsNotNotWritable()` — com `hostsPath` apontando para arquivo não gravável, `skippedDueToPermission()` é `true`, nada é destravado e o `blocks.json` no disco continua com os expirados.
- `sweepFailureLeavesStateOnDisk()` — o blocker lança `IOException` em `unblockAll`; o sweep propaga a exceção e o `blocks.json` no disco ainda tem os expirados.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=BlockServiceSweepTest`
Expected: erro de compilação — `SiteBlocker` não tem `unblockAll`.

- [ ] **Step 3: Estenda a interface e a implementação**

Em `SiteBlocker`, acrescente os dois métodos **sem `default`** (a interface tem uma implementação só; forçar o compilador a achar as duas é mais barato que caçar `UnsupportedOperationException` em produção). Em `HostsFileBlocker`, ambos delegam para o `HostsFileManager` de `List`.

- [ ] **Step 4: Migre o laço de `sweepExpired`**

Em `BlockService.sweepExpired`, troque o `for` por domínio (linhas 56-73) por **uma** chamada `blocker.unblockAll(expired)`: no sucesso, remove os expirados do mapa e faz o `store.save(state)` de sempre; na `IOException`, joga todos no `failures` e lança `new IOException("Falha ao limpar expirados do hosts: " + String.join("; ", failures))` — a mesma mensagem que hoje sai. Como a escrita em lote é atômica, ou todos saem ou nenhum sai.

Isto **muda o contrato observável** de `sweepExpired`, e é a troca certa: hoje uma falha no domínio *k* deixa *k-1* removidos do estado e do hosts; com o lote, uma falha não remove nenhum e a próxima iteração tenta todos de novo. O campo `failures` do `SweepResult` fica sempre vazio nas valorações devolvidas — os três chamadores (`BlockCommand.java:97`, `StatusCommand.java:45`, `UnblockCommand.java:84`) só leem `removed()`, então mantenha o campo e documente-o, mas não há caminho que o preencha.

- [ ] **Step 5: Rode o teste e a suíte**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/blocker/ sitelock-core/src/main/java/com/sitelock/core/BlockService.java sitelock-core/src/test/java/com/sitelock/core/BlockServiceSweepTest.java
git commit -m "Núcleo: sweepExpired destrava em lote"
```

---

### Task 3: perfis — modelo e persistência

**Files:**
- Create: `sitelock-core/src/main/java/com/sitelock/core/ProfileEntry.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/Profile.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/ProfileDocument.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/ProfileNames.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/ProfileStore.java`
- Test: `sitelock-core/src/test/java/com/sitelock/core/ProfileStoreTest.java`

**Interfaces:**
- Consumes: `CorruptedStateException` (já existe); o padrão de mapper, escrita atômica e lock de `BlockStore`.
- Produces:
  - `ProfileEntry(Long durationMillis)` — `null` = permanente; com `getDurationMillis()`.
  - `Profile(Instant createdAt, Instant updatedAt, Map<String, ProfileEntry> entries)` — `entries` é um `TreeMap` para sair determinístico no JSON.
  - `ProfileDocument(int version, Map<String, Profile> profiles)` — `version` = 1.
  - `ProfileNames.isValid(String) -> boolean`; `ProfileNames.require(String) throws IllegalArgumentException`.
  - `ProfileStore(Path profilesFile)`: `load() -> Map<String, Profile>` (arquivo ausente = mapa vazio), `save(Map<String, Profile>)`, `delete(String)`, `getProfilesFile()`.

- [ ] **Step 1: Escreva o teste que falha**

Crie `ProfileStoreTest` com `@TempDir Path tmp` e `new ProfileStore(tmp.resolve("profiles.json"))`. Casos:

- `roundTripsProfiles()` — salva dois perfis, um com `durationMillis` 28800000 e outro com `null`, e relê: os campos batem, inclusive `version` = 1.
- `missingFileLoadsEmpty()` — `load()` sem arquivo devolve mapa vazio, sem exceção.
- `corruptedJsonThrowsAndKeepsFile()` — escreve `{ nao é json`; `load()` lança `CorruptedStateException` e o conteúdo do arquivo continua exatamente como estava.
- `deleteRemovesOnlyTheNamedProfile()`
- `invalidNamesAreRejected()` — `require` lança `IllegalArgumentException` para `""`, `" "`(espaços em volta), `"a b"`, `"../escape"`, `"perfil\tcom\ttab"` e uma string de 65 caracteres; `isValid` devolve `false` para os mesmos. `require("trabalho_2")` e `require("Perfil-1")` passam.
- `nullDurationMeansPermanent()` — round-trip de `new ProfileEntry(null)` continua `null` (não vira 0).

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ProfileStoreTest`
Expected: erro de compilação — as classes não existem.

- [ ] **Step 3: Escreva as três classes de dados**

Anote com `@JsonIgnoreProperties(ignoreUnknown = true)` e um construtor `@JsonCreator`, como `BlockEntry` faz. `durationMillis` é `Long`, não `long`: é o que distingue "permanente" de "0 ms", e o `null` é o mesmo `null` de `BlockEntry.expiresAt`.

- [ ] **Step 4: Escreva `ProfileNames`**

`private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$")`. `isValid` faz só o `matches`; `require` lança `IllegalArgumentException` com a mensagem `"Nome de perfil inválido: '<nome>'. Use apenas letras, números, '-' e '_' (até 64 caracteres)."` O nome vira chave de JSON e pode aparecer em log, então `../../` tem que morrer aqui.

- [ ] **Step 5: Escreva `ProfileStore`**

Copie a disciplina de `BlockStore`: `ObjectMapper` com `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` e `FAIL_ON_UNKNOWN_PROPERTIES` desligados; lock em `stateFile.resolveSibling(fileName + ".lock")` na escrita; escrita atômica com arquivo temporário no mesmo diretório e `ATOMIC_MOVE`; JSON ilegível vira `CorruptedStateException` **sem apagar o arquivo**. Grave `ProfileDocument` (não o mapa solto), para o `version` ter onde morar. O caminho vem do construtor — o `SiteLockPaths` resolve o caso do `--state-file` na camada da CLI, não aqui.

- [ ] **Step 6: Rode o teste e veja passar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ProfileStoreTest`
Expected: PASS, 6 testes.

- [ ] **Step 7: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/Profile*.java sitelock-core/src/test/java/com/sitelock/core/ProfileStoreTest.java
git commit -m "Perfis: modelo e persistência em profiles.json"
```

---

### Task 4: a mesclagem do perfil só aumenta o prazo

**Files:**
- Create: `sitelock-core/src/main/java/com/sitelock/core/ProfileMerge.java`
- Test: `sitelock-core/src/test/java/com/sitelock/core/ProfileMergeTest.java`

**Interfaces:**
- Consumes: `BlockEntry`, `Profile`, `ProfileEntry` (Task 3).
- Produces:
  - `enum ProfileMerge.Action { ADDED, RAISED, KEPT }`
  - `record ProfileMerge.Decision(String domain, Action action, BlockEntry entry)`
  - `static Map<String, ProfileMerge.Decision> ProfileMerge.merge(Map<String, BlockEntry> state, Profile profile, Instant now)`

- [ ] **Step 1: Escreva o teste que falha**

Crie `ProfileMergeTest` com `Clock`-free (o `now` é um `Instant` fixo passado no método). Um caso por linha da tabela da spec, com `state` e `profile` montados à mão:

- `absentPlusTemporaryIsAdded()` — ausente + 8h → `ADDED`, `expiresAt == now.plus(8h)`
- `absentPlusPermanentIsAdded()` — ausente + `null` → `ADDED`, `expiresAt == null`
- `temporaryRaised()` — restam 30min, perfil oferece 8h → `RAISED`, novo `expiresAt == now.plus(8h)`
- `temporaryLongerIsKept()` — restam 10h, perfil oferece 8h → `KEPT`
- `temporaryEqualIsKept()` — restam 8h, perfil oferece 8h → `KEPT`
- `temporaryRaisedToPermanent()` — restam 30min, perfil é permanente → `RAISED`, `expiresAt == null`
- `permanentStaysPermanent()` — permanente + 8h → `KEPT`
- `permanentStaysPermanentWithPermanentProfile()` — permanente + `null` → `KEPT`
- `keptIsByteIdentical()` — o `KEPT` devolve **a mesma instância** do `BlockEntry` do `state` (`assertSame`), e `createdAt`/`expiresAt` não mudam.
- `raisedKeepsOriginalCreatedAt()` — o `RAISED` preserva o `createdAt` existente; só o `expiresAt` muda.
- `applyingTwiceIsIdempotent()` — o resultado de `merge` aplicado de novo sobre o mapa de `entry()` dos resultados devolve `KEPT` em todos os domínios.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ProfileMergeTest`
Expected: erro de compilação — `ProfileMerge` não existe.

- [ ] **Step 3: Implemente `ProfileMerge`**

Puro, sem I/O e sem `Clock`. Trate `expiresAt == null` como infinito: o estado atual oferece `PERMANENT` quando `expiresAt` é nulo, e o perfil oferece `PERMANENT` quando `durationMillis` é nulo. A regra é `resultado = max(atual, perfil)`. Devolva **todas** as entradas do perfil no mapa, inclusive as `KEPT` — quem chama decide se as que não mudaram precisam ir para o `/etc/hosts`. Como o mapa é um `TreeMap` de `BlockEntry`, chame `BlockEntry.equals` — ele compara `domain`, `createdAt` e `expiresAt`.

- [ ] **Step 4: Rode o teste e veja passar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ProfileMergeTest`
Expected: PASS, 11 testes.

- [ ] **Step 5: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/ProfileMerge.java sitelock-core/src/test/java/com/sitelock/core/ProfileMergeTest.java
git commit -m "Perfis: a mesclagem só aumenta o prazo"
```

---

### Task 5: `profile save` / `list` / `show` / `delete`

**Files:**
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileSaveCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileListCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileShowCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileDeleteCommand.java`
- Modify: `sitelock-app/src/main/java/com/sitelock/SiteLockApplication.java:14-15`
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/CliSupport.java`
- Test: `sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java`

**Interfaces:**
- Consumes: `ProfileStore`, `Profile`, `ProfileEntry`, `ProfileNames` (Task 3); `BlockService.sweepExpired()`; `DurationParser.formatRemaining(Duration)`.
- Produces:
  - `CliSupport.resolveProfilesFile(String stateFileOverride) -> Path` — `resolveSibling("profiles.json")` do `--state-file`, senão `SiteLockPaths.getStateDir().resolve("profiles.json")`, igual ao padrão de `CliSupport.java:34-40`.
  - Construtores injetáveis (para teste, no padrão de `CliCommandsTest`): `ProfileSaveCommand(String name, boolean force, String hostsFile, String stateFile)`, `ProfileListCommand(String stateFile)`, `ProfileShowCommand(String name, String stateFile)`, `ProfileDeleteCommand(String name, String stateFile)`.
  - `ProfileCommand` registrado como subcomando de `siteblock`.

- [ ] **Step 1: Escreva o teste que falha**

Crie `ProfileCommandsTest`, com o mesmo `@TempDir` e as mesmas fábricas `hosts()`/`state()` de `CliCommandsTest.java:19-27`. Casos:

- `saveStoresRemainingDurations()` — `block tiktok.com --for 4h`, depois `profile save trabalho`; o `profiles.json` tem `tiktok.com` com `durationMillis` **menor** que 14400000 e maior que 14340000 (é o que resta, não as 4h originais).
- `saveFailsWithNoActiveBlocks()` — sem nenhum bloqueio, `save` devolve 1 e não cria arquivo.
- `saveFiltersPendingExpired()` — com `blocks.json` contendo um domínio já vencido e o hosts **não gravável** (o sweep devolve `skippedDueToPermission` e não limpa nada, `BlockService.java:53-55`), `save` não inclui esse domínio no perfil.
- `saveRefusesToOverwriteWithoutForce()` — dois `save` do mesmo nome: o segundo devolve 1 e o conteúdo do perfil não muda; com `--force`, devolve 0.
- `saveRejectsInvalidName()` — `profile save "../evil"` devolve 1.
- `listShowsNameAndCount()` — com dois perfis, a saída contém os dois nomes e o número de domínios.
- `showListsDomainsWithDurations()` — a saída contém o domínio e `permanente` para um bloqueio sem prazo.
- `showUnknownProfileFails()` — devolve 1.
- `deleteRemovesProfile()` — `delete` devolve 0 e o `list` seguinte não mostra o nome.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=ProfileCommandsTest`
Expected: erro de compilação — as classes de comando não existem.

- [ ] **Step 3: Escreva `ProfileCommand` e registre-o**

`@Command(name = "profile", subcommands = { ProfileSaveCommand.class, ProfileListCommand.class, ProfileShowCommand.class, ProfileDeleteCommand.class, ProfileApplyCommand.class, ProfileUnblockCommand.class }, description = "Conjuntos de domínios salvos com nome.")` com `call()` imprimindo `CommandLine.usage(this, System.out)` e devolvendo 2, como `SiteLockApplication.call()` faz. Adicione `ProfileCommand.class` e `DaemonCommand.class` ao array `subcommands` de `SiteLockApplication`. (Crie `DaemonCommand` vazio agora, com o pai e o `call()` de uso; a Task 10 o preenche.)

- [ ] **Step 4: Escreva os quatro comandos**

Todos pegam `CorruptedStateException` e `IOException` e saem com 1 como `BlockCommand.java:101-109` faz. `save`: sweep primeiro; nome inválido ou `IllegalArgumentException` → 1; nome existente sem `--force` → 1; nenhum bloqueio ativo → 1. O que é gravado é o **prazo restante no momento do save**, nunca o `expiresAt` original — um perfil é uma receita, não um histórico.

E aplique o seu próprio filtro `!isExpired(now)` sobre o resultado do sweep, porque o sweep **não** garante "só o que está de fato ativo agora": quando o hosts não está gravável ele devolve o estado intacto com `skippedDueToPermission` (`BlockService.java:53-55`), e os expirados continuam no mapa. Sem esse filtro, salvar um perfil às 23h de um bloqueio que venceu às 22h gravaria o domínio como ativo, e aplicá-lo amanhã o re-bloquearia.

- [ ] **Step 5: Rode o teste e a suíte**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/ProfileCommand.java sitelock-app/src/main/java/com/sitelock/cli/ProfileSaveCommand.java sitelock-app/src/main/java/com/sitelock/cli/ProfileListCommand.java sitelock-app/src/main/java/com/sitelock/cli/ProfileShowCommand.java sitelock-app/src/main/java/com/sitelock/cli/ProfileDeleteCommand.java sitelock-app/src/main/java/com/sitelock/cli/CliSupport.java sitelock-app/src/main/java/com/sitelock/SiteLockApplication.java sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java
git commit -m "CLI: comandos profile save/list/show/delete"
```

---

### Task 6: `profile apply`

**Files:**
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileApplyCommand.java`
- Test: `sitelock-core` não muda; `Test:` em `sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java`

**Interfaces:**
- Consumes: `ProfileMerge` (Task 4), `ProfileStore` (Task 3), `SiteBlocker.blockAll` (Task 2), `CliSupport.resolveProfilesFile` (Task 5).
- Produces: `ProfileApplyCommand(String name, String hostsFile, String stateFile)`.

- [ ] **Step 1: Escreva o teste que falha**

Casos em `ProfileCommandsTest`:

- `applyBlocksEveryDomainOfTheProfile()` — perfil com 3 domínios; após `apply`, os 3 estão no `blocks.json` e no `hosts`.
- `applyIsIdempotent()` — dois `apply` seguidos; o segundo imprime `mantido` para os três e o `hosts` não ganha linha nenhuma.
- `applyNeverShortens()` — bloqueio de 10h no estado, perfil oferecendo 2h; após `apply` o `expiresAt` do `blocks.json` **não** mudou.
- `applyRaisesShortBlock()` — bloqueio de 30min, perfil com 8h; o `expiresAt` passa a `now + 8h`.
- `rollbackWhenBlockAllFails()` — um `SiteBlocker` que lança em `blockAll` faz o comando devolver 1 e o `blocks.json` voltar ao conteúdo anterior (nenhum domínio novo nele). Para injetar o blocker nesse teste, monte o `BlockService` à mão em vez de usar `CliSupport.newService` — se isso exigir um gancho novo em `CliSupport`, acrescente um `newService(hosts, state, SiteBlocker)` sobrecarregado.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=ProfileCommandsTest#applyBlocksEveryDomainOfTheProfile`
Expected: erro de compilação — `ProfileApplyCommand` não existe.

- [ ] **Step 3: Implemente o comando**

Ordem, e ela não é negociável: `sweepExpired()` → carrega o perfil (nome desconhecido → 1) → `ProfileMerge.merge` → **um** `store.save(state)` → **um** `blocker.blockAll(...)` com os domínios `ADDED` e `RAISED`. Se o `blockAll` falhar, restaura o `save` anterior e sai com 1. Estado antes do hosts, pelo mesmo motivo do `BlockCommand`: entrada no hosts sem registro no estado é um domínio preso para sempre, porque o `sweepExpired` varre o JSON.

A saída é a da spec: uma linha por domínio com o que aconteceu, e o resumo `"N domínio(s) no perfil, X alterados, Y mantidos."`. `KEPT` não vai para o `blockAll` — a linha já está lá.

- [ ] **Step 4: Rode o teste e veja passar**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/ProfileApplyCommand.java sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java
git commit -m "CLI: profile apply grava o estado uma vez e reverte em falha"
```

---

### Task 7: `profile unblock`

**Files:**
- Create: `sitelock-app/src/main/java/com/sitelock/cli/ProfileUnblockCommand.java`
- Test: `sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java`

**Interfaces:**
- Consumes: `SiteBlocker.unblockAll` (Task 2), `CliSupport.resolveProfilesFile` (Task 5), `BlockEntry.isTemporaryAndActive(Instant)`.
- Produces: `ProfileUnblockCommand(String name, boolean force, String hostsFile, String stateFile)`.

- [ ] **Step 1: Escreva o teste que falha**

Casos em `ProfileCommandsTest` (entrada pela mesma `readLine()` que `UnblockCommand` usa):

- `refusesWithoutForceWhenTemporaryIsActive()` — bloco de 4h sem `--force` → saída 3 e o `blocks.json` intacto.
- `refusesWithoutForceWhenOnlyPermanentsAreBlocked()` — perfil só com permanentes, sem `--force` → saída 3. É a única leitura que fecha o contraditório da spec: se o gate cobrisse só os temporários, um perfil de travas ilimitadas sairia sem flag e sem digitar nada.
- `wrongConfirmationUnblocksNothing()` — com `--force` e entrada `""` → saída 3, nada muda.
- `confirmationUnblocksEveryDomain()` — entrada `SIM` → saída 0, todos os domínios do perfil saem do `blocks.json` e do `hosts`.
- `permanentsAreListedInTheConfirmation()` — a saída da confirmação contém `permanente` para um bloqueio sem prazo (eles entram na lista, ao contrário do `unblock` individual).
- `alreadyExpiredPassesWithoutPrompt()` — um domínio já vencido é removido pelo sweep e não aparece na confirmação.
- `emptyTargetListDoesNotAskAnything()` — nada do perfil está ativo; sai com 0 e sem pedir entrada.
- `onlyOnePromptForManyDomains()` — 4 domínios ativos, entrada `SIM`: a entrada é lida **uma** vez.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=ProfileCommandsTest#refusesWithoutForceWhenTemporaryIsActive`
Expected: erro de compilação — o comando não existe.

- [ ] **Step 3: Implemente o comando**

Há **duas** camadas de resistência, e elas não são a mesma coisa. A externa é o `--force`: sem ele, qualquer domínio do perfil que esteja **ativo agora** faz o comando recusar tudo e sair com 3 — e "ativo" inclui permanente, porque sem isso um perfil só de travas ilimitadas sairia sem flag e sem confirmação. A interna é a confirmação digitada: com `--force`, uma única pergunta `SIM` antes de tocar em qualquer domínio. Os permanentes entram na **confirmação**, nunca passam calados.

Depois, liste todos os domínios do perfil que estão no estado — temporários com o tempo restante, permanentes marcados `permanente` — e some o total de tempo que está sendo descartado. Se a lista estiver vazia, libere sem perguntar. Entrada errada não desfaz nada e sai com 3. Na confirmação, um `unblockAll` e **um** `store.save`.

Os permanentes entram porque um bloqueio permanente é o compromisso mais forte que a pessoa assumiu, não o mais fraco — soltá-lo calado seria o caminho mais curto para descartar uma trava ilimitada sem digitar uma tecla. Comente isso no código.

- [ ] **Step 4: Rode o teste e veja passar**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/ProfileUnblockCommand.java sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java
git commit -m "CLI: profile unblock com confirmação única"
```

---

### Task 8: sono adaptativo do daemon

**Files:**
- Create: `sitelock-core/src/main/java/com/sitelock/core/SweeperSchedule.java`
- Test: `sitelock-core/src/test/java/com/sitelock/core/SweeperScheduleTest.java`

**Interfaces:**
- Consumes: `BlockEntry.getExpiresAt()`.
- Produces: `static Duration SweeperSchedule.nextSleep(Map<String, BlockEntry> state, Instant now, Duration maxInterval, Duration idleInterval)`.

- [ ] **Step 1: Escreva o teste que falha**

Casos de `SweeperScheduleTest`, com `max = 60s` e `idle = 5min`:

- `noTemporariesSleepsTheIdleInterval()` — mapa vazio → 5min
- `permanentOnlySleepsTheIdleInterval()` → 5min
- `expiringSoonSleepsUntilThen()` — expira em 30s → 30s
- `farExpiryCapsAtMaxInterval()` — expira em 10min → 60s
- `nearExpiryFloorsAtOneSecond()` — expira em 500ms → 1s
- `exactMaxIntervalIsUnchanged()` — expira em 60s → 60s
- `picksTheEarliestOfSeveral()` — dois temporários, um em 20s e outro em 45s → 20s
- `alreadyExpiredEntriesDoNotShortenTheSleep()` — entrada vencida há 1h mais um temporário em 10min → 60s

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=SweeperScheduleTest`
Expected: erro de compilação — `SweeperSchedule` não existe.

- [ ] **Step 3: Implemente `SweeperSchedule`**

Puro, sem `Clock` e sem I/O. Ache o menor `expiresAt` **maior** que `now`; se não houver nenhum, devolva `idleInterval`. Senão, `clamp(expiresAt - now, 1s, maxInterval)`.

- [ ] **Step 4: Rode o teste e veja passar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=SweeperScheduleTest`
Expected: PASS, 8 testes.

- [ ] **Step 5: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/SweeperSchedule.java sitelock-core/src/test/java/com/sitelock/core/SweeperScheduleTest.java
git commit -m "Núcleo: sono adaptativo do daemon de expiração"
```

---

### Task 9: estado, lock de instância única e posse dos arquivos do daemon

**Files:**
- Create: `sitelock-core/src/main/java/com/sitelock/core/ExpirationDaemonState.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/ExpirationDaemonLock.java`
- Modify: `sitelock-core/src/main/java/com/sitelock/core/SiteLockPaths.java:83-107`
- Modify: `sitelock-core/src/main/java/com/sitelock/core/BlockStore.java:131-137`
- Test: `sitelock-core/src/test/java/com/sitelock/core/ExpirationDaemonStateTest.java`

**Interfaces:**
- Consumes: o padrão de `DnsDaemonState` (statics de arquivo, `chownToRealUserIfSudo`, `isAlive` por PID).
- Produces:
  - `ExpirationDaemonState(long pid, Instant startedAt, String stateDir, Instant lastSweepAt)`
  - statics `statePath(Path stateDir)` (`daemon.json`), `logPath(Path stateDir)` (`daemon.log`), `write(Path, ExpirationDaemonState)`, `read(Path) -> Optional<…>`, `delete(Path)`, `updateLastSweep(Path stateDir, Instant at)`, `isAlive(ExpirationDaemonState)`
  - `ExpirationDaemonLock.acquire(Path stateDir) throws IOException` — mantém `FileLock` vivo; `close()`
  - `SiteLockPaths.chownToUserIfConfigured(Path path)` — devolve a posse ao usuário nomeado na property `sitelock.user`, caindo no `SUDO_USER` quando ela não existir

- [ ] **Step 1: Escreva o teste que falha**

`ExpirationDaemonStateTest`, com `@TempDir Path tmp` e `tmp` como `stateDir`:

- `roundTripPreservesEveryField()` — `new ExpirationDaemonState(4242, now, "/home/chico/.sitelock", lastSweep)`; `write` + `read` devolve os quatro campos.
- `missingFileReadsEmpty()` — `read` devolve `Optional.empty()`.
- `updateLastSweepKeepsPidAndStartedAt()` — depois de `updateLastSweep(tmp, t2)`, o `pid` e o `startedAt` continuam os mesmos e só `lastSweepAt` muda.
- `isAliveFollowsThePid()` — `new ExpirationDaemonState(ProcessHandle.current().pid(), …)` é `true`; `pid` de um processo já morto é `false`.
- `acquireIsExclusive()` — dois `acquire` seguidos no mesmo `stateDir`: o segundo lança `IOException` com mensagem contendo `"já está rodando"`; depois de `close()`, um novo `acquire` funciona.
- `chownToUserIfConfiguredPrefersThePropertyOverSudoUser()` — com `-Dsitelock.user=<dono>` setado, o arquivo é do `<dono>`; sem a property, o comportamento é o de `chownToRealUserIfSudo` (que é no-op sem `SUDO_USER`).

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ExpirationDaemonStateTest`
Expected: erro de compilação — as classes não existem.

- [ ] **Step 3: Escreva `ExpirationDaemonState`**

Copie a forma de `DnsDaemonState`: mapper com `JavaTimeModule`, `write` criando o diretório e chamando `SiteLockPaths.chownToRealUserIfSudo`. `updateLastSweep` relê, troca o campo e regrava — quem escreve é o filho, e só quando o sweep removeu algo, para não haver uma escrita de root no diretório do usuário a cada minuto.

- [ ] **Step 4: Escreva `ExpirationDaemonLock`**

`FileChannel.open(lockFile, CREATE, WRITE)` + `channel.lock()`, com o lock retido pela vida inteira. `OverlappingFileLockException` no mesmo processo significa "outro daemon está rodando": converta em `IOException` com o PID do `daemon.json` na mensagem. Este é o ponto do `FileLock` e não do PID: um `kill -9` não órfãoa um `FileLock` porque o SO o libera na morte do processo; um lock por PID sobreviveria ao processo e travaria o próximo boot.

- [ ] **Step 5: Devolva a posse dos arquivos ao usuário, mesmo sem `SUDO_USER`**

Em `SiteLockPaths`, extraia o corpo de `chownToRealUserIfSudo` (`:83-107`) para um método que aceite o nome do usuário, e acrescente `chownToUserIfConfigured(Path)`: lê a property `sitelock.user` e, se existir, devolve a posse ao usuário nomeado; se não existir, cai no comportamento atual (`SUDO_USER`, no-op quando vazio). Em `BlockStore`, onde a escrita atômica chama `chownToRealUserIfSudo` (`:136-137`), chame o novo método.

Sem isso o primeiro sweep do daemon deixa `blocks.json` e `blocks.json.lock` do root — a escrita atômica substitui o inode — e a spec quebra a própria promessa de que `daemon status` roda sem `sudo`. Vale nos três SOs, não só no Windows: um unit de sistema e uma LaunchDaemon são root-sem-`SUDO_USER` exatamente como o `schtasks /ru SYSTEM`.

- [ ] **Step 6: Rode o teste e veja passar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=ExpirationDaemonStateTest`
Expected: PASS, 6 testes.

- [ ] **Step 7: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/ExpirationDaemonState.java sitelock-core/src/main/java/com/sitelock/core/ExpirationDaemonLock.java sitelock-core/src/main/java/com/sitelock/core/SiteLockPaths.java sitelock-core/src/main/java/com/sitelock/core/BlockStore.java sitelock-core/src/test/java/com/sitelock/core/ExpirationDaemonStateTest.java
git commit -m "Núcleo: estado, lock de instância única e posse dos arquivos do daemon"
```

---

### Task 10: `daemon start` / `stop` / `status` e o laço

**Files:**
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonRunCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonStartCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonStopCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonStatusCommand.java`
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/DaemonCommand.java`
- Test: `sitelock-app/src/test/java/com/sitelock/cli/DaemonCliTest.java`

**Interfaces:**
- Consumes: `SweeperSchedule` (Task 8), `ExpirationDaemonState`/`ExpirationDaemonLock` (Task 9), `BlockService.sweepExpired()` (Task 2), o padrão de fork de `DnsStartCommand.java:199-228`.
- Produces:
  - `DaemonCommand` com `subcommands = { DaemonStartCommand, DaemonStopCommand, DaemonStatusCommand, DaemonEnableCommand, DaemonDisableCommand, DaemonRunCommand }`
  - `DaemonRunCommand` = `@Command(name = "__run-daemon", hidden = true)` com `--interval` (padrão 60), `--once` (hidden) e `--state-file` (hidden)
  - `DaemonStartCommand(String interval, String hostsFile, String stateFile)`, `DaemonStopCommand(String stateFile)`, `DaemonStatusCommand(String stateFile)`

- [ ] **Step 1: Escreva o teste que falha**

Crie `DaemonCliTest` com `@TempDir`. Casos:

- `pinnedStateDirIsUsedWhenSudoUserIsAbsent()` — **o teste que o design doc exige.** Monte um `tmp/state/blocks.json` com um domínio **expirado** e um `tmp/hosts` que contém esse domínio no bloco `# SITELOCK`. Lance um subprocesso: `java -cp <java.class.path> com.sitelock.SiteLockApplication daemon __run-daemon --once -Dsitelock.stateDir=<tmp/state> -Dsitelock.hostsFile=<tmp/hosts> -Duser.home=<tmp/empty>`, com `SUDO_USER` **removido** do ambiente do filho e com o `TMPDIR` normal. Espere o processo terminar. Assert: o `tmp/hosts` **não** contém mais o domínio, e o `blocks.json` relido também não. Se o daemon tiver usado `user.home`, nenhum dos dois muda — que é o bug que este teste existe para pegar.
- `onceSweepRemovesNothingWhenNothingExpired()` — estado só com permanentes: o `--once` sai com 0 e o `hosts` fica byte-idêntico.
- `childWritesItsOwnStateFile()` — lance `__run-daemon` (não `--once`) com `--interval 1s` contra o `stateDir` de teste; faça poll de `daemon.json` por até 10s e afirme que ele existe e que o `pid` gravado é o do processo lançado. **Sem este passo não existe `pid` depois de um boot por autostart** — ver deviation 1.
- `startRefusesWithoutRoot()` — `daemon start` com `service.isHostsWritable()` falso sai com 1 e diz para repetir com elevação, sem chegar a forkar nada. Sem isso o daemon sobe, faz sweep e não remove nada, e ninguém percebe — o mesmo no-op silencioso, agora com o processo vivo.
- `startRefusesWhenDaemonAlreadyRunning()` — com `daemon.json` apontando para o PID vivo e `daemon.lock` tomado, `daemon start` devolve 1 e não cria um segundo processo.
- `stateFilesStayOwnedByTheRealUser()` — depois de um `--once` que remove algo, o `blocks.json` do teste não é do root (é o usuário que o criou).
- `iterationSurvivesCorruptedState()` — com `blocks.json`containing `{ nao é json`, um `--once` termina com **exit 1** mas imprime que o estado está corrompido; o daemon não fica em laço nem trava. (O laço em si é coberto pelo teste do passo 4.)
- `lockIsReleasedAfterRun()` — depois de um `--once`, `daemon.lock` pode ser tomado de novo (o `FileLock` foi liberado).
- `statusReportsNoDaemonWhenPidFileIsMissing()` — sem `daemon.json`, a saída diz que não está rodando e sai com 0.
- `statusWorksWithoutRoot()` — com um `daemon.json` apontando para o PID atual, a saída mostra o PID e não tenta nada privilegiado.

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=DaemonCliTest`
Expected: erro de compilação — os comandos não existem.

- [ ] **Step 3: Escreva `DaemonRunCommand`, o laço**

Ordem do `call()`: pegue o `ExpirationDaemonLock` (falhou → mensagem de que já existe um daemon, saída 1); **escreva o seu próprio `daemon.json`** com o PID, `startedAt` e o `stateDir` — e apague-o no `finally`; instale o shutdown hook; e então, em laço: `sweepExpired()`; se `removed()` não estiver vazio, **uma linha de log por domínio**, `updateLastSweep` e `CliSupport.flushDnsBestEffort()`; se não houver temporário ativo, durma `idleInterval` em vez de 1 s; senão durma o `nextSleep`; se o sweep voltou com `skippedDueToPermission`, durma o `idleInterval` também — senão o clamp de 1 s faz o daemon passar o diainteiro relendo o hosts sem conseguir escrever nele.

Uma iteração que lança exceção **não mata o daemon**: registre e siga para a próxima. O `CorruptedStateException` é o caso provável (o usuário editou o JSON na mão) e ele não pode virar um daemon que não renasce. No `stop`, um `SweepResult` final roda antes de sair, para não deixar expirado para trás num shutdown.

O daemon **não adiciona nada** ao `/etc/hosts` — só remove. `sweepExpired()` e o flush são as únicas coisas que ele chama; se alguém jogar `block` dentro dele, um `profile apply` errado passa a parecer um bloqueio válido.

`--once` faz um sweep e sai. Não é o modo de produção: ele não escreve `daemon.json` nem trava o lock, porque é depuração e teste, e um `--once` disparado por engano não pode deixar estado para trás.

- [ ] **Step 4: Escreva `DaemonStartCommand`**

Recuse cedo, **antes de forkar**, se o hosts não estiver gravável — `BlockCommand.java:87-91` e `DnsStartCommand.java:95-99` já fazem isso e o motivo é o mesmo: um filho que não escreve no hosts é um daemon que roda e não faz nada.

Depois, fork de `__run-daemon` copiando `DnsStartCommand.buildChildCommand`: `java` absoluto de `java.home`, `-cp java.class.path` ou `-jar`, e **as properties `-Dsitelock.stateDir=` e `-Dsitelock.hostsFile=` resolvidas e absolutas** — o mesmo caminho que o `daemon enable` vai cravar, senão `start` e `enable` leem arquivos diferentes. `DnsStartCommand.buildChildCommand:207-210` só encaminha `siteblock.bin`; o novo encaminha as duas, e é o que faz `daemon status` sem sudo ler o arquivo certo. Quem escreve o `daemon.json` é o filho, nesta task; o pai só verifica, depois de alguns segundos, que o arquivo apareceu e que o PID está vivo — como `DnsStartCommand.java:154-172` faz com o filho.

Se o jar estiver num caminho gravável pelo usuário, **avise** e não recuse: o caminho de instalação documentado é gravável por construção, e um portão rígido quebraria o dia 1.

- [ ] **Step 5: Escreva `DaemonStopCommand` e `DaemonStatusCommand`**

`stop`: leia o `daemon.json`, mande `destroy()` no PID, espere até ~5 s e, se não morreu, `destroyForcibly()`; devolve 1 se não havia daemon. O sweep final é do filho, no shutdown hook. `status`: **sem root**, mostre PID, rodando?, `stateDir`, último sweep e o autostart (Task 11); devolve 0 mesmo sem daemon — é consulta, não erro.

- [ ] **Step 6: Rode o teste e veja passar**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS. Se `pinnedStateDirIsUsedWhenSudoUserIsAbsent` falhar com "não alterou nada", o `__run-daemon` está lendo `user.home` em vez do `stateDir` cravado — é o bug que a spec chama de "a feature inteira é um no-op silencioso".

- [ ] **Step 7: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/Daemon*.java sitelock-app/src/test/java/com/sitelock/cli/DaemonCliTest.java
git commit -m "CLI: daemon start/stop/status e o laço de expiração"
```

---

### Task 11: artefatos de autostart para os três SOs

**Files:**
- Create: `sitelock-core/src/main/java/com/sitelock/core/CommandRunner.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/AutostartPaths.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/AutostartArtifact.java`
- Create: `sitelock-core/src/main/java/com/sitelock/core/AutostartInstaller.java`
- Modify: `sitelock-core/src/main/java/com/sitelock/core/DnsCacheFlusher.java:35-37`
- Test: `sitelock-core/src/test/java/com/sitelock/core/AutostartArtifactTest.java`

**Interfaces:**
- Consumes: `OsType`, `SiteLockPaths`, `DnsCacheFlusher.CommandRunner`.
- Produces:
  - `CommandRunner` (top-level): `int run(List<String> command) throws IOException` — e `DnsCacheFlusher.CommandRunner extends CommandRunner`, sem breaking change
  - `record AutostartPaths(Path stateDir, Path hostsFile, String javaBin, String jarPath, String classpath, String realUserName, OsType os)`
  - `AutostartArtifact.systemdUnit(AutostartPaths) -> String`, `.launchdPlist(AutostartPaths) -> String`, `.schtasksCommand(AutostartPaths) -> String`
  - `AutostartInstaller(OsType os, Path systemRoot, CommandRunner runner)`: `artifactPath()`, `isEnabled()`, `install(AutostartPaths) -> InstallResult`, `disable() -> InstallResult`; `record InstallResult(boolean success, String detail)`

- [ ] **Step 1: Escreva o teste que falha**

`AutostartArtifactTest` — puro, roda em qualquer SO. Um `AutostartPaths` fixo com `stateDir=/home/chico/.sitelock`, `hostsFile=/etc/hosts`, `javaBin=/usr/lib/jvm/temurin-26/bin/java`, `jarPath=/opt/sitelock/siteblock.jar`. Casos:

- `systemdUnitContainsPinnedPaths()` — o texto contém `-Dsitelock.stateDir=/home/chico/.sitelock`, `-Dsitelock.hostsFile=/etc/hosts`, o `javaBin` e o `jarPath`; e não contém a string `user.home`
- `launchdPlistContainsPinnedPaths()` — o mesmo, com os `ProgramArguments` do plist
- `schtasksCommandContainsPinnedPaths()` — o comando tem `/sc ONLOGON`, `/rl HIGHEST` e `/tn sitelock-sweeper`, além das duas properties
- `artifactEmbedsTheRealUserName()` — os três artefatos contêm `-Dsitelock.user=<dono>`, resolvido no momento do `enable`, sem depender de `SUDO_USER`
- `artifactEscapesSpacesInPaths()` — um `stateDir` com espaço no nome aparece entre aspas no unit e no `schtasks /tr` (ver abaixo)
- `artifactFallsBackToClasspathWhenNoJar()` — com `jarPath` nulo, unit e plist usam `-cp <classpath>`
- `artifactPathPerOs()` — Linux `/etc/systemd/system/sitelock-sweeper.service`, macOS `/Library/LaunchDaemons/com.sitelock.sweeper.plist`
- `installThenDisableIsSymmetric()` — com `systemRoot` apontando para um `@TempDir`, `install` cria o arquivo e `isEnabled()` é `true`; `disable` o remove e `isEnabled()` é `false`
- `installRunsTheScheduler()` — um `CommandRunner` fake captura os comandos: Linux recebe `systemctl enable --now …`, macOS `launchctl bootstrap system …`, Windows `schtasks /create …`

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-core test -Dtest=AutostartArtifactTest`
Expected: erro de compilação — as classes não existem.

- [ ] **Step 3: Extraia `CommandRunner` e escreva os geradores**

`CommandRunner` top-level em `com.sitelock.core`; `DnsCacheFlusher.CommandRunner` passa a `extends CommandRunner` (a assinatura é idêntica, então `DnsCacheFlusherTest` não muda).

`AutostartArtifact` é o único lugar que escreve as três strings, e é **puro**: nenhum caminho é resolvido dentro dele. As duas properties vão para o ExecStart/ProgramArguments/`/tr` de qualquer jeito — um unit de sistema não tem `User=`, uma LaunchDaemon e uma `schtasks /ru SYSTEM` rodam como root **sem `SUDO_USER`**, e aí `user.home` é `/root` ou `C:\Windows\System32\config\systemprofile`: o daemon leria um `blocks.json` que não é o do `sudo siteblock block`.

No Windows use `/sc ONLOGON` e `/rl HIGHEST`, **não** `ONSTART /ru SYSTEM`: com o profile do SYSTEM, `user.home` é o do SYSTEM, todo arquivo nasce root e o `chownToRealUserIfSudo` não socorre, porque `SUDO_USER` não existe nesse contexto. Perde-se só o caso "ligada, ninguém logado" — e sem sessão não há navegador para estar bloqueado.

Embe `-Dsitelock.user=<dono>` nos três artefatos, resolvido no `enable`. É a property que a Task 9 consome para devolver a posse do `blocks.json` depois da escrita atômica, que troca o inode. Sem ela, o primeiro sweep de um daemon iniciado por autostart deixa o estado do root e quebra a promessa de que `status` roda sem elevação.

Dois cuidados com o texto gerado: um path com espaço no `schtasks /tr` quebra a linha de comando (o `/tr` é um argumento só), e no unit systemd o separador de classpath é `:` — nunca embuta a lista crua se o `jarPath` estiver disponível. O fallback para `java.class.path` é último recurso, e vale lembrar que sob `mvn test` essa string é uma lista de muitas entradas, não um jar.

- [ ] **Step 4: Escreva `AutostartInstaller`**

`systemRoot` é `/` em produção e um diretório temporário nos testes; todo caminho do artefato sai de `systemRoot.resolve(...)`. `isEnabled()` é **só teste de existência** do arquivo (no Windows, `schtasks /query`), para que o `daemon status` rode sem `sudo`, como o `dns status` já roda.

- [ ] **Step 5: Rode o teste e veja passar**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS, incluindo `DnsCacheFlusherTest`.

- [ ] **Step 6: Commit**

```bash
git add sitelock-core/src/main/java/com/sitelock/core/CommandRunner.java sitelock-core/src/main/java/com/sitelock/core/Autostart*.java sitelock-core/src/main/java/com/sitelock/core/DnsCacheFlusher.java sitelock-core/src/test/java/com/sitelock/core/AutostartArtifactTest.java
git commit -m "Núcleo: artefatos de autostart para os três SOs"
```

---

### Task 12: `daemon enable` / `disable`

**Files:**
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonEnableCommand.java`
- Create: `sitelock-app/src/main/java/com/sitelock/cli/DaemonDisableCommand.java`
- Test: `sitelock-app/src/test/java/com/sitelock/cli/DaemonCliTest.java`

**Interfaces:**
- Consumes: `AutostartInstaller`, `AutostartArtifact`, `AutostartPaths` (Task 11), `PermissionAdvisor.elevationHint` (existente), `CliSupport`.
- Produces: `DaemonEnableCommand(String jarPath, String hostsFile, String stateFile)`, `DaemonDisableCommand(String hostsFile, String stateFile)`.

- [ ] **Step 1: Escreva o teste que falha`

Casos em `DaemonCliTest`:

- `linuxWithoutSystemdPrintsManualInstructions()` — sem `/run/systemd/system`, `enable` imprime o que fazer à mão, não escreve nada e **não diz que deu certo**
- `enableRefusesWithoutRootAndSaysHow()` — a saída contém o comando para repetir com elevação, via `PermissionAdvisor`
- `enableWarnsAboutWritableJarPath()` — com `--jar` num `@TempDir` (gravável pelo usuário), a saída avisa e mesmo assim instala
- `disableRemovesTheArtifact()`

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=DaemonCliTest`
Expected: erro de compilação — os comandos não existem.

- [ ] **Step 3: Implemente `enable`**

Ordem: detecte ausência de systemd (`/run/systemd/system` não existe) ou de `launchctl` e **imprima as instruções manuais em vez de fingir que funcionou**; detecte falta de elevação e mande repetir com `sudo`, reusando `PermissionAdvisor`; resolva o `java` absoluto e o jar (`-Dsiteblock.jar`, com `java.class.path` de reserva, porque a propriedade não existe em lugar nenhum ainda — quem a cria é a Task 14); monte o `AutostartPaths` com `stateDir` e `hostsFile` **resolvidos agora**; instale e rode o agendador.

- [ ] **Step 4: Implemente `disable`**

Simétrico: remove o artefato e chama `systemctl disable --now`, `launchctl bootout system` ou `schtasks /delete`. Sem root quando possível.

- [ ] **Step 5: Rode o teste e a suíte**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/DaemonEnableCommand.java sitelock-app/src/main/java/com/sitelock/cli/DaemonDisableCommand.java sitelock-app/src/test/java/com/sitelock/cli/DaemonCliTest.java
git commit -m "CLI: daemon enable/disable com elevação e detecção de systemd"
```

---

### Task 13: o aviso de daemon ausente

**Files:**
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/CliSupport.java`
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/BlockCommand.java:147-152`
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/StatusCommand.java:89`
- Modify: `sitelock-app/src/main/java/com/sitelock/cli/ProfileApplyCommand.java`
- Test: `sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java`

**Interfaces:**
- Consumes: `ExpirationDaemonState.read/isAlive` (Task 9), `AutostartInstaller.isEnabled` (Task 11).
- Produces: `CliSupport.warnIfNoDaemon(String stateFileOverride)` e `CliSupport.daemonLine(String stateFileOverride)` — stdout, com o prefixo `AVISO:`, igual ao que `StatusCommand.java:55` já usa.

- [ ] **Step 1: Escreva o teste que falha**

Casos em `ProfileCommandsTest` (e um em `CliCommandsTest` para o `block`):

- `blockWithDurationWarnsWhenNoDaemonExists()` — `block --for 30m` sem daemon e sem autostart: a saída contém `AVISO:` e menciona `daemon enable`
- `blockPermanentDoesNotWarn()` — sem `--for`, nada é impresso
- `applyWithTemporaryWarnsWhenNoDaemonExists()` — idem pelo `profile apply`
- `statusShowsDaemonLineWhenTemporaryIsActive()` — com um temporário ativo, `status` mostra uma linha sobre o daemon
- `statusWithoutTemporaryHasNoDaemonLine()`

- [ ] **Step 2: Rode o teste e veja falhar**

Run: `./mvnw -B -pl sitelock-app -am test -Dtest=ProfileCommandsTest`
Expected: falha de asserção — a saída não tem o aviso.

- [ ] **Step 3: Implemente o aviso**

Gatilho: avisa quando **não há daemon vivo** *ou* **não há autostart instalado** — os dois são defeito do ponto de vista de quem digitou `--for 4h`, e mesmo com autostart instalado o daemon pode estar naquele boot atrasado. `status` só fala quando há temporário ativo: bloqueio permanente não tem prazo para vencer, então não há o que avisar. Vai para **stdout** com `AVISO:`; erro continua indo para stderr, porque isto não é erro, é informação.

A mensagem precisa dizer a verdade inteira, não a versão anêmica: `"o prazo só vai ser aplicado quando você rodar outro comando do siteblock; rode 'sudo siteblock daemon enable' para isso acontecer sozinho."`

- [ ] **Step 4: Rode o teste e a suíte**

Run: `./mvnw -B test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add sitelock-app/src/main/java/com/sitelock/cli/CliSupport.java sitelock-app/src/main/java/com/sitelock/cli/BlockCommand.java sitelock-app/src/main/java/com/sitelock/cli/StatusCommand.java sitelock-app/src/main/java/com/sitelock/cli/ProfileApplyCommand.java sitelock-app/src/test/java/com/sitelock/cli/ProfileCommandsTest.java
git commit -m "CLI: avisa quando não há daemon para cumprir o prazo"
```

---

### Task 14: `-Dsiteblock.jar` nos três launchers

**Files:**
- Modify: `bin/siteblock:33`
- Modify: `run.sh:28`
- Modify: `run.bat:34`

**Interfaces:**
- Produz: `-Dsiteblock.jar=<absolut>` passado ao app por `bin/siteblock`, `run.sh` e `run.bat`. A property não existe hoje — `bin/siteblock:33` só passa `-Dsiteblock.bin` — e o autostart não tem o que executar sem ela. `DaemonStartCommand` usa a property quando existe e cai para `java.class.path`.

- [ ] **Step 1: Passe a property nos três**

Em `bin/siteblock`, a linha `exec` passa a ser `exec "$JAVA_BIN" -Dsiteblock.bin="$SCRIPT" -Dsiteblock.jar="$JAR" -jar "$JAR" "$@"`. Em `run.sh`, `exec "$JAVA_BIN" -Dsiteblock.jar="$JAR" -jar "$JAR" "$@"`. Em `run.bat`, `java -Dsiteblock.jar="%JAR%" -jar "%JAR%" %*`. Nada mais muda — o `SITEBLOCK_JAR` continua mandando.

- [ ] **Step 2: Verifique na mão**

Run: `./mvnw -B -q package -DskipTests` e depois `java -Dsiteblock.jar=x -jar sitelock-app/target/siteblock-1.0.0.jar status | head -3`
Expected: o comando roda e lista o estado. Depois, `rm -rf sitelock-*/target` e rode `./mvnw -B -q test` para confirmar que a limpeza não quebrou nada.

- [ ] **Step 3: Commit**

```bash
git add bin/siteblock run.sh run.bat
git commit -m "Launchers: property siteblock.jar no caminho absoluto do jar"
```

---

### Task 15: README e remoção das specs

**Files:**
- Modify: `README.md`
- Delete: `docs/superpowers/specs/2026-10-15-profiles-and-expiry-daemon-design.md`
- Delete: este arquivo, `docs/superpowers/plans/2026-10-15-profiles-and-expiry-daemon.md`

- [ ] **Step 1: Reescreva as partes do README que mentem**

`README.md:120-122` diz que expirado não sai sozinho — isso deixa de ser verdade, e um README que mente é pior do que um que cala. Troque por: o prazo vence sozinho com o daemon ligado, e o que acontece sem ele. Acrescente `profile` e `daemon` à seção de comandos, uma linha em "Onde ficam as coisas" para `profiles.json` e `daemon.json`, e o procedimento de `sudo siteblock daemon enable` como o passo recomendado depois de instalar.

- [ ] **Step 2: Verifique manual documentado, com o SiteBlock real**

Run: `sudo ./run.sh daemon start` · `sudo ./run.sh block exemplo-teste.com --for 30s` · esperar 40s · `sed -n '/SITELOCK-START/,/SITELOCK-END/p' /etc/hosts`
Expected: o domínio sumiu **sem rodar nenhum comando**. Depois `sudo ./run.sh daemon enable`, reboote, e confirme que o processo subiu com `sudo ./run.sh daemon status`.

Se você não quiser digitar a senha agora, deixe estes dois passos para o fim e diga — o resto da Task não depende deles.

- [ ] **Step 3: Apague os documentos e commite**

```bash
git rm docs/superpowers/specs/2026-10-15-profiles-and-expiry-daemon-design.md docs/superpowers/plans/2026-10-15-profiles-and-expiry-daemon.md
git add README.md
git commit -m "Documentação: README cobre perfis e daemon de expiração"
```

---

## Self-Review

**1. Cobertura da spec.** Parte 1 (perfis): Tasks 3, 4, 5, 6, 7. Parte 2 (daemon): Tasks 8, 9, 10, 11, 12, 13, 14. Parte 3 (lote): Tasks 1, 2. Testes da spec: `ProfileMergeTest` (Task 4), `ProfileStoreTest` (Task 3), `HostsFileManagerBulkTest` (Task 1), `SweeperScheduleTest` (Task 8), CLI de profile (Tasks 5-7), `AutostartArtifactTest` (Task 11), simetria do disable (Task 11/12), teste de caminho cravado (Task 10). Manual documentado no README (Task 15). Cada item da tabela de decisões 1-20 do design tem task ou passo: 1-2 (Task 3), 4 (Task 6), 5 (Task 7), 6-9 (Tasks 10-12), 10 (Task 14), 11 (Task 2), 12 (Task 9), 13 (Task 9), 14 (Tasks 9-10), 15 (Task 5), 16 (`reset --yes` não toca `profiles.json` — já vale hoje, `ResetCommand` só apaga o `blocks.json`; nenhum teste novo), 17 (Task 13), 18 (reconciliação estado↔hosts fica fora, decisão 18), 19 (Task 11), 20 (Task 7).

**2. Varredura de passos.** Nenhum passo é "implemente a lógica"; todos dizem assinatura exata + arquivo + valores cravados da spec. Os corpos só aparecem onde a spec fixa o texto (as três linhas do aviso da Task 13, o exemplo de saída do `apply` na Task 6, a estrutura das properties).

**3. Consistência de tipos.** `ProfileMerge.Decision` é consumido na Task 6 pelo campo `action()`. `SiteBlocker.blockAll/unblockAll` são introduzidos na Task 2 e usados nas Tasks 6 e 7. `AutostartPaths` é criado na Task 11 e construído na Task 12. `SweeperSchedule.nextSleep` (Task 8) é o único chamador do laço (Task 10). `ExpirationDaemonState.updateLastSweep` (Task 9) é chamado pelo laço (Task 10). Nenhum nome muda entre tasks.

**4. Review Focus.** 1 → Task 10, `pinnedStateDirIsUsedWhenSudoUserIsAbsent`. 2 → Task 9 `acquireIsExclusive` + Task 10 `startRefusesWhenDaemonAlreadyRunning`. 3 → Task 10 `iterationSurvivesCorruptedState`. 4 → Task 6 `rollbackWhenBlockAllFails`. 5 → Task 12 `linuxWithoutSystemdPrintsManualInstructions`.

**5. Proporção.** O plano descreve 15 commits; a spec tem 623 linhas. O plano é mais longo que a spec em linhas, mas a spec é prosa e o plano é interface. Nenhuma tarefa traz o corpo de uma classe: só assinatura, nome de teste e asserção.