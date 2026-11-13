package com.camisetas360.recommendation.config;

import com.camisetas360.recommendation.messaging.KafkaTopics;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaConsumerConfigTest {

    @SuppressWarnings("unchecked")
    private final KafkaOperations<String, String> template = mock(KafkaOperations.class);
    @SuppressWarnings("unchecked")
    private final Consumer<String, String> consumer = mock(Consumer.class);
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final ConsumerRecord<String, String> record =
            new ConsumerRecord<>(KafkaTopics.PRODUCTO_VISTO, 0, 7L, "t:o", "{\"eventId\":\"x\"}");

    @SuppressWarnings("unchecked")
    KafkaConsumerConfigTest() {
        when(template.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @Test
    void falloTransitorioSeReintentaYAlAgotarseVaALaDlt() {
        // Un reintento sin espera para que el test sea rapido.
        var handler = KafkaConsumerConfig.errorHandler(template, new FixedBackOff(0L, 1L));
        var caida = new ListenerExecutionFailedException("listener",
                new TransientDataAccessResourceException("base caida"));

        assertThatThrownBy(() -> handler.handleRemaining(caida, List.of(record), consumer, container));
        verify(template, never()).send(ArgumentMatchers.<ProducerRecord<String, String>>any());

        handler.handleRemaining(caida, List.of(record), consumer, container);
        assertThat(publicadoEnDlt().value()).isEqualTo(record.value());
    }

    @Test
    void errorDeDatosVaDirectoALaDltConClaveYPayloadOriginales() {
        var handler = KafkaConsumerConfig.errorHandler(template, new FixedBackOff(0L, 5L));
        var rechazo = new ListenerExecutionFailedException("listener",
                new DataIntegrityViolationException("value too long for type character varying(255)"));

        handler.handleRemaining(rechazo, List.of(record), consumer, container);

        var dlt = publicadoEnDlt();
        assertThat(dlt.key()).isEqualTo("t:o");
        assertThat(dlt.value()).isEqualTo(record.value());
    }

    @Test
    void siLaDltFallaElRegistroNoSeDaPorProcesado() {
        when(template.send(ArgumentMatchers.<ProducerRecord<String, String>>any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("kafka caido")));
        var handler = KafkaConsumerConfig.errorHandler(template, new FixedBackOff(0L, 0L));
        var rechazo = new ListenerExecutionFailedException("listener",
                new DataIntegrityViolationException("rechazo"));

        // La excepcion hace que el contenedor vuelva a buscar el offset en vez de confirmarlo.
        assertThatThrownBy(() -> handler.handleRemaining(rechazo, List.of(record), consumer, container));
    }

    @Test
    void reintentosCubrenUnaCaidaCortaDeLaBase() {
        BackOffExecution ejecucion = KafkaConsumerConfig.reintentos().start();
        List<Long> esperas = new ArrayList<>();
        for (long espera = ejecucion.nextBackOff(); espera != BackOffExecution.STOP; espera = ejecucion.nextBackOff()) {
            esperas.add(espera);
        }
        assertThat(esperas).containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L);
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> publicadoEnDlt() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(template).send(captor.capture());
        assertThat(captor.getValue().topic()).isEqualTo(KafkaTopics.INTERACCIONES_DLT);
        return captor.getValue();
    }
}
