package com.atria.reminder.model;

/**
 * The model envelope could not be read: unparseable JSON, a missing required field, an unknown field or a wrong
 * type. Consumes the single retry; a second failure exits 7 (MODEL_OUTPUT_INVALID).
 *
 * <p>Out-of-range <em>values</em> are never this exception — they are the signal that FR-06 or FR-10 applies.
 */
public class ExtractionException extends Exception {

    public ExtractionException(String message) {
        super(message);
    }
}
