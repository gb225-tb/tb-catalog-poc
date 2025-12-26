# Reactive Spring Boot API with GCP Pub/Sub & Dataflow

## Overview
- Reactive Spring Boot application using **WebFlux**
- Publishes JSON events to **Google Cloud Pub/Sub**
- Consumes messages using **reactive Flux subscriber**
- Runs on **Google Cloud Dataflow (Apache Beam runner)**
- Persists processed data into **MongoDB Atlas**
- Cloud resources secured using **GCP IAM & Service Accounts**

---

## Account & Access Context
- **GCP Infrastructure**
  - Created using **personal Gmail account**
  - Reason: Office email lacked permission for Cloud Storage bucket creation
- **MongoDB Atlas**
  - Created and managed using **office email ID**
- Runtime access is handled using **IAM service accounts**

---

## GCP Project Details
- **Project ID:** `sand-480914`

---

## GCP Services Used
- Google Cloud Pub/Sub
- Google Cloud Dataflow (Apache Beam)
- Google Cloud Storage
- Google Cloud IAM
- Google Cloud Service Accounts

---

## Pub/Sub Configuration
- **Topic**
  ```
  projects/sand-480914/topics/tb-catalog-product-inbound
  ```

---

## Cloud Storage
- **Bucket:** `sand-480914-dataflow-temp`
- **Purpose**
  - Dataflow staging
  - Temporary pipeline execution files

---

## Service Accounts & IAM

### Spring Boot / Apache Beam Application
- **Service Account**
  ```
  tb-catalog-product-srvc-acc@sand-480914.iam.gserviceaccount.com
  ```
- **Roles**
  - Pub/Sub Admin
  - Dataflow Admin
  - Storage Admin
  - BigQuery User (future use)

---

### Dataflow Worker
- **Service Account**
  ```
  dataflow-worker-sa@sand-480914.iam.gserviceaccount.com
  ```
- **Roles**
  - Dataflow Admin
  - Dataflow Developer
  - Service Account User
  - Storage Object Admin

---

## Reactive Spring Boot API

### Features
- Non-blocking REST API
- Reactive Pub/Sub publisher
- Flux-based subscriber
- Backpressure-aware processing

### API Endpoint
```
POST /api/v1/publish
```

- Accepts JSON payload
- Publishes message asynchronously to Pub/Sub

---

## Architecture Diagram

```
+-------------------+
|   Client / UI     |
+-------------------+
          |
          v
+---------------------------+
| Reactive Spring Boot API  |
| (Spring WebFlux)          |
+---------------------------+
          |
          v
+---------------------------+
| Google Cloud Pub/Sub      |
| Topic                     |
+---------------------------+
          |
          v
+---------------------------+
| Pub/Sub Subscription      |
+---------------------------+
          |
          v
+---------------------------+
| Apache Beam Pipeline      |
| (Google Dataflow)         |
+---------------------------+
          |
          v
+---------------------------+
| MongoDB Atlas             |
+---------------------------+
```

---

## End-to-End Data Flow
- Client sends JSON request
- Reactive API publishes message to Pub/Sub
- Pub/Sub triggers Dataflow pipeline
- Apache Beam processes message
- Data stored in MongoDB Atlas

---

## Key Characteristics
- Fully reactive & non-blocking
- Cloud-native and scalable
- Secure IAM-based authentication
- Production-ready design

---

## Error Handling
- Messages acknowledged only on success
- Automatic Pub/Sub retry on failure
- Dead Letter Topic ready (future)

---

## Future Enhancements
- Dead Letter Topics
- BigQuery analytics sink
- OpenTelemetry tracing
- Metrics with Micrometer
