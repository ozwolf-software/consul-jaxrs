package net.ozwolf.consul;

import com.orbitz.consul.HealthClient;
import com.orbitz.consul.cache.ServiceHealthCache;

/**
 * <h1>Service Health Cache Provider</h1>
 *
 * This provider interface can be implemented and provided to the `ConsulJaxRsClientPool`, providing an injectable instruction method to create a new `ServiceHealthCache` instance.
 *
 * The client pool will then attach a listener to this cache to update available client instances.
 */
public interface ServiceHealthCacheProvider {
    /**
     * Create a new service health cache using the provided client for the supplied service ID
     *
     * @param client            the active Consul health client
     * @param serviceId         the service ID to poll against
     * @param pollRateInSeconds the polling rate in seconds the cache will use
     * @return the service health cache instance
     */
    ServiceHealthCache get(HealthClient client, String serviceId, Integer pollRateInSeconds);
}
