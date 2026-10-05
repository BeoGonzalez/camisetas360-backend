package com.camisetas360.auth.controller;

import com.camisetas360.auth.dtos.UserProfileDTO;
import com.camisetas360.auth.service.UserProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserProfileService profiles;

    public AuthController(UserProfileService profiles) {
        this.profiles = profiles;
    }

    @GetMapping("/profile")
    public ResponseEntity<UserProfileDTO> getAuthenticatedUserProfile(
            @AuthenticationPrincipal Jwt jwt) {

        return ResponseEntity.ok(profiles.synchronize(jwt));
    }
}
