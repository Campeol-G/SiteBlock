package com.sitelock.dns;

/** Consulta DNS que não respeita o formato esperado (RFC 1035 §4.1). */
public class DnsParseException extends Exception {

    public DnsParseException(String message) {
        super(message);
    }
}
