import request from './request'

export function getLogPath() {
  return request.get('/system/log-path')
}

export function openLogDir() {
  return request.post('/system/open-log-dir')
}

export function getDataPath() {
  return request.get('/system/data-path')
}

export function chooseSystemFile(body) {
  return request.post('/system/choose-file', body, { timeout: 0 })
}

export function chooseDirectory(body) {
  return request.post('/system/choose-dir', body || {}, { timeout: 0 })
}

export function checkModelDir(path) {
  return request.post('/system/check-model-dir', { path })
}

/** 弹出系统文件框选择 model.onnx，返回它所在目录。 */
export async function pickOnnxModelDir(initialDir) {
  const picked = await chooseSystemFile({
    title: '选择 model.onnx',
    mode: 'open',
    extensions: 'onnx',
    initialDir: initialDir || ''
  })
  if (!picked || picked.canceled || !picked.path) {
    return { canceled: true }
  }
  const fileName = String(picked.path).split(/[/\\]/).pop() || ''
  if (fileName.toLowerCase() !== 'model.onnx') {
    return { canceled: false, ok: false, message: '请选择名为 model.onnx 的文件' }
  }
  const dir = String(picked.path).replace(/[/\\][^/\\]+$/, '')
  const check = await checkModelDir(dir)
  if (!check?.ok) {
    return { canceled: false, ok: false, message: check?.message || '同一文件夹里还需要 tokenizer.json' }
  }
  return { canceled: false, ok: true, dir }
}

export function backupData(targetPath) {
  return request.post('/system/backup', { targetPath }, { timeout: 120000 })
}

export function restoreData(sourcePath) {
  return request.post('/system/restore', { sourcePath }, { timeout: 120000 })
}

export function resetData() {
  return request.post('/system/reset')
}

export function openDataDir() {
  return request.post('/system/open-data-dir')
}
