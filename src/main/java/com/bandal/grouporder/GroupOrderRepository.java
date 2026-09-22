package com.bandal.grouporder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GroupOrderRepository extends JpaRepository<GroupOrder, Long> {
    Optional<GroupOrder> findById(Long id);
}
