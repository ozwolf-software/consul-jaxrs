package net.ozwolf.consul;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.google.common.net.HostAndPort;
import com.orbitz.consul.Consul;
import com.orbitz.consul.NotRegisteredException;
import com.orbitz.consul.model.State;
import com.orbitz.consul.model.agent.Registration;
import com.orbitz.consul.model.catalog.CatalogRegistration;
import com.orbitz.consul.model.catalog.ImmutableCatalogRegistration;
import com.pszymczyk.consul.junit.ConsulResource;
import net.ozwolf.consul.exception.ClientAvailabilityException;
import net.ozwolf.consul.testutils.PortFactory;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import javax.ws.rs.ServerErrorException;
import javax.ws.rs.client.Client;
import javax.ws.rs.core.UriBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"ThrowFromFinallyBlock", "Duplicates", "OptionalGetWithoutIsPresent"})
public class ConsulJaxRsClientPoolIntegrationTest {
    @ClassRule
    public final static ConsulResource CONSUL = new ConsulResource();

    @Rule
    public final WireMockRule instance1 = new WireMockRule(PortFactory.getNextAvailable());
    @Rule
    public final WireMockRule instance2 = new WireMockRule(PortFactory.getNextAvailable());
    @Rule
    public final WireMockRule instance3 = new WireMockRule(PortFactory.getNextAvailable());

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    private Client client;
    private Consul consul;

    @Before
    public void setUp() {
        CONSUL.reset();

        this.client = new JerseyClientBuilder().build();
        this.consul = Consul.builder().withHostAndPort(HostAndPort.fromParts("localhost", CONSUL.getHttpPort())).build();

        CatalogRegistration registration = ImmutableCatalogRegistration.builder()
                .node("node1")
                .address("127.0.0.1")
                .build();

        this.consul.catalogClient().register(registration);
    }

    @Test
    public void shouldOnlyUseViableInstances() throws Exception {
        instance1.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong 1")));
        instance2.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong 2")));
        instance3.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong 3")));

        publishInstance(instance1, State.PASS);
        publishInstance(instance2, State.WARN);
        publishInstance(instance3, State.FAIL);

        ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("testing", client, consul)
                .useHttpMode(HttpMode.HTTP)
                .withPollRate(1)
                .withStateWeightingOf(State.WARN, 0.75d);
        pool.connect();

        try {
            // Wait for the services to be available.
            TimeUnit.MILLISECONDS.sleep(1500);

            Map<String, AtomicInteger> responses = new HashMap<>();
            for (int i = 0; i <= 1000; i++) {
                String response = pool.next().target("/ping").request().get(String.class);
                responses.computeIfAbsent(response, k -> new AtomicInteger(0)).addAndGet(1);
            }

            assertThat(responses.containsKey("pong 3")).isFalse();
            assertThat(responses.containsKey("pong 1")).isTrue();
            assertThat(responses.containsKey("pong 2")).isTrue();

            Integer pong1Count = responses.get("pong 1").get();
            Integer pong2Count = responses.get("pong 2").get();

            double ratio = ((double) pong2Count) / ((double) pong1Count);

            assertThat(ratio).isGreaterThanOrEqualTo(0.65d).isLessThanOrEqualTo(0.85d);
        } finally {
            pool.disconnect();
        }
    }

    @Test
    public void shouldFailIfNoInstancesMeetRequiredHealthState() throws Exception {
        publishInstance(instance1, State.FAIL);
        publishInstance(instance2, State.FAIL);
        publishInstance(instance3, State.FAIL);

        ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("testing", client, consul).useHttpMode(HttpMode.HTTP).withPollRate(1);
        pool.connect();

        try {
            // Wait for the services to be available.
            TimeUnit.MILLISECONDS.sleep(1500);

            exception.expect(ClientAvailabilityException.class);
            exception.expectMessage("No instances (including revoked) available of at least [ WARN ] state available.");
            pool.next().target("/ping").request().get(String.class);
        } finally {
            pool.disconnect();
        }
    }

    @Test
    public void shouldFailIfNoInstancesAvailableAndNoFallback() throws Exception {
        ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("testing", client, consul).useHttpMode(HttpMode.HTTP).withPollRate(1);
        pool.connect();

        try {
            // Wait for the services to be available.
            TimeUnit.MILLISECONDS.sleep(1500);

            exception.expect(ClientAvailabilityException.class);
            exception.expectMessage("No instances published and no fallback provided.");
            pool.next().target("/ping").request().get(String.class);
        } finally {
            pool.disconnect();
        }
    }

    @Test
    public void shouldUseRetryMethod() throws Exception {
        instance1.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(500).withBody("internal error")));
        instance2.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(500).withBody("internal error")));
        instance3.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong 3")));

        publishInstance(instance1, State.PASS);
        publishInstance(instance2, State.PASS);
        publishInstance(instance3, State.PASS);

        ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("testing", client, consul).useHttpMode(HttpMode.HTTP).withPollRate(1);
        pool.connect();

        try {
            // Wait for the services to be available.
            TimeUnit.MILLISECONDS.sleep(1500);

            String result = pool.retry(3)
                    .revokeOn(ServerErrorException.class, 1, TimeUnit.MINUTES)
                    .execute(c -> c.target("/ping").request().get(String.class));

            assertThat(result).isEqualTo("pong 3");
        } finally {
            pool.disconnect();
        }
    }

    @Test
    public void shouldUseFallbackInstance() throws Exception {
        instance1.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("pong 1")));

        ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("testing", client, consul)
                .withFallbackInstance(UriBuilder.fromPath("/").scheme("http").host("localhost").port(instance1.port()).build())
                .useHttpMode(HttpMode.HTTP);

        pool.connect();

        try {
            // Wait for the services to be available.
            TimeUnit.MILLISECONDS.sleep(1500);

            String response = pool.next().target("/ping").request().get(String.class);

            assertThat(response).isEqualTo("pong 1");
        } finally {
            pool.disconnect();
        }
    }

    private void publishInstance(WireMockRule rule, State state) throws NotRegisteredException {
        Integer status = 200;
        if (state == State.FAIL)
            status = 500;
        if (state == State.WARN)
            status = 429;

        rule.stubFor(get(urlEqualTo("/health")).willReturn(aResponse().withStatus(status).withBody("help")));

        Registration.RegCheck check = Registration.RegCheck.http("http://localhost:" + rule.port() + "/health", 1);

        consul.agentClient()
                .register(rule.port(), check, "testing", "testing-" + rule.port());
    }
}