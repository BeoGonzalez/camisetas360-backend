package com.camisetas360.orders.repository;

import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import com.camisetas360.orders.support.PostgresTestSupport;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryTest extends PostgresTestSupport {

    private static final String EMAIL = "buyer@example.test";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private OrderRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    // JPA-ORD-001
    @Test
    void save_shouldPersistOrderAndItems_whenAggregateIsReloaded() {
        var order = order(EMAIL, CREATED_AT);
        var id = repository.saveAndFlush(order).getId();
        var itemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();

        assertThat(id).isNotNull();
        assertThat(itemIds).hasSize(2).doesNotContainNull().doesNotHaveDuplicates();
        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded).isNotSameAs(order);
        assertThat(reloaded.getId()).isEqualTo(id);
        assertThat(reloaded.getUserEmail()).isEqualTo(EMAIL);
        assertThat(reloaded.getTotalAmount()).isEqualTo(69.0);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getItems()).extracting(OrderItem::getId)
                .containsExactlyInAnyOrderElementsOf(itemIds);
        assertThat(reloaded.getItems())
                .extracting(OrderItem::getSku, OrderItem::getQuantity, OrderItem::getUnitPrice)
                .containsExactlyInAnyOrder(tuple("SKU-A", 2, 19.5), tuple("SKU-B", 3, 10.0));
        assertThat(reloaded.getItems()).allSatisfy(item ->
                assertThat(item.getOrder().getId()).isEqualTo(id));
        assertThat(entityManager.createNativeQuery("select status from orders where id = :id", String.class)
                .setParameter("id", id).getSingleResult()).isEqualTo("CREATED");
    }

    // JPA-ORD-002
    @Test
    void findByUserEmailOrderByCreatedAtDesc_shouldFilterAndSort_whenUsersHaveOrders() {
        var oldest = repository.save(order(EMAIL, CREATED_AT));
        var newest = repository.save(order(EMAIL, Instant.parse("2026-01-03T12:00:00Z")));
        repository.save(order("other@example.test", Instant.parse("2026-01-04T12:00:00Z")));
        var middle = repository.save(order(EMAIL, Instant.parse("2026-01-02T12:00:00Z")));
        repository.flush();
        entityManager.clear();

        var result = repository.findByUserEmailOrderByCreatedAtDesc(EMAIL);

        assertThat(result).extracting(Order::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
        assertThat(result).extracting(Order::getUserEmail).containsOnly(EMAIL);
    }

    // JPA-ORD-003
    @Test
    void findByUserEmailOrderByCreatedAtDesc_shouldReturnEmpty_whenOnlyAnotherUserHasOrders() {
        repository.saveAndFlush(order("other@example.test", CREATED_AT));
        entityManager.clear();

        assertThat(repository.findByUserEmailOrderByCreatedAtDesc(EMAIL)).isEmpty();
    }

    // JPA-ORD-003
    @Test
    void findById_shouldReturnEmpty_whenOrderDoesNotExist() {
        assertThat(repository.findById(-1L)).isEmpty();
    }

    // JPA-ORD-004
    @Test
    void setItems_shouldDeleteOrphans_whenItemsAreReplaced() {
        var order = repository.saveAndFlush(order(EMAIL, CREATED_AT));
        var id = order.getId();
        var oldItemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();
        var managed = repository.findById(id).orElseThrow();
        managed.setItems(List.of(item("SKU-NEW", 1, 25.0)));
        repository.saveAndFlush(managed);
        entityManager.clear();

        var reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getId()).isNotNull().isNotIn(oldItemIds);
            assertThat(item.getSku()).isEqualTo("SKU-NEW");
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualTo(25.0);
            assertThat(item.getOrder().getId()).isEqualTo(id);
        });
        oldItemIds.forEach(itemId -> assertThat(entityManager.find(OrderItem.class, itemId)).isNull());
    }

    // JPA-ORD-005
    @Test
    void delete_shouldRemoveAssociatedItems_whenOrderIsDeleted() {
        var order = repository.saveAndFlush(order(EMAIL, CREATED_AT));
        var id = order.getId();
        var itemIds = order.getItems().stream().map(OrderItem::getId).toList();
        entityManager.clear();
        repository.delete(repository.findById(id).orElseThrow());
        repository.flush();
        entityManager.clear();

        assertThat(repository.findById(id)).isEmpty();
        itemIds.forEach(itemId -> assertThat(entityManager.find(OrderItem.class, itemId)).isNull());
    }

    private static Order order(String email, Instant createdAt) {
        var order = new Order();
        order.setUserEmail(email);
        order.setTotalAmount(69.0);
        order.setStatus(OrderStatus.CREATED);
        order.setCreatedAt(createdAt);
        order.setItems(List.of(item("SKU-A", 2, 19.5), item("SKU-B", 3, 10.0)));
        return order;
    }

    private static OrderItem item(String sku, int quantity, double price) {
        var item = new OrderItem();
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitPrice(price);
        return item;
    }
}
