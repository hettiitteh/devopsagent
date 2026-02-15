package com.example.devopsagent.repository;

import com.example.devopsagent.domain.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, String> {

    List<RiskAssessment> findByStatusOrderByCreatedAtDesc(RiskAssessment.Status status);

    List<RiskAssessment> findAllByOrderByCreatedAtDesc();

    List<RiskAssessment> findByServiceOrderByCreatedAtDesc(String service);

    long countByStatus(RiskAssessment.Status status);

    /** Check for duplicate risk by title and service */
    boolean existsByRiskTitleAndServiceAndStatus(String riskTitle, String service, RiskAssessment.Status status);
}
