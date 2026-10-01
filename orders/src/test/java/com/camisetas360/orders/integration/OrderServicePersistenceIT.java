package com.camisetas360.orders.integration;

import com.camisetas360.orders.dto.OrderItemResponseDTO;
import com.camisetas360.orders.dto.OrderResponseDTO;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.service.OrderService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false",
        "spring.sql.init.mode=never"
})
@Import(OrderService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderServicePersistenceIT {

    private static final String EMAIL = "buyer@example.test";
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T12:00:00Z");

    @Autowired
    private OrderRepository repository;

    @Autowired
    private OrderService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private OrderEventPublisher publisher;

    @BeforeEach
    void cleanDatabaseBeforeTest() {
        cleanDatabase();
    }

    @AfterEach
    void cleanDatabaseAndVerifyNoPublication() {
        try {
            verifyNoInteractions(publisher);
        } finally {
            cleanDatabase();
        }
    }

    // IT-ORD-001: assert the functional contract, not LazyInitializationException.
    @Test
    void findById_shouldReturnCompleteDto_whenSetupTransactionHasClosed() {
        var id = persistOrder(EMAIL, CREATED_AT);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        assertOrder(service.findById(id, EMAIL), id, CREATED_AT);
    }

    // IT-ORD-002
    @Test
    void findByUserEmail_shouldReturnCompleteDtos_whenSetupTransactionsHaveClosed() {
        var olderId = persistOrder(EMAIL, CREATED_AT);
        var later = Instant.parse("2026-01-02T12:00:00Z");
        var newerId = persistOrder(EMAIL, later);
        persistOrder("other@example.test", Instant.parse("2026-01-03T12:00:00Z"));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        var result = service.findByUserEmail(EMAIL);

        assertThat(result).hasSize(2);
        assertOrder(result.get(0), newerId, later);
        assertOrder(result.get(1), olderId, CREATED_AT);
    }

    private Long persistOrder(String email, Instant createdAt) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            var order = new Order();
            order.setUserEmail(email);
            order.setTotalAmount(69.0);
            order.setStatus(OrderStatus.CREATED);
            order.setCreatedAt(createdAt);
            order.setItems(List.of(item("SKU-A", 2, 19.5), item("SKU-B", 3, 10.0)));
            return repository.saveAndFlush(order).getId();
        });
    }

    private void cleanDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createQuery("delete from OrderItem").executeUpdate();
            entityManager.createQuery("delete from Order").executeUpdate();
        });
    }

    private static void assertOrder(OrderResponseDTO response, Long id, Instant createdAt) {
        assertThat(response.orderId()).isEqualTo(id);
        assertThat(response.userEmail()).isEqualTo(EMAIL);
        assertThat(response.totalAmount()).isEqualTo(69.0);
        assertThat(response.status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.items()).containsExactlyInAnyOrder(
                new OrderItemResponseDTO("SKU-A", 2, 19.5),
                new OrderItemResponseDTO("SKU-B", 3, 10.0));
    }

    private static OrderItem item(String sku, int quantity, double price) {
        var item = new OrderItem();
        item.setSku(sku);
        item.setQuantity(quantity);
        item.setUnitPrice(price);
        return item;
    }
}
