package com.camisetas360.pago.repository;

import com.camisetas360.pago.model.OutboxEvento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventoRepository extends JpaRepository<OutboxEvento, UUID> {
    List<OutboxEvento> findTop50ByEstadoOrderByCreatedAtAsc(String estado);
}
