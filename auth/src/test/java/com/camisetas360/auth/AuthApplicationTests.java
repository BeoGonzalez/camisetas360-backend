package com.camisetas360.auth;

import com.camisetas360.auth.controller.AuthController;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;



import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class AuthApplicationTests {

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private JwtDecoder decoder;

    // IT-CFG-001
    @Test
    void contextLoads() {
        assertThat(context.getBean(AuthController.class)).isNotNull();

    }
}
