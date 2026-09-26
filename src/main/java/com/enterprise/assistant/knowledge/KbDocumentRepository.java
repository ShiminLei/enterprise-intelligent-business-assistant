package com.enterprise.assistant.knowledge;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KbDocumentRepository extends JpaRepository<KbDocument, Long> {

    Optional<KbDocument> findByName(String name);

    List<KbDocument> findAllByOrderById();
}
