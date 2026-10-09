import request from './request'

export function syncSchema(datasourceId, database) {
  return request.post(`/schema/sync/${datasourceId}`, null, {
    timeout: 120000,
    params: database ? { database } : undefined
  })
}

export function listDatabases(datasourceId) {
  return request.get(`/schema/databases/${datasourceId}`)
}

export function listTables(datasourceId, database) {
  return request.get(`/schema/tables/${datasourceId}`, {
    params: database ? { database } : undefined
  })
}

export function indexSchema(datasourceId, database) {
  return request.post(`/schema/index/${datasourceId}`, null, {
    timeout: 180000,
    params: database ? { database } : undefined
  })
}

export function generateAliases(datasourceId, overwrite = false) {
  return request.post(`/schema/aliases/generate/${datasourceId}`, null, {
    params: { overwrite },
    timeout: 600000
  })
}

export function inferRelations(datasourceId, database) {
  return request.post(`/schema/infer-relations/${datasourceId}`, null, {
    timeout: 60000,
    params: database ? { database } : undefined
  })
}
