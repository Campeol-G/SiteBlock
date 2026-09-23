package com.sitelock.cli;

import com.sitelock.core.BlockStore;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * siteblock reset --yes — apaga o blocks.json. Não altera o arquivo hosts.
 */
@Command(name = "reset", description = "Apaga o estado (blocks.json), recriando-o vazio. Nao altera o arquivo hosts.",
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
            System.err.println("O arquivo hosts (" + SiteLockPaths.getHostsPath() + ") NÃO será alterado por este comando.");
            System.err.println("Rode de novo com --yes para confirmar: siteblock reset --yes");
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
        System.out.println("Lembrete: o arquivo hosts não foi alterado; use 'unblock' para limpar bloqueios restantes.");
        return 0;
    }
}
