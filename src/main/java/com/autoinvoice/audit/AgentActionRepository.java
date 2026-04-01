package com.autoinvoice.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AgentActionRepository extends JpaRepository<AgentAction, UUID> {
    List<AgentAction> findByUserIdOrderByCreatedAtDesc(String userId);
    List<AgentAction> findByInvoiceId(UUID invoiceId);
}
