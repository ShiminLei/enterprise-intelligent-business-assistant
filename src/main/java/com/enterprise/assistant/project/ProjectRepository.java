package com.enterprise.assistant.project;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @EntityGraph(attributePaths = "manager")
    List<Project> findAllByOrderByCode();

    @EntityGraph(attributePaths = "manager")
    Optional<Project> findByCodeIgnoreCase(String code);
}
