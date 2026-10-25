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
 * Discovers the machine's local IP to print in the terminal when the server
 * starts: the user points the phone/PC DNS at that IP.
 */
public final class NetworkUtils {

    private NetworkUtils() {
    }

    // WHY: site-local addresses (192.168/10/172.16-31) come first — with several
    // interfaces (Wi-Fi + Ethernet + VPN + Docker), that is the one reachable from
    // the local network. Filtering the essentials (up, non-loopback, non-virtual)
    // avoids per-OS logic.
    /** All local non-loopback IPv4 addresses (site-local first). */
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
                    if (!isUsableAsDnsAddress(addr)) {
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

    // True if the address can be used to configure a network device's DNS.
    // WHY: link-local (169.254/16 — hotspot, PPPoE) is not routable — a phone
    // pointed at it resolves nothing; 0.0.0.0 and loopback do not work either.
    static boolean isUsableAsDnsAddress(InetAddress addr) {
        if (addr == null || !(addr instanceof Inet4Address)) {
            return false;
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
            return false;
        }
        return !addr.isMulticastAddress();
    }

    /** Primary IP to configure on devices (or 127.0.0.1 as a fallback). */
    public static String primaryLocalIp() {
        List<String> ips = localIPv4Addresses();
        // Honest fallback: 127.0.0.1 only reaches the machine itself, and the
        // startup message says so — better than an address that does not work.
        return ips.isEmpty() ? "127.0.0.1" : ips.get(0);
    }

    /**
     * All addresses (IPv4 and IPv6) of the machine's interfaces. Used by the
     * source policy: a query from the machine's own address is local by
     * definition, even outside a private range (a VPS with a public IP).
     */
    public static Set<InetAddress> localAddresses() {
        Set<InetAddress> addresses = new LinkedHashSet<>();
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
                addresses.addAll(Collections.list(iface.getInetAddresses()));
            }
        } catch (Exception e) {
            return Set.of();
        }
        return Set.copyOf(addresses);
    }

    /** Prints the configuration help block when the server starts. */
    public static void printStartupInfo(int port, String upstream) {
        List<String> ips = localIPv4Addresses();
        System.out.println("SiteLock DNS is running.");
        if (ips.isEmpty()) {
            System.out.println("Local IP: not detected (using 127.0.0.1 — only this machine can reach it).");
        } else {
            System.out.println("Local IP of this machine (point device DNS at it): "
                    + String.join(", ", ips));
        }
        System.out.println("Port: " + port + " | Upstream: " + upstream);
        System.out.println("On your phone/PC, set DNS manually to "
                + primaryLocalIp() + " (see the README for step-by-step instructions).");
    }
}
