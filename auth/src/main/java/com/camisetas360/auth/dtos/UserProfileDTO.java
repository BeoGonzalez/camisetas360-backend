package com.camisetas360.auth.dtos;

public record UserProfileDTO(
        String userId,
        String tenantId,
        String email,
        String name) {
}