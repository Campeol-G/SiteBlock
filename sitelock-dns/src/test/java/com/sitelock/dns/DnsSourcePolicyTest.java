package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Who may query the DNS server: by default, refuse everything that is not local.
// Listening on 0.0.0.0 without checking the source turns SiteLock into an open
// resolver — outsiders amplify responses and discover what the network queries.
class DnsSourcePolicyTest {

    private static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    @Test
    void localSourceIsAllowed() throws Exception {
        DnsServer.SourcePolicy policy = DnsServer.SourcePolicy.defaultPolicy(List.of());
        assertTrue(policy.isAllowed(ip("127.0.0.1")), "loopback");
        assertTrue(policy.isAllowed(ip("127.0.0.53")), "entire loopback range");
        assertTrue(policy.isAllowed(ip("::1")), "IPv6 loopback");
        assertTrue(policy.isAllowed(ip("192.168.1.13")), "RFC1918 192.168/16");
        assertTrue(policy.isAllowed(ip("10.1.2.3")), "RFC1918 10/8");
        assertTrue(policy.isAllowed(ip("172.16.0.1")), "RFC1918 172.16/12");
        assertTrue(policy.isAllowed(ip("172.31.255.254")), "RFC1918 172.16/12 (end)");
        assertTrue(policy.isAllowed(ip("169.254.10.1")), "link-local");
        assertTrue(policy.isAllowed(ip("fe80::1")), "IPv6 link-local");
    }

    @Test
    void publicSourceIsRefused() throws Exception {
        DnsServer.SourcePolicy policy = DnsServer.SourcePolicy.defaultPolicy(List.of());
        assertFalse(policy.isAllowed(ip("8.8.8.8")), "a public IP is not local");
        assertFalse(policy.isAllowed(ip("203.0.113.5")), "a public IP is not local");
        assertFalse(policy.isAllowed(ip("172.32.0.1")), "172.32/12 is outside RFC1918");
        assertFalse(policy.isAllowed(ip("172.15.255.255")), "172.15/12 is outside RFC1918");
        assertFalse(policy.isAllowed(ip("2001:db8::1")), "global IPv6 is not local");
    }

    @Test
    void machineOwnAddressIsAllowed() throws Exception {
        DnsServer.SourcePolicy policy = DnsServer.SourcePolicy.defaultPolicy(List.of());
        for (InetAddress address : NetworkUtils.localAddresses()) {
            assertTrue(policy.isAllowed(address),
                    "the machine's own address is local: " + address.getHostAddress());
        }
    }

    @Test
    void allowFromAllowsExplicitSource() throws Exception {
        DnsServer.SourcePolicy policy = DnsServer.SourcePolicy.defaultPolicy(List.of("203.0.113.0/24"));
        assertTrue(policy.isAllowed(ip("203.0.113.5")), "allowed CIDR");
        assertFalse(policy.isAllowed(ip("203.0.114.5")), "outside the allowed CIDR");
    }

    @Test
    void allowFromAcceptsSingleAddress() throws Exception {
        DnsServer.SourcePolicy policy = DnsServer.SourcePolicy.defaultPolicy(List.of("198.51.100.7"));
        assertTrue(policy.isAllowed(ip("198.51.100.7")));
        assertFalse(policy.isAllowed(ip("198.51.100.8")));
    }

    @Test
    void invalidListIsRejectedWithMessage() {
        assertThrows(IllegalArgumentException.class,
                () -> DnsServer.parseAllowFrom("203.0.113.0/99"));
        assertThrows(IllegalArgumentException.class,
                () -> DnsServer.parseAllowFrom("example.com"));
        assertThrows(IllegalArgumentException.class,
                () -> DnsServer.parseAllowFrom("999.1.1.1"));
    }
}
