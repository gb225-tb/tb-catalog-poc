package com.tailoredbrand.config;

public class AppConfig {

    public Gcp gcp;
    public MongoDB mongodb;
    public Pipeline pipeline;


    public static class Gcp {
        public String projectId;
        public String region;

        public String tempLocation;
        public String serviceAccount;
        public PubSub pubsub;

        public static class PubSub {
            public String inboundTopic;
            public String outboundTopic;
            public String inputSubscription;
        }
    }

    public static class MongoDB {
        public String uri;
        public String database;
        public String collection;
    }

    public static class Pipeline {
        public String runner;
        public boolean streaming;
    }
}

