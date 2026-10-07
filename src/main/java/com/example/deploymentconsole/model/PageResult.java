package com.example.deploymentconsole.model;

import java.util.List;

/** One page of results plus the totals needed to render a pager. {@code page} is 0-based. */
public record PageResult<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResult<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = (int) Math.min(Integer.MAX_VALUE, (totalElements + size - 1) / size);
        return new PageResult<>(List.copyOf(content), page, size, totalElements, totalPages);
    }
}
