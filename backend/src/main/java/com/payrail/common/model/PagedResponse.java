package com.payrail.common.model;

import java.util.List;

public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages
) {
    public static <T> PagedResponse<T> of(List<T> items, int page, int size, long totalItems) {
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) totalItems / size);
        return new PagedResponse<>(items, page, size, totalItems, totalPages);
    }
}
