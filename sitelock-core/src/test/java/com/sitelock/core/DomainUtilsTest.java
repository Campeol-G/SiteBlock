package com.sitelock.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class DomainUtilsTest {

    @Test
    void keepsBareDomainUnchanged() {
        DomainUtils.NormalizationResult r = DomainUtils.normalize("tiktok.com");
        assertEquals("tiktok.com", r.domain());
        assertFalse(r.normalized());
    }

    @Test
    void stripsWwwAndLowercases() {
        DomainUtils.NormalizationResult r = DomainUtils.normalize("WWW.TikTok.COM");
        assertEquals("tiktok.com", r.domain());
        assertTrue(r.normalized());
    }

    @Test
    void stripsProtocolPathQueryAndPort() {
        DomainUtils.NormalizationResult r =
                DomainUtils.normalize("https://WWW.TikTok.com:443/feed?x=1#frag");
        assertEquals("tiktok.com", r.domain());
        assertTrue(r.normalized());
    }

    @Test
    void stripsTrailingDotAndSchemeWithoutWww() {
        assertEquals("example.com",
                DomainUtils.normalize("http://example.com./path").domain());
    }

    @Test
    void preservesNonWwwSubdomains() {
        assertEquals("m.tiktok.com", DomainUtils.normalize("m.tiktok.com").domain());
        assertEquals("a.b.example.com", DomainUtils.normalize("a.b.example.com").domain());
    }

    @Test
    void acceptsPunycodeAndHyphens() {
        assertEquals("xn--exemplo-9ta.com", DomainUtils.normalize("xn--exemplo-9ta.com").domain());
        assertEquals("meu-site.com.br", DomainUtils.normalize("meu-site.com.br").domain());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "tik tok.com", "https://", "www", ".com",
            "com", "localhost", "192.168.0.1", "exemplo.c", "exemplo.123",
            "-bad.com", "bad-.com", "a..b.com", "site_cool.com"})
    void rejectsInvalid(String input) {
        assertThrows(IllegalArgumentException.class, () -> DomainUtils.normalize(input),
                "deveria rejeitar: '" + input + "'");
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> DomainUtils.normalize(null));
    }

    @Test
    void isValidMatchesNormalizeAcceptance() {
        assertTrue(DomainUtils.isValid("tiktok.com"));
        assertFalse(DomainUtils.isValid("192.168.0.1"));
        assertFalse(DomainUtils.isValid("localhost"));
        assertFalse(DomainUtils.isValid(null));
    }
}
