param env string = 'dev'
param location string = resourceGroup().location

// TODO: storage + function + RBAC
output envName string = env
