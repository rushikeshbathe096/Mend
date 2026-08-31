package com.mend.exception;

public class WebhookPublishException extends RuntimeException {
    public WebhookPublishException(String message) {
        super(message);
    }

    public WebhookPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
