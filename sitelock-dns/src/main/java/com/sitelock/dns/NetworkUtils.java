package com.sitelock.dns;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Descoberta do IP local da máquina para imprimir no terminal ao iniciar o
 * servidor: o usuário aponta o DNS do celular/PC para esse IP.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    // POR QUE: as site-local (192.168/10/172.16-31) vêm primeiro — com várias
    // interfaces (Wi-Fi + Ethernet + VPN + Docker), é a alcançável pela rede
    // local. Filtrar o essencial (ativa, não-loopback, não virtual) dispensa lógica por SO.
    /** Todas as IPv4 locais não-loopback (site-locais primeiro). */
    public static List<String> localIPv4Addresses() {
        List<String> siteLocal = new ArrayList<>();
        List<String> others = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return List.of();
            }
            for (NetworkInterface iface : Collections.list(ifaces)) {
                try {
                    if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (!serveParaApontarDns(addr)) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (addr.isSiteLocalAddress()) {
                        if (!siteLocal.contains(ip)) {
                            siteLocal.add(ip);
                        }
                    } else if (!addr.isMulticastAddress()) {
                        if (!others.contains(ip)) {
                            others.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        List<String> all = new ArrayList<>(siteLocal);
        all.addAll(others);
        return List.copyOf(all);
    }

    // true se o endereço serve para configurar o DNS de um dispositivo da rede.
    // POR QUE: link-local (169.254/16 — hotspot, PPPoE) não é roteável — o
    // celular apontado para ele não resolve nada; 0.0.0.0 e loopback também não.
    static boolean serveParaApontarDns(InetAddress addr) {
        if (addr == null || !(addr instanceof Inet4Address)) {
            return false;
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
            return false;
        }
        return !addr.isMulticastAddress();
    }

    /** IP principal para configurar nos dispositivos (ou 127.0.0.1 como fallback). */
    public static String primaryLocalIp() {
        List<String> ips = localIPv4Addresses();
        // Fallback honesto: 127.0.0.1 só alcança a própria máquina, e a
        // mensagem de inicialização diz isso — melhor do que um endereço que
        // não funciona.
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
    }

    /**
     * Todos os endereços (IPv4 e IPv6) das interfaces da máquina. Usado pela
     * política de origem: uma consulta vinda do próprio endereço da máquina é
     * local por definição, mesmo sem cair em rede privada (VPS com IP público).
     */
    public static Set<InetAddress> localAddresses() {
        Set<InetAddress> enderecos = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return Set.of();
            }
            for (NetworkInterface iface : Collections.list(ifaces)) {
                try {
                    if (!iface.isUp()) {
                        continue;
                    }
                } catch (Exception e) {
                    continue;
                }
                enderecos.addAll(Collections.list(iface.getInetAddresses()));
            }
        } catch (Exception e) {
            return Set.of();
        }
        return Set.copyOf(enderecos);
    }

    /** Imprime o bloco de ajuda de configuração ao subir o servidor. */
    public static void printStartupInfo(int port, String upstream) {
        List<String> ips = localIPv4Addresses();
        System.out.println("SiteLock DNS ativo.");
        if (ips.isEmpty()) {
            System.out.println("IP local: não detectado (usando 127.0.0.1 — só esta máquina alcança).");
        } else {
            System.out.println("IP local desta máquina (aponte o DNS dos dispositivos para ele): "
                    + String.join(", ", ips));
        }
        System.out.println("Porta: " + port + " | Upstream: " + upstream);
        System.out.println("No celular/PC, configure o DNS manualmente para "
                + primaryLocalIp() + " (veja o README para o passo a passo).");
    }
}
