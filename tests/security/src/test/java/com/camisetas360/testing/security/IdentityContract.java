package com.camisetas360.testing.security;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import static org.assertj.core.api.Assertions.*;
public interface IdentityContract {
 String TENANT="11111111-1111-1111-1111-111111111111";
 String OBJECT="22222222-2222-2222-2222-222222222222";
 Function<Jwt, ?> identity();
 default Jwt jwt(Map<String,Object> claims) {
  return Jwt.withTokenValue("test").header("alg","none").subject("pairwise-subject").claims(c->c.putAll(claims)).build();
 }
 @Test default void immutableIdentityDoesNotNeedUsername() {
  assertThat(identity().apply(jwt(Map.of("tid",TENANT,"oid",OBJECT))))
   .extracting("tenantId","objectId").containsExactly(UUID.fromString(TENANT),UUID.fromString(OBJECT));
 }
 @Test default void changedContactDoesNotChangeIdentity() {
  assertThat(identity().apply(jwt(Map.of("tid",TENANT,"oid",OBJECT,"preferred_username","old@example.test"))))
   .isEqualTo(identity().apply(jwt(Map.of("tid",TENANT,"oid",OBJECT,"preferred_username","new@example.test"))));
 }
 @Test default void sameEmailDoesNotMakeDistinctObjectsEqual() {
  assertThat(identity().apply(jwt(Map.of("tid",TENANT,"oid",OBJECT,"preferred_username","same@example.test"))))
   .isNotEqualTo(identity().apply(jwt(Map.of("tid",TENANT,"oid","33333333-3333-3333-3333-333333333333","preferred_username","same@example.test"))));
 }
 @Test default void missingClaimsAreRejected() {
  for(var claims:List.of(Map.<String,Object>of(),Map.<String,Object>of("tid",TENANT),Map.<String,Object>of("oid",OBJECT))) {
   assertThatThrownBy(()->identity().apply(jwt(claims))).isInstanceOf(InvalidBearerTokenException.class);
  }
 }
 @Test default void invalidIdentityClaimsAreRejected() {
  for(Object invalid:List.of("", "tenant-name", 42, List.of(TENANT))) {
   assertThatThrownBy(()->identity().apply(jwt(Map.of("tid",invalid,"oid",OBJECT))))
    .isInstanceOf(InvalidBearerTokenException.class);
  }
 }
}
