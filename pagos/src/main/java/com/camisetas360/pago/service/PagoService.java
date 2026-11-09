package com.camisetas360.pago.service;

import com.camisetas360.pago.dto.AlertaFraudeResponseDTO;
import com.camisetas360.pago.dto.IniciarPagoRequest;
import com.camisetas360.pago.dto.IniciarPagoResponse;
import com.camisetas360.pago.dto.PagoResponseDTO;
import com.camisetas360.pago.exception.FraudeDetectadoException;
import com.camisetas360.pago.exception.PagoInvalidoException;
import com.camisetas360.pago.exception.PagoNotFoundException;
import com.camisetas360.pago.messaging.PagoEvento;
import com.camisetas360.pago.messaging.PagoTopicos;
import com.camisetas360.pago.model.AlertaFraude;
import com.camisetas360.pago.model.EstadoPago;
import com.camisetas360.pago.model.Pago;
import com.camisetas360.pago.port.ConfirmarPagoResult;
import com.camisetas360.pago.port.FraudeDetector;
import com.camisetas360.pago.port.IniciarPagoComando;
import com.camisetas360.pago.port.IniciarPagoResult;
import com.camisetas360.pago.port.PasarelaPagoPort;
import com.camisetas360.pago.port.ResultadoEvaluacionFraude;
import com.camisetas360.pago.repository.AlertaFraudeRepository;
import com.camisetas360.pago.repository.PagoRepository;
import com.camisetas360.pago.util.TarjetaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PagoService {

    private static final Logger log = LoggerFactory.getLogger(PagoService.class);

    private final PagoRepository pagoRepository;
    private final AlertaFraudeRepository alertaFraudeRepository;
    private final FraudeDetector fraudeDetector;
    private final PasarelaPagoPort pasarelaPagoPort;
    private final OutboxService outboxService;
    private final String secretoHash;

    public PagoService(
            PagoRepository pagoRepository,
            AlertaFraudeRepository alertaFraudeRepository,
            FraudeDetector fraudeDetector,
            PasarelaPagoPort pasarelaPagoPort,
            OutboxService outboxService,
            @Value("${antifraude.secreto-hash:dev-antifraude-secret-key-360-supersecreto}") String secretoHash
    ) {
        this.pagoRepository = pagoRepository;
        this.alertaFraudeRepository = alertaFraudeRepository;
        this.fraudeDetector = fraudeDetector;
        this.pasarelaPagoPort = pasarelaPagoPort;
        this.outboxService = outboxService;
        this.secretoHash = secretoHash;
    }

    @Transactional(noRollbackFor = FraudeDetectadoException.class)
    public IniciarPagoResponse iniciarPago(IniciarPagoRequest request) {
        String tarjetaHash = TarjetaUtils.calcularHmacSha256(request.numeroTarjeta(), secretoHash);
        String ultimos4 = TarjetaUtils.extraerUltimos4(request.numeroTarjeta());
        String marca = TarjetaUtils.detectarMarca(request.numeroTarjeta());

        log.info("Evaluando inicio de pago para orden {}, tarjeta terminada en {}, marca {}",
                request.ordenId(), ultimos4, marca);

        // 1. Evaluación de regla antifraude (ventana deslizante)
        ResultadoEvaluacionFraude evaluacion = fraudeDetector.evaluarTransaccion(tarjetaHash, request.ordenId());

        if (!evaluacion.permitido()) {
            // Bloqueo preventivo de fraude
            Pago pagoBloqueado = new Pago();
            pagoBloqueado.setOrdenId(request.ordenId());
            pagoBloqueado.setMonto(request.monto());
            pagoBloqueado.setMoneda("CLP");
            pagoBloqueado.setEstado(EstadoPago.BLOQUEADO_FRAUDE);
            pagoBloqueado.setTarjetaHash(tarjetaHash);
            pagoBloqueado.setTarjetaUltimos4(ultimos4);
            pagoBloqueado.setTarjetaMarca(marca);
            pagoBloqueado.setMensajeRespuesta(evaluacion.motivo());
            pagoBloqueado = pagoRepository.save(pagoBloqueado);

            AlertaFraude alerta = new AlertaFraude(
                    request.ordenId(),
                    pagoBloqueado.getId(),
                    tarjetaHash,
                    ultimos4,
                    evaluacion.intentosEnVentana(),
                    evaluacion.ventanaSegundos(),
                    evaluacion.motivo()
            );
            alertaFraudeRepository.save(alerta);

            // Registro en Outbox para publicación en Kafka
            PagoEvento eventoFraude = PagoEvento.crear(
                    "PAGO_FRAUDE_DETECTADO",
                    request.ordenId(),
                    pagoBloqueado.getId(),
                    request.monto(),
                    EstadoPago.BLOQUEADO_FRAUDE.name(),
                    tarjetaHash,
                    ultimos4,
                    evaluacion.motivo()
            );
            outboxService.registrarEvento(PagoTopicos.FRAUDE_DETECTADO, request.ordenId().toString(), eventoFraude);

            throw new FraudeDetectadoException(
                    evaluacion.motivo(),
                    request.ordenId(),
                    pagoBloqueado.getId(),
                    tarjetaHash,
                    ultimos4,
                    evaluacion.intentosEnVentana(),
                    evaluacion.ventanaSegundos()
            );
        }

        // 2. Transacción permitida: iniciar en pasarela (Webpay simulado)
        IniciarPagoResult pasarelaResult = pasarelaPagoPort.iniciarTransaccion(
                new IniciarPagoComando(request.ordenId(), request.monto(), ultimos4, marca)
        );

        Pago pago = new Pago();
        pago.setOrdenId(request.ordenId());
        pago.setMonto(request.monto());
        pago.setMoneda("CLP");
        pago.setEstado(EstadoPago.INICIADO);
        pago.setTokenTransaccion(pasarelaResult.token());
        pago.setTarjetaHash(tarjetaHash);
        pago.setTarjetaUltimos4(ultimos4);
        pago.setTarjetaMarca(marca);
        pago = pagoRepository.save(pago);

        // Registro de evento intento-registrado en Outbox
        PagoEvento eventoIntento = PagoEvento.crear(
                "PAGO_INTENTO_REGISTRADO",
                request.ordenId(),
                pago.getId(),
                request.monto(),
                EstadoPago.INICIADO.name(),
                tarjetaHash,
                ultimos4,
                "Intento de pago iniciado"
        );
        outboxService.registrarEvento(PagoTopicos.INTENTO_REGISTRADO, request.ordenId().toString(), eventoIntento);

        return new IniciarPagoResponse(
                pago.getId(),
                pago.getOrdenId(),
                pago.getMonto(),
                pago.getEstado().name(),
                pasarelaResult.token(),
                pasarelaResult.urlRedireccion(),
                ultimos4,
                marca
        );
    }

    @Transactional
    public PagoResponseDTO confirmarPago(String token) {
        Pago pago = pagoRepository.findByTokenTransaccion(token)
                .orElseThrow(() -> new PagoNotFoundException("Transacción no encontrada para token: " + token));

        if (pago.getEstado() == EstadoPago.AUTORIZADO || pago.getEstado() == EstadoPago.RECHAZADO) {
            log.info("Pago {} ya se encontraba resuelto en estado {}", pago.getId(), pago.getEstado());
            return PagoResponseDTO.fromEntity(pago);
        }

        if (pago.getEstado() != EstadoPago.INICIADO) {
            throw new PagoInvalidoException("La transacción no se encuentra en estado INICIADO: " + pago.getEstado());
        }

        ConfirmarPagoResult result = pasarelaPagoPort.confirmarTransaccion(token, pago.getTarjetaUltimos4());

        if (result.autorizada()) {
            pago.setEstado(EstadoPago.AUTORIZADO);
            pago.setCodigoAutorizacion(result.codigoAutorizacion());
            pago.setMensajeRespuesta(result.motivo());
            pago = pagoRepository.save(pago);

            PagoEvento eventoAutorizado = PagoEvento.crear(
                    "PAGO_AUTORIZADO",
                    pago.getOrdenId(),
                    pago.getId(),
                    pago.getMonto(),
                    EstadoPago.AUTORIZADO.name(),
                    pago.getTarjetaHash(),
                    pago.getTarjetaUltimos4(),
                    result.motivo()
            );
            outboxService.registrarEvento(PagoTopicos.AUTORIZADO, pago.getOrdenId().toString(), eventoAutorizado);
        } else {
            pago.setEstado(EstadoPago.RECHAZADO);
            pago.setMensajeRespuesta(result.motivo());
            pago = pagoRepository.save(pago);

            PagoEvento eventoRechazado = PagoEvento.crear(
                    "PAGO_RECHAZADO",
                    pago.getOrdenId(),
                    pago.getId(),
                    pago.getMonto(),
                    EstadoPago.RECHAZADO.name(),
                    pago.getTarjetaHash(),
                    pago.getTarjetaUltimos4(),
                    result.motivo()
            );
            outboxService.registrarEvento(PagoTopicos.RECHAZADO, pago.getOrdenId().toString(), eventoRechazado);
        }

        return PagoResponseDTO.fromEntity(pago);
    }

    @Transactional(readOnly = true)
    public PagoResponseDTO obtenerPagoPorId(UUID id) {
        return pagoRepository.findById(id)
                .map(PagoResponseDTO::fromEntity)
                .orElseThrow(() -> new PagoNotFoundException("Pago no encontrado con id: " + id));
    }

    @Transactional(readOnly = true)
    public List<PagoResponseDTO> obtenerPagosPorOrden(UUID ordenId) {
        return pagoRepository.findByOrdenIdOrderByCreatedAtDesc(ordenId)
                .stream()
                .map(PagoResponseDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AlertaFraudeResponseDTO> obtenerAlertasFraude() {
        return alertaFraudeRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(AlertaFraudeResponseDTO::fromEntity)
                .toList();
    }
}
