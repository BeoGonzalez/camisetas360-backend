package com.camisetas360.auth.controller;

import com.camisetas360.auth.dtos.UserProfileDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDTO> getAuthenticatedUserProfile(
            @AuthenticationPrincipal Jwt jwt) {

        String userId = jwt.getClaimAsString("oid");
        String tenantId = jwt.getClaimAsString("tid");
        String email = jwt.getClaimAsString("preferred_username");
        String fullName = jwt.getClaimAsString("name");

        UserProfileDTO profile = new UserProfileDTO(
                userId,
                tenantId,
                email,
                fullName);

        return ResponseEntity.ok(profile);
    }
}