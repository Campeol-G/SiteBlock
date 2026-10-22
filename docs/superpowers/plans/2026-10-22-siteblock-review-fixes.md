# SiteBlock — correções da revisão de 2026-10-22

## Objetivo

Corrigir os 13 achados da revisão (`C1`, `C2`, `A1`–`A8`, `S1`–`S3`), com teste de
regressão para cada um, sem mudar o contrato dos comandos existentes. O Windows
é validado pelo job `windows-latest` que já existe em `.github/workflows/build.yml`;
a VM Windows fica para um smoke test manual posterior.

## Decisões

- Cada correção entra em um commit próprio, na ordem de dependência abaixo.
- Testes usam arquivos temporários, processos reais apenas quando necessário e
  nenhum acesso à internet.
- O limite de `--for` será explícito e conservador: 365.000 dias (1.000 anos).
  Isso cabe em `Instant.plus` e em `Duration.toMillis`.
- O `dns start` terá uma trava de inicialização (`dns-server.start.lock`) e o
  log será lido a partir do offset daquela execução. O filho só apaga o estado
  que tem o PID dele.
- O `dns stop` continua aceitando registros antigos sem `processStartedAt`; os
  registros novos sempre terão o campo.
- O `daemon` fará a varredura final no shutdown hook; a main espera o hook
  terminar antes de liberar a trava e apagar o estado.
- CI: o job Windows já roda `mvnw.cmd -B clean package`, portanto os testes com
  `@EnabledOnOs(OS.WINDOWS)` entram na matriz existente. Não será criado um
  workflow duplicado.

## Tarefas

### 1. A3 — duração fora do intervalo suportado

Arquivos: `sitelock-core/.../DurationParser.java`,
`DurationParserTest.java`, `CliCommandsTest.java`, testes de `profile add`.

- Adicionar `MAX_DURATION` (365.000 dias) e rejeitar `total > MAX_DURATION` com
  `IllegalArgumentException` citando a entrada e o limite.
- Testar `999999999999d` no parser, em `block` e em `profile add`; todos devem
  retornar 2/1 sem gravar estado e sem `DateTimeException`/`ArithmeticException`.

### 2. S2 — linha que virou só IP + comentário

Arquivos: `HostsFileManager.java`, `HostsFileManagerTest.java`.

- `stripHostnames` devolve `null` quando, depois de remover os hostnames
  gerenciados, não sobra nenhum host (só IP e comentário).
- `isEntryLine` só considera entrada quando existe host antes do `#`.
- Testar remoção de `127.0.0.1 tiktok.com # note` e de bloco com só essa linha;
  os marcadores devem sumir.

### 3. A2 — apply com tudo KEPT repara o hosts

Arquivos: `ProfileApplyCommand.java`, `ProfileApplyTest.java`.

- O `hosts.write` roda sempre que `erroAoSalvar == null` e há decisões, mesmo
  com `alterados == 0`.
- Flush do DNS quando o hosts foi reaplicado.
- Teste: bloquear/salvar/aplicar, remover o domínio do hosts por fora, aplicar
  de novo (decisão KEPT) e conferir que as linhas voltaram.

### 4. A5 — confirmação fora da transação

Arquivos: `UnblockCommand.java`, `ProfileUnblockCommand.java`,
`CliCommandsTest.java`, `ProfileUnblockTransacaoTest.java`.

- Guardar o `BlockEntry` lido antes da confirmação; dentro da transação,
  remover só se a entrada atual for igual à confirmada.
- Entrada alterada/removida por outro processo vira conflito: não remove,
  avisa e retorna 1. No `profile unblock`, qualquer conflito cancela o lote.
- Testes com `StdinQueGrava` (mesmo padrão de `ProfileUnblockTransacaoTest`)
  provando que um `block` concorrente no alvo não é apagado sem `--force`.

### 5. A4 e S3 — schtasks e drenagem de saída

Arquivos: `AutostartArtifact.java`, `AutostartInstaller.java`,
`DnsCacheFlusher.java`, `AutostartArtifactTest.java`, `DnsCacheFlusherTest.java`.

- `schtasksArgs` inclui `/f`; o helper textual não duplica.
- `AutostartInstaller.runWithProcessBuilder` redireciona a saída para
  `DISCARD` (não precisa do texto).
- `DnsCacheFlusher.runWithProcessBuilder` drena a saída em paralelo ao
  `waitFor`; mantém a mensagem de erro.
- Teste: args de `schtasks` contêm `/f`; um comando fake que escreve mais que
  o buffer do pipe termina sem timeout.

### 6. A6 — dono do diretório de estado

Arquivos: `SiteLockPaths.java`, `DnsDaemonState.java`,
`ExpirationDaemonLock.java`, `ExpirationDaemonState.java`, `ProfileStore.java`,
`BlockStore.java`, `DnsStartCommand.java`.

- Criar `SiteLockPaths.createDirectories(Path)` que cria e chama
  `chownToUserIfConfigured`.
- Usar em todos os pontos que criam `~/.sitelock`/estado e no parent de
  `profiles.json`.
- Chown do log do DNS depois de o filho abrir.
- Teste de regressão: `ProfileStore` e `DnsDaemonState` criam o diretório sem
  erro com `sitelock.user` apontando para o usuário atual; a correção de dono
  em si é best-effort e não dá para provar sem root.

### 7. C1, C2 e A1 — dns start

Arquivos: `DnsStartCommand.java`, `DnsRunDaemonCommand.java`,
`DnsDaemonState.java`, `DnsDaemonLock.java` (novo), testes de DNS.

- C1: pular a checagem de porta privilegiada quando `OsType` for Windows;
  testar a função pura `needsPrivilegedPort(port, os)`.
- C2: antes de iniciar, rotacionar o log se passar de 5 MiB e guardar o offset;
  `logShowsListening` lê só os bytes a partir do offset. Teste existente
  `DnsStartEscutaTest` ganha um marcador antigo pré-gravado e continua falhando.
- A1: `DnsStartCommand` grava `processStartedAt` a partir de
  `Process.info().startInstant()`; `DnsDaemonState.deleteIfOwned` evita que um
  filho antigo apague o estado de um novo; `DnsDaemonLock` serializa dois
  `dns start` (tryLock, sem espera).
- Teste: estado gravado tem `processStartedAt` e `isAlive`; `deleteIfOwned`
  não apaga PID alheio; trava não é reentrante na mesma JVM.

### 8. A8 — vazamento do socket UDP no bind TCP

Arquivo: `DnsServer.java`, `DnsServerTest.java`.

- Envolver `bind()`, `bindTcp()`, upstream e loop num `try/finally` que fecha
  tudo.
- Teste: ocupar a porta TCP, deixar a UDP livre, chamar `serveForever` e provar
  que a porta UDP pode ser reaberta depois da exceção.

### 9. A7 — fallback TCP e buffer grande

Arquivos: `DnsResolver.java`, `DnsServer.java`, testes de DNS.

- `UpstreamClient.encaminhar` refaz a consulta por TCP quando o upstream
  devolve `TC=1`; valida ID/pergunta e reescreve o ID do cliente.
- `MAX_PACKET_BYTES` sobe para 65535 para não truncar EDNS grande.
- Teste com upstream falso UDP+TCP: UDP devolve TC=1 e TCP devolve resposta
  grande; cliente recebe a resposta completa com TC=0.
- Teste de resposta UDP > 8192 sem TC, provando que ela não é truncada.

### 10. S1 — varredura final no shutdown

Arquivo: `DaemonRunCommand.java`, `DaemonCliTest.java`.

- O shutdown hook faz a varredura final e sinaliza um latch; a main espera o
  latch antes de fechar a trava e apagar o registro.
- Teste de subprocesso: daemon dormindo, domínio expira, SIGTERM (`destroy()`),
  e o domínio sai do hosts/estado.

### 11. README e CI

Arquivos: `README.md`, `.github/workflows/build.yml` (somente se necessário).

- README continua curto: instalação, comandos, `sudo`, DNS, daemon, notas de
  Windows. Atualizar o que mudou e remover promessas que não existem.
- Confirmar que o job `windows-latest` executa os testes da matriz; adicionar
  um teste Windows-only de `schtasks`/detecção de SO para provar o caminho.
- Rodar `./mvnw -B test` no Linux antes de cada commit relevante.

## Critérios de aceite

- `./mvnw -B test` verde no Linux.
- Nenhum dos cenários reproduzidos na revisão volta a acontecer.
- Job `windows-latest` verde no GitHub Actions (push só com confirmação).
- `git diff --check` limpo e sem segredos.
