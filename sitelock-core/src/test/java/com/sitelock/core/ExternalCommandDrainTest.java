package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Comando externo verboso não pode encher o pipe antes do waitFor.
 *
 * <p>Os dois executores que chamam processo de fora drenam a saída em paralelo
 * (ou descartam, quando o texto não é usado). O cenário reproduz um comando que
 * escreve mais que o buffer padrão do pipe.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class ExternalCommandDrainTest {

    private static List<String> verboseCommand() {
        return List.of("sh", "-c", "yes x | head -c 200000");
    }

    @Test
    void flusherDrainsOutputBeforeWaiting() throws Exception {
        assertEquals(0, DnsCacheFlusher.runWithProcessBuilder(verboseCommand()));
    }

    @Test
    void installerDiscardsOutputWithoutBlocking() throws Exception {
        assertEquals(0, AutostartInstaller.runWithProcessBuilder(verboseCommand()));
    }
}
