package com.bandal.pickupspot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PickupSpotRepository extends JpaRepository<PickupSpot, Long> {
    Optional<PickupSpot> findById(Long id);
}
