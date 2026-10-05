package com.camisetas360.testing.messaging;

import com.camisetas360.carrito.messaging.CheckoutEventPublisher;
import com.camisetas360.notifications.listener.OrderCreatedListener;
import com.camisetas360.notifications.service.EmailService;
import com.camisetas360.orders.messaging.OrderEventPublisher;
import com.camisetas360.orders.messaging.listener.CheckoutRequestedListener;
import com.camisetas360.orders.messaging.event.CheckoutRequestedEvent;
import com.camisetas360.orders.model.Order;
import com.camisetas360.orders.model.OrderItem;
import com.camisetas360.orders.model.OrderStatus;
import com.camisetas360.orders.repository.OrderRepository;
import com.camisetas360.orders.service.OrderService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ResourceLock(Resources.LOCALE)
class MessagingRabbitIT {

    private static final String EXCHANGE = "camisetas360.orders";
    private static final String CHECKOUT_QUEUE = "orders.checkout-requested.q";
    private static final String NOTIFICATION_QUEUE = "notifications.order-created.q";
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer(
            System.getProperty("rabbitmq.image", "rabbitmq:4-management"));
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private CachingConnectionFactory connection;
    private RabbitAdmin admin;

    @BeforeAll
    static void startBroker() {
        // No disabledWithoutDocker: infrastructure failures must fail this explicit profile.
        RABBIT.start();
        POSTGRES.start();
    }

    @AfterAll
    static void stopBroker() {
        POSTGRES.stop();
        RABBIT.stop();
    }

    @BeforeEach
    void connectAndDeclareTopology() {
        connection = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getAmqpPort());
        connection.setUsername(RABBIT.getAdminUsername());
        connection.setPassword(RABBIT.getAdminPassword());
        admin = new RabbitAdmin(connection);
        var cart = new com.camisetas360.carrito.config.RabbitMQConfig();
        var orders = new com.camisetas360.orders.config.RabbitMQConfig();
        var notifications = new com.camisetas360.notifications.config.RabbitMQConfig();
        admin.declareExchange(cart.ordersExchange());
        admin.declareExchange(orders.ordersExchange());
        admin.declareExchange(notifications.ordersExchange());
        admin.declareQueue(orders.checkoutRequestedQueue());
        admin.declareQueue(notifications.notificationsOrderCreatedQueue());
        admin.declareBinding(orders.checkoutRequestedBinding(orders.checkoutRequestedQueue(), orders.ordersExchange()));
        admin.declareBinding(notifications.notificationsOrderCreatedBinding(
                notifications.notificationsOrderCreatedQueue(), notifications.ordersExchange()));
        admin.purgeQueue(CHECKOUT_QUEUE);
        admin.purgeQueue(NOTIFICATION_QUEUE);
    }

    @AfterEach
    void disconnect() {
        if (connection != null) {
            connection.destroy();
        }
    }

    // IT-AMQP-001
    @Test
    void topology_shouldMatchProductionDeclarations_onRealBroker() throws Exception {
        var exchange = managementMap("/api/exchanges/%2F/" + EXCHANGE);
        assertThat(exchange).containsEntry("type", "direct")
                .containsEntry("durable", true).containsEntry("auto_delete", false);
        for (String queue : List.of(CHECKOUT_QUEUE, NOTIFICATION_QUEUE)) {
            assertThat(managementMap("/api/queues/%2F/" + queue))
                    .containsEntry("durable", true).containsEntry("auto_delete", false);
        }
        assertBinding(CHECKOUT_QUEUE, "checkout.requested");
        assertBinding(NOTIFICATION_QUEUE, "order.created");
    }

    // IT-AMQP-002
    @Test
    void checkout_shouldReachOrdersListener_asConsumerRecord() {
        var service = mock(OrderService.class);
        when(service.createOrder(any())).thenReturn(new Order());
        try (var context = consumer(OrdersConsumer.class, service, null)) {
            awaitConsumer(context);
            var template = template(new com.camisetas360.carrito.config.RabbitMQConfig().jsonMessageConverter());
            new CheckoutEventPublisher(template).publishCheckoutRequested(EventFixtures.checkout());

            var captor = ArgumentCaptor.forClass(CheckoutRequestedEvent.class);
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                    verify(service).createOrder(captor.capture()));
            assertThat(captor.getValue()).isEqualTo(new CheckoutRequestedEvent(
                    EventFixtures.EVENT_ID, "buyer@example.test", List.of(
                    new com.camisetas360.orders.messaging.event.CheckoutItemEvent("CAM-Ñ-東京", 2, 19.5),
                    new com.camisetas360.orders.messaging.event.CheckoutItemEvent("SKU-B", 3, 10.0)),
                    EventFixtures.OCCURRED_AT));
        }
    }

    // IT-AMQP-003
    @Test
    void orderCreated_shouldReachNotificationsListener_andRequestExpectedEmail() {
        var email = mock(EmailService.class);
        var previous = Locale.getDefault(Locale.Category.FORMAT);
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        try (var context = consumer(NotificationsConsumer.class, null, email)) {
            awaitConsumer(context);
            var template = template(new com.camisetas360.orders.config.RabbitMQConfig().jsonMessageConverter());
            new OrderEventPublisher(template).publishOrderCreated(EventFixtures.orderCreated());

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> verify(email).sendEmail(
                    "buyer@example.test", "Orden creada #42", """
                    Hola,

                    Tu orden fue creada correctamente.

                    Número de orden: 42
                    Total: $69.00
                    Estado: CREATED

                    Gracias por comprar en Camisetas360.
                    """));
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, previous);
        }
    }

    // IT-AMQP-004
    @Test
    void checkout_shouldPersistAggregateAndPublishMatchingOrderCreated_whenConsumed() {
        var capture = QueueBuilder.nonDurable("test.capture." + UUID.randomUUID()).exclusive().autoDelete().build();
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new DirectExchange(EXCHANGE)).with("order.created"));
        try (var context = consumer(OrderProcessing.class, null, null)) {
            awaitConsumer(context);
            var publisherTemplate = template(new com.camisetas360.carrito.config.RabbitMQConfig().jsonMessageConverter());
            new CheckoutEventPublisher(publisherTemplate).publishCheckoutRequested(EventFixtures.checkout());

            // Blocking receive has a finite deadline and wakes when the message arrives.
            var message = publisherTemplate.receive(capture.getName(), 15000);
            assertThat(message).as("order.created captured within 15 seconds").isNotNull();
            message.getMessageProperties().setInferredArgumentType(
                    com.camisetas360.notifications.event.OrderCreatedEvent.class);
            var event = (com.camisetas360.notifications.event.OrderCreatedEvent)
                    new com.camisetas360.notifications.config.RabbitMQConfig().jsonMessageConverter().fromMessage(message);
            assertThat(event.eventId()).isNotNull();
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.orderId()).isNotNull();
            assertThat(event.userEmail()).isEqualTo("buyer@example.test");
            assertThat(event.totalAmount()).isEqualTo(69.0);
            assertThat(event.items()).containsExactlyInAnyOrder(
                    new com.camisetas360.notifications.event.OrderItemEvent("CAM-Ñ-東京", 2, 19.5),
                    new com.camisetas360.notifications.event.OrderItemEvent("SKU-B", 3, 10.0));

            var repository = context.getBean(OrderRepository.class);
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                assertThat(repository.count()).isEqualTo(1);
                var order = repository.findById(event.orderId()).orElseThrow();
                assertThat(order.getUserEmail()).isEqualTo(event.userEmail());
                assertThat(order.getTotalAmount()).isEqualTo(event.totalAmount());
                assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
                assertThat(order.getCreatedAt()).isNotNull();
                assertThat(order.getItems()).extracting(OrderItem::getSku, OrderItem::getQuantity, OrderItem::getUnitPrice)
                        .containsExactlyInAnyOrder(tuple("CAM-Ñ-東京", 2, 19.5), tuple("SKU-B", 3, 10.0));
                assertThat(order.getItems()).allSatisfy(item -> {
                    assertThat(item.getId()).isNotNull();
                    assertThat(item.getOrder().getId()).isEqualTo(event.orderId());
                });
            });
        }
    }

    private AnnotationConfigApplicationContext consumer(Class<?> config, OrderService service, EmailService email) {
        var context = new AnnotationConfigApplicationContext();
        context.registerBean(ConnectionFactory.class, () -> connection);
        if (service != null) {
            context.registerBean(OrderService.class, () -> service);
        }
        if (email != null) {
            context.registerBean(EmailService.class, () -> email);
        }
        context.register(ListenerInfrastructure.class, config);
        try {
            context.refresh();
            return context;
        } catch (RuntimeException exception) {
            context.close();
            throw exception;
        }
    }

    private static void awaitConsumer(AnnotationConfigApplicationContext context) {
        var registry = context.getBean(RabbitListenerEndpointRegistry.class);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(registry.getListenerContainers()).hasSize(1).allSatisfy(container ->
                        assertThat(((SimpleMessageListenerContainer) container).getActiveConsumerCount()).isEqualTo(1)));
    }

    private RabbitTemplate template(MessageConverter converter) {
        var template = new RabbitTemplate(connection);
        template.setMessageConverter(converter);
        return template;
    }

    private String management(String path) throws Exception {
        var credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Basic " + Base64.getEncoder()
                            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            return response.body();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> managementMap(String path) throws Exception {
        return JsonMapper.builder().build().readValue(management(path), Map.class);
    }

    @SuppressWarnings("unchecked")
    private void assertBinding(String queue, String routing) throws Exception {
        List<Map<String, Object>> bindings = JsonMapper.builder().build().readValue(
                management("/api/bindings/%2F/e/" + EXCHANGE + "/q/" + queue), List.class);
        assertThat(bindings).anySatisfy(binding ->
                assertThat(binding).containsEntry("source", EXCHANGE).containsEntry("destination", queue)
                        .containsEntry("routing_key", routing).containsEntry("destination_type", "queue"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableRabbit
    static class ListenerInfrastructure {
        @Bean
        SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
                ConnectionFactory connection, MessageConverter converter) {
            var factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connection);
            factory.setMessageConverter(converter);
            factory.setDefaultRequeueRejected(false);
            factory.setMissingQueuesFatal(true);
            return factory;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import({com.camisetas360.orders.config.RabbitMQConfig.class, CheckoutRequestedListener.class})
    static class OrdersConsumer {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({com.camisetas360.notifications.config.RabbitMQConfig.class, OrderCreatedListener.class})
    static class NotificationsConsumer {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableJpaRepositories(basePackageClasses = OrderRepository.class)
    @EnableTransactionManagement
    @Import({OrdersConsumer.class, OrderService.class, OrderEventPublisher.class})
    static class OrderProcessing {
        @Bean
        RabbitTemplate rabbitTemplate(ConnectionFactory connection, MessageConverter converter) {
            var template = new RabbitTemplate(connection);
            template.setMessageConverter(converter);
            return template;
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(POSTGRES.getJdbcUrl(),
                    POSTGRES.getUsername(), POSTGRES.getPassword());
        }

        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).load();
        }

        @Bean
        @DependsOn("flyway")
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan(Order.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
