# trading-card-uploader

Android app that captures photos and automatically uploads to Azure Blob Storage using a short-lived SAS URL issued by an Azure Functions API.

## Repo layout
- android-app/       Android (Kotlin) application
- api-sas-issuer/    Azure Functions: issues scoped, short-lived upload SAS URLs
- infra/             Bicep templates to provision Azure resources
- docs/              Delivery notes, runbooks, architecture
