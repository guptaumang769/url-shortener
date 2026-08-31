package com.umang.urlshortener.repository;

import com.umang.urlshortener.model.entity.UrlMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    // Keyset pagination: seek on the indexed (created_by, id) instead of OFFSET, so deep
    // pages stay O(pageSize) rather than degrading as the offset grows.
    @Query("SELECT u FROM UrlMapping u WHERE u.createdBy = :user AND u.id < :afterId "
            + "ORDER BY u.id DESC")
    List<UrlMapping> findPageByUser(@Param("user") String user,
                                    @Param("afterId") Long afterId,
                                    Limit limit);

    // Single atomic UPDATE avoids the read-modify-write lost-update race on concurrent clicks.
    @Transactional
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + 1 WHERE u.id = :id")
    void incrementClickCount(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + 1 WHERE u.shortCode = :code")
    void incrementClickCountByShortCode(@Param("code") String code);
}
