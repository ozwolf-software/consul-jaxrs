package net.ozwolf.consul.util;

import com.orbitz.consul.model.State;
import net.ozwolf.consul.client.ConsulJaxRsClient;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;

/**
 * <h1>Weighted Client Randomizer</h1>
 *
 * Utility class to randomly select a client instance based on the provided weightings.
 */
public class WeightedClientRandomizer {
    private final static SecureRandom RANDOM = new SecureRandom();

    /**
     * Randomly select a client from the provided set using the state weightings provided.
     *
     * @param clients    the clients to select from
     * @param weightings the weightings of each client state.  If a state is missing, it is defaulted to a `0.1` weighting
     * @return the selected client instance
     */
    public static ConsulJaxRsClient select(Set<ConsulJaxRsClient> clients, Map<State, Double> weightings) {
        Double totalWeight = clients.stream().map(c -> weightings.getOrDefault(c.getState(), 0.1d)).reduce(0.0d, (v1, v2) -> v1 + v2);
        Double random = RANDOM.nextDouble() * totalWeight;

        for (ConsulJaxRsClient client : clients) {
            random -= weightings.getOrDefault(client.getState(), 0.1d);
            if (random <= 0.0d)
                return client;
        }

        throw new UnknownError("We should never have reached this point!!!");
    }
}
