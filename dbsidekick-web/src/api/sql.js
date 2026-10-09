import request from './request'

export function executeSql({ datasourceId, sql, database, signal } = {}) {
  return request.post('/sql/execute', { datasourceId, sql, database }, { signal, timeout: 120000 })
}

export function explainPlan({ datasourceId, sql, database }) {
  return request.post('/sql/explain-plan', { datasourceId, sql, database }, { timeout: 60000 })
}
