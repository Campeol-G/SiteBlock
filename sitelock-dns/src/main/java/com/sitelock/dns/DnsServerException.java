package com.sitelock.dns;

/** User-friendly DNS server failure (message ready to print, no stack trace). */
public class DnsServerException extends RuntimeException {

    public DnsServerException(String message) {
        super(message);
    }

    public DnsServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
