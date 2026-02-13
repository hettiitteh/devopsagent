package com.example.devopsagent.controller;

import com.example.devopsagent.domain.AlertRule;
import com.example.devopsagent.repository.AlertRuleRepository;
import com.example.devopsagent.security.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Alert Rules and Security Audit REST API Controller.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AlertController {

    private final AlertRuleRepository alertRuleRepository;
    private final SecurityAuditService securityAuditService;

    // Alert Rule endpoints
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertRule>> listAlertRules() {
        return ResponseEntity.ok(alertRuleRepository.findAll());
    }

    @PostMapping("/alerts")
    public ResponseEntity<AlertRule> createAlertRule(@RequestBody AlertRule rule) {
        rule.setEnabled(true);
        return ResponseEntity.ok(alertRuleRepository.save(rule));
    }

    @GetMapping("/alerts/{id}")
    public ResponseEntity<AlertRule> getAlertRule(@PathVariable String id) {
        return alertRuleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/alerts/{id}")
    public ResponseEntity<Map<String, String>> deleteAlertRule(@PathVariable String id) {
        alertRuleRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    // Security Audit endpoints
    @GetMapping("/security/audit")
    public ResponseEntity<Map<String, Object>> runSecurityAudit() {
        var findings = securityAuditService.runAudit();
        return ResponseEntity.ok(Map.of(
                "findings", findings.stream().map(SecurityAuditService.AuditFinding::toMap).collect(Collectors.toList()),
                "total", findings.size()
        ));
    }

    @GetMapping("/security/findings")
    public ResponseEntity<List<Map<String, String>>> getSecurityFindings() {
        return ResponseEntity.ok(
                securityAuditService.getFindings().stream()
                        .map(SecurityAuditService.AuditFinding::toMap)
                        .collect(Collectors.toList()));
    }
}
