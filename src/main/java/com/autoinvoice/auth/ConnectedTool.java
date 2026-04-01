package com.autoinvoice.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "connected_tools", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"user_id", "tool_name"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectedTool {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "vault_ref", nullable = false)
    private String vaultRef;

    @Column(name = "encrypted_api_key")
    private String encryptedApiKey;

    @Column(name = "connected_at")
    private LocalDateTime connectedAt = LocalDateTime.now();

    @Column(name = "is_active")
    private Boolean isActive = true;
}
