package com.camisetas360.recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration,org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
        "management.endpoint.health.validate-group-membership=false",
        "app.kafka.listeners-enabled=false", "app.dynamodb.enabled=false", "app.tensorflow.enabled=false",
        "RABBITMQ_HOST=unused.invalid",
        "RABBITMQ_USERNAME=test",
        "RABBITMQ_PASSWORD=test",
        "RABBITMQ_VHOST=/test"
})
class RecommendationApplicationTests {

    @MockitoBean
    JdbcTemplate jdbc;

	@Test
	void contextLoads() {
	}

}
