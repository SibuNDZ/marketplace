package com.marketplace.api.repository;

import com.marketplace.api.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Pessimistic write lock (SELECT ... FOR UPDATE) for checkout stock
     * decrements. H2 supports FOR UPDATE but its semantics differ from
     * PostgreSQL — concurrency tests MUST use TestContainers PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    /**
     * Batch lock in ascending id order. Consistent lock ordering across all
     * transactions is the deadlock-prevention strategy.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id IN :ids ORDER BY p.id ASC")
    List<Product> findAllByIdForUpdate(@Param("ids") List<Long> ids);

    Page<Product> findByIsActiveTrue(Pageable pageable);

    Optional<Product> findBySku(String sku);
}
