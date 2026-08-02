package com.marketplace.api.repository;

import com.marketplace.api.entity.PlatformFeedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformFeedbackRepository extends JpaRepository<PlatformFeedback, Long> {

    /** Admin inbox: user fetched eagerly because the summary shows the email. */
    @EntityGraph(attributePaths = "user")
    Page<PlatformFeedback> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<PlatformFeedback> findByStatusOrderByCreatedAtDescIdDesc(
            PlatformFeedback.Status status, Pageable pageable);
}
