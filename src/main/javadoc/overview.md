# Consul JAX RS

Consul JAX RS is a Java library that provides a Consul-backed service discovery framework for JAX RS-based clients.  Using a provided JAX RS client implementation and a Consul client, this service will maintain a pool of available Jersey clients, with the ability to revoke a client for a period of time, removing it from the selection process.
  
The design goal behind this library is to allow services that use a JAX RS-based client structure to utilise client-side service discovery and fault tolerance handling in combination with the Consul system.

The library also provides a basic retry framework, allowing requests to be retried and revoking client instances on errors appropriately.  However, if you want to use a more robust solution, then a library like [Failsafe](https://github.com/jhalterman/failsafe) might be more suitable.

## Dependency Management

### Maven

```xml
<dependency>
    <groupId>net.ozwolf</groupId>
    <artifactId>consul-jaxrs</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle

```gradle
compile 'net.ozwolf:consul-jaxrs:1.1.0'
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

### Using The Retry Function

It is possible to prepare a retry handler from the pool.  This fluent builder will take in instructions, such as how many attempts to make, when to revoke a client instance and for how long, when the break from the retry loop.

It accepts a `RequestAction` implementation, that accepts a `ConsulJaxRsClient` instance and can throw an exception.

```java
return pool.retry(3)
    .revokeOn(ServerException.class, 5, TimeUnit.MINUTES)
    .breakOn(ClientException.class)
    .execute(c -> 
        c.target("/path/to/something")
            .request()
            .get(String.class)
    );
```

In the above example, we have directed the pool to retry our request 3 times.  If we receive a server exception (ie. `5xx` response code), we will revoke that instance, effectively providing a new client instance on the next attempt.
 
If we receive a client exception (ie. `4xx` response code), we want the request to not retry and break from the loop (as it kind of means the error is on our end anyway).

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