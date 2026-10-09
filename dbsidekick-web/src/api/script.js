import request from './request'

export function listScripts({ keyword, onlyFav } = {}) {
  return request.get('/script/list', {
    params: {
      keyword: keyword || undefined,
      onlyFav: onlyFav === true || onlyFav === false ? onlyFav : undefined
    }
  })
}

export function getScript(id) {
  return request.get(`/script/${id}`)
}

export function saveScript(detail) {
  return request.post('/script/save', detail)
}

export function deleteScript(id) {
  return request.delete(`/script/${id}`)
}

export function toggleScriptFavorite(id) {
  return request.put(`/script/${id}/favorite`)
}
