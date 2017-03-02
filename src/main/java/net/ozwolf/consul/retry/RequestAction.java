package net.ozwolf.consul.retry;

import net.ozwolf.consul.client.ConsulJaxRsClient;

/**
 * <h1>Request Action</h1>
 *
 * Interface to implement when using the retry policy functionality of the connection pool.
 */
public interface RequestAction<T> {
    /**
     * The do request method implement.
     *
     * @param client the selected client instance for the request attempt
     * @return the expected result
     * @throws Exception when any exception occurs
     */
    T doRequest(ConsulJaxRsClient client) throws Exception;
}
