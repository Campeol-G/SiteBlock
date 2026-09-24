package com.sitelock.core;

import java.nio.file.Path;

/**
 * Mensagens de elevação de privilégio específicas por SO, num único ponto.
 *
 * <p>Motivação: antes, {@code CliSupport.sudoHint} dizia sempre "rode com sudo",
 * o que é errado no Windows (sem sudo nativo confiável). Agora os comandos CLI
 * pedem o texto aqui, passando o caminho real do hosts e um exemplo de
 * subcomando — nada de strings de permissão espalhadas pelo código.
 *
 * <ul>
 *   <li>Linux/macOS: instruir a rodar novamente com {@code sudo}.</li>
 *   <li>Windows: instruir a fechar o terminal e reabrir como Administrador
 *       (Iniciar → cmd/PowerShell → "Executar como administrador").</li>
 * </ul>
 */
public final class PermissionAdvisor {

    private final OsType osType;

    /** Usa o SO real da JVM. */
    public PermissionAdvisor() {
        this(OsType.detect());
    }

    /** Injeta o SO (para testes e reuso interno). */
    public PermissionAdvisor(OsType osType) {
        this.osType = osType == null ? OsType.UNKNOWN : osType;
    }

    /** Injeta o valor de {@code os.name} (para testes dos três ramos). */
    public PermissionAdvisor(String osName) {
        this(OsType.detect(osName));
    }

    public OsType getOsType() {
        return osType;
    }

    /**
     * Dica de elevação quando falta escrita no hosts.
     *
     * @param hostsPath caminho real do hosts (já resolvido por SO).
     * @param subcommandExample ex.: "block tiktok.com --for 1h" (sem o binário).
     */
    public String elevationHint(Path hostsPath, String subcommandExample) {
        String args = subcommandExample == null || subcommandExample.isBlank()
                ? ""
                : " " + subcommandExample.strip();
        if (osType.isWindows()) {
            return "Sem permissão de escrita em " + hostsPath
                    + ". Feche o terminal atual e reabra como Administrador "
                    + "(Iniciar → cmd/PowerShell → \"Executar como administrador\") "
                    + "e rode novamente: siteblock" + args + ".";
        }
        // Linux, macOS e UNKNOWN: sudo.
        return "Sem permissão de escrita em " + hostsPath
                + ". Rode novamente com sudo, ex.: sudo siteblock" + args + ".";
    }

    /**
     * Dica para porta privilegiada (&lt; 1024 exige elevação nos três SOs).
     * Reutiliza o mesmo vocabulário de elevação do {@link #elevationHint}.
     *
     * @param port porta pedida.
     * @param retryExample exemplo de repetição, ex.: "dns start" ou "dns start --for 2h".
     */
    public String privilegedPortHint(int port, String retryExample) {
        String args = retryExample == null || retryExample.isBlank() ? "" : " " + retryExample.strip();
        if (osType.isWindows()) {
            return "A porta " + port + " é privilegiada no Windows (portas < 1024 exigem "
                    + "Administrador). Feche o terminal e reabra como Administrador "
                    + "(Iniciar → cmd/PowerShell → \"Executar como administrador\") "
                    + "e rode novamente: siteblock" + args + ". "
                    + "Para testar sem elevação, use uma porta alta: siteblock dns start --port 5300.";
        }
        String osLabel = osType.isMac() ? "macOS" : "Linux";
        return "A porta " + port + " é privilegiada no " + osLabel + " (só root abre "
                + "portas < 1024). Rode com sudo, ex.: sudo siteblock" + args + ". "
                + "Para testar sem sudo, use uma porta alta: siteblock dns start --port 5300.";
    }

    /**
     * Aviso best-effort quando o flush de DNS falha: o bloqueio já foi salvo,
     * só o cache pode estar desatualizado.
     */
    public static String dnsFlushFailureWarning() {
        return "bloqueio salvo, mas não foi possível limpar o cache de DNS automaticamente; "
                + "considere reiniciar o navegador.";
    }
}
