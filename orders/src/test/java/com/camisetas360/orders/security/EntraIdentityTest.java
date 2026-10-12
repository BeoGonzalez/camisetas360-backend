package com.camisetas360.orders.security;
import com.camisetas360.testing.security.IdentityContract;
import java.util.function.Function;
import org.springframework.security.oauth2.jwt.Jwt;
class EntraIdentityTest implements IdentityContract {
 public Function<Jwt,?> identity() { return EntraIdentity::from; }
}
