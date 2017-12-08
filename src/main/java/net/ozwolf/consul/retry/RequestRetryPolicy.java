package net.ozwolf.consul.retry;

import com.github.davidmoten.rx.RetryWhen;
import net.ozwolf.consul.client.ConsulJaxRsClient;
import rx.Observable;

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
 *      .retryIntervalOf(
 *      .revokeOn(ServerException.class, 5, TimeUnit.MINUTES)
 *      .breakOn(ClientException.class)
 *      .execute(c -> c.target("/path/to/something").request().get(String.class));
 * ```
 */
@SuppressWarnings("WeakerAccess")
public class RequestRetryPolicy {
    private final Integer attempts;
    private final Supplier<ConsulJaxRsClient> onNext;

    private final Map<Class<? extends Exception>, Long> revokeOn;
    private final Set<Class<? extends Exception>> breakOn;

    private Long retryInterval;
    private Double backoffFactor;

    private final static Long DEFAULT_RETRY_INTERVAL = 100L;
    private final static Double DEFAULT_BACKOFF_FACTOR = 2.0;

    /**
     * Create a new retry policy that will make the provided number of attempts to execute the action.
     *
     * @param attempts the number of request attempts to make
     * @param onNext   the supplier of clients when the next instance is required
     */
    public RequestRetryPolicy(Integer attempts, Supplier<ConsulJaxRsClient> onNext) {
        this.attempts = attempts;
        this.onNext = onNext;

        this.revokeOn = new HashMap<>();
        this.breakOn = new HashSet<>();

        this.retryInterval = DEFAULT_RETRY_INTERVAL;
        this.backoffFactor = DEFAULT_BACKOFF_FACTOR;
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
     * Define a flat retry interval.  Setting this means no backoff will be made.
     *
     * @param duration the starting duration to wait between retries
     * @param units    the time units of the duration
     * @return the updated policy
     */
    public RequestRetryPolicy retryIntervalOf(Integer duration, TimeUnit units) {
        return this.retryIntervalOf(duration, 1.0, units);
    }

    /**
     * Define the amount of time between retries and a max duration which will use an exponential backoff on retries.
     *
     * The defaults for these are 100ms for the initial duration and a backoff factor of 2.0
     *
     * @param duration      the starting duration to wait between retries
     * @param backoffFactor the factor to backoff retries.  Will not allow factors less than 1.0
     * @param units         the time units of the duration
     * @return the updated policy
     * @throws IllegalArgumentException if the backoff factor is less than 1.0
     */
    public RequestRetryPolicy retryIntervalOf(Integer duration, Double backoffFactor, TimeUnit units) {
        if (backoffFactor < 1.0)
            throw new IllegalArgumentException("Backoff factor must be at least 1.0");

        this.retryInterval = units.toMillis(new Long(duration));
        this.backoffFactor = backoffFactor;
        return this;
    }

    /**
     * Execute the provided action against the configured retry policy.
     *
     * @param action the action to execute that accepts a client instance
     * @return the action result
     */
    public <T> T execute(RequestAction<T> action) {
        return observe(action).toBlocking().single();
    }

    public <T> Observable<T> observe(RequestAction<T> action) {
        return Observable.fromCallable(() -> {
            ConsulJaxRsClient client = onNext.get();
            try {
                return action.doRequest(client);
            } catch (Throwable t) {
                this.revokeOn
                        .entrySet()
                        .stream()
                        .filter(e -> e.getKey().isInstance(t))
                        .findFirst()
                        .ifPresent(e -> client.revoke(e.getValue()));

                throw t;
            }
        }).retryWhen(
                RetryWhen.maxRetries(attempts)
                        .exponentialBackoff(retryInterval, TimeUnit.MILLISECONDS, backoffFactor)
                        .retryIf(t -> breakOn.stream().noneMatch(b -> b.isInstance(t)))
                        .build()
        );
    }
}
