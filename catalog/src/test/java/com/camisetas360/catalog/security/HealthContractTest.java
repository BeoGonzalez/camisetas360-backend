package com.camisetas360.catalog.security;
import com.camisetas360.testing.security.HealthContract;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes=HealthContractTest.TestApplication.class, properties={
 "management.health.defaults.enabled=false",
 "management.endpoint.health.group.readiness.include=readinessState"
})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("health-contract")
class HealthContractTest implements HealthContract, com.camisetas360.testing.security.GatewayCorsContract {
 @Autowired MockMvc mvc;
 @Autowired AtomicReference<Health> dependency;
 @MockitoBean JwtDecoder decoder;
 public MockMvc mvc() { return mvc; }
 public AtomicReference<Health> dependency() { return dependency; }
 @Configuration(proxyBeanMethods=false)
 @org.springframework.context.annotation.Profile("health-contract")
 @EnableAutoConfiguration(excludeName={"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration","org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration","org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration","org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration","org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration"})
 @Import(SecurityConfig.class)
 static class TestApplication {
  @Bean AtomicReference<Health> dependency() { return new AtomicReference<>(Health.up().build()); }
  @Bean HealthIndicator controlledHealthIndicator(AtomicReference<Health> dependency) { return dependency::get; }
 }
}
