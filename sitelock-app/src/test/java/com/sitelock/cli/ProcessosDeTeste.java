package com.sitelock.cli;

import java.util.concurrent.TimeUnit;

/**
 * Utilidades de teste para processos filhos.
 *
 * <p>ATENÇÃO: {@code destroyForcibly()} é assíncrono. Um teste que sai com o
 * processo ainda vivo deixa a porta do servidor DNS ocupada por um instante — e
 * a execução seguinte falha com "address already in use".
 */
final class ProcessosDeTeste {

    private ProcessosDeTeste() {
    }

    /** Mata o processo e espera ele morrer. */
    static void matar(ProcessHandle handle) {
        if (handle == null) {
            return;
        }
        handle.destroyForcibly();
        try {
            handle.onExit().get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // o processo já foi sinalizado; não há mais o que fazer aqui
        }
    }

    /** Mata o processo e espera ele morrer. */
    static void matar(Process process) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Mata o processo de um PID, se ele existir. */
    static void matarPid(long pid) {
        if (pid <= 0) {
            return;
        }
        ProcessHandle.of(pid).ifPresent(ProcessosDeTeste::matar);
    }
}
