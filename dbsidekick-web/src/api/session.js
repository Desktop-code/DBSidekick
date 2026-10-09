import request from './request'

export function newSession({ datasourceId, dbName } = {}) {
  return request.post('/session/new', { datasourceId, dbName })
}

export function listSessions({ keyword } = {}) {
  return request.get('/session/list', { params: { keyword: keyword || undefined } })
}

export function getSession(id) {
  return request.get(`/session/${id}`)
}

export function renameSession(id, title) {
  return request.put(`/session/${id}/title`, { title })
}

export function deleteSession(id) {
  return request.delete(`/session/${id}`)
}

export function frequentQuestions(datasourceId, limit = 5) {
  return request.get('/session/frequent-questions', {
    params: { datasourceId: datasourceId || undefined, limit }
  })
}
