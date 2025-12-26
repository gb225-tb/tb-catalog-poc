# GCP Account & Access Context

This document describes the Google Cloud Platform (GCP) account setup, access context, IAM configuration, and end-to-end data flow used in this project.

---

## Overview

While setting up the GCP environment, **Cloud Storage bucket creation could not be completed using the office email ID** due to permission constraints.  
As a result, a **personal Gmail account** was used to create the GCP project and associated infrastructure.

- **GCP Project & Infrastructure** → Created using **personal Gmail account**
- **MongoDB Atlas** → Successfully created and configured using **office email ID**

This separation was required only due to GCP permission limitations and does not impact runtime integration.

---

## GCP Services Configured

- Google Cloud Pub/Sub  
- Google Cloud Service Accounts  
- Google Cloud Storage (Bucket)  
- Identity and Access Management (IAM)  
- Google Cloud Dataflow (Apache Beam Runner)

---

## Project Details

| Item | Value |
|-----|------|
| **Project ID** | `sand-480914` |

---

## Pub/Sub Configuration

### Topic Created

```
projects/sand-480914/topics/tb-catalog-product-inbound
```

---

## Service Accounts & IAM Role Assignments

### 1. Apache Beam / Spring Boot Application Service Account

**Service Account**
```
tb-catalog-product-srvc-acc@sand-480914.iam.gserviceaccount.com
```

**IAM Roles Assigned**

- BigQuery User  
- Pub/Sub Admin  
- Dataflow Admin  
- Storage Admin  

**Purpose**

This service account is used by the **Spring Boot Apache Beam application** for authentication and resource access.

---

### 2. Dataflow Worker Service Account

**Service Account**
```
dataflow-worker-sa@sand-480914.iam.gserviceaccount.com
```

**IAM Roles Assigned**

- Dataflow Admin  
- Dataflow Developer  
- Service Account User  
- Storage Object Admin  

**Purpose**

This account is used internally by **Google Cloud Dataflow workers** during pipeline execution.

---

## Cloud Storage Bucket

| Item | Value |
|----|-----|
| **Bucket Name** | `sand-480914-dataflow-temp` |
| **Purpose** | Temporary storage for Apache Beam / Dataflow job execution |

**Note:**  
This bucket could not be created using the office email ID and was therefore created under the personal GCP account.

---

## End-to-End Data Flow

1. Messages are published to Google Cloud Pub/Sub  
2. Apache Beam pipeline (Spring Boot application) consumes the messages  
3. The pipeline runs on Google Cloud Dataflow  
4. Processed data is inserted into MongoDB Atlas  

```
Pub/Sub → Apache Beam (Spring Boot) → Dataflow → MongoDB Atlas
```

---

## Notes & Constraints

- GCP resources are owned by a **personal Gmail account** due to office email permission restrictions.
- MongoDB Atlas is owned and managed under the **office email ID**.
- Runtime access is controlled using **service accounts and IAM roles**.

---

## Future Enhancements

- Enable BigQuery sinks for analytics  
- Add Dead Letter Topics (DLT) for Pub/Sub failures  
- Integrate Cloud Monitoring & Logging
