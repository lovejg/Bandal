package com.bandal.participation.dto;

import com.bandal.participation.OrderItem;

public record OrderItemResponse(
        Long id,
        Long participationId,
        String menuName,
        String options,
        long unitPrice,
        int quantity,
        long amount
) {

    public static OrderItemResponse of(OrderItem orderItem) {
        return new OrderItemResponse(
                orderItem.getId(),
                orderItem.getParticipation().getId(),
                orderItem.getMenuName(),
                orderItem.getOptions(),
                orderItem.getUnitPrice(),
                orderItem.getQuantity(),
                orderItem.getAmount()
        );
    }
}
