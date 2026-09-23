package com.sitelock.cli;

import com.sitelock.core.BlockStore;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * {@code sitelock reset --yes} — recuperação de estado corrompido.
 *
 * <p>Comando extra (além de block/unblock/status): como o modo de
 * JSON-corrompido é não-interativo por decisão da Etapa 1, este comando oferece
 * o caminho explícito de recriação. <b>Não toca no /etc/hosts</b> — limpe as
 * linhas manualmente com {@code unblock} ou editando o hosts (há backup em
 * {@code ~/.sitelock/hosts.backup}).
 */
@Command(name = "reset", description = "Apaga o estado (blocks.json), recriando-o vazio. Nao altera o /etc/hosts.",
        mixinStandardHelpOptions = true)
public class ResetCommand implements Callable<Integer> {

    @Option(names = {"-y", "--yes"}, description = "Confirma a operação sem perguntar.")
    boolean yes;

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public ResetCommand() {
    }

    ResetCommand(boolean yes, String stateFileOverride) {
        this.yes = yes;
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        if (!yes) {
            System.err.println("Isso apagará TODOS os registros de bloqueio do estado (blocks.json).");
            System.err.println("O /etc/hosts NÃO será alterado por este comando.");
            System.err.println("Rode de novo com --yes para confirmar: sitelock reset --yes");
            return 2;
        }
        Path stateFile = CliSupport.resolveStateFile(stateFileOverride);
        try {
            new BlockStore(stateFile).clear();
        } catch (Exception e) {
            System.err.println("Erro ao recriar estado: " + e.getMessage());
            return 1;
        }
        System.out.println("Estado recriado vazio em " + stateFile + ".");
        System.out.println("Lembrete: o /etc/hosts não foi alterado; use 'unblock' para limpar bloqueios restantes.");
        return 0;
    }
}
