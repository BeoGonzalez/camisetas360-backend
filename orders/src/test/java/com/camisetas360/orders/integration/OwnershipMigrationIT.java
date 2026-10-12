package com.camisetas360.orders.integration;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class OwnershipMigrationIT {
 @Container static final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17");
 @Test void upgradesWithoutInferringLegacyOwnershipFromEmail() {
  var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
  config.target("4").load().migrate();
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
  jdbc.update("INSERT INTO orders (user_email,total_amount,status,created_at) VALUES ('legacy@example.test',20,'CREATED',CURRENT_TIMESTAMP)");
  Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
  assertThat(jdbc.queryForMap("SELECT tenant_id,object_id,user_email FROM orders"))
   .containsEntry("tenant_id",null).containsEntry("object_id",null).containsEntry("user_email","legacy@example.test");
  assertThatThrownBy(()->jdbc.update("UPDATE orders SET tenant_id='11111111-1111-1111-1111-111111111111'"))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
}
