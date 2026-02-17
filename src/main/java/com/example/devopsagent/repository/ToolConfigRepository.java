package com.example.devopsagent.repository;

import com.example.devopsagent.domain.ToolConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolConfigRepository extends JpaRepository<ToolConfig, String> {

    List<ToolConfig> findByEnabled(boolean enabled);

    List<ToolConfig> findByCategory(String category);

    List<ToolConfig> findByApprovalRequired(boolean approvalRequired);
}
