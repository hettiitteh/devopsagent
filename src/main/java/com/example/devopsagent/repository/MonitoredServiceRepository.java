package com.example.devopsagent.repository;

import com.example.devopsagent.domain.MonitoredService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, String> {

    Optional<MonitoredService> findByName(String name);

    List<MonitoredService> findByEnabled(boolean enabled);

    List<MonitoredService> findByHealthStatus(MonitoredService.HealthStatus status);

    List<MonitoredService> findByNamespace(String namespace);

    List<MonitoredService> findByType(MonitoredService.ServiceType type);
}
