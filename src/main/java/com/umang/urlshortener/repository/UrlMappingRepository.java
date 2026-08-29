package com.umang.urlshortener.repository;

import com.umang.urlshortener.model.entity.UrlMapping;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Keyset (cursor) pagination: fetch the next page of a user's URLs whose id is below
     * the last id seen. Unlike OFFSET pagination, this stays O(pageSize) no matter how
     * deep you scroll, because it seeks on the indexed id instead of counting+skipping rows.
     */
    @Query("SELECT u FROM UrlMapping u WHERE u.createdBy = :user AND u.id < :afterId "
            + "ORDER BY u.id DESC")
    List<UrlMapping> findPageByUser(@Param("user") String user,
                                    @Param("afterId") Long afterId,
                                    Limit limit);

    /** Fire-and-forget click increment; done as a single atomic UPDATE to avoid lost updates. */
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + 1 WHERE u.id = :id")
    void incrementClickCount(@Param("id") Long id);
}
