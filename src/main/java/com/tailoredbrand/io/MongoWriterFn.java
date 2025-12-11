package com.tailoredbrand.io;

import com.mongodb.client.*;
import com.tailoredbrand.config.AppConfig;
import org.apache.beam.sdk.transforms.DoFn;
import org.bson.Document;

public class MongoWriterFn extends DoFn<String, Void> {

    private final String mongoUri;
    private final String databaseName;
    private final String collectionName;

    private transient MongoClient mongoClient;
    private transient MongoCollection<Document> collection;

    public MongoWriterFn(String mongoUri, String databaseName, String collectionName) {
        this.mongoUri = mongoUri;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    @Setup
    public void setup() {
        mongoClient = MongoClients.create(mongoUri);
        collection = mongoClient.getDatabase(databaseName).getCollection(collectionName);
    }

    @ProcessElement
    public void processElement(ProcessContext ctx) {
        String message = ctx.element();
        Document doc = Document.parse(message);
        collection.insertOne(doc);
    }

    @Teardown
    public void teardown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
    }
}


