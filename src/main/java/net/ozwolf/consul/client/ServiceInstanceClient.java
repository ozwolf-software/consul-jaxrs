package net.ozwolf.consul.client;

import com.orbitz.consul.cache.ServiceHealthKey;
import com.orbitz.consul.model.State;
import com.orbitz.consul.model.health.ServiceHealth;
import net.ozwolf.consul.HttpMode;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Configuration;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.toSet;

/**
 * <h1>Service Instance Client</h1>
 *
 * The service instance client is a delegating wrapper to a provided JAX RS `Client` instance that is mapped to a published Consul service instance.
 *
 * This client will maintain certain health and state knowledge relevant to the particular instance.  Any target requests will override the scheme, host and port with the instances specific information.
 *
 * This allows path-only resources to be provided to the client.
 *
 * ## Example Usage
 *
 * ```java
 * ServiceInstanceClient client = pool.next();
 *
 * String result = client.target("/path/to/something").request().get(String.class);
 * ```
 */
@SuppressWarnings("WeakerAccess")
public class ServiceInstanceClient implements Client {
    private final ServiceHealthKey key;
    private final Client client;
    private final HttpMode scheme;

    private Instant revoked;
    private ServiceHealth health;

    /**
     * Create a new client instance
     * @param key the service instance key
     * @param client the delegating client
     * @param scheme the HTTP scheme to use in requests (ie. HTTP or HTTPS)
     */
    public ServiceInstanceClient(ServiceHealthKey key,
                                 Client client,
                                 HttpMode scheme) {
        this.key = key;
        this.client = client;
        this.scheme = scheme;
    }

    /**
     * The service instance client key
     * @return the key
     */
    public ServiceHealthKey getKey() {
        return key;
    }

    /**
     * Flags this client to be revoked, removing it from selection until the timeout expires.
     *
     * Note: this instance can still be selected if _all_ available instances are revoked.
     *
     * @param milliseconds the revoke period in milliseconds
     */
    public void revoke(Long milliseconds) {
        this.revoked = Instant.now().plusMillis(milliseconds);
    }

    /**
     * Flags this client to be revoked, removing it from selection until the timeout expires.
     *
     * Note: this instance can still be selected if _all_ available instances are revoked.
     *
     * @param period the revoke time period
     * @param units the revoke time units
     */
    public void revoke(Integer period, TimeUnit units){
        this.revoke(units.toMillis(period));
    }

    /**
     * Flag if the client has been revoked form selection
     *
     * @return true if the revoked period is in effect
     */
    public boolean isRevoked() {
        return this.revoked != null &&
                this.revoked.isAfter(Instant.now());
    }

    /**
     * Flag if the client is at least the provided state.
     *
     * The `PASS`, `WARN` and `FAIL` states each include the state(s) above them respectively (ie. `WARN` includes `WARN` and `PASS` states)
     *
     * @param state the state to test
     * @return tru if the client has a state that is at least the provided state
     */
    public boolean isAtLeast(State state) {
        State actual = getState();
        switch (state) {
            case PASS:
                return actual == State.PASS;
            case WARN:
                return actual == State.PASS || actual == State.WARN;
            case FAIL:
                return actual == State.PASS || actual == State.WARN || actual == State.FAIL;
            default:
                return actual == State.PASS || actual == State.WARN;
        }
    }

    /**
     * Returns the client state.
     *
     * + `PASS` - all associated health checks are `PASS`
     * + `FAIL` - all associated health checks are `FAIL`
     * + `WARN` - no available health checks or health checks are a mixture of `PASS`, `WARN` and `FAIL`
     *
     * @return the client state
     */
    public State getState() {
        if (health == null || health.getChecks().isEmpty())
            return State.WARN;

        State serfHealthState = health.getChecks().stream().filter(c -> c.getCheckId().equals("serfHealth")).findFirst().map(c -> State.fromName(c.getStatus())).orElse(State.FAIL);

        if (serfHealthState == State.FAIL)
            return State.FAIL;

        Set<State> states = health.getChecks()
                .stream()
                .filter(c -> !c.getCheckId().equals("serfHealth"))
                .map(c -> State.fromName(c.getStatus()))
                .distinct()
                .collect(toSet());

        if (states.size() == 1 && states.contains(State.FAIL))
            return State.FAIL;

        if (states.size() == 1 && states.contains(State.PASS) && serfHealthState == State.PASS)
            return State.PASS;

        return State.WARN;
    }

    /**
     * Update the client state from the provided Consul health
     *
     * @param health the service health from Consul
     * @return the updated client
     */
    public ServiceInstanceClient update(ServiceHealth health) {
        this.health = health;
        return this;
    }

    /**
     * Will close the delegating client.
     *
     * **WARNING:** This will close all client instances that are using the delegate authority!!!
     */
    @Override
    public void close() {
        this.client.close();
    }

    @Override
    public WebTarget target(String uri) {
        return client.target(UriBuilder.fromUri(uri).scheme(scheme.getScheme()).host(key.getHost()).port(key.getPort()).build());
    }

    @Override
    public WebTarget target(URI uri) {
        return client.target(UriBuilder.fromUri(uri).scheme(scheme.getScheme()).host(key.getHost()).port(key.getPort()).build());
    }

    @Override
    public WebTarget target(UriBuilder uriBuilder) {
        return client.target(uriBuilder.scheme(scheme.getScheme()).host(key.getHost()).port(key.getPort()));
    }

    @Override
    public WebTarget target(Link link) {
        return client.target(UriBuilder.fromLink(link).host(key.getHost()).port(key.getPort()).build());
    }

    @Override
    public Invocation.Builder invocation(Link link) {
        return client.invocation(Link.fromUri(UriBuilder.fromLink(link).host(key.getHost()).port(key.getPort()).build()).build());
    }

    @Override
    public SSLContext getSslContext() {
        return client.getSslContext();
    }

    @Override
    public HostnameVerifier getHostnameVerifier() {
        return client.getHostnameVerifier();
    }

    @Override
    public Configuration getConfiguration() {
        return client.getConfiguration();
    }

    @Override
    public Client property(String name, Object value) {
        this.client.property(name, value);
        return this;
    }

    @Override
    public Client register(Class<?> componentClass) {
        this.client.register(componentClass);
        return this;
    }

    @Override
    public Client register(Class<?> componentClass, int priority) {
        this.client.register(componentClass, priority);
        return this;
    }

    @Override
    public Client register(Class<?> componentClass, Class<?>... contracts) {
        this.client.register(componentClass, contracts);
        return this;
    }

    @Override
    public Client register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        this.client.register(componentClass, contracts);
        return this;
    }

    @Override
    public Client register(Object component) {
        this.client.register(component);
        return this;
    }

    @Override
    public Client register(Object component, int priority) {
        this.client.register(component, priority);
        return this;
    }

    @Override
    public Client register(Object component, Class<?>... contracts) {
        this.client.register(component, contracts);
        return this;
    }

    @Override
    public Client register(Object component, Map<Class<?>, Integer> contracts) {
        this.client.register(component, contracts);
        return this;
    }
}
