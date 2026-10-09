import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  optimizeDeps: {
    include: ['monaco-editor']
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: false,
        // SSE：关闭代理层缓冲
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // 同源代理不应触发 Spring CORS；去掉浏览器带来的 Origin
            proxyReq.removeHeader('origin')
          })
          proxy.on('proxyRes', (proxyRes, req, res) => {
            const ct = String(proxyRes.headers['content-type'] || '')
            if (ct.includes('text/event-stream')) {
              proxyRes.headers['cache-control'] = 'no-cache, no-transform'
              proxyRes.headers['x-accel-buffering'] = 'no'
              // 压缩会缓冲整段响应；SSE 通常已是 chunked，去掉可能残留的 length
              delete proxyRes.headers['content-encoding']
              delete proxyRes.headers['content-length']
              const originalWrite = res.write.bind(res)
              res.write = (chunk, encoding, cb) => {
                const ok = originalWrite(chunk, encoding, cb)
                if (typeof res.flush === 'function') {
                  try {
                    res.flush()
                  } catch {
                    // ignore
                  }
                }
                return ok
              }
            }
          })
        }
      }
    }
  }
})
