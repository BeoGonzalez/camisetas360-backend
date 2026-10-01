package com.camisetas360.notifications.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only issuer keys and loopback JWKS; no external identity provider. */
final class LocalJwtIssuer implements AutoCloseable {

    static final String AUDIENCE = "camisetas360-test";
    private final HttpServer server;
    private final RSAKey trustedKey;
    private final RSAKey wrongKey;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    LocalJwtIssuer() {
        try {
            trustedKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] jwks = new JWKSet(trustedKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    jwksRequests.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwks.length);
                    exchange.getResponseBody().write(jwks);
                }
            });
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException("Cannot start test JWKS server", exception);
        }
    }

    String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    int jwksRequests() {
        return jwksRequests.get();
    }

    String token(String scope, String variant) throws JOSEException {
        if ("malformed".equals(variant)) {
            return "not-a-jwt";
        }
        var claims = new JWTClaimsSet.Builder()
                .issuer("issuer".equals(variant) ? issuer() + "/wrong" : issuer())
                .audience("audience".equals(variant) ? "another-api" : AUDIENCE)
                .subject("user-1")
                .claim("oid", "user-1")
                .claim("tid", "tenant-1")
                .claim("name", "Buyer")
                .claim("preferred_username", "buyer@example.test")
                .claim("scp", scope)
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .notBeforeTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse(
                        "expired".equals(variant) ? "2021-01-01T00:00:00Z" : "2100-01-01T00:00:00Z")))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedKey.getKeyID()).build(), claims);
        // Same kid for the wrong key: test signature verification, not key lookup failure.
        jwt.sign(new RSASSASigner("signature".equals(variant) ? wrongKey : trustedKey));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

