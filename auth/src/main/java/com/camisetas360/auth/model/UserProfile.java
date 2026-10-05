package com.camisetas360.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "user_profiles")
@IdClass(UserProfile.Identity.class)
public class UserProfile {
    @Id @Column(nullable = false, columnDefinition = "text")
    private String issuer;
    @Id @Column(nullable = false, columnDefinition = "text")
    private String subject;
    @Column(name = "user_id", columnDefinition = "text")
    private String userId;
    @Column(name = "tenant_id", columnDefinition = "text")
    private String tenantId;
    @Column(columnDefinition = "text")
    private String email;
    @Column(columnDefinition = "text")
    private String name;

    protected UserProfile() { }
    public String getIssuer() { return issuer; }
    public String getSubject() { return subject; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getEmail() { return email; }
    public String getName() { return name; }

    public static class Identity implements Serializable {
        private static final long serialVersionUID = 1L;
        public String issuer;
        public String subject;
        public Identity() { }
        public Identity(String issuer, String subject) {
            this.issuer = issuer;
            this.subject = subject;
        }
        @Override public boolean equals(Object other) {
            return other instanceof Identity identity
                    && Objects.equals(issuer, identity.issuer) && Objects.equals(subject, identity.subject);
        }
        @Override public int hashCode() { return Objects.hash(issuer, subject); }
    }
}
