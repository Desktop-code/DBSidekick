import request from './request'

export function listRelations(params) {
  return request.get('/relation/list', { params })
}

export function confirmRelation(id) {
  return request.put(`/relation/${id}/confirm`)
}

export function rejectRelation(id) {
  return request.put(`/relation/${id}/reject`)
}

export function resetRelation(id) {
  return request.put(`/relation/${id}/reset`)
}

export function deleteRelation(id) {
  return request.delete(`/relation/${id}`)
}

export function getRelationConfig() {
  return request.get('/relation/config')
}

export function updateRelationConfig(body) {
  return request.post('/relation/config/update', body)
}
