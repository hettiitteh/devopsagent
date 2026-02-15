package com.example.devopsagent.repository;

import com.example.devopsagent.domain.PlaybookSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlaybookSuggestionRepository extends JpaRepository<PlaybookSuggestion, String> {

    List<PlaybookSuggestion> findByStatus(PlaybookSuggestion.SuggestionStatus status);

    Optional<PlaybookSuggestion> findByToolSequenceAndStatus(String toolSequence,
                                                              PlaybookSuggestion.SuggestionStatus status);

    long countByStatus(PlaybookSuggestion.SuggestionStatus status);
}
