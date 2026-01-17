# Architecture (Draft)

1. Android captures photo -> saves locally
2. Worker requests SAS from API
3. Upload directly to Azure Blob via PUT
4. Mark complete locally
