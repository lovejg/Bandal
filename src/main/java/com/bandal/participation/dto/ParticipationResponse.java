package com.bandal.participation.dto;

import com.bandal.participation.Participation;

import java.time.Instant;

public record ParticipationResponse(
        Long id,
        Long groupOrderId,
        Long userId,
        Instant joinedAt
) {

    public static ParticipationResponse of(Participation participation) {
        return new ParticipationResponse(
                participation.getId(),
                participation.getGroupOrder().getId(),
                participation.getUser().getId(),
                participation.getJoinedAt()
        );
    }
}
