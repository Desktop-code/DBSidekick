import request from './request'

export function listDatasources() {
  return request.get('/datasource/list')
}

export function addDatasource(config) {
  return request.post('/datasource/add', config)
}

export function updateDatasource(config) {
  return request.post('/datasource/update', config)
}

export function removeDatasource(id) {
  return request.delete(`/datasource/${id}`)
}

export function deleteCheckDatasource(id) {
  return request.get(`/datasource/${id}/delete-check`)
}

export function testDatasource(config) {
  return request.post('/datasource/test', config)
}
