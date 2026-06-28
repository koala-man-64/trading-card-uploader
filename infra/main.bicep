targetScope = 'resourceGroup'

@allowed([
  'dev'
  'test'
  'prod'
])
param env string = 'dev'
param location string = resourceGroup().location
param namePrefix string = 'tcu'
param tags object = {}
param pythonVersion string = '3.11'
param functionsExtensionVersion string = '~4'
param uploadContainerName string = 'card-uploads'
param maxUploadBytes int = 10485760
param sasTtlMinutes int = 5
param allowedContentTypes array = [
  'image/jpeg'
  'image/heic'
]
param entraTenantId string
param apiClientId string
param apiAppIdUri string = 'api://${apiClientId}'
param allowedAndroidClientIds array = []
param allowedAudiences array = [
  apiAppIdUri
  apiClientId
]
param logRetentionDays int = 30
param appInsightsDailyCapGb int = 1
param githubSmokePrincipalId string = ''

var normalizedPrefix = toLower(replace('${namePrefix}${env}', '-', ''))
var unique = uniqueString(resourceGroup().id, env, namePrefix)
var uploadStorageName = take('${normalizedPrefix}upload${unique}', 24)
var hostStorageName = take('${normalizedPrefix}host${unique}', 24)
var functionAppName = '${namePrefix}-${env}-sas-issuer-${unique}'
var identityName = '${namePrefix}-${env}-sas-issuer-id'
var workspaceName = '${namePrefix}-${env}-logs'
var appInsightsName = '${namePrefix}-${env}-appi'
var planName = '${namePrefix}-${env}-functions-plan'

var storageBlobDataContributorRoleId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  'ba92f5b4-2d11-453d-a403-e96b0029c9fe'
)
var storageBlobDelegatorRoleId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  'db58b8e5-c6ad-4a2a-8342-4190687cbf4a'
)
var storageBlobDataReaderRoleId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '2a2b9908-6ea1-4ae2-8e65-a410df84e7d1'
)
var monitoringReaderRoleId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '43d0d8ad-25c7-4714-9337-8ba259a9fe05'
)

resource identity 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: identityName
  location: location
  tags: tags
}

resource uploadStorage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: uploadStorageName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    allowBlobPublicAccess: false
    allowSharedKeyAccess: false
    supportsHttpsTrafficOnly: true
    minimumTlsVersion: 'TLS1_2'
    accessTier: 'Hot'
    deleteRetentionPolicy: {
      enabled: true
      days: 7
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: 7
    }
  }
}

resource uploadBlobService 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
  name: 'default'
  parent: uploadStorage
  properties: {
    deleteRetentionPolicy: {
      enabled: true
      days: 7
    }
    containerDeleteRetentionPolicy: {
      enabled: true
      days: 7
    }
  }
}

resource uploadContainer 'Microsoft.Storage/storageAccounts/blobServices/containers@2023-05-01' = {
  name: uploadContainerName
  parent: uploadBlobService
  properties: {
    publicAccess: 'None'
  }
}

resource hostStorage 'Microsoft.Storage/storageAccounts@2023-05-01' = {
  name: hostStorageName
  location: location
  tags: tags
  sku: {
    name: 'Standard_LRS'
  }
  kind: 'StorageV2'
  properties: {
    allowBlobPublicAccess: false
    supportsHttpsTrafficOnly: true
    minimumTlsVersion: 'TLS1_2'
  }
}

resource workspace 'Microsoft.OperationalInsights/workspaces@2023-09-01' = {
  name: workspaceName
  location: location
  tags: tags
  properties: {
    sku: {
      name: 'PerGB2018'
    }
    retentionInDays: logRetentionDays
  }
}

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: appInsightsName
  location: location
  kind: 'web'
  tags: tags
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: workspace.id
    IngestionMode: 'LogAnalytics'
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
    RetentionInDays: logRetentionDays
    dailyDataCap: appInsightsDailyCapGb
  }
}

resource plan 'Microsoft.Web/serverfarms@2023-12-01' = {
  name: planName
  location: location
  tags: tags
  sku: {
    name: 'Y1'
    tier: 'Dynamic'
  }
  kind: 'functionapp'
  properties: {
    reserved: true
  }
}

resource functionApp 'Microsoft.Web/sites@2023-12-01' = {
  name: functionAppName
  location: location
  tags: tags
  kind: 'functionapp,linux'
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${identity.id}': {}
    }
  }
  properties: {
    serverFarmId: plan.id
    httpsOnly: true
    siteConfig: {
      linuxFxVersion: 'Python|${pythonVersion}'
      appSettings: [
        {
          name: 'FUNCTIONS_EXTENSION_VERSION'
          value: functionsExtensionVersion
        }
        {
          name: 'FUNCTIONS_WORKER_RUNTIME'
          value: 'python'
        }
        {
          name: 'AzureWebJobsStorage__accountName'
          value: hostStorage.name
        }
        {
          name: 'AzureWebJobsStorage__credential'
          value: 'managedidentity'
        }
        {
          name: 'AzureWebJobsStorage__clientId'
          value: identity.properties.clientId
        }
        {
          name: 'APPINSIGHTS_INSTRUMENTATIONKEY'
          value: appInsights.properties.InstrumentationKey
        }
        {
          name: 'ENVIRONMENT'
          value: env
        }
        {
          name: 'ENTRA_TENANT_ID'
          value: entraTenantId
        }
        {
          name: 'API_CLIENT_ID'
          value: apiClientId
        }
        {
          name: 'API_APP_ID_URI'
          value: apiAppIdUri
        }
        {
          name: 'ALLOWED_AUDIENCES'
          value: join(allowedAudiences, ',')
        }
        {
          name: 'ALLOWED_ANDROID_CLIENT_IDS'
          value: join(allowedAndroidClientIds, ',')
        }
        {
          name: 'REQUIRED_SCOPE'
          value: 'upload.write'
        }
        {
          name: 'UPLOAD_STORAGE_ACCOUNT_URL'
          value: uploadStorage.properties.primaryEndpoints.blob
        }
        {
          name: 'UPLOAD_CONTAINER_NAME'
          value: uploadContainerName
        }
        {
          name: 'MAX_UPLOAD_BYTES'
          value: string(maxUploadBytes)
        }
        {
          name: 'SAS_TTL_MINUTES'
          value: string(sasTtlMinutes)
        }
        {
          name: 'ALLOWED_CONTENT_TYPES'
          value: join(allowedContentTypes, ',')
        }
        {
          name: 'HASH_SALT'
          value: uniqueString(subscription().id, resourceGroup().id, namePrefix, env)
        }
        {
          name: 'SAS_SIGNER_MODE'
          value: 'managed_identity'
        }
        {
          name: 'MANAGED_IDENTITY_CLIENT_ID'
          value: identity.properties.clientId
        }
      ]
    }
  }
}

resource authSettings 'Microsoft.Web/sites/config@2023-12-01' = {
  name: 'authsettingsV2'
  parent: functionApp
  properties: {
    platform: {
      enabled: true
    }
    globalValidation: {
      requireAuthentication: true
      unauthenticatedClientAction: 'Return401'
      excludedPaths: [
        '/api/healthz'
      ]
    }
    identityProviders: {
      azureActiveDirectory: {
        enabled: true
        registration: {
          clientId: apiClientId
          openIdIssuer: 'https://login.microsoftonline.com/${entraTenantId}/v2.0'
        }
        validation: {
          allowedAudiences: allowedAudiences
        }
      }
    }
  }
}

resource hostBlobContributor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(hostStorage.id, identity.id, 'host-blob-contributor')
  scope: hostStorage
  properties: {
    roleDefinitionId: storageBlobDataContributorRoleId
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource uploadDelegator 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(uploadStorage.id, identity.id, 'upload-blob-delegator')
  scope: uploadStorage
  properties: {
    roleDefinitionId: storageBlobDelegatorRoleId
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource uploadContributor 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(uploadContainer.id, identity.id, 'upload-container-contributor')
  scope: uploadContainer
  properties: {
    roleDefinitionId: storageBlobDataContributorRoleId
    principalId: identity.properties.principalId
    principalType: 'ServicePrincipal'
  }
}

resource smokeReader 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(githubSmokePrincipalId) && env != 'prod') {
  name: guid(uploadContainer.id, githubSmokePrincipalId, 'smoke-reader')
  scope: uploadContainer
  properties: {
    roleDefinitionId: storageBlobDataReaderRoleId
    principalId: githubSmokePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource smokeAppInsightsReader 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(githubSmokePrincipalId) && env != 'prod') {
  name: guid(appInsights.id, githubSmokePrincipalId, 'smoke-appinsights-reader')
  scope: appInsights
  properties: {
    roleDefinitionId: monitoringReaderRoleId
    principalId: githubSmokePrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource smokeWorkspaceReader 'Microsoft.Authorization/roleAssignments@2022-04-01' = if (!empty(githubSmokePrincipalId) && env != 'prod') {
  name: guid(workspace.id, githubSmokePrincipalId, 'smoke-workspace-reader')
  scope: workspace
  properties: {
    roleDefinitionId: monitoringReaderRoleId
    principalId: githubSmokePrincipalId
    principalType: 'ServicePrincipal'
  }
}

output functionAppName string = functionApp.name
output functionAppPrincipalId string = identity.properties.principalId
output functionBaseUrl string = 'https://${functionApp.properties.defaultHostName}/api/'
output uploadStorageAccountName string = uploadStorage.name
output uploadContainerName string = uploadContainer.name
output uploadBlobEndpoint string = uploadStorage.properties.primaryEndpoints.blob
output hostStorageAccountName string = hostStorage.name
output appInsightsName string = appInsights.name
output appInsightsResourceId string = appInsights.id
output logAnalyticsWorkspaceId string = workspace.id
output androidApiBaseUrl string = 'https://${functionApp.properties.defaultHostName}/api/'
output androidAuthority string = 'https://login.microsoftonline.com/${entraTenantId}'
output androidApiScope string = '${apiAppIdUri}/upload.write'
