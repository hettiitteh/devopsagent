package com.example.devopsagent.repository;

import com.example.devopsagent.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, String> {

    List<AuditLog> findByActorOrderByTimestampDesc(String actor);

    List<AuditLog> findByActionOrderByTimestampDesc(String action);

    List<AuditLog> findByTargetOrderByTimestampDesc(String target);

    List<AuditLog> findBySessionIdOrderByTimestampDesc(String sessionId);

    List<AuditLog> findByTimestampBetweenOrderByTimestampDesc(Instant start, Instant end);

    @Query("SELECT a FROM AuditLog a ORDER BY a.timestamp DESC")
    Page<AuditLog> findAllPaged(Pageable pageable);

    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:actor IS NULL OR a.actor = :actor) AND " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:target IS NULL OR a.target = :target) " +
           "ORDER BY a.timestamp DESC")
    List<AuditLog> findFiltered(String actor, String action, String target);

    long countByAction(String action);
}
