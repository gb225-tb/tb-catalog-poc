package com.tailoredbrand.model;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
public final class TailoredBrandEventModels {

    private TailoredBrandEventModels() {
        // prevent instantiation
    }

    // =========================
    // Root Event
    // =========================
    public record TailoredBrandEvent(
            EventMetadata eventMetadata,
            TailoredBrand tailoredBrand,
            ProcessingContext processingContext
    ) {}

    // =========================
    // Event Metadata
    // =========================
    public record EventMetadata(
            String eventId,
            String eventType,
            String eventVersion,
            Instant eventTimestamp,
            String sourceSystem,
            String initiatedBy,
            String environment
    ) {}

    // =========================
    // Tailored Brand
    // =========================
    public record TailoredBrand(
            String tailoredBrandId,
            String enterpriseBrandCode,
            String displayName,
            String legalName,
            String brandCategory,
            String ownershipType,
            String status,
            boolean isExclusive,
            boolean isCustomFitSupported,
            int businessPriority,
            double qualityRating,
            Lifecycle lifecycle,
            List<String> segments,
            List<Market> supportedMarkets,
            FitCapabilities fitCapabilities,
            Compliance compliance,
            Map<String, Localization> localization,
            DigitalAssets digitalAssets,
            OperationalAttributes operationalAttributes
    ) {}

    // =========================
    // Nested Records
    // =========================
    public record Lifecycle(
            String introducedDate,
            String lastReviewedDate,
            boolean sunsetPlanned
    ) {}

    public record Market(
            String country,
            String currency,
            List<String> channels
    ) {}

    public record FitCapabilities(
            boolean supportsMadeToMeasure,
            boolean supportsAlterations,
            List<String> standardFitTypes
    ) {}

    public record Compliance(
            String taxCategory,
            String countryOfOrigin,
            List<String> certifications
    ) {}

    public record Localization(
            String marketingName,
            String tagline
    ) {}

    public record DigitalAssets(
            Logo logo,
            List<BrandAsset> brandGallery
    ) {}

    public record Logo(
            String url,
            String format
    ) {}

    public record BrandAsset(
            String assetType,
            String url,
            String resolution
    ) {}

    public record OperationalAttributes(
            int averageAlterationTimeDays,
            int storeCount,
            double onlineOnlySkuPercentage,
            boolean madeToOrderEnabled
    ) {}

    // =========================
    // Processing Context
    // =========================
    public record ProcessingContext(
            String traceId,
            String correlationId,
            int retryCount,
            boolean isReplay
    ) {}
}

