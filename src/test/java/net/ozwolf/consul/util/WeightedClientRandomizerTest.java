package net.ozwolf.consul.util;

import com.orbitz.consul.cache.ServiceHealthKey;
import com.orbitz.consul.model.State;
import net.ozwolf.consul.client.ServiceInstanceClient;
import org.assertj.core.api.Condition;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class WeightedClientRandomizerTest {
    @Test
    public void shouldRandomlySelectServicesUsingExpectedDistribution() {
        Set<ServiceInstanceClient> clients = new HashSet<>();
        clients.add(client(1, State.PASS));
        clients.add(client(2, State.WARN));
        clients.add(client(3, State.WARN));
        clients.add(client(4, State.WARN));
        clients.add(client(5, State.FAIL));
        clients.add(client(6, State.FAIL));
        clients.add(client(7, State.FAIL));

        Map<State, Double> weighting = new HashMap<>();
        weighting.put(State.PASS, 1.0d);
        weighting.put(State.WARN, 0.5d);
        weighting.put(State.FAIL, 0.1d);

        Map<Integer, AtomicInteger> distribution = new HashMap<>();

        // Create a random distribution
        for(int i = 1; i <= 10000; i++){
            ServiceInstanceClient client = WeightedClientRandomizer.select(clients, weighting);
            distribution.computeIfAbsent(client.getKey().getPort(), k -> new AtomicInteger(0)).addAndGet(1);
        }

        Integer passCount = distribution.get(1).get();
        Integer warn1Count = distribution.get(2).get();
        Integer warn2Count = distribution.get(3).get();
        Integer warn3Count = distribution.get(4).get();
        Integer fail1Count = distribution.get(5).get();
        Integer fail2Count = distribution.get(6).get();
        Integer fail3Count = distribution.get(7).get();

        // Test the weighted distribution of results
        assertThat(ratioOf(warn1Count, passCount)).is(between(0.4d, 0.6d));
        assertThat(ratioOf(warn2Count, passCount)).is(between(0.4d, 0.6d));
        assertThat(ratioOf(warn3Count, passCount)).is(between(0.4d, 0.6d));
        assertThat(ratioOf(fail1Count, passCount)).is(between(0.05d, 0.15d));
        assertThat(ratioOf(fail2Count, passCount)).is(between(0.05d, 0.15d));
        assertThat(ratioOf(fail3Count, passCount)).is(between(0.05d, 0.15d));
    }

    private static double ratioOf(Integer numerator, Integer divisor){
        return ((double) numerator) / ((double) divisor);
    }

    private static Condition<Double> between(double lower, double upper){
        return new Condition<>(v -> v >= lower && v <= upper, "[lower = <" + lower + ">, upper = <" + upper + ">]");
    }

    private static void assertDistribution(Integer value, Integer ... greaterThan){
        for(Integer testValue: greaterThan)
            assertThat(value).isGreaterThan(testValue);
    }

    private static ServiceInstanceClient client(int port, State state){
        ServiceInstanceClient client = mock(ServiceInstanceClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", port));
        when(client.getState()).thenReturn(state);
        return client;
    }
}