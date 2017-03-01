package net.ozwolf.consul;

/**
 * <h1>HTTP Scheme</h1>
 *
 * The HTTP scheme to use for outgoing requests.
 */
public enum HttpMode {
    HTTP("http"),
    HTTPS("https");

    private final String scheme;

    HttpMode(String scheme) {
        this.scheme = scheme;
    }

    public String getScheme() {
        return scheme;
    }
}
