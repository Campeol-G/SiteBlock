package com.sitelock.dns;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The address the CLI tells the user to configure on their phone. A link-local
// address (169.254/16 — hotspot, PPPoE) is not routable: a phone pointed at it
// resolves nothing, and the CLI used to print
// "set DNS manually to 169.254.210.73".
class NetworkUtilsTest {

    private static InetAddress ip(String text) throws Exception {
        return InetAddress.getByName(text);
    }

    @Test
    void linkLocalIsNotUsableAsDnsAddress() throws Exception {
        assertFalse(NetworkUtils.isUsableAsDnsAddress(ip("169.254.210.73")),
                "link-local is not routable");
        assertFalse(NetworkUtils.isUsableAsDnsAddress(ip("169.254.0.1")),
                "link-local is not routable");
        assertFalse(NetworkUtils.isUsableAsDnsAddress(ip("0.0.0.0")),
                "any-local is nobody's address");
    }

    @Test
    void privateAddressIsUsableAsDnsAddress() throws Exception {
        assertTrue(NetworkUtils.isUsableAsDnsAddress(ip("192.168.1.13")));
        assertTrue(NetworkUtils.isUsableAsDnsAddress(ip("10.0.0.5")));
        assertTrue(NetworkUtils.isUsableAsDnsAddress(ip("172.17.0.1")));
    }

    @Test
    void loopbackIsNotUsableAsDnsAddress() throws Exception {
        // Loopback is local, but pointing a phone at it does not work: the phone's
        // own 127.0.0.1 is the phone itself.
        assertFalse(NetworkUtils.isUsableAsDnsAddress(ip("127.0.0.1")));
    }

    @Test
    void noListedAddressIsLinkLocal() {
        for (String address : NetworkUtils.localIPv4Addresses()) {
            assertFalse(address.startsWith("169.254."),
                    "link-local must not be offered to the user: " + address);
            assertFalse(address.equals("0.0.0.0"),
                    "any-local must not be offered to the user: " + address);
        }
    }
}
