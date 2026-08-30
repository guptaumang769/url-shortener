package com.umang.urlshortener.exception;

public class AliasTakenException extends RuntimeException {
    public AliasTakenException(String alias) {
        super("Custom alias already in use: " + alias);
    }
}
