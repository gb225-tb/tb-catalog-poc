package com.tailoredbrand.importer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable result record produced by both import pipelines for every
 * entity (product or business unit) processed from an uploaded CSV.
 *
 * <ul>
 *   <li>{@code key}       – the CT resource key from the CSV row</li>
 *   <li>{@code operation} – {@code "create"}, {@code "skip"} (already exists),
 *                           or {@code "error"}</li>
 *   <li>{@code success}   – {@code true} for create / skip; {@code false} for error</li>
 *   <li>{@code statusCode}– HTTP status from the CT API response (0 when not applicable)</li>
 *   <li>{@code message}   – human-readable detail (null on success)</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportResult(
        String  key,
        String  operation,
        boolean success,
        int     statusCode,
        String  message
) {

    public static ImportResult created(String key) {
        return new ImportResult(key, "create", true, 201, null);
    }

    public static ImportResult skipped(String key) {
        return new ImportResult(key, "skip", true, 200, null);
    }

    public static ImportResult deleted(String key) {
        return new ImportResult(key, "delete", true, 200, null);
    }

    public static ImportResult updated(String key) {
        return new ImportResult(key, "update", true, 200, null);
    }

    public static ImportResult failure(String key, int statusCode, String message) {
        return new ImportResult(key, "error", false, statusCode, message);
    }
}
