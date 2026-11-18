package com.camisetas360.analytics.security;

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
                .subject("22222222-2222-2222-2222-222222222222")
                .claim("oid", "missing-object".equals(variant) ? null : "22222222-2222-2222-2222-222222222222")
                .claim("tid", "missing-tenant".equals(variant) ? null : "11111111-1111-1111-1111-111111111111")
                .claim("name", "Buyer")
                .claim("preferred_username", "buyer@example.test")
                .claim("scp", scope)
                .claim("roles", java.util.List.of(variant.startsWith("role-") ? variant.substring(5) : "CUSTOMER"))
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .notBeforeTime(Date.from(Instant.parse("future-nbf".equals(variant) ? "2100-01-01T00:00:00Z" : "2020-01-01T00:00:00Z")))
                .expirationTime("missing-exp".equals(variant) ? null : Date.from(Instant.parse(
                        "expired".equals(variant) ? "2021-01-01T00:00:00Z" : "2100-01-01T00:00:00Z")))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedKey.getKeyID()).build(), claims);
        // Same kid for the wrong key: test signature verification, not key lookup failure.
        jwt.sign(new RSASSASigner("signature".equals(variant) ? wrongKey : trustedKey));
        return jwt.serialize();
    }

    static final String COGNITO_CLIENT = "cognito-test-client";
    static final String COGNITO_SUB = "33333333-3333-3333-3333-333333333333";

    /** Issuer de un user pool de Cognito simulado: mismo JWKS local, otro 'iss'. */
    String cognitoIssuer() {
        return issuer() + "/us-east-1_TestPool";
    }

    /**
     * Access token con la forma de Cognito (sin aud ni tid/oid, con client_id, token_use, username y
     * cognito:groups). Variantes: valid, id-token, other-client, no-username, sub-not-uuid, wrong-issuer,
     * signature, expired.
     */
    String cognitoToken(java.util.List<String> groups, String variant) throws JOSEException {
        var claims = new JWTClaimsSet.Builder()
                .issuer("wrong-issuer".equals(variant) ? issuer() + "/us-east-1_OtherPool" : cognitoIssuer())
                .subject("sub-not-uuid".equals(variant) ? "usuario-1" : COGNITO_SUB)
                .claim("token_use", "id-token".equals(variant) ? "id" : "access")
                .claim("client_id", "other-client".equals(variant) ? "another-client" : COGNITO_CLIENT)
                .claim("username", "no-username".equals(variant) ? null : "usuario@example.test")
                .claim("scope", "aws.cognito.signin.user.admin")
                .claim("roles", java.util.List.of("ADMIN"))
                .claim("cognito:groups", groups)
                .issueTime(Date.from(Instant.parse("2020-01-01T00:00:00Z")))
                .expirationTime(Date.from(Instant.parse(
                        "expired".equals(variant) ? "2021-01-01T00:00:00Z" : "2100-01-01T00:00:00Z")))
                .build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(trustedKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner("signature".equals(variant) ? wrongKey : trustedKey));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

