import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Emisor JWT solo para pruebas de estres locales. Uso: java GenTokens.java <dirSalida> <usuarios> */
public class GenTokens {
    static final String ISSUER = "https://stress.camisetas360.local/v2.0";
    static final String AUDIENCE = "camisetas360-stress";
    static final String TID = "11111111-1111-1111-1111-111111111111";

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        int usuarios = Integer.parseInt(args[1]);
        Files.createDirectories(out);
        RSAKey key = new RSAKeyGenerator(2048).keyID("stress-key").generate();
        Files.writeString(out.resolve("jwks.json"), new JWKSet(key.toPublicJWK()).toString());

        var csv = new StringBuilder();
        Instant now = Instant.now();
        for (int i = 1; i <= usuarios; i++) {
            String oid = UUID.nameUUIDFromBytes(("stress-user-" + i).getBytes(StandardCharsets.UTF_8)).toString();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).audience(AUDIENCE).subject("stress-user-" + i)
                    .claim("tid", TID).claim("oid", oid)
                    .claim("preferred_username", "stress" + i + "@camisetas360.test")
                    .claim("email", "stress" + i + "@camisetas360.test")
                    .claim("name", "Stress User " + i)
                    .claim("roles", List.of("CUSTOMER"))
                    .claim("scp", "Catalog.Read Checkout.Create Orders.Read Profile.Read")
                    .issueTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                    .notBeforeTime(Date.from(now.minus(1, ChronoUnit.MINUTES)))
                    .expirationTime(Date.from(now.plus(12, ChronoUnit.HOURS)))
                    .build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            csv.append(i).append(',').append(jwt.serialize()).append('\n');
        }
        Files.writeString(out.resolve("tokens.csv"), csv.toString());
        System.out.println("OK " + usuarios + " tokens -> " + out);
    }
}
