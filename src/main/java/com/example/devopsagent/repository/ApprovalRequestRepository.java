package com.example.devopsagent.repository;

import com.example.devopsagent.domain.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    List<ApprovalRequest> findByStatus(ApprovalRequest.ApprovalStatus status);

    List<ApprovalRequest> findBySessionId(String sessionId);

    List<ApprovalRequest> findBySessionIdAndStatus(String sessionId, ApprovalRequest.ApprovalStatus status);
}
