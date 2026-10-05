import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** Real ephemeral RS256 issuer, reachable by the Compose containers. */
public final class ComposeJwtIssuer {
    public static void main(String[] args) throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("compose-qa").generate();
        byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
            }
        });
        server.start();
        try {
            String base = "http://host.docker.internal:" + server.getAddress().getPort();
            String issuer = base + "/issuer";
            String email = "compose-buyer@example.test";
            var metadata = Map.of("issuer", issuer, "jwks", base + "/jwks",
                    "audience", "camisetas360-compose-qa", "email", email,
                    "ownerToken", token(key, issuer, email, true),
                    "foreignToken", token(key, issuer, "compose-other@example.test", true),
                    "missingSubjectToken", token(key, issuer, email, false));
            Files.writeString(Path.of(args[0]), JsonMapper.builder().build().writeValueAsString(metadata));
            System.in.read(); // Parent closes stdin when the isolated test finishes.
        } finally {
            server.stop(0);
        }
    }

    private static String token(RSAKey key, String issuer, String email, boolean includeSubject) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience("camisetas360-compose-qa")
                .subject(includeSubject ? email : null).claim("preferred_username", email).claim("name", "Compose QA Buyer")
                .claim("oid", "compose-user").claim("tid", "compose-tenant")
                .claim("scp", "Checkout.Create Orders.Read Profile.Read Catalog.Read")
                .claim("roles", List.of("CUSTOMER"))
                .issueTime(Date.from(now)).notBeforeTime(Date.from(now.minusSeconds(30)))
                .expirationTime(Date.from(now.plusSeconds(3600))).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
