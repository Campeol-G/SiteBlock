package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O endereço que a CLI manda o usuário configurar no celular.
 *
 * <p>Um endereço link-local (169.254/16 — hotspot, PPPoE) não é roteável: o
 * celular pointed para ele não resolve nada, e a CLI imprimia
 * "configure o DNS manualmente para 169.254.210.73" como se fosse o caminho
 * certo.
 */
class NetworkUtilsTest {

    private static InetAddress ip(String texto) throws Exception {
        return InetAddress.getByName(texto);
    }

    @Test
    void linkLocalNaoServeParaApontarDns() throws Exception {
        assertFalse(NetworkUtils.serveParaApontarDns(ip("169.254.210.73")),
                "link-local não é roteável");
        assertFalse(NetworkUtils.serveParaApontarDns(ip("169.254.0.1")),
                "link-local não é roteável");
        assertFalse(NetworkUtils.serveParaApontarDns(ip("0.0.0.0")),
                "any-local não é endereço de ninguém");
    }

    @Test
    void enderecoPrivadoServeParaApontarDns() throws Exception {
        assertTrue(NetworkUtils.serveParaApontarDns(ip("192.168.1.13")));
        assertTrue(NetworkUtils.serveParaApontarDns(ip("10.0.0.5")));
        assertTrue(NetworkUtils.serveParaApontarDns(ip("172.17.0.1")));
    }

    @Test
    void loopbackNaoServeParaApontarDns() throws Exception {
        // O loopback é local, mas apontar o celular para ele não funciona:
        // o 127.0.0.1 do celular é o próprio celular.
        assertFalse(NetworkUtils.serveParaApontarDns(ip("127.0.0.1")));
    }

    @Test
    void nenhumEnderecoListadoEhLinkLocal() {
        for (String endereco : NetworkUtils.localIPv4Addresses()) {
            assertFalse(endereco.startsWith("169.254."),
                    "link-local não pode ser oferecido ao usuário: " + endereco);
            assertFalse(endereco.equals("0.0.0.0"),
                    "any-local não pode ser oferecido ao usuário: " + endereco);
        }
    }
}
