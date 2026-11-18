package com.camisetas360.analytics.messaging;

import com.camisetas360.analytics.repository.RegistroKafkaRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Copia a DynamoDB (tabla kafka-registros) cada mensaje de los topicos de telemetria (catalogo.*,
 * carrito.* y telemetria.*), tal cual se publico (clave, valor, headers, particion, offset). Los topicos
 * de pagos y de recommendation no se archivan. Usa su propio grupo de consumidores, asi no afecta a los
 * offsets de analytics-recommendations ni de recommendation, y un topico nuevo que calce con el patron
 * se detecta solo (refresco de metadatos de Kafka).
 */
@Component
@ConditionalOnProperty(name = "app.kafka.archivo.enabled", havingValue = "true", matchIfMissing = true)
public class ArchivoKafkaListener {

    private final RegistroKafkaRepository registros;

    public ArchivoKafkaListener(RegistroKafkaRepository registros) {
        this.registros = registros;
    }

    @KafkaListener(
            id = "analytics-archivo-kafka",
            groupId = "analytics-archivo-kafka",
            topicPattern = "${app.kafka.archivo.patron:^(catalogo|carrito|telemetria)[.].*}",
            autoStartup = "${app.kafka.listeners-enabled:true}")
    public void onRegistro(ConsumerRecord<String, String> registro) {
        registros.guardar(registro);
    }
}
