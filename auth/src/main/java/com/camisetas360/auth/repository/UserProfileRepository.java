package com.camisetas360.auth.repository;

import com.camisetas360.auth.model.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserProfileRepository extends JpaRepository<UserProfile, UserProfile.Identity> {
    /** Entra ID: el token es la fuente de verdad, los claims ausentes quedan en null. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_profiles (issuer, subject, user_id, tenant_id, email, name)
            VALUES (:issuer, :subject, :userId, :tenantId, :email, :name)
            ON CONFLICT (issuer, subject) DO UPDATE SET
                user_id = EXCLUDED.user_id, tenant_id = EXCLUDED.tenant_id,
                email = EXCLUDED.email, name = EXCLUDED.name
            """, nativeQuery = true)
    void upsert(@Param("issuer") String issuer, @Param("subject") String subject,
                @Param("userId") String userId, @Param("tenantId") String tenantId,
                @Param("email") String email, @Param("name") String name);

    /** Cognito: email/name dependen de una Lambda o de userInfo; si faltan se conserva lo ya guardado. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO user_profiles (issuer, subject, user_id, tenant_id, email, name)
            VALUES (:issuer, :subject, :userId, :tenantId, :email, :name)
            ON CONFLICT (issuer, subject) DO UPDATE SET
                user_id = EXCLUDED.user_id, tenant_id = EXCLUDED.tenant_id,
                email = COALESCE(EXCLUDED.email, user_profiles.email),
                name = COALESCE(EXCLUDED.name, user_profiles.name)
            """, nativeQuery = true)
    void upsertKeepingContact(@Param("issuer") String issuer, @Param("subject") String subject,
                @Param("userId") String userId, @Param("tenantId") String tenantId,
                @Param("email") String email, @Param("name") String name);
}
