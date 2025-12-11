package com.tailoredbrand;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.ServerApi;
import com.mongodb.ServerApiVersion;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

public class MongoClientConnectionExample {
    public static void main(String[] args) {
        String connectionString = "mongodb+srv://tb_gb225_db_user:7Ao2rXNvNJ9flhG7@tb-menswearclub.535gxzn.mongodb.net/?appName=tb-menswearclub";

        ServerApi serverApi = ServerApi.builder()
                .version(ServerApiVersion.V1)
                .build();

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(connectionString))
                .serverApi(serverApi)
                .build();

        // Create a new client and connect to the server
        try (MongoClient mongoClient = MongoClients.create(settings)) {
            try {
                // Send a ping to confirm a successful connection
                MongoDatabase database = mongoClient.getDatabase("catalog_db");
                // Get collection
                MongoCollection<Document> collection = database.getCollection("products");

                // Retrieve a single document (e.g., the first one)
                Document foundDoc = collection.find().first();

                if (foundDoc != null) {
                    System.out.println("Retrieved Document:");
                    System.out.println(foundDoc.toJson());
                } else {
                    System.out.println("No document found in the collection.");
                }
            } catch (MongoException e) {
                e.printStackTrace();
            }
        }
    }
}
