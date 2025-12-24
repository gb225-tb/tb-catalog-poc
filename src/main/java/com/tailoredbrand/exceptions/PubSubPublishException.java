package com.tailoredbrand.exceptions;

public class PubSubPublishException extends RuntimeException {

    public PubSubPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
