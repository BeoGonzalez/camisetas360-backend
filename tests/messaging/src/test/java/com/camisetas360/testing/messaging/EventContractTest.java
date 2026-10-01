package com.camisetas360.testing.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EventContractTest {

    @TempDir
    Path transport;

    // CT-CHECKOUT-001
    @Test
    void checkoutSchema_shouldMatch_betweenActualProducerAndConsumerRecords() {
        assertSameSchema(com.camisetas360.carrito.messaging.event.CheckoutRequestedEvent.class,
                com.camisetas360.orders.messaging.event.CheckoutRequestedEvent.class);
        assertSameSchema(com.camisetas360.carrito.messaging.event.CheckoutItemEvent.class,
                com.camisetas360.orders.messaging.event.CheckoutItemEvent.class);
    }

    // CT-ORDER-001
    @Test
    void orderCreatedSchema_shouldMatch_betweenActualProducerAndConsumerRecords() {
        assertSameSchema(com.camisetas360.orders.messaging.event.OrderCreatedEvent.class,
                com.camisetas360.notifications.event.OrderCreatedEvent.class);
        assertSameSchema(com.camisetas360.orders.messaging.event.OrderItemEvent.class,
                com.camisetas360.notifications.event.OrderItemEvent.class);
    }

    // CT-CHECKOUT-002
    @Test
    void checkoutJson_shouldDeserializeAsOrdersRecord_withProducerBytesAndHeaders() throws Exception {
        var message = transport(new com.camisetas360.carrito.config.RabbitMQConfig().jsonMessageConverter(),
                EventFixtures.checkout());
        message.getMessageProperties().setInferredArgumentType(
                com.camisetas360.orders.messaging.event.CheckoutRequestedEvent.class);

        var result = new com.camisetas360.orders.config.RabbitMQConfig().jsonMessageConverter().fromMessage(message);

        assertThat(result).isEqualTo(new com.camisetas360.orders.messaging.event.CheckoutRequestedEvent(
                EventFixtures.EVENT_ID, "buyer@example.test", List.of(
                new com.camisetas360.orders.messaging.event.CheckoutItemEvent("CAM-Ñ-東京", 2, 19.5),
                new com.camisetas360.orders.messaging.event.CheckoutItemEvent("SKU-B", 3, 10.0)),
                EventFixtures.OCCURRED_AT));
    }

    // CT-ORDER-002
    @Test
    void orderCreatedJson_shouldDeserializeAsNotificationsRecord_withProducerBytesAndHeaders() throws Exception {
        var message = transport(new com.camisetas360.orders.config.RabbitMQConfig().jsonMessageConverter(),
                EventFixtures.orderCreated());
        message.getMessageProperties().setInferredArgumentType(
                com.camisetas360.notifications.event.OrderCreatedEvent.class);

        var result = new com.camisetas360.notifications.config.RabbitMQConfig().jsonMessageConverter().fromMessage(message);

        assertThat(result).isEqualTo(new com.camisetas360.notifications.event.OrderCreatedEvent(
                EventFixtures.EVENT_ID, 42L, "buyer@example.test", 69.0, List.of(
                new com.camisetas360.notifications.event.OrderItemEvent("CAM-Ñ-東京", 2, 19.5),
                new com.camisetas360.notifications.event.OrderItemEvent("SKU-B", 3, 10.0)),
                EventFixtures.OCCURRED_AT));
    }

    private Message transport(MessageConverter producer, Object event) throws Exception {
        var produced = producer.toMessage(event, new MessageProperties());
        assertThat(produced.getMessageProperties().getHeaders())
                .containsEntry("__TypeId__", event.getClass().getName());
        Files.write(transport.resolve("event.json"), produced.getBody());
        var properties = new Properties();
        properties.setProperty("contentType", produced.getMessageProperties().getContentType());
        if (produced.getMessageProperties().getContentEncoding() != null) {
            properties.setProperty("contentEncoding", produced.getMessageProperties().getContentEncoding());
        }
        produced.getMessageProperties().getHeaders().forEach((key, value) -> {
            assertThat(value).isInstanceOf(String.class);
            properties.setProperty("header." + key, (String) value);
        });
        try (var output = Files.newOutputStream(transport.resolve("headers.properties"))) {
            properties.store(output, "Actual producer metadata");
        }

        var loaded = new Properties();
        try (var input = Files.newInputStream(transport.resolve("headers.properties"))) {
            loaded.load(input);
        }
        var received = new MessageProperties();
        received.setContentType(loaded.getProperty("contentType"));
        if (loaded.containsKey("contentEncoding")) {
            received.setContentEncoding(loaded.getProperty("contentEncoding"));
        }
        loaded.stringPropertyNames().stream().filter(key -> key.startsWith("header."))
                .forEach(key -> received.setHeader(key.substring(7), loaded.getProperty(key)));
        return new Message(Files.readAllBytes(transport.resolve("event.json")), received);
    }

    private static void assertSameSchema(Class<?> producer, Class<?> consumer) {
        assertThat(producer.isRecord()).isTrue();
        assertThat(consumer.isRecord()).isTrue();
        assertThat(producer.getSimpleName()).isEqualTo(consumer.getSimpleName());
        assertThat(schema(producer)).isEqualTo(schema(consumer));
    }

    private static Map<String, String> schema(Class<?> record) {
        var schema = new TreeMap<String, String>();
        for (var component : record.getRecordComponents()) {
            var annotations = Arrays.stream(component.getAnnotatedType().getAnnotations())
                    .map(annotation -> annotation.toString()).sorted().collect(Collectors.joining(","));
            var fieldAnnotations = Arrays.stream(record.getDeclaredFields())
                    .filter(field -> field.getName().equals(component.getName()))
                    .flatMap(field -> Arrays.stream(field.getAnnotations()))
                    .map(annotation -> annotation.toString()).sorted().collect(Collectors.joining(","));
            schema.put(component.getName(), logicalType(component.getGenericType())
                    + ";typeAnnotations=" + annotations + ";fieldAnnotations=" + fieldAnnotations);
        }
        return schema;
    }

    private static String logicalType(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            return logicalType(parameterized.getRawType()) + "<"
                    + Arrays.stream(parameterized.getActualTypeArguments()).map(EventContractTest::logicalType)
                    .collect(Collectors.joining(",")) + ">";
        }
        if (type instanceof Class<?> clazz) {
            return clazz.isRecord() ? clazz.getSimpleName() : clazz.getName();
        }
        return type.getTypeName();
    }
}

