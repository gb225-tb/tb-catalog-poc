package com.tailoredbrand.importer;

import java.util.List;

/**
 * Aggregated response returned by both import controller endpoints.
 * Wraps the per-entity {@link ImportResult} list with summary counters.
 */
public record ImportSummary(
        int total,
        int succeeded,
        int failed,
        List<ImportResult> results
) {

    public static ImportSummary of(List<ImportResult> results) {
        int succeeded = (int) results.stream().filter(ImportResult::success).count();
        return new ImportSummary(results.size(), succeeded, results.size() - succeeded, results);
    }
}
