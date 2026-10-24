package com.payrail.outbox.service;

/**
 * Exception used to simulate an ungraceful process halt (crash/SIGKILL)
 * where the application stops immediately after broker acknowledgment
 * without executing database updates or error handlers.
 */
public class PublisherProcessHaltException extends RuntimeException {

    public PublisherProcessHaltException(String message) {
        super(message);
    }
}
