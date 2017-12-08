package net.ozwolf.consul.rx;

import rx.Observable;
import rx.functions.Func1;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ObservableHttpRetry implements Func1<Observable<? extends Throwable>, Observable<?>> {
    private final Long retryInterval;
    private final Set<Class<? extends Exception>> breakOn;

    private CountDownLatch retries;

    public ObservableHttpRetry(Integer retries,
                               Long retryInterval,
                               Set<Class<? extends Exception>> breakOn) {
        this.retries = new CountDownLatch(retries);
        this.retryInterval = retryInterval;
        this.breakOn = breakOn;
    }

    @Override
    public Observable<?> call(Observable<? extends Throwable> t) {
        return t.flatMap(th -> {
            if (retries.getCount() > 0 && breakOn.stream().noneMatch(c -> c.isInstance(th))) {
                retries.countDown();
                return Observable.timer(retryInterval, TimeUnit.MILLISECONDS);
            }

            return Observable.<Object>error(th);
        });
    }
}
