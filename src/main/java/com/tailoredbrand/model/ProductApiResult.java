package com.tailoredbrand.model;

import lombok.Builder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.coders.SerializableCoder;

import java.io.Serializable;

@DefaultCoder(SerializableCoder.class)
@Builder
public record ProductApiResult(
        String itemCode,
        String operation,
        boolean success,
        int statusCode,
        String message
) implements Serializable {

    public static ProductApiResult ofSuccess(String itemCode, String operation, int statusCode) {
        return new ProductApiResult(itemCode, operation, true, statusCode, operation + "d");
    }

    public static ProductApiResult ofFailure(String itemCode, String operation, int statusCode, String message) {
        return new ProductApiResult(itemCode, operation, false, statusCode, message);
    }
}
