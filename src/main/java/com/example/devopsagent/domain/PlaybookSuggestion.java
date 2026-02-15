package com.example.devopsagent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * AI-suggested playbook derived from learned resolution patterns.
 * When the learning system detects recurring successful tool sequences
 * that don't already have a playbook, it creates a suggestion for human review.
 */
@Entity
@Table(name = "playbook_suggestions", indexes = {
        @Index(name = "idx_suggestion_status", columnList = "status"),
        @Index(name = "idx_suggestion_tool_seq", columnList = "tool_sequence")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /** Suggested name for the playbook */
    private String name;

    /** Auto-generated description */
    @Column(length = 2048)
    private String description;

    /** JSON array of the tool sequence */
    @Column(name = "tool_sequence", length = 4096)
    private String toolSequence;

    /** Primary service this pattern was observed for */
    private String service;

    /** How many times this exact pattern successfully resolved incidents */
    private int frequency;

    /** Average resolution time in milliseconds */
    @Column(name = "avg_resolution_ms")
    private long avgResolutionMs;

    @Enumerated(EnumType.STRING)
    private SuggestionStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    public enum SuggestionStatus {
        SUGGESTED, APPROVED, DISMISSED
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = SuggestionStatus.SUGGESTED;
    }
}
