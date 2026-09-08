package com.camisetas360.auth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * Endpoint protegido: Extrae el perfil del usuario validado por Azure Entra ID.
     * Clean Code: El nombre revela exactamente qué hace el método.
     */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> getAuthenticatedUserProfile(@AuthenticationPrincipal Jwt jwt) {
        // En Azure Entra ID, el 'sub' suele ser el identificador único (oid), 
        // y 'preferred_username' contiene el correo electrónico del usuario.
        String userId = jwt.getSubject();
        String email = jwt.getClaimAsString("preferred_username");
        String fullName = jwt.getClaimAsString("name");

        Map<String, Object> userProfile = new HashMap<>();
        userProfile.put("id", userId);
        userProfile.put("email", email != null ? email : "Email no proporcionado");
        userProfile.put("name", fullName != null ? fullName : "Usuario Entra ID");
        userProfile.put("message", "Perfil obtenido exitosamente v1.1.1");

        return ResponseEntity.ok(userProfile);
    }

    /**
     * Endpoint protegido: Valida una acción o confirma que la sesión es válida.
     */
    @PostMapping("/verify-session")
    public ResponseEntity<String> verifyActiveSession(@AuthenticationPrincipal Jwt jwt) {
        String activeUser = jwt.getClaimAsString("preferred_username");
        return ResponseEntity.ok("Sesión validada exitosamente para el usuario: " + activeUser);
    }

    /**
     * Endpoint público: Sirve como Health Check para el Load Balancer o API Gateway.
     * SOLID (SRP): Solo tiene la responsabilidad de indicar el estado público del servicio.
     */
    @GetMapping("/public/status")
    public ResponseEntity<String> getPublicSystemStatus() {
        return ResponseEntity.ok("Servicio de autenticación operativo. Endpoint público sin restricción.");
    }
}