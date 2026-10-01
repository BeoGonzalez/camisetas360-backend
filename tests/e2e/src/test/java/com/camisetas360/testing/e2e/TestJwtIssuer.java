package com.camisetas360.testing.e2e;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

final class TestJwtIssuer implements AutoCloseable {
    static final String AUDIENCE = "camisetas360-e2e";
    private final RSAKey key;
    private final HttpServer server;

    TestJwtIssuer() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("e2e-key").generate();
        var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    String jwks() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    String token(String email, String scopes) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer()).audience(AUDIENCE)
                .subject(email).claim("preferred_username", email).claim("scp", scopes)
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .notBeforeTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse("2100-01-01T00:00:00Z"))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

