package com.sitelock.dns;

/** Falha amigável do servidor DNS (mensagem pronta para o usuário, sem stack trace). */
public class DnsServerException extends RuntimeException {

    public DnsServerException(String message) {
        super(message);
    }

    public DnsServerException(String message, Throwable cause) {
        super(message, cause);
    }
}
