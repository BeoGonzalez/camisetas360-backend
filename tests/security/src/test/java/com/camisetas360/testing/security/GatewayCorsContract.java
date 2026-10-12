package com.camisetas360.testing.security;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
public interface GatewayCorsContract {
 MockMvc mvc();
 @Test default void gatewayOwnsCorsWithoutBackendHeaders() throws Exception {
  mvc().perform(options("/actuator/health").header("Origin","https://frontend.example.test")
    .header("Access-Control-Request-Method","GET").header("Access-Control-Request-Headers","Authorization"))
    .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
 }
}
