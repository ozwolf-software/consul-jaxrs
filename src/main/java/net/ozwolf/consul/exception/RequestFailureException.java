package net.ozwolf.consul.exception;

/**
 * <h1>Request Failure Exception</h1>
 *
 * Wrapper exception for failures that occur during the request retry process.
 */
public class RequestFailureException extends RuntimeException {
    public RequestFailureException(String serviceId, String reason, Throwable cause) {
        super(String.format("[ Service ID: %s ] - Request failed (%s)", serviceId, reason), cause);
    }
}
