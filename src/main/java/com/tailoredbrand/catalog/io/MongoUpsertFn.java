package com.tailoredbrand.catalog.io;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.beam.sdk.transforms.DoFn;
import org.bson.Document;

/**
 * Beam {@link DoFn} that upserts a single JSON document into a MongoDB collection.
 *
 * <p>Input is a plain JSON string (produced by {@link org.bson.Document#toJson()}).
 * The document <em>must</em> contain an {@code _id} field; it is used as the
 * filter key for the {@code replaceOne(upsert=true)} call, making the operation
 * fully idempotent.
 *
 * <p>The MongoDB connection is opened once per worker in {@link #setup()} and
 * closed in {@link #teardown()} to maximise connection reuse across elements.
 *
 * <p>Collections are created automatically by MongoDB on the first write if they
 * do not already exist.  A unique index on {@code _id} is always present by default.
 */
@Slf4j
public class MongoUpsertFn extends DoFn<String, Void> {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);

    private final String mongoUri;
    private final String databaseName;
    private final String collectionName;

    private transient MongoClient mongoClient;
    private transient MongoCollection<Document> collection;

    public MongoUpsertFn(String mongoUri, String databaseName, String collectionName) {
        this.mongoUri       = mongoUri;
        this.databaseName   = databaseName;
        this.collectionName = collectionName;
    }

    @Setup
    public void setup() {
        mongoClient = MongoClients.create(mongoUri);
        collection  = mongoClient
                .getDatabase(databaseName)
                .getCollection(collectionName);

        // Ensure a unique sparse index on _id (always present in MongoDB by default,
        // but explicitly logged here so pipeline startup is traceable).
        log.info("[MONGO UPSERT] Connected | db={} collection={}", databaseName, collectionName);
    }

    @ProcessElement
    public void processElement(ProcessContext ctx) {
        String json = ctx.element();
        try {
            Document doc = Document.parse(json);
            Object id    = doc.get("_id");
            if (id == null) {
                log.warn("[MONGO UPSERT] Skipping document with null _id | preview={}",
                        json.length() > 120 ? json.substring(0, 120) + "…" : json);
                return;
            }
            collection.replaceOne(Filters.eq("_id", id), doc, UPSERT);
            log.debug("[MONGO UPSERT] ✓ upserted | collection={} _id={}", collectionName, id);
        } catch (Exception e) {
            log.error("[MONGO UPSERT] ✗ failed | collection={} error={} | preview={}",
                    collectionName, e.getMessage(),
                    json.length() > 120 ? json.substring(0, 120) + "…" : json);
        }
    }

    @Teardown
    public void teardown() {
        if (mongoClient != null) {
            mongoClient.close();
            log.info("[MONGO UPSERT] Connection closed | collection={}", collectionName);
        }
    }
}
