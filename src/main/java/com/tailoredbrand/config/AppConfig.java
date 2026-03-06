package com.tailoredbrand.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class AppConfig {

    private Gcp gcp;
    private MongoDB mongodb;
    private Pipeline pipeline;
    private Csv csv;
    private CommerceApi commerce;

    @Data
    @NoArgsConstructor
    public static class Gcp {
        private String projectId;
        private String region;
        private String tempLocation;
        private String serviceAccount;
        private PubSub pubsub;

        @Data
        @NoArgsConstructor
        public static class PubSub {
            private String inboundTopic;
            private String outboundTopic;
            private String inputSubscription;
        }
    }

    @Data
    @NoArgsConstructor
    public static class MongoDB {
        private String uri;
        private String database;
        private String collection;
        /** Catalog pipeline – Products collection (default: Products) */
        private String productsCollection  = "Products";
        /** Catalog pipeline – Variants collection (default: Variants) */
        private String variantsCollection  = "Variants";
        /** Catalog pipeline – Skus collection (default: Skus) */
        private String skusCollection      = "Skus";
    }

    @Data
    @NoArgsConstructor
    public static class Pipeline {
        private String runner;
        private boolean streaming;
    }

    @Data
    @NoArgsConstructor
    public static class Csv {
        private String inputFile;
    }

    @Data
    @NoArgsConstructor
    public static class CommerceApi {
        // ── Token endpoint ────────────────────────────────────────────────
        /** e.g. https://auth.us-central1.gcp.commercetools.com */
        private String authUrl;
        /** Base64-encoded clientId:clientSecret for the Basic auth header. */
        private String clientCredentials;
        /** OAuth2 scope, e.g. manage_products:data-import */
        private String scope;

        // ── Products API ──────────────────────────────────────────────────
        /** e.g. https://api.us-central1.gcp.commercetools.com */
        private String apiUrl;
        /** CommerceTools project key (path segment after the API base URL). */
        private String projectKey;
        /** Primary product type key (required, or leave blank for auto-detect). */
        private String productTypeKey;

        /**
         * Secondary product type key (optional). When set, records whose division is
         * in {@link #primaryProductTypeDivisions} use the primary type; all others use this one.
         */
        private String secondaryProductTypeKey;

        /**
         * Division values (from the CSV) that are routed to the primary product type.
         * Only consulted when {@link #secondaryProductTypeKey} is also set.
         * Example YAML: primaryProductTypeDivisions: ["10", "20"]
         */
        private List<String> primaryProductTypeDivisions;

        // ── Resilience ────────────────────────────────────────────────────
        private Integer connectTimeoutMs;
        private Integer readTimeoutMs;
        private Integer maxRetries;
        private Long backoffMs;
    }
}
