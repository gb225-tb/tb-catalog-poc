package com.tailoredbrand.commerce;

import lombok.Builder;
import lombok.Value;

import java.io.Serializable;
import java.util.List;

/**
 * Immutable, fully-serializable connection settings for the CommerceTools API.
 * Build with the generated builder:
 * <pre>{@code
 *   CommerceToolsSettings.builder()
 *       .authUrl("https://auth.us-central1.gcp.commercetools.com")
 *       .clientCredentials("<base64(clientId:clientSecret)>")
 *       .scope("manage_products:data-import")
 *       .apiUrl("https://api.us-central1.gcp.commercetools.com")
 *       .projectKey("data-import")
 *       .productTypeKey("apparel")
 *       .build();
 * }</pre>
 */
@Value
@Builder
public class CommerceToolsSettings implements Serializable {

    // ── Auth (token endpoint) ──────────────────────────────────────────────
    /** e.g. https://auth.us-central1.gcp.commercetools.com */
    String authUrl;

    /** Base64-encoded "clientId:clientSecret" used in the Basic auth header. */
    String clientCredentials;

    /** OAuth scope, e.g. manage_products:data-import */
    String scope;

    // ── Product API ────────────────────────────────────────────────────────
    /** e.g. https://api.us-central1.gcp.commercetools.com */
    String apiUrl;

    /** CommerceTools project key (the path segment after the API base URL). */
    String projectKey;

    /**
     * Key (or id) of the <em>primary</em> product type. Required unless the CT project
     * contains exactly one product type (auto-detect fallback).
     */
    String productTypeKey;

    /**
     * Key (or id) of the <em>secondary</em> product type.
     *
     * <p>When set, the pipeline pre-resolves both type references at startup.
     * Records whose {@code division} value is <em>not</em> listed in
     * {@link #primaryProductTypeDivisions} are routed to this type.</p>
     *
     * <p>Leave blank to always use the primary product type.</p>
     */
    String secondaryProductTypeKey;

    /**
     * Comma-separated list of {@code division} values (from the CSV) that should be
     * routed to the <em>primary</em> product type.
     *
     * <p>When {@link #secondaryProductTypeKey} is configured:
     * <ul>
     *   <li>Records whose {@code division} appears in this list → primary type</li>
     *   <li>All other records → secondary type</li>
     * </ul>
     * When this list is empty/null and a secondary key is configured, ALL records use
     * the primary type (secondary is resolved but never selected).
     * </p>
     *
     * <p>Example: {@code ["10", "20"]} routes divisions 10 and 20 to the primary type.</p>
     */
    List<String> primaryProductTypeDivisions;

    // ── Resilience ────────────────────────────────────────────────────────
    @Builder.Default int connectTimeoutMs = 5_000;
    @Builder.Default int readTimeoutMs    = 15_000;
    @Builder.Default int maxRetries       = 3;
    @Builder.Default long backoffMs       = 500L;
}
