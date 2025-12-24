package com.tailoredbrand.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.time.Instant;

public class GsonConfig {

    public static Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapter(
                        Instant.class,
                        (com.google.gson.JsonSerializer<Instant>)
                                (src, type, ctx) ->
                                        new com.google.gson.JsonPrimitive(src.toString())
                )
                .create();
    }
}

