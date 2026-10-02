package com.achintha.apigateway;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

/** Stands in for user-service's {@code GET /.well-known/jwks.json}: one server per JVM, on a free port. */
public final class JwksTestServer {

    private static final HttpServer SERVER = start();

    private JwksTestServer() {
    }

    public static String jwksUri() {
        return "http://localhost:" + SERVER.getAddress().getPort() + "/.well-known/jwks.json";
    }

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = TestTokens.jwksJson().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
