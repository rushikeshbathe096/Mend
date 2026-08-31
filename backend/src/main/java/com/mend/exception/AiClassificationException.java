package com.mend.exception;

public class AiClassificationException extends RuntimeException {
    public AiClassificationException(String message) {
        super(message);
    }

    public AiClassificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
