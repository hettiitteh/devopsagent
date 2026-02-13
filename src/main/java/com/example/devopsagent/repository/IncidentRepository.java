package com.example.devopsagent.repository;

import com.example.devopsagent.domain.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface IncidentRepository extends JpaRepository<Incident, String> {

    List<Incident> findByStatus(Incident.IncidentStatus status);

    List<Incident> findByService(String service);

    List<Incident> findBySeverity(Incident.Severity severity);

    List<Incident> findByStatusIn(List<Incident.IncidentStatus> statuses);

    List<Incident> findByServiceAndStatusIn(String service, List<Incident.IncidentStatus> statuses);

    @Query("SELECT i FROM Incident i WHERE i.createdAt >= :since ORDER BY i.createdAt DESC")
    List<Incident> findRecentIncidents(Instant since);

    @Query("SELECT i FROM Incident i WHERE i.status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'MITIGATING') ORDER BY i.severity, i.createdAt DESC")
    List<Incident> findActiveIncidents();

    @Query("SELECT COUNT(i) FROM Incident i WHERE i.service = :service AND i.createdAt >= :since")
    long countIncidentsByServiceSince(String service, Instant since);
}
