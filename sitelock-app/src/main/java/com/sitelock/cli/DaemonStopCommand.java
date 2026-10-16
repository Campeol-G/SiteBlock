package com.sitelock.cli;

import com.sitelock.core.ExpirationDaemonState;
import com.sitelock.core.SiteLockPaths;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * siteblock daemon stop — encerra o daemon.
 *
 * <p>A varredura final é do filho, no shutdown hook: é ele que sabe o que
 * ainda está vencido. O pai só manda o sinal e espera.
 */
@Command(name = "stop",
        description = "Encerra o daemon de expiracao.",
        mixinStandardHelpOptions = true)
public class DaemonStopCommand implements Callable<Integer> {

    @Option(names = "--state-file", hidden = true) String stateFileOverride;

    public DaemonStopCommand() {
    }

    DaemonStopCommand(String stateFileOverride) {
        this.stateFileOverride = stateFileOverride;
    }

    @Override
    public Integer call() {
        java.nio.file.Path stateDir = CliSupport.stateDirFor(stateFileOverride);
        Optional<ExpirationDaemonState> state;
        try {
            state = ExpirationDaemonState.read(stateDir);
        } catch (IOException e) {
            System.err.println("Erro ao ler o estado do daemon: " + e.getMessage());
            return 1;
        }
        if (state.isEmpty() || !ExpirationDaemonState.isAlive(state.get())) {
            System.err.println("Nenhum daemon rodando.");
            return 1;
        }

        long pid = state.get().getPid();
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty()) {
            System.err.println("Nenhum daemon rodando (pid " + pid + " não existe).");
            return 1;
        }

        handle.get().destroy();
        for (int i = 0; i < 50; i++) {
            if (!handle.get().isAlive()) {
                System.out.println("Daemon de expiração encerrado (pid " + pid + ").");
                return 0;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        handle.get().destroyForcibly();
        System.out.println("Daemon de expiração encerrado à força (pid " + pid
                + "); não respondeu ao pedido de saída.");
        return 0;
    }
}