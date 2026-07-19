package com.traveltrace.build;

/** Thrown when the LIVE model list contains no model matching the vision ruleset. */
public class NoSuitableModelException extends RuntimeException {
    public NoSuitableModelException(String message) {
        super(message);
    }
}
