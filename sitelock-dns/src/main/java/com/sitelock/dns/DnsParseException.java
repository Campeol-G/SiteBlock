package com.sitelock.dns;

/** A DNS query that does not follow the expected format (RFC 1035 §4.1). */
public class DnsParseException extends Exception {

    public DnsParseException(String message) {
        super(message);
    }
}
