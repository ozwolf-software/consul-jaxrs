package net.ozwolf.consul.client;

import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.orbitz.consul.cache.ServiceHealthKey;
import com.orbitz.consul.model.State;
import com.orbitz.consul.model.health.HealthCheck;
import com.orbitz.consul.model.health.ServiceHealth;
import net.ozwolf.consul.HttpMode;
import org.glassfish.jersey.client.JerseyClientBuilder;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.ws.rs.client.Client;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.UriBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ConsulJaxRsClientTest {
    @Rule
    public final WireMockRule server = new WireMockRule();

    private Client client;

    @Before
    public void setUp() {
        server.stubFor(
                get(urlEqualTo("/ping"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody("pong")
                        )
        );

        this.client = new JerseyClientBuilder().build();
    }

    @Test
    public void shouldConnectToServerAndReturnIgnoringAnyHostPortSchemeProvided() {
        ServiceHealthKey key = ServiceHealthKey.of("testing", "localhost", server.port());

        Client consulClient = new ConsulJaxRsClient(key, client, HttpMode.HTTP);

        UriBuilder builder = UriBuilder.fromPath("/ping").scheme("https").host("wrong").port(18562);

        String result1 = consulClient.target(builder.build()).request().get(String.class);
        String result2 = consulClient.target(builder).request().get(String.class);
        String result3 = consulClient.target("/ping").request().get(String.class);
        String result4 = consulClient.target(Link.fromUriBuilder(builder).build()).request().get(String.class);

        assertThat(result1).isEqualTo("pong");
        assertThat(result2).isEqualTo("pong");
        assertThat(result3).isEqualTo("pong");
        assertThat(result4).isEqualTo("pong");
    }

    @Test
    public void shouldRecordClientAsRevoked() throws InterruptedException {
        ServiceHealthKey key = ServiceHealthKey.of("testing", "localhost", server.port());

        ConsulJaxRsClient consulClient = new ConsulJaxRsClient(key, client, HttpMode.HTTP);

        assertThat(consulClient.isRevoked()).isFalse();

        consulClient.revoke(100, TimeUnit.MILLISECONDS);

        assertThat(consulClient.isRevoked()).isTrue();

        TimeUnit.MILLISECONDS.sleep(200);

        assertThat(consulClient.isRevoked()).isFalse();
    }

    @Test
    public void shouldReturnAppropriateClientState() {
        ServiceHealthKey key = ServiceHealthKey.of("testing", "localhost", server.port());

        ConsulJaxRsClient consulClient = new ConsulJaxRsClient(key, client, HttpMode.HTTP);

        assertThat(consulClient.getState()).isEqualTo(State.WARN);

        ServiceHealth passing = health("passing", "passing");
        ServiceHealth failing = health("critical", "critical");
        ServiceHealth warning1 = health("passing", "critical");
        ServiceHealth warning2 = health("passing", "warning");
        ServiceHealth warning3 = health("passing", "warning", "critical");

        assertThat(consulClient.update(passing).getState()).isEqualTo(State.PASS);
        assertThat(consulClient.isAtLeast(State.PASS)).isTrue();
        assertThat(consulClient.isAtLeast(State.WARN)).isTrue();
        assertThat(consulClient.isAtLeast(State.FAIL)).isTrue();

        assertThat(consulClient.update(failing).getState()).isEqualTo(State.FAIL);
        assertThat(consulClient.isAtLeast(State.PASS)).isFalse();
        assertThat(consulClient.isAtLeast(State.WARN)).isFalse();
        assertThat(consulClient.isAtLeast(State.FAIL)).isTrue();

        assertThat(consulClient.update(warning1).getState()).isEqualTo(State.WARN);
        assertThat(consulClient.isAtLeast(State.PASS)).isFalse();
        assertThat(consulClient.isAtLeast(State.WARN)).isTrue();
        assertThat(consulClient.isAtLeast(State.FAIL)).isTrue();

        assertThat(consulClient.update(warning2).getState()).isEqualTo(State.WARN);
        assertThat(consulClient.update(warning3).getState()).isEqualTo(State.WARN);
    }

    private static ServiceHealth health(String... status) {
        ServiceHealth health = mock(ServiceHealth.class);
        HealthCheck serfHealth = mock(HealthCheck.class);
        when(serfHealth.getCheckId()).thenReturn("serfHealth");
        when(serfHealth.getStatus()).thenReturn(State.PASS.getName());

        List<HealthCheck> checks = Arrays.stream(status).map(ConsulJaxRsClientTest::check).collect(toList());
        checks.add(serfHealth);
        when(health.getChecks()).thenReturn(checks);
        return health;
    }

    private static HealthCheck check(String status) {
        HealthCheck check = mock(HealthCheck.class);

        when(check.getStatus()).thenReturn(status);
        when(check.getCheckId()).thenReturn(UUID.randomUUID().toString());

        return check;
    }
}