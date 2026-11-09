package com.camisetas360.pago.repository;

import com.camisetas360.pago.model.AlertaFraude;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertaFraudeRepository extends JpaRepository<AlertaFraude, UUID> {
    List<AlertaFraude> findByTarjetaHashOrderByCreatedAtDesc(String tarjetaHash);
    List<AlertaFraude> findAllByOrderByCreatedAtDesc();
}
