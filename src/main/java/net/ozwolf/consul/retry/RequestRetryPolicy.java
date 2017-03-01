package net.ozwolf.consul.retry;

import net.ozwolf.consul.client.ServiceInstanceClient;
import net.ozwolf.consul.exception.RequestFailureException;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * <h1>Request Retry Policy</h1>
 *
 * This class is used by the pool to wrap client requests within a retry loop.
 *
 * The policy will execute the provided action, using revoke and break instructions to renew client instances or break the retry loop as needed.
 *
 * ## Example Usage
 *
 * ```java
 * String result = pool.retry(3)
 * .revokeOn(ServerException.class, 5, TimeUnit.MINUTES)
 * .breakOn(ClientException.class)
 * .execute(c -> c.target("/path/to/something").request().get(String.class));
 * ```
 */
@SuppressWarnings("WeakerAccess")
public class RequestRetryPolicy {
    private final Integer attempts;
    private final Supplier<ServiceInstanceClient> onNext;

    private final Map<Class<? extends Exception>, Long> revokeOn;
    private final Set<Class<? extends Exception>> breakOn;

    /**
     * Create a new retry policy that will make the provided number of attempts to execute the action.
     *
     * @param attempts the number of request attempts to make
     * @param onNext   the supplier of clients when the next instance is required
     */
    public RequestRetryPolicy(Integer attempts, Supplier<ServiceInstanceClient> onNext) {
        this.attempts = attempts;
        this.onNext = onNext;

        this.revokeOn = new HashMap<>();
        this.breakOn = new HashSet<>();
    }

    /**
     * Instruct the retry policy to revoke clients on the provided exception type for the given amount of time.
     *
     * @param cause        the request exception cause
     * @param revokePeriod the revocation time period
     * @param revokeUnits  the revocation time units
     * @return the updated policy
     */
    public RequestRetryPolicy revokeOn(Class<? extends Exception> cause, Integer revokePeriod, TimeUnit revokeUnits) {
        this.revokeOn.put(cause, revokeUnits.toMillis(revokePeriod));
        return this;
    }

    /**
     * Instruct the retry policy to break the retry loop on the provided exception type
     *
     * @param cause the request exception cause
     * @return the updated policy
     */
    public RequestRetryPolicy breakOn(Class<? extends Exception> cause) {
        this.breakOn.add(cause);
        return this;
    }

    /**
     * Execute the provided action against the configured retry policy.
     *
     * @param action the action to execute that accepts a client instance
     * @return the action result
     * @throws RequestFailureException if the request fails for any reason (ie. max attempts reached or a breaking exception caught).  The exception contains the cause of the failure.
     */
    public <T> T execute(RequestAction<T> action) {
        return doAttempt(1, onNext.get(), action);
    }

    private <T> T doAttempt(int attempt, ServiceInstanceClient client, RequestAction<T> action) {
        try {
            return action.doRequest(client);
        } catch (Exception e) {
            // Always revoke first.  This means an exception that revokes and breaks will still be revoked appropriately.
            this.revokeOn.entrySet()
                    .stream()
                    .filter(r -> r.getKey().isInstance(e))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .ifPresent(client::revoke);

            if (isLastAttempt(attempt))
                throw new RequestFailureException(client.getKey().getServiceId(), "Request failed due to exception", e);

            if (this.breakOn.stream().anyMatch(c -> c.isInstance(e)))
                throw new RequestFailureException(client.getKey().getServiceId(), "Request aborted due to exception", e);

            return doAttempt(attempt + 1, client.isRevoked() ? onNext.get() : client, action);
        }
    }

    private boolean isLastAttempt(int attempt) {
        return attempt >= this.attempts;
    }
}
