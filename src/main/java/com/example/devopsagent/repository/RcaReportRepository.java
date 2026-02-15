package com.example.devopsagent.repository;

import com.example.devopsagent.domain.RcaReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RcaReportRepository extends JpaRepository<RcaReport, String> {

    Optional<RcaReport> findByIncidentId(String incidentId);

    List<RcaReport> findByStatus(RcaReport.RcaStatus status);

    List<RcaReport> findByServiceOrderByGeneratedAtDesc(String service);

    List<RcaReport> findAllByOrderByGeneratedAtDesc();

    @Query("SELECT COUNT(r) FROM RcaReport r WHERE r.status = :status")
    long countByStatus(RcaReport.RcaStatus status);
}
