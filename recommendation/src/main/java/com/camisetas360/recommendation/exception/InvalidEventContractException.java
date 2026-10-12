package com.camisetas360.recommendation.exception;

/**
 * Thrown when an incoming event payload fails contract validation or deserialization.
 * Extends {@link IllegalArgumentException} to maintain compatibility with Spring AMQP
 * listener reject/dead-letter policies.
 */
public class InvalidEventContractException extends IllegalArgumentException {

    public InvalidEventContractException(String message) {
        super(message);
    }

    public InvalidEventContractException(String message, Throwable cause) {
        super(message, cause);
    }
}

