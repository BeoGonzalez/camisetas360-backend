package com.camisetas360.orders.integration;

import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostgresSchemaIT extends PostgresTestSupport {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void flyway_shouldOwnTheValidatedPostgresSchema() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
        }
        assertThat(jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE checksum IS NOT NULL AND success", Integer.class))
                .isEqualTo(3);
        assertThat(columnType("orders", "total_amount")).isEqualTo("double precision");
        assertThat(columnType("order_items", "unit_price")).isEqualTo("double precision");
        assertThat(columnType("orders", "created_at")).isEqualTo("timestamp with time zone");
        assertThat(jdbc.queryForObject("SELECT datetime_precision FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='orders' AND column_name='created_at'", Integer.class))
                .isEqualTo(6);
    }

    static Stream<String> missingRequiredColumns() {
        var orderColumns = List.of("user_email", "total_amount", "status", "created_at");
        var itemColumns = List.of("sku", "quantity", "unit_price", "order_id");
        return Stream.concat(
                orderColumns.stream().map(column -> "INSERT INTO orders (id,user_email,total_amount,status,created_at) "
                        + "VALUES (100," + (column.equals("user_email") ? "NULL" : "'buyer@example.test'")
                        + "," + (column.equals("total_amount") ? "NULL" : "69.0")
                        + "," + (column.equals("status") ? "NULL" : "'CREATED'")
                        + "," + (column.equals("created_at") ? "NULL" : "CURRENT_TIMESTAMP") + ")"),
                itemColumns.stream().map(column -> "INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ("
                        + (column.equals("sku") ? "NULL" : "'SKU-A'")
                        + "," + (column.equals("quantity") ? "NULL" : "2")
                        + "," + (column.equals("unit_price") ? "NULL" : "19.5")
                        + "," + (column.equals("order_id") ? "NULL" : "100") + ")"));
    }

    @ParameterizedTest
    @MethodSource("missingRequiredColumns")
    void database_shouldRejectNullRequiredFields(String sql) throws Exception {
        insertOrder(100);
        // Delete the parent for order INSERT cases so a duplicate PK cannot mask NOT NULL.
        if (sql.startsWith("INSERT INTO orders ")) jdbc.update("DELETE FROM orders WHERE id=100");
        assertSqlState(sql, "23502");
    }

    @Test
    void database_shouldRejectDuplicatePrimaryKeysAndOrphanItems() throws Exception {
        insertOrder(100);
        assertSqlState("INSERT INTO orders (id,user_email,total_amount,status,created_at) "
                + "VALUES (100,'buyer@example.test',69,'CREATED',CURRENT_TIMESTAMP)", "23505");
        jdbc.update("INSERT INTO order_items (id,sku,quantity,unit_price,order_id) VALUES (200,'SKU-A',2,19.5,100)");
        assertSqlState("INSERT INTO order_items (id,sku,quantity,unit_price,order_id) "
                + "VALUES (200,'SKU-B',1,10,100)", "23505");
        assertSqlState("INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ('ORPHAN',1,10,999999)", "23503");
        assertSqlState("DELETE FROM orders WHERE id=100", "23503");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(1);
    }

    @Test
    void database_shouldRejectInvalidStatusAndNonNumericPrice() throws Exception {
        assertSqlState("INSERT INTO orders (user_email,total_amount,status,created_at) "
                + "VALUES ('buyer@example.test',69,'UNKNOWN',CURRENT_TIMESTAMP)", "23514");
        assertSqlState("INSERT INTO orders (user_email,total_amount,status,created_at) "
                + "VALUES ('buyer@example.test','not-money','CREATED',CURRENT_TIMESTAMP)", "22P02");
        assertThat(repository.count()).isZero();
    }

    @Test
    void timestamp_shouldPreserveTheInstantFromAnotherTimezone() {
        var instant = OffsetDateTime.parse("2026-04-05T01:30:15.123456-03:00").toInstant();
        var order = aggregate(instant);
        Long id = new TransactionTemplate(transactionManager).execute(status -> repository.saveAndFlush(order).getId());
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getCreatedAt()).isEqualTo(Instant.parse("2026-04-05T04:30:15.123456Z"));
        assertThat(jdbc.queryForObject("SELECT created_at FROM orders WHERE id=?", OffsetDateTime.class, id)
                .toInstant()).isEqualTo(instant);
    }

    @Test
    void transaction_shouldRollbackTheWholeAggregateAfterFlushWhenWorkFails() {
        var order = aggregate(Instant.parse("2026-01-01T12:00:00Z"));
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.saveAndFlush(order);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("Simulated failure after the database writes");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Simulated failure after the database writes");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    void failedItemInsert_shouldRollbackAnAlreadyInsertedParent() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            insertOrder(100);
            jdbc.update("INSERT INTO order_items (sku,quantity,unit_price,order_id) VALUES ('SKU-A',NULL,19.5,100)");
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(repository.findById(100L)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items", Integer.class)).isZero();
    }

    @Test
    void migrations_shouldIndexOwnerHistoryAndItemForeignKey() {
        var indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname='public' "
                + "AND indexname IN ('idx_orders_user_email_created_at','idx_order_items_order_id')", String.class);
        assertThat(indexes).hasSize(2);
        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("(user_email, created_at DESC)"));
        assertThat(indexes).anySatisfy(index -> assertThat(index).contains("(order_id)"));
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name=? AND column_name=?", String.class, table, column);
    }

    private void assertSqlState(String sql, String expected) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql))
                    .isInstanceOf(SQLException.class)
                    .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(expected));
        }
    }

    private void insertOrder(long id) {
        jdbc.update("INSERT INTO orders (id,user_email,total_amount,status,created_at) VALUES (?,?,69,'CREATED',CURRENT_TIMESTAMP)",
                id, "buyer@example.test");
    }

    private static Order aggregate(Instant createdAt) {
        var order = new Order();
        order.setUserEmail("buyer@example.test");
        order.setTotalAmount(39.0);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(createdAt);
        var item = new OrderItem();
        item.setSku("SKU-A");
        item.setQuantity(2);
        item.setUnitPrice(19.5);
        order.addItem(item);
        return order;
    }
}
