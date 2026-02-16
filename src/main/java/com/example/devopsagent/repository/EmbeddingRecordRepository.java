package com.example.devopsagent.repository;

import com.example.devopsagent.domain.EmbeddingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmbeddingRecordRepository extends JpaRepository<EmbeddingRecord, String> {

    Optional<EmbeddingRecord> findByEntityTypeAndEntityId(String entityType, String entityId);

    List<EmbeddingRecord> findAllByEntityType(String entityType);

    void deleteByEntityTypeAndEntityId(String entityType, String entityId);

    long countByEntityType(String entityType);
}
