package com.example.devopsagent.repository;

import com.example.devopsagent.domain.PlaybookExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaybookExecutionRepository extends JpaRepository<PlaybookExecution, String> {

    List<PlaybookExecution> findByPlaybookId(String playbookId);

    List<PlaybookExecution> findByIncidentId(String incidentId);

    List<PlaybookExecution> findByStatus(PlaybookExecution.ExecutionStatus status);
}
