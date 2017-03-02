package net.ozwolf.consul;

import com.orbitz.consul.Consul;
import com.orbitz.consul.cache.ConsulCache;
import com.orbitz.consul.cache.ServiceHealthCache;
import com.orbitz.consul.cache.ServiceHealthKey;
import com.orbitz.consul.model.State;
import com.orbitz.consul.model.health.ServiceHealth;
import net.ozwolf.consul.client.ConsulJaxRsClient;
import net.ozwolf.consul.exception.ClientAvailabilityException;
import net.ozwolf.consul.retry.RequestRetryPolicy;
import net.ozwolf.consul.util.WeightedClientRandomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.client.Client;
import java.net.URI;
import java.util.*;

import static java.util.stream.Collectors.toSet;

/**
 * <h1>Service Instance Client Pool</h1>
 *
 * The pool class is the centralized collection of available instances from the provided Consul instance.
 *
 * It will automatically self-update the healthy state of available instances for the given service ID and provide the ability for instances to be mapped to specific client instances.
 *
 * The pool is configured by default to use the HTTPS scheme when preparing requests.
 *
 * ## Example Usage
 *
 * ```java
 * Client client = new JerseyClientBuilder().withConfig(new ClientConfig(JacksonJaxbJsonProvider.class)).build();
 * Consul consul = Consul.builder().withHostAndPort(HostAndPort.fromParts("consul.local", 8500)).build();
 *
 * ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("my-service", client, consul);
 * ```
 */
@SuppressWarnings("WeakerAccess")
public class ConsulJaxRsClientPool implements ConsulCache.Listener<ServiceHealthKey, ServiceHealth> {
    private final String serviceId;
    private final Client baseClient;
    private final Consul consul;

    private URI fallback;
    private HttpMode scheme;
    private Integer pollRate;

    private ServiceHealthCache serviceCache;

    private Set<ConsulJaxRsClient> clients;

    private final Map<State, Double> weightings;

    private final static Logger LOGGER = LoggerFactory.getLogger(ConsulJaxRsClientPool.class);
    private final static Integer DEFAULT_POLL_RATE_SECONDS = 300;

    /**
     * Construct a new client pool
     *
     * @param serviceId  the Consul service ID this client pool is for
     * @param baseClient the configured JAX RS client that will be used
     * @param consul     the configured Consul client connection for retrieving discoverable state
     */
    public ConsulJaxRsClientPool(String serviceId, Client baseClient, Consul consul) {
        this.serviceId = serviceId;
        this.baseClient = baseClient;
        this.consul = consul;

        this.scheme = HttpMode.HTTPS;
        this.pollRate = DEFAULT_POLL_RATE_SECONDS;

        this.clients = new HashSet<>();
        this.weightings = defaultWeightings();
    }

    /**
     * Provide a fallback instance URI to use.  This will be used in place of any discoverable instance _if_ no instances have been registered.
     *
     * @param uri the base URI to the fallback service instance
     * @return the updated pool
     */
    public ConsulJaxRsClientPool withFallbackInstance(URI uri) {
        this.fallback = uri;
        return this;
    }

    /**
     * Change the request scheme that will be used.  The pool is configured by default to use HTTPS.
     *
     * @param scheme the request scheme
     * @return the updated pool
     */
    public ConsulJaxRsClientPool useHttpMode(HttpMode scheme) {
        this.scheme = scheme;
        return this;
    }

    /**
     * Modifies the rate at which the pool polls for service instance updates
     *
     * @param seconds the poll rate in seconds
     * @return the updated pool
     */
    public ConsulJaxRsClientPool withPollRate(Integer seconds) {
        this.pollRate = seconds;
        return this;
    }

    /**
     * Change the default weighting of services with a specific state.
     *
     * The default weightings are:
     *
     * + `PASS` - `1.0`
     * + `WARN` - `0.5`
     * + `FAIL` - `0.1`
     *
     * @param state     the state to set the weighting of
     * @param weighting the weight amount
     * @return the updated pool
     */
    public ConsulJaxRsClientPool withStateWeightingOf(State state, Double weighting) {
        this.weightings.put(state, weighting);
        return this;
    }

    /**
     * Retrieve the next available service instance client that has at least the minimum health state.
     *
     * Will randomly select an available instance, including revoked clients if no other instances are available.
     *
     * @param minimumState the minimum state the client should be in
     * @return the service instance client
     * @throws ClientAvailabilityException if there are no instances and no fallback, or no valid instance could be selected matching the desired state
     */
    public ConsulJaxRsClient next(State minimumState) {
        if (this.clients.isEmpty() && this.fallback == null)
            throw new ClientAvailabilityException(serviceId, "No instances published and no fallback provided.");

        if (this.clients.isEmpty() && this.fallback != null)
            return new ConsulJaxRsClient(ServiceHealthKey.of(serviceId, fallback.getHost(), fallback.getPort()), baseClient, scheme);

        Set<ConsulJaxRsClient> available = this.clients.stream()
                .filter(c -> c.isAtLeast(minimumState))
                .filter(c -> !c.isRevoked())
                .collect(toSet());

        if (available.isEmpty()) {
            LOGGER.warn(String.format("[ Service ID: %s ] - No active instances of at least [ %s ] state found.  Including revoked instances.", serviceId, minimumState));
            available = this.clients.stream().filter(c -> c.isAtLeast(minimumState)).collect(toSet());
        }

        if (available.isEmpty())
            throw new ClientAvailabilityException(serviceId, String.format("No instances (including revoked) available of at least [ %s ] state available.", minimumState));

        return WeightedClientRandomizer.select(available, weightings);
    }

    /**
     * Retrieve the next available service instance client that has at least the `WARN` health state.
     *
     * Will randomly select an available instance, including revoked clients if no other instances are available.
     *
     * @return the service instance client
     * @throws ClientAvailabilityException if there are no instances and no fallback, or no valid instance could be selected matching the desired state
     */
    public ConsulJaxRsClient next() {
        return next(State.WARN);
    }

    /**
     * Begin preparing a retry policy that will make the provided number of attempts to complete the request.
     *
     * This retry policy will use instances that have at least the `WARN` health state.
     *
     * @param attempts the number of attempts the retry attempt should make
     * @return the request retry policy
     */
    public RequestRetryPolicy retry(Integer attempts) {
        return retry(State.WARN, attempts);
    }

    /**
     * Begin preparing a retry policy that will make the provided number of attempts to complete the request.
     *
     * @param minimumState the minimum health state client instances being used must have
     * @param attempts     the number of attempts the retry attempt should make
     * @return the request retry policy
     */
    public RequestRetryPolicy retry(State minimumState, Integer attempts) {
        return new RequestRetryPolicy(attempts, () -> next(minimumState));
    }

    /**
     * Connects the client to the Consul instance and begins listening for published service instances.
     *
     * @throws Exception if any exception occurs during the connection process.
     */
    public void connect() throws Exception {
        this.serviceCache = ServiceHealthCache.newCache(this.consul.healthClient(), serviceId, false, null, pollRate);
        this.serviceCache.addListener(this);
        this.serviceCache.start();

        this.notify(this.serviceCache.getMap());
    }

    /**
     * Shuts down the client and stops it from listening to the Consul instance for updates.
     *
     * The last retrieved state remains the static state until the client is re-connected.
     *
     * @throws Exception if any exception occurs during the connection process.
     */
    public void disconnect() throws Exception {
        if (this.serviceCache != null) {
            this.serviceCache.stop();
            this.serviceCache = null;
        }
    }

    /**
     * The listener method that receives updates to service health
     *
     * @param instances the updated instances from Consul
     */
    @Override
    public void notify(Map<ServiceHealthKey, ServiceHealth> instances) {
        Set<ConsulJaxRsClient> updated = new HashSet<>();

        instances.entrySet().stream()
                .forEach(e -> {
                    Optional<ConsulJaxRsClient> previous = clients.stream().filter(c -> c.getKey().equals(e.getKey())).findFirst();
                    if (previous.isPresent()) {
                        updated.add(previous.get().update(e.getValue()));
                    } else {
                        updated.add(new ConsulJaxRsClient(e.getKey(), baseClient, scheme).update(e.getValue()));
                    }
                });

        this.clients = updated;
    }

    private static Map<State, Double> defaultWeightings() {
        Map<State, Double> weightings = new HashMap<>();
        weightings.put(State.PASS, 1.0d);
        weightings.put(State.WARN, 0.5d);
        weightings.put(State.FAIL, 0.1d);
        return weightings;
    }
}
