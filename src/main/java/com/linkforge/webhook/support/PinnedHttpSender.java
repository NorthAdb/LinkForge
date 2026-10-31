package com.linkforge.webhook.support;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;

/**
 * D-C4-1: the relay's outbound hop with DNS rebinding pinned shut. The JDK HttpClient
 * this replaces resolved DNS itself at connect time, so a second resolution after the
 * guard's check could land on an attacker-flipped internal address; it also cannot keep
 * the original Host header (restricted) or validate the server certificate against the
 * original hostname while connecting to a chosen IP. Apache HttpClient 5 has a pluggable
 * {@link DnsResolver}: the request keeps the ORIGINAL uri, so Host, TLS SNI and
 * certificate validation stay on the hostname, while the socket goes only to the
 * addresses {@link WebhookTargetGuard#resolveForSend(String)} resolved and validated.
 */
@Component
public class PinnedHttpSender {

    /** One completed send: a response status, or a transport failure for the relay's retry policy. */
    public sealed interface Result {
    }

    public record Responded(int statusCode) implements Result {
    }

    public record Failed(IOException cause) implements Result {
    }

    /**
     * POSTs body to url connecting ONLY to pinnedAddresses — pass the addresses the guard
     * returned for this exact url. A fresh client per delivery is deliberate: the pinned
     * set lives in the connection manager, and the low-volume relay (seconds-scale
     * backoff) never earns shared per-route pinned-IP state across threads.
     */
    public Result send(String url, InetAddress[] pinnedAddresses, byte[] body,
                       Map<String, String> headers, Duration connectTimeout, Duration requestTimeout) {
        URI uri = URI.create(url);
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(requestTimeout.toMillis()))
                .setExpectContinueEnabled(false) // keep the wire identical to the JDK client this replaces
                // D-C4-6: redirects stay never-followed; following a Location would need the
                // whole resolve+validate+pin cycle re-run for the redirect target first
                .setRedirectsEnabled(false)
                .build();
        try (CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PinnedDnsResolver(uri.getHost(), pinnedAddresses))
                        .build())
                .setDefaultRequestConfig(config)
                .build()) {
            BasicClassicHttpRequest request = new BasicClassicHttpRequest("POST", uri);
            // Content-Type travels as an explicit header so the relay's header map stays authoritative
            request.setEntity(new ByteArrayEntity(body, null));
            headers.forEach(request::addHeader);
            try (CloseableHttpResponse response = client.execute(request)) {
                // webhook receivers answer with a status and an (irrelevant) body; discard it
                return new Responded(response.getCode());
            }
        } catch (IOException ex) {
            return new Failed(ex);
        }
    }

    /**
     * Answers every lookup for the pinned host with the pre-validated addresses and
     * refuses every other host, so nothing this client does can trigger a fresh DNS
     * resolution — that is the pin.
     */
    private record PinnedDnsResolver(String host, InetAddress[] addresses) implements DnsResolver {

        @Override
        public InetAddress[] resolve(String target) throws UnknownHostException {
            if (!target.equalsIgnoreCase(host)) {
                throw new UnknownHostException(target);
            }
            return addresses;
        }

        @Override
        public String resolveCanonicalHostname(String target) throws UnknownHostException {
            resolve(target);
            return target;
        }
    }
}
