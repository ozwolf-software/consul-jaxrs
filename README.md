# Consul JAX RS

[![Status](https://img.shields.io/badge/status-eol.svg?style=flat-square)](https://img.shields.io/badge/status-eol.svg)
[![Travis](https://img.shields.io/travis/ozwolf-software/consul-jaxrs.svg?style=flat-square)](https://travis-ci.org/ozwolf-software/consul-jaxrs)
[![Maven Central](https://img.shields.io/maven-central/v/net.ozwolf/consul-jaxrs.svg?style=flat-square)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22net.ozwolf%22%20AND%20a%3A%22consul-jaxrs%22)
[![Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)

Consul JAX RS is a Java library that provides a Consul-backed service discovery framework for JAX RS-based clients.  Using a provided JAX RS client implementation and a Consul client, this service will maintain a pool of available Jersey clients, with the ability to revoke a client for a period of time, removing it from the selection process.
  
The design goal behind this library is to allow services that use a JAX RS-based client structure to utilise client-side service discovery and fault tolerance handling in combination with the Consul system.

The library also provides a basic retry framework, allowing requests to be retried and revoking client instances on errors appropriately.  However, if you want to use a more robust solution, then a library like [Failsafe](https://github.com/jhalterman/failsafe) might be more suitable.

## Changes From Version 1.x

+ Removed the `RequestFailureException` error wrapper used by the retry functionality.  The original error will now be returned.
+ Converted the retry functionality to use RxJava, allowing the creating of `Observables` around fault-tolerant retry loops.
+ Added backoff retry interval functionality.

## Dependency Management

### Maven

```xml
<dependency>
    <groupId>net.ozwolf</groupId>
    <artifactId>consul-jaxrs</artifactId>
    <version>2.0.0</version>
</dependency>
```

### Gradle

```gradle
compile 'net.ozwolf:consul-jaxrs:2.0.0'
```

### Provided Dependencies

The following dependencies need to be provided by the consumer of the library:

+ **JAX RS Client** - Any implementation of the JAX RS framework, such as the [Jersey2](https://jersey.java.net/) library.
+ **SLF4J Implementation** - Any implementation of the SLF4J logging framework, such as the [QOS Logback](https://logback.qos.ch/) library.

## Consul Client

This library utilises the [Consul Client](https://github.com/OrbitzWorldwide/consul-client) library provided by [Orbitz Worldwilde](https://github.com/OrbitzWorldwide).

When creating a new pool, an instantiated instance of the `Consul` class needs to be provided, appropriately configured to work against your own Consul ecosystem.

## Usage

### Creating A New Client Pool

The base class of this library is the `ConsulJaxRsClientPool` object.  This object needs to be provided three things:

+ `serviceId` - the consul-registered service ID the pool instance will be related to.  You can create a `ConsulJaxRsClientPool` per service you wish to use instances of.
+ `client` - Any JAX RS client implementation.
+ `consul` - An instantiated instance of the `Consul` object configured to work with your Consul ecosystem.

```java
Client client = new JerseyClientBuilder().withConfig(new ClientConfig(JacksonJaxbJsonProvider.class)).build();
Consul consul = Consul.builder().withHostAndPort(HostAndPort.fromParts("consul.local", 8500)).build();

ConsulJaxRsClientPool pool = new ConsulJaxRsClientPool("my-service", client, consul);
```

#### Service Health Cache

The client pool uses a `ServiceHealthCache` to monitor the state of the service instances behind the scenes.  By default, the pool will create it's own default service health cache that will retrieve all instances (including critical instances) and a default service health key.  For example:

```java
ServiceHealthCache cache = ServiceHealthCache.newCache(consul.healthClient(), serviceId, false, CatalogOptions.BLANK, pollRate)
```

The pool also provides an option to provide an implementation of the `ServiceHealthCacheProvider` interface to create your own cache, allowing for more custom cache creation when needed (eg. if wanting to customise the `ServiceHealthKey` implementation).

### Using A Client Directly

To get a client from the pool, simply call the `.next()` method.  If no state is provided, the pool will automatically try and randomly select an instance that isn't revoked and either has a `PASS` or `WARN` state (see below for further information).

The returned client is an implementation of 

```java
ConsulJaxRsClient client = pool.next();

try {
    return client.target("/path/to/something")
        .request()
        .get(String.class);
} catch (ServerException e) {
    client.revoke(5, TimeUnit.MINUTES);
}
```

The above example will randomly provide the next instance client for use.  If the request receives a server exception (ie. `5xx` response code), we will revoke that instance for 5 minutes, effectively removing it as an available instance in the pool.

## RxJava Functionality

This library makes no attempt to implement RxJava functionality around singular uses of `Client` instances as you can adhere to standard RxJava functionality.  For example:

```java
return Observable.fromCallable(() -> {
    ConsulJaxRsClient client = pool.next();
    
    try {
        return client.target("/path/to/something")
            .request()
            .get(String.class);
    } catch (ServerException | IOException e) {
        client.revoke(5, TimeUnit.MINUTES);
        throw e;
    }
});
```

The above code will run a single client call with revoke functionality within an Observable block.

If you wish to use the JAX-RS asynchronous features directly with RxJava, you can use the `Observable.from(Future)` method to create your observable.

### Retrying Requests

The library provides an option to undertake retries against instances of services.  The idea behind this is to allow fault tolerance when instances may not be available and re-attempt requests.  This follows the simple premise of fluently describing on what exceptions to revoke a client instance (eg. server errors or network errors) and on what exceptions to break the retry loop on (eg. client errors).

The retry handler accepts a `RetryAction` implementation that can be executed immediately or returned as an `Observable`.

The retry handler uses a backing-off retry policy.  The defaults for this are an initial delay of 100ms with a 2.0 backoff factor.

This can modified with the `.`

#### Execute Example

Immediate execution effectively runs `Observable.toBlocking().single()` straight away, forcing the observable to complete on the current thread.

```java
return pool.retry(3)
    .revokeOn(ServerException.class, 5, TimeUnit.MINUTES)
    .revokeOn(IOException.class, 5, TimeUnit.MINUTES)
    .breakOn(ClientException.class)
    .execute(c -> 
        c.target("/path/to/something")
            .request()
            .get(String.class)
    );
```

In the above example, we have directed the pool to retry our request 3 times.  If we receive a server exception (ie. `5xx` response code) or something indicating a network exception, we will revoke that instance, effectively providing a new client instance on the next attempt.
 
If we receive a client exception (ie. `4xx` response code), we want the request to not retry and break from the loop (as it kind of means the error is on our end anyway).

#### Observable Example

```java
Observable<String> observable = pool.retry(3)
    .revokeOn(ServerException.class, 5, TimeUnit.MINUTES)
    .revokeOn(IOException.class, 5, TimeUnit.MINUTES)
    .breakOn(ClientException.class)
    .observe(c -> 
        c.target("/path/to/something")
            .request()
            .get(String.class)
    );

MySubscriber subscriber = new MySubscriber();
Subscription subscription = observable.subscribe(subscriber);

...
```

The setup for the observable is the same as above with regards to what will cause a revoke on a client and a break in the retry.  This response though will return a standard RxJava `Observable` instance of your return type.

## Instance Client Details

Below are particular details around instance clients and their states, behaviour and how they are selected.

### Base Client

The service instance clients all use the provided JAX RS client instance assigned to the pool as their delegate client implementation.  

This means any ConsulJaxRsClient will use the configuration of the provided base class, including timeout, keep alive and retry policies.  It will also mean any registered request or response filters will still apply.
 
The primary purpose of the service instance client is to provide the scheme, host and port of the request mapped to the instance provided from Consul.

### State

Instance clients maintain a state of either `PASS`, `WARN` or `FAIL` as defined by the `State` class from the consul client library.  This state is derived using the following rules:

+ `PASS` - all health checks associated to the service instance are passing
+ `FAIL` - all health checks associated to the service instance are failing
+ `WARN` - there are no health checks available, or the health checks are a mixture of `PASS`, `WARN` and `FAIL`.

### How Is An Instance Client Selected

Instance clients are selected using a combination of a weight random lottery and whether or not they are revoked.

#### Fallback Instance

The client pool can be provided with a fixed URI for it to fallback to if no service instances have been registered with Consul.  This URI could be a direct reference to a singular instance or might point at a more rudimentary load-balanced 

#### Weighting

An instance client's weighting in the random lottery is based on their last reported state.  These weightings can be adjusted from the default using the `withStateWeightingOf(...)` method.  

Below are the default weightings:

+ `PASS` - `1.0`
+ `WARN` - `0.5`
+ `FAIL` - `0.1`

This means that while other states _can_ be randomly chosen, your service is more likely to get a instance higher up the health chain.

#### State

When using the `.next()` or `.retry()` methods on the pool, by default it will try and select instances that either have a `PASS` or `WARN` state.  This can be overridden by providing a state parameter to these methods.

#### Revocation

It is possible for a process to reject a service instance when it fails expected tolerance rules (ie. if client calls wrapped in Hystrix aren't responding in time and causing timeouts).  Revoked instance clients will be excluded from the selection process with one caveat.

If there are no available services with the desired minimum state that haven't been revoked, the revoked service instances will be re-introduced _for that selection only_.  This means that revoked statuses remain active and if an instance that is not revoked becomes available again, revoked instances will once again be ignored.

## Credits

+ [Consul Client](https://github.com/OrbitzWorldwide/consul-client)
+ [RxJava Extras](https://github.com/davidmoten/rxjava-extras)
