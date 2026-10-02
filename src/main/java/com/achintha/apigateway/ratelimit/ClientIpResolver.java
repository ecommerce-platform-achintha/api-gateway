package com.achintha.apigateway.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * The client's IP address for rate limiting. {@code X-Forwarded-For} is honoured only when the request comes from a
 * configured trusted proxy; the client is then the right-most address in the chain that isn't a trusted proxy itself
 * (entries further left could be anything the client typed).
 */
public class ClientIpResolver {

    static final String X_FORWARDED_FOR = "X-Forwarded-For";
    static final String UNKNOWN = "unknown";

    private final List<Cidr> trustedProxies;

    public ClientIpResolver(List<String> trustedProxies) {
        this.trustedProxies = trustedProxies.stream().map(Cidr::parse).toList();
    }

    public String resolve(ServerHttpRequest request) {
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return UNKNOWN;
        }
        InetAddress client = remote.getAddress();
        if (!isTrusted(client)) {
            return client.getHostAddress();
        }

        List<String> chain = new ArrayList<>();
        for (String header : request.getHeaders().getOrEmpty(X_FORWARDED_FOR)) {
            for (String entry : header.split(",")) {
                chain.add(entry.trim());
            }
        }
        for (int i = chain.size() - 1; i >= 0; i--) {
            InetAddress hop = literal(chain.get(i));
            if (hop == null) {
                break; // Garbage in the chain: stop at the last address we could verify
            }
            client = hop;
            if (!isTrusted(hop)) {
                break;
            }
        }
        return client.getHostAddress();
    }

    private boolean isTrusted(InetAddress address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    /** Only IP literals: never a host name, which would mean a DNS lookup per request. */
    private static InetAddress literal(String value) {
        try {
            return value.isEmpty() ? null : InetAddress.ofLiteral(value);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An IP address ({@code 10.0.0.5}) or range ({@code 10.0.0.0/8}, {@code fd00::/8}). */
    record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String value) {
            int slash = value.indexOf('/');
            InetAddress address = literal(slash < 0 ? value : value.substring(0, slash));
            if (address == null) {
                throw new IllegalArgumentException("Not an IP address or CIDR range: '" + value + "'");
            }
            byte[] bytes = address.getAddress();
            int prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(value.substring(slash + 1));
            if (prefix < 0 || prefix > bytes.length * 8) {
                throw new IllegalArgumentException("Invalid prefix length in '" + value + "'");
            }
            return new Cidr(bytes, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (bytes[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainingBits);
            return (bytes[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
