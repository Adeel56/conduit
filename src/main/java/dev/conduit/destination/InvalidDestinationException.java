package dev.conduit.destination;

/**
 * A destination create/update payload that fails structural validation (blank name, or a
 * non-absolute / non-http(s) URL). The controller maps it to a 400 with the message — it carries no
 * tenant data, so the message is safe to return.
 */
public class InvalidDestinationException extends RuntimeException {

    public InvalidDestinationException(String message) {
        super(message);
    }
}
