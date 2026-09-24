package com.sitelock.dns;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Descoberta do IP local da máquina para imprimir no terminal ao iniciar o servidor.
 * O usuário aponta o DNS do celular/PC para esse IP sem precisar descobrir manualmente.
 *
 * <p>Caso extremo (múltiplas interfaces — Wi-Fi + Ethernet + VPN + Docker): devolvemos
 * <b>todas</b> as IPv4 não-loopback e destacamos como primário o primeiro endereço
 * site-local (192.168/10/172.16-31), que é o alcançável pelos dispositivos da mesma
 * rede Wi-Fi. Se não houver nenhum, cai para 127.0.0.1 (só a própria máquina alcança).
 *
 * <p>Portabilidade (validado por revisão para Linux/macOS/Windows): a enumeração via
 * {@code NetworkInterface} do Java já é cross-platform por natureza. Filtramos apenas
 * interfaces ativas ({@code isUp}) e não-loopback, ignorando virtuais/desligadas — que
 * aparecem com nomes diferentes em cada SO (ex.: {@code docker0/veth*} no Linux,
 * {@code awdl0/utun*} no macOS, adaptadores Hyper-V/VPN no Windows). Nenhuma lógica
 * por SO foi necessária aqui; só esse filtro.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

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
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
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

    /** IP principal para configurar nos dispositivos (ou 127.0.0.1 como fallback). */
    public static String primaryLocalIp() {
        List<String> ips = localIPv4Addresses();
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
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
