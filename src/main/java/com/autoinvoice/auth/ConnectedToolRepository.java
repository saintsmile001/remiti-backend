package com.autoinvoice.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConnectedToolRepository extends JpaRepository<ConnectedTool, UUID> {
    Optional<ConnectedTool> findByUserIdAndToolNameAndIsActiveTrue(String userId, String toolName);
    Optional<ConnectedTool> findByUserIdAndToolName(String userId, String toolName);

    List<ConnectedTool> findByUserIdAndIsActiveTrue(String userId);

    List<ConnectedTool> findByToolNameAndIsActiveTrue(String toolName);
}
