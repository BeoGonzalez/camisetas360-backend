package com.camisetas360.recommendation.config;

import com.camisetas360.recommendation.messaging.KafkaTopics;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;

import java.util.HashMap;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    /** Valores como String: el JSON se valida en InteraccionEventoParser, sin deserializar tipos arbitrarios. */
    @Bean
    public ConsumerFactory<String, String> consumerFactory(
            @Value("${app.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Productor solo para la DLT: reenvia clave y payload tal como llegaron. */
    @Bean
    public ProducerFactory<String, String> dltProducerFactory(
            @Value("${app.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers) {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30_000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate(ProducerFactory<String, String> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    /**
     * Crea la DLT al arrancar (el broker tiene auto-create desactivado). Si los listeners estan
     * apagados no hace falta y no se intenta conectar.
     */
    @Bean
    public KafkaAdmin kafkaAdmin(@Value("${app.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
                                 @Value("${app.kafka.listeners-enabled:true}") boolean listenersEnabled) {
        var admin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers));
        admin.setAutoCreate(listenersEnabled);
        return admin;
    }

    @Bean
    public NewTopic interaccionesDlt() {
        // Sin replicas explicitas: usa el default del broker (1 en local).
        return TopicBuilder.name(KafkaTopics.INTERACCIONES_DLT).partitions(3).build();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KafkaTemplate<String, String> dltKafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(errorHandler(dltKafkaTemplate, reintentos()));
        return factory;
    }

    /** ~35 s en total (1, 2, 4, 8, 10 y 10 s): cubre un reinicio o failover corto de la base. */
    static BackOff reintentos() {
        var backOff = new ExponentialBackOffWithMaxRetries(6);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(10_000L);
        return backOff;
    }

    /**
     * Commit por registro. Los fallos transitorios (base caida, deadlock, timeout) se reintentan con
     * backoff; los errores de datos que la base rechaza van directo a la DLT. Agotados los
     * reintentos, el registro se publica en {@link KafkaTopics#INTERACCIONES_DLT} antes de avanzar
     * el offset: no se pierde. Si la DLT tampoco esta disponible, el registro se vuelve a procesar.
     * Los mensajes con JSON o contrato invalido no llegan aqui: el parser los descarta.
     */
    static DefaultErrorHandler errorHandler(KafkaOperations<?, ?> template, BackOff backOff) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, failure) -> new TopicPartition(KafkaTopics.INTERACCIONES_DLT, -1));
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(DataIntegrityViolationException.class);
        return handler;
    }
}
