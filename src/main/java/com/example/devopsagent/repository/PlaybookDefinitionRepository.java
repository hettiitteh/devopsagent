package com.example.devopsagent.repository;

import com.example.devopsagent.domain.PlaybookDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PlaybookDefinitionRepository extends JpaRepository<PlaybookDefinition, String> {

    List<PlaybookDefinition> findByEnabledTrue();

    List<PlaybookDefinition> findAllByOrderByCreatedAtDesc();

    boolean existsByName(String name);
}
