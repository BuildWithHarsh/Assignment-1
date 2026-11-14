package com.atria.reminder.model;

/**
 * The local model service could not be reached or failed: connection refused, read timeout, HTTP error, or the
 * process time budget exhausted. Exits 6, which the evaluation treats as an environment ERROR.
 *
 * <p>Never raised for an application bug — that is exit 1 (INTERNAL_ERROR) — and never retried, so exit 6 keeps
 * meaning "the service is down".
 */
public class ModelUnavailableException extends Exception {

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
