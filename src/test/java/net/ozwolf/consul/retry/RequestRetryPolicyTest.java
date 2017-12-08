package net.ozwolf.consul.retry;

import com.orbitz.consul.cache.ServiceHealthKey;
import net.ozwolf.consul.client.ConsulJaxRsClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import rx.Observable;
import rx.Subscriber;
import rx.Subscription;
import rx.internal.util.BlockingUtils;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ServerErrorException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
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
    public void shouldObserveAction() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);

        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestRetryPolicy policy = new RequestRetryPolicy(1, onNext);

        Observable<String> result = policy.observe(c -> c.getKey().getServiceId());

        TestSubscriber<String> subscriber = new TestSubscriber<>();

        Subscription subscription = result.subscribe(subscriber);

        BlockingUtils.awaitForComplete(subscriber.latch, subscription);

        assertThat(subscriber.value).isEqualTo("test");
    }

    @Test
    public void shouldBreakOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestAction<String> action = breakOnAction();

        exception.expect(ClientErrorException.class);
        exception.expectMessage("HTTP 400 Bad Request");
        new RequestRetryPolicy(3, onNext)
                .breakOn(ClientErrorException.class)
                .execute(action);
    }

    @Test
    public void shouldBreakObserveActionOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestAction<String> action = breakOnAction();

        RequestRetryPolicy policy = new RequestRetryPolicy(1, onNext);

        Observable<String> result = policy
                .breakOn(ClientErrorException.class)
                .observe(action);

        TestSubscriber<String> subscriber = new TestSubscriber<>();

        Subscription subscription = result.subscribe(subscriber);

        BlockingUtils.awaitForComplete(subscriber.latch, subscription);

        assertThat(subscriber.exception).isInstanceOf(ClientErrorException.class);
        assertThat(((ClientErrorException) subscriber.exception).getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    public void shouldFailOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestAction<String> action = throwErrorAction();

        exception.expect(ServerErrorException.class);
        exception.expectMessage("HTTP 503 Service Unavailable");
        new RequestRetryPolicy(3, onNext)
                .execute(action);
    }

    @Test
    public void shouldFailObservableOnException() {
        ConsulJaxRsClient client = mock(ConsulJaxRsClient.class);
        when(client.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));

        Supplier<ConsulJaxRsClient> onNext = () -> client;

        RequestAction<String> action = throwErrorAction();

        Observable<String> result = new RequestRetryPolicy(3, onNext).observe(action);

        TestSubscriber<String> subscriber = new TestSubscriber<>();

        Subscription subscription = result.subscribe(subscriber);

        BlockingUtils.awaitForComplete(subscriber.latch, subscription);

        assertThat(subscriber.exception).isInstanceOf(ServerErrorException.class);
        assertThat(subscriber.exception.getMessage()).isEqualTo("HTTP 503 Service Unavailable");
    }

    @Test
    public void shouldRevokeClientOnException() {
        ConsulJaxRsClient client1 = mock(ConsulJaxRsClient.class);
        ConsulJaxRsClient client2 = mock(ConsulJaxRsClient.class);

        when(client1.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));
        when(client2.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 5678));

        doAnswer(i -> {
            when(client1.isRevoked()).thenReturn(true);
            return null;
        }).when(client1).revoke(anyLong());

        Supplier<ConsulJaxRsClient> onNext = () -> client1.isRevoked() ? client2 : client1;

        RequestAction<String> action = revokedAction(client1);

        String result = new RequestRetryPolicy(3, onNext)
                .revokeOn(ServerErrorException.class, 5, TimeUnit.MINUTES)
                .execute(action);

        assertThat(result).isEqualTo("success!");

        verify(client1, times(1)).revoke(TimeUnit.MINUTES.toMillis(5));
    }


    @Test
    public void shouldRevokeClientOnObservableException() {
        ConsulJaxRsClient client1 = mock(ConsulJaxRsClient.class);
        ConsulJaxRsClient client2 = mock(ConsulJaxRsClient.class);

        when(client1.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 1234));
        when(client2.getKey()).thenReturn(ServiceHealthKey.of("test", "localhost", 5678));

        doAnswer(i -> {
            when(client1.isRevoked()).thenReturn(true);
            return null;
        }).when(client1).revoke(anyLong());

        Supplier<ConsulJaxRsClient> onNext = () -> client1.isRevoked() ? client2 : client1;

        RequestAction<String> action = revokedAction(client1);

        Observable<String> result = new RequestRetryPolicy(3, onNext)
                .retryIntervalOf(500, TimeUnit.MILLISECONDS)
                .revokeOn(ServerErrorException.class, 5, TimeUnit.MINUTES)
                .observe(action);

        Instant start = Instant.now();
        TestSubscriber<String> subscriber = new TestSubscriber<>();

        Subscription subscription = result.subscribe(subscriber);

        BlockingUtils.awaitForComplete(subscriber.latch, subscription);

        Duration duration = Duration.between(start, subscriber.finish);

        assertThat(subscriber.value).isEqualTo("success!");
        assertThat(TimeUnit.NANOSECONDS.toMillis(duration.getNano())).isGreaterThan(500);

        verify(client1, times(1)).revoke(TimeUnit.MINUTES.toMillis(5));
    }

    private static RequestAction<String> breakOnAction() {
        AtomicInteger calls = new AtomicInteger(0);

        return c -> {
            Integer current = calls.getAndAdd(1);
            if (current == 0)
                throw new ServerErrorException(503);

            throw new ClientErrorException(400);
        };
    }

    private static RequestAction<String> throwErrorAction() {
        return c -> {
            throw new ServerErrorException(503);
        };
    }

    private static RequestAction<String> revokedAction(ConsulJaxRsClient errorClient) {
        return c -> {
            if (c == errorClient)
                throw new ServerErrorException(503);

            return "success!";
        };
    }

    private static class TestSubscriber<T> extends Subscriber<T> {
        private T value;
        private Throwable exception;
        private Instant finish;
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onCompleted() {
            latch.countDown();
            this.finish = Instant.now();
        }

        @Override
        public void onError(Throwable throwable) {
            this.exception = throwable;
            latch.countDown();
            this.finish = Instant.now();
        }

        @Override
        public void onNext(T t) {
            this.value = t;
        }
    }
}