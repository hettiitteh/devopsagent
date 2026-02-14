package com.example.devopsagent.repository;

import com.example.devopsagent.domain.ResolutionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResolutionRecordRepository extends JpaRepository<ResolutionRecord, String> {

    List<ResolutionRecord> findByServiceAndSuccessOrderByCreatedAtDesc(String service, boolean success);

    List<ResolutionRecord> findByServiceOrderByCreatedAtDesc(String service);

    List<ResolutionRecord> findByIncidentId(String incidentId);

    @Query("SELECT r FROM ResolutionRecord r WHERE r.service = :service AND r.success = true " +
           "ORDER BY r.createdAt DESC")
    List<ResolutionRecord> findSuccessfulByService(String service);

    @Query("SELECT COUNT(r) FROM ResolutionRecord r WHERE r.service = :service AND r.success = true")
    long countSuccessfulByService(String service);

    @Query("SELECT COUNT(r) FROM ResolutionRecord r WHERE r.service = :service")
    long countByService(String service);
}
