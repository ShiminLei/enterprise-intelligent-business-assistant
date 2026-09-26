package com.enterprise.assistant.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    @Override
    @EntityGraph(attributePaths = {"project", "assignee"})
    List<Task> findAll(Specification<Task> spec);

    @EntityGraph(attributePaths = {"project", "project.manager", "assignee"})
    Optional<Task> findByCodeIgnoreCase(String code);

    List<Task> findByProjectId(Long projectId);

    /** 当前最大的任务序号（任务编号形如 T-131）。 */
    @Query(value = "SELECT COALESCE(MAX(CAST(substring(code FROM 3) AS integer)), 100) FROM task WHERE code ~ '^T-[0-9]+$'",
            nativeQuery = true)
    int maxTaskNumber();
}
