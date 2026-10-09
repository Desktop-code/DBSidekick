<template>
  <el-dialog
    v-model="visible"
    title="开始使用前"
    width="520px"
    align-center
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    :show-close="false"
    class="setup-wizard"
  >
    <p class="lead">
      这三项可以现在填，也可以以后在「配置」里改。留空不影响写 SQL、连数据库。
    </p>

    <div class="field">
      <div class="label">向量模型</div>
      <div class="row">
        <el-input :model-value="modelDir" readonly placeholder="点击右侧选择 model.onnx" />
        <el-button :disabled="busy" @click="onBrowse">选择文件</el-button>
      </div>
      <p v-if="modelMsg" class="msg" :class="modelOk ? 'ok' : 'fail'">{{ modelMsg }}</p>
    </div>

    <div class="field">
      <div class="label">Milvus</div>
      <div class="row milvus">
        <el-input v-model="milvusHost" placeholder="localhost" />
        <el-input-number v-model="milvusPort" :min="1" :max="65535" controls-position="right" />
        <el-button :loading="probing" :disabled="busy" @click="onProbe">测试</el-button>
      </div>
      <p v-if="milvusMsg" class="msg" :class="milvusOk ? 'ok' : 'fail'">{{ milvusMsg }}</p>
    </div>

    <div class="field">
      <div class="label">LLM API Key</div>
      <el-input
        v-model="apiKey"
        type="password"
        show-password
        placeholder="可留空，稍后在配置里填写"
        autocomplete="new-password"
      />
      <p class="quiet">默认地址 https://api.deepseek.com，模型 deepseek-v4-pro。</p>
    </div>

    <template #footer>
      <el-button :disabled="busy" @click="onSkip">跳过，以后再配</el-button>
      <el-button type="primary" :loading="busy" @click="onFinish">完成</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getSettings, updateSettings } from '../api/settings'
import { checkModelDir, pickOnnxModelDir } from '../api/system'
import { probeMilvus } from '../api/text2sql'

const visible = ref(false)
const busy = ref(false)
const probing = ref(false)
const modelDir = ref('')
const modelMsg = ref('')
const modelOk = ref(false)
const milvusHost = ref('localhost')
const milvusPort = ref(19530)
const milvusMsg = ref('')
const milvusOk = ref(false)
const apiKey = ref('')

onMounted(async () => {
  try {
    const s = await getSettings()
    if (s && s.setupCompleted === true) {
      return
    }
    const savedPath = String(s?.['embedding.onnxModelPath'] || '').trim()
    if (savedPath) {
      modelDir.value = savedPath
    }
    milvusHost.value = String(s?.['milvus.host'] || 'localhost')
    const port = Number(s?.['milvus.port'] ?? 19530)
    milvusPort.value = Number.isFinite(port) && port > 0 ? port : 19530
    visible.value = true
  } catch {
    visible.value = false
  }
})

async function onBrowse() {
  modelMsg.value = ''
  try {
    const picked = await pickOnnxModelDir(modelDir.value)
    if (picked?.canceled) return
    modelOk.value = !!picked?.ok
    if (!picked?.ok) {
      modelMsg.value = picked?.message || '请选择 model.onnx'
      return
    }
    modelDir.value = picked.dir
    modelMsg.value = '已选择模型文件'
  } catch {
    // 拦截器已提示
  }
}

async function onProbe() {
  milvusMsg.value = ''
  probing.value = true
  try {
    const res = await probeMilvus({ host: milvusHost.value, port: milvusPort.value })
    milvusOk.value = !!res?.success
    milvusMsg.value = res?.success ? '可以连通' : (res?.message || '无法连接')
  } catch (err) {
    milvusOk.value = false
    milvusMsg.value = err?.message || '无法连接'
  } finally {
    probing.value = false
  }
}

async function onSkip() {
  busy.value = true
  try {
    await updateSettings({ 'setup.completed': 'true' })
    visible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    busy.value = false
  }
}

async function onFinish() {
  const path = modelDir.value.trim()
  modelMsg.value = ''
  if (path) {
    try {
      const check = await checkModelDir(path)
      modelOk.value = !!check?.ok
      if (!check?.ok) {
        modelMsg.value = check?.message || '目录中需要同时有 model.onnx 和 tokenizer.json'
        return
      }
    } catch {
      return
    }
  }
  const payload = {
    'setup.completed': 'true',
    'milvus.host': (milvusHost.value || 'localhost').trim() || 'localhost',
    'milvus.port': String(milvusPort.value || 19530)
  }
  if (path) {
    payload['embedding.onnxModelPath'] = path
  }
  if (apiKey.value.trim()) {
    payload['llm.apiKey'] = apiKey.value.trim()
  }
  busy.value = true
  try {
    const resp = await updateSettings(payload)
    visible.value = false
    if (resp?.milvusError) {
      ElMessage.warning('已保存。Milvus 这次没连上，可稍后在配置里再试')
    } else {
      ElMessage.success('已保存，之后可在配置里修改')
    }
  } catch {
    // 拦截器已提示，向导保持打开
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.lead {
  margin: 0 0 16px;
  color: #606266;
  line-height: 1.6;
  font-size: 13px;
}
.field {
  margin-bottom: 16px;
}
.label {
  margin-bottom: 6px;
  font-size: 13px;
  color: #303133;
}
.row {
  display: flex;
  gap: 8px;
}
.row .el-input {
  flex: 1;
}
.milvus .el-input-number {
  width: 130px;
}
.msg {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.4;
}
.msg.ok {
  color: #1f8a4c;
}
.msg.fail {
  color: #c45656;
}
.quiet {
  margin: 6px 0 0;
  font-size: 12px;
  color: #909399;
}
</style>
