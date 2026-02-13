package com.example.devopsagent.repository;

import com.example.devopsagent.domain.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlertRuleRepository extends JpaRepository<AlertRule, String> {

    List<AlertRule> findByEnabled(boolean enabled);

    List<AlertRule> findByServiceName(String serviceName);

    List<AlertRule> findByMetric(String metric);
}
