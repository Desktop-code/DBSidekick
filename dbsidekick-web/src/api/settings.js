import request from './request'

export function getSettings() {
  return request.get('/settings')
}

export function updateSettings(map) {
  return request.post('/settings/update', map)
}

export function openDataDir() {
  return request.post('/settings/open-data-dir')
}

export function testLlm(payload) {
  return request.post('/ai/llm/test', payload)
}

export function testEmbedding(payload) {
  return request.post('/ai/embedding/test', payload || {})
}
