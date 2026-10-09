import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

const request = axios.create({
  baseURL: '/api',
  timeout: 60000
})

const SPECIAL_CODES = new Set(['DB_CONNECT_FAILED', 'MILVUS_UNAVAILABLE'])

request.interceptors.response.use(
  (res) => res.data,
  (err) => {
    const data = err?.response?.data
    const code = data?.code || ''
    const msg =
      data?.error ||
      data?.message ||
      (typeof data === 'string' ? data : null) ||
      err?.message ||
      '请求失败'

    // 避免短时间内同一错误刷屏；交给调用方再决定是否重复提示
    const enriched = Object.assign(new Error(msg), {
      code,
      status: err?.response?.status,
      response: err?.response,
      __sidekickHandled: false
    })

    if (SPECIAL_CODES.has(code)) {
      // 特殊错误：弹对话框（异步，不阻塞 reject）
      ElMessageBox.alert(msg, '连接异常', {
        type: 'warning',
        confirmButtonText: '知道了',
        distinguishCancelAndClose: true
      }).catch(() => {})
      enriched.__sidekickHandled = true
    } else if (err?.response) {
      ElMessage.error(msg)
      enriched.__sidekickHandled = true
    }

    return Promise.reject(enriched)
  }
)

export default request
