package net.ozwolf.consul.exception;

/**
 * <h1>Client Availability Exception</h1>
 *
 * Exception thrown when the client pool is unable to find an appropriate service instance client.
 */
public class ClientAvailabilityException extends RuntimeException {
    public ClientAvailabilityException(String serviceId, String reason) {
        super(String.format("[ Service ID: %s ] - %s", serviceId, reason));
    }
}
