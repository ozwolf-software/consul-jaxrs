package net.ozwolf.consul.retry;

import com.orbitz.consul.cache.ServiceHealthKey;
import net.ozwolf.consul.client.ConsulJaxRsClient;
import net.ozwolf.consul.exception.RequestFailureException;
import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.stubbing.Answer;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ServerErrorException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

public class RequestRetryPolicyTest {
    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @Test
    public void shouldExecuteAction() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);

        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestRetryPolicy policy = new RequestRetryPolicy(1, onNext);

        String result = policy.execute(c -> c.getKey().getServiceId());

        assertThat(result).isEqualTo("test");
    }

    @Test
    public void shouldBreakOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        AtomicInteger calls = new AtomicInteger(0);

        RequestAction<String> action = c -> {
            Integer current = calls.getAndAdd(1);
            if (current == 0)
                throw new ServerErrorException(503);

            throw new ClientErrorException(400);
        };

        exception.expect(failureOf(ClientErrorException.class, "[ Service ID: test ] - Request failed (Request aborted due to exception)"));
        new RequestRetryPolicy(3, onNext)
                .breakOn(ClientErrorException.class)
                .execute(action);
    }

    @Test
    public void shouldFailOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestAction<String> action = c -> {
            throw new ServerErrorException(503);
        };

        exception.expect(failureOf(ServerErrorException.class, "[ Service ID: test ] - Request failed (Request failed due to exception)"));
        new RequestRetryPolicy(3, onNext)
                .execute(action);
    }

    @Test
    public void shouldRevokeClientOnException() {
        ConsulJaxRsClient client1 = mock(ConsulJaxRsClient.class);
        ConsulJaxRsClient client2 = mock(ConsulJaxRsClient.class);

        when(client1.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));
        when(client2.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 5678));

        doAnswer((Answer<Void>) i -> {
            when(client1.isRevoked()).thenReturn(true);
            return null;
        }).when(client1).revoke(anyLong());

        Supplier<ConsulJaxRsClient> onNext = () -> client1.isRevoked() ? client2 : client1;

        RequestAction<String> action = c -> {
            if (c == client1)
                throw new ServerErrorException(503);

            return "success!";
        };

        String result = new RequestRetryPolicy(3, onNext)
                .revokeOn(ServerErrorException.class, 5, TimeUnit.MINUTES)
                .execute(action);

        assertThat(result).isEqualTo("success!");

        verify(client1, times(1)).revoke(TimeUnit.MINUTES.toMillis(5));
    }

    private static TypeSafeMatcher<RequestFailureException> failureOf(Class<? extends Exception> cause, String message) {
        return new TypeSafeMatcher<RequestFailureException>() {
            @Override
            protected boolean matchesSafely(RequestFailureException e) {
                return cause.isInstance(e.getCause()) &&
                        e.getMessage().equals(message);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText(String.format("[cause = <%s>, message = <%s>]", cause.getSimpleName(), message));
            }

            @Override
            protected void describeMismatchSafely(RequestFailureException item, Description mismatchDescription) {
                mismatchDescription.appendText(String.format("[cause = <%s>, message = <%s>]", item.getCause().getClass().getSimpleName(), item.getMessage()));
            }
        };
    }
}