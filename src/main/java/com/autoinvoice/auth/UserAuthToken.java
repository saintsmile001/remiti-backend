package com.autoinvoice.auth;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class UserAuthToken {

    @Id
    private String userId; // Auth0 "sub"

    private String refreshToken;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}