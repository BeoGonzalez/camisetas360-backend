package com.camisetas360.carrito.integration;
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
  config.target("1").load().migrate();
  var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
  jdbc.update("INSERT INTO checkout_requests (id,user_email,total_amount,status,created_at) VALUES ('44444444-4444-4444-4444-444444444444','legacy@example.test',20,'PROCESSING',CURRENT_TIMESTAMP)");
  Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load().migrate();
  assertThat(jdbc.queryForMap("SELECT tenant_id,object_id,user_email FROM checkout_requests"))
   .containsEntry("tenant_id",null).containsEntry("object_id",null).containsEntry("user_email","legacy@example.test");
  assertThatThrownBy(()->jdbc.update("UPDATE checkout_requests SET tenant_id='11111111-1111-1111-1111-111111111111'"))
   .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
}
