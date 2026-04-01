package com.autoinvoice.invoice;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {
    List<Invoice> findByUserIdOrderByCreatedAtDesc(String userId);
    List<Invoice> findByUserIdAndStatus(String userId, InvoiceStatus status);
    Optional<Invoice> findBySourceEmailId(String sourceEmailId);
    
    List<Invoice> findByStatusAndDueDateBefore(InvoiceStatus status, java.time.LocalDate dueDate);
}
