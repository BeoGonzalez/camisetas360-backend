package com.camisetas360.auth.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Emisor de pruebas con forma de Amazon Cognito: segundo issuer ({@code <base>/<userPoolId>}), segundo JWKS
 * en {@code <issuer>/.well-known/jwks.json} y un endpoint {@code /oauth2/userInfo} con fallos controlados.
 * Usa el mismo {@code kid} que {@link LocalJwtIssuer} para que la firma cruzada falle por firma, no por kid.
 */
public final class LocalCognitoIssuer implements AutoCloseable {

    public static final String POOL_ID = "us-east-1_TestPool";
    public static final String CLIENT_ID = "test-client";
    public static final String RESOURCE_SERVER = "camisetas360-api";
    public static final String SUB = "33333333-3333-3333-3333-333333333333";
    public static final String USERNAME = "cliente-externo";

    public enum UserInfoMode { OK, ERROR, SLOW, REDIRECT, NOT_JSON, NON_STRING }

    private final HttpServer server;
    private final RSAKey trustedKey;
    private final RSAKey wrongKey;
    private final AtomicReference<UserInfoMode> userInfoMode = new AtomicReference<>(UserInfoMode.OK);
    private final AtomicInteger userInfoRequests = new AtomicInteger();
    private final AtomicReference<String> lastUserInfoAuthorization = new AtomicReference<>();

    public LocalCognitoIssuer() {
        try {
            trustedKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
            byte[] jwks = new JWKSet(trustedKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/" + POOL_ID + "/.well-known/jwks.json", exchange -> {
                try (exchange) {
                    respond(exchange, 200, "application/json", jwks);
                }
            });
            server.createContext("/oauth2/userInfo", this::userInfo);
            server.createContext("/elsewhere", exchange -> {
                try (exchange) {
                    respond(exchange, 200, "application/json",
                            "{\"email\":\"leaked@example.test\",\"name\":\"Leaked\"}".getBytes(StandardCharsets.UTF_8));
                }
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
        } catch (IOException | JOSEException exception) {
            throw new IllegalStateException("Cannot start test Cognito issuer", exception);
        }
    }

    private void userInfo(HttpExchange exchange) throws IOException {
        try (exchange) {
            userInfoRequests.incrementAndGet();
            lastUserInfoAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            switch (userInfoMode.get()) {
                case OK -> respond(exchange, 200, "application/json",
                        "{\"sub\":\"%s\",\"email\":\"fallback@example.test\",\"name\":\"Fallback\"}".formatted(SUB)
                                .getBytes(StandardCharsets.UTF_8));
                case NON_STRING -> respond(exchange, 200, "application/json",
                        "{\"email\":42,\"name\":[\"x\"]}".getBytes(StandardCharsets.UTF_8));
                case NOT_JSON -> respond(exchange, 200, "text/html", "<html/>".getBytes(StandardCharsets.UTF_8));
                case ERROR -> respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                case REDIRECT -> {
                    exchange.getResponseHeaders().set("Location", base() + "/elsewhere");
                    exchange.sendResponseHeaders(302, -1);
                }
                case SLOW -> {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    respond(exchange, 200, "application/json", "{\"email\":\"late@example.test\"}".getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ignored) {
            // El cliente ya corto la conexion por timeout.
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public String issuer() {
        return base() + "/" + POOL_ID;
    }

    public String userInfoUri() {
        return base() + "/oauth2/userInfo";
    }

    public void userInfoMode(UserInfoMode mode) {
        userInfoMode.set(mode);
    }

    public int userInfoRequests() {
        return userInfoRequests.get();
    }

    public String lastUserInfoAuthorization() {
        return lastUserInfoAuthorization.get();
    }

    /** Claims de un access token de Cognito (Hosted UI, code + PKCE) con grupo CUSTOMER y email/name de la Lambda. */
    public static Map<String, Object> accessTokenClaims(String issuer) {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", issuer);
        claims.put("sub", SUB);
        claims.put("username", USERNAME);
        claims.put("client_id", CLIENT_ID);
        claims.put("token_use", "access");
        claims.put("scope", "openid email " + RESOURCE_SERVER + "/Profile.Read");
        claims.put("cognito:groups", List.of("CUSTOMER"));
        claims.put("email", "cliente@example.test");
        claims.put("name", "Cliente Externo");
        claims.put("auth_time", Instant.parse("2020-01-01T00:00:00Z").getEpochSecond());
        claims.put("iat", Date.from(Instant.parse("2020-01-01T00:00:00Z")));
        claims.put("exp", Date.from(Instant.parse("2100-01-01T00:00:00Z")));
        claims.put("jti", "jti-1");
        return claims;
    }

    /** Token valido con los claims modificados; un valor {@code null} elimina el claim. */
    public String token(Consumer<Map<String, Object>> customizer) throws JOSEException {
        return sign(customizer, trustedKey);
    }

    /** Mismos claims, firmado con una clave que no esta en el JWKS (mismo kid). */
    public String tokenSignedWithWrongKey() throws JOSEException {
        return sign(claims -> { }, wrongKey);
    }

    /** Firma claims arbitrarios con la clave de Cognito (firma cruzada). */
    public String sign(JWTClaimsSet claims) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(trustedKey.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(trustedKey));
        return jwt.serialize();
    }

    private String sign(Consumer<Map<String, Object>> customizer, RSAKey key) throws JOSEException {
        var claims = accessTokenClaims(issuer());
        customizer.accept(claims);
        var builder = new JWTClaimsSet.Builder();
        claims.forEach((name, value) -> {
            if (value != null) builder.claim(name, value);
        });
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), builder.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
