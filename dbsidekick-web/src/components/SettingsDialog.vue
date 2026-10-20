<template>
  <el-dialog
    :model-value="appStore.settingsDialog.open"
    title="设置"
    width="900px"
    align-center
    append-to-body
    destroy-on-close
    class="settings-dialog"
    modal-class="settings-dialog-mask"
    @close="onClose"
    @update:model-value="(v) => { if (!v) onClose() }"
  >
    <div class="settings-layout">
      <aside class="settings-nav">
        <el-menu
          :key="'settings-nav-' + active"
          :default-active="active"
          class="nav-menu"
          @select="onSelect"
        >
          <el-menu-item
            v-for="item in menuItems"
            :key="item.id"
            :index="item.id"
          >
            {{ item.label }}
          </el-menu-item>
        </el-menu>
      </aside>
      <div class="settings-body" v-loading="loading">
        <!-- LLM -->
        <el-form v-show="active === 'llm'" label-width="120px" size="default" class="form">
          <el-form-item label="提供商">
            <el-select v-model="form.llmProvider" style="width: 280px" @change="onProviderChange">
              <el-option label="DeepSeek" value="DEEPSEEK" />
              <el-option label="OpenAI 兼容" value="OPENAI_COMPATIBLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="API 地址">
            <el-input
              v-model="form.llmBaseUrl"
              :placeholder="openaiCompatible ? 'https://api.openai.com 或 http://localhost:11434/v1' : 'https://api.deepseek.com'"
            />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="form.llmApiKey"
              type="password"
              show-password
              :placeholder="apiKeyPlaceholder"
              autocomplete="new-password"
            />
          </el-form-item>
          <el-form-item label="模型">
            <el-select
              v-if="!openaiCompatible"
              v-model="form.llmModel"
              style="width: 320px"
              placeholder="选择或输入模型"
              filterable
              allow-create
              default-first-option
            >
              <el-option label="deepseek-v4-pro（DeepSeek-V4 专业版）" value="deepseek-v4-pro" />
              <el-option label="deepseek-flash（DeepSeek-V4 极速版，推荐日常查数）" value="deepseek-flash" />
              <el-option label="deepseek-chat（旧版兼容）" value="deepseek-chat" />
            </el-select>
            <el-input
              v-else
              v-model="form.llmModel"
              placeholder="gpt-4o-mini"
              style="width: 320px"
            />
            <p v-if="openaiCompatible" class="field-hint block-hint">
              填写服务商文档中的模型名，如 gpt-4o-mini / qwen-plus / llama3.1
            </p>
            <p v-else class="field-hint block-hint">
              可从列表选择，也可直接输入自定义模型名（如 deepseek-v4.1-pro）
            </p>
          </el-form-item>
          <el-form-item label="温度">
            <el-slider v-model="form.llmTemperature" :min="0" :max="1" :step="0.05" style="width: 280px" show-input />
          </el-form-item>
          <el-form-item>
            <el-button :loading="testingLlm" @click="onTestLlm">测试连接</el-button>
            <el-button type="primary" :loading="saving" @click="onSaveLlm">保存</el-button>
            <span v-if="llmTestMsg" class="hint" :class="llmTestOk ? 'ok' : 'fail'">{{ llmTestMsg }}</span>
          </el-form-item>
        </el-form>

        <!-- Embedding -->
        <el-form v-show="active === 'embedding'" label-width="130px" size="default" class="form">
          <el-form-item label="提供商">
            <el-select v-model="form.embProvider" style="width: 280px">
              <el-option label="ONNX" value="onnx" />
              <el-option label="OLLAMA" value="ollama" />
            </el-select>
          </el-form-item>
          <el-form-item label="向量模型">
            <div class="path-row">
              <el-input
                :model-value="form.embOnnxPath"
                readonly
                placeholder="尚未选择，请点右侧选择 model.onnx"
              />
              <el-button :disabled="saving" @click="onChooseModel">选择文件</el-button>
            </div>
            <div class="field-hint block-hint">选择 model.onnx，同一文件夹里还要有 tokenizer.json</div>
          </el-form-item>
          <el-form-item label="Ollama 地址">
            <el-input v-model="form.embOllamaUrl" />
          </el-form-item>
          <el-form-item label="Ollama 模型名">
            <el-input v-model="form.embOllamaModel" />
          </el-form-item>
          <el-form-item label="向量维度">
            <el-input-number v-model="form.embDimension" :min="1" disabled />
          </el-form-item>
          <el-form-item>
            <el-button :loading="testingEmb" @click="onTestEmb">测试连接</el-button>
            <el-button type="primary" :loading="saving" @click="onSaveEmb">保存</el-button>
            <span v-if="embTestMsg" class="hint" :class="embTestOk ? 'ok' : 'fail'">{{ embTestMsg }}</span>
          </el-form-item>
        </el-form>

        <!-- Milvus -->
        <el-form v-show="active === 'milvus'" label-width="120px" size="default" class="form">
          <el-form-item label="Host">
            <el-input v-model="form.milvusHost" style="width: 280px" />
          </el-form-item>
          <el-form-item label="Port">
            <el-input-number v-model="form.milvusPort" :min="1" :max="65535" />
          </el-form-item>
          <el-form-item label="Collection">
            <el-input v-model="form.milvusCollection" style="width: 280px" />
          </el-form-item>
          <el-form-item label="rowCount">
            <span>{{ milvusRowCount ?? '-' }}</span>
          </el-form-item>
          <el-form-item>
            <el-button :loading="testingMilvus" @click="onTestMilvus">测试连接</el-button>
            <el-button type="primary" :loading="saving" @click="onSaveMilvus">保存</el-button>
            <span v-if="milvusTestMsg" class="hint" :class="milvusTestOk ? 'ok' : 'fail'">{{ milvusTestMsg }}</span>
          </el-form-item>
        </el-form>

        <!-- SQL -->
        <el-form v-show="active === 'sql'" label-width="160px" size="default" class="form">
          <el-form-item label="默认 LIMIT 开关">
            <el-switch v-model="form.sqlLimitEnabled" active-text="开启" inactive-text="关闭" />
          </el-form-item>
          <el-form-item label="默认 LIMIT">
            <el-input-number
              v-model="form.sqlDefaultLimit"
              :min="1"
              :max="10000"
              :disabled="!form.sqlLimitEnabled"
            />
            <span class="field-hint">门禁强制补齐；结果集最大行数同步取此值</span>
          </el-form-item>
          <el-form-item label="脚本最大条数">
            <el-input-number v-model="form.sqlMaxStatements" :min="1" :max="200" />
            <span class="field-hint">建议不超过 200，保存时硬顶 200</span>
          </el-form-item>
          <el-form-item label="查询超时（秒）">
            <el-input-number v-model="form.sqlQueryTimeout" :min="1" :max="600" />
            <span class="field-hint">默认 30s</span>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="onSaveSql">保存</el-button>
          </el-form-item>
        </el-form>

        <!-- Storage -->
        <el-form v-show="active === 'storage'" label-width="120px" size="default" class="form">
          <el-form-item label="数据库路径">
            <el-input :model-value="dbPath" readonly />
          </el-form-item>
          <el-form-item>
            <el-button @click="onOpenDir">打开所在目录</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getSettings,
  openDataDir,
  testEmbedding,
  testLlm,
  updateSettings
} from '../api/settings'
import { milvusHealth } from '../api/text2sql'
import { pickOnnxModelDir } from '../api/system'
import { useAppStore } from '../stores/app'

const appStore = useAppStore()

const menuItems = [
  { id: 'llm', label: 'LLM 配置' },
  { id: 'embedding', label: 'Embedding 配置' },
  { id: 'milvus', label: 'Milvus 配置' },
  { id: 'sql', label: 'SQL 配置' },
  { id: 'storage', label: '本地存储' }
]

const active = ref('llm')
const loading = ref(false)
const saving = ref(false)
const testingLlm = ref(false)
const testingEmb = ref(false)
const testingMilvus = ref(false)
const llmTestMsg = ref('')
const llmTestOk = ref(false)
const embTestMsg = ref('')
const embTestOk = ref(false)
const milvusTestMsg = ref('')
const milvusTestOk = ref(false)
const milvusRowCount = ref(null)
const dbPath = ref('')
const apiKeyConfigured = ref(false)

const form = reactive({
  llmProvider: 'DEEPSEEK',
  llmBaseUrl: 'https://api.deepseek.com',
  llmApiKey: '',
  llmModel: 'deepseek-chat',
  llmTemperature: 0.1,
  embProvider: 'onnx',
  embOnnxPath: '',
  embOllamaUrl: 'http://localhost:11434',
  embOllamaModel: 'bge-base-zh-1.5b',
  embDimension: 768,
  milvusHost: 'localhost',
  milvusPort: 19530,
  milvusCollection: 'schema_chunks',
  sqlLimitEnabled: true,
  sqlDefaultLimit: 100,
  sqlMaxStatements: 20,
  sqlQueryTimeout: 30
})

const openaiCompatible = computed(() => form.llmProvider === 'OPENAI_COMPATIBLE')

const apiKeyPlaceholder = computed(() => {
  if (openaiCompatible.value) {
    return apiKeyConfigured.value
      ? '已配置，留空保存将清除（本地 Ollama 可留空）'
      : '本地服务可留空'
  }
  return apiKeyConfigured.value
    ? '已配置，不修改请留空'
    : '请输入 API Key'
})

function onSelect(index) {
  active.value = index
  appStore.setSettingsSection(index)
}

function onClose() {
  appStore.closeSettingsDialog()
}

function displayModel(provider, raw) {
  const m = String(raw || '').trim()
  if (provider === 'OPENAI_COMPATIBLE') return m
  // 保留预设与自定义模型名（allow-create）；空值回退旧版兼容
  if (m) return m
  return 'deepseek-chat'
}

function onProviderChange(value) {
  if (value === 'OPENAI_COMPATIBLE') {
    if (!form.llmBaseUrl || form.llmBaseUrl === 'https://api.deepseek.com') {
      form.llmBaseUrl = ''
    }
    // 切换到 OpenAI 兼容时清空模型，由用户自由填写
    form.llmModel = ''
    return
  }
  form.llmBaseUrl = 'https://api.deepseek.com'
  if (!form.llmModel) {
    form.llmModel = 'deepseek-chat'
  }
}

async function load() {
  loading.value = true
  try {
    const s = await getSettings()
    form.llmProvider = s['llm.provider'] || 'DEEPSEEK'
    form.llmBaseUrl = s['llm.baseUrl'] || (form.llmProvider === 'OPENAI_COMPATIBLE' ? '' : 'https://api.deepseek.com')
    form.llmApiKey = ''
    form.llmModel = displayModel(form.llmProvider, s['llm.model'])
    form.llmTemperature = Number(s['llm.temperature'] ?? 0.1)
    form.embProvider = (s['embedding.provider'] || 'onnx').toLowerCase()
    form.embOnnxPath = s['embedding.onnxModelPath'] || form.embOnnxPath
    form.embOllamaUrl = s['embedding.ollamaUrl'] || form.embOllamaUrl
    form.embOllamaModel = s['embedding.ollamaModel'] || form.embOllamaModel
    form.embDimension = Number(s['embedding.dimension'] ?? 768)
    form.milvusHost = s['milvus.host'] || 'localhost'
    form.milvusPort = Number(s['milvus.port'] ?? 19530)
    form.milvusCollection = s['milvus.collection'] || 'schema_chunks'
    const limRaw = String(s['sql.limitEnabled'] ?? 'true').trim().toLowerCase()
    form.sqlLimitEnabled = limRaw !== 'false' && limRaw !== '0'
    form.sqlDefaultLimit = Number(s['sql.defaultLimit'] ?? 100)
    form.sqlMaxStatements = Number(s['sql.maxStatements'] ?? 20)
    form.sqlQueryTimeout = Number(s['sql.queryTimeoutSeconds'] ?? 30)
    dbPath.value = s.dbPath || ''
    apiKeyConfigured.value = !!s['llm.apiKeyConfigured']
  } finally {
    loading.value = false
  }
}

async function onSaveLlm() {
  saving.value = true
  try {
    const payload = {
      'llm.provider': form.llmProvider || 'DEEPSEEK',
      'llm.baseUrl': (form.llmBaseUrl || '').trim(),
      'llm.model': (form.llmModel || '').trim(),
      'llm.temperature': String(form.llmTemperature)
    }
    if (form.llmProvider === 'OPENAI_COMPATIBLE') {
      payload['llm.apiKey'] = form.llmApiKey.trim()
    } else if (form.llmApiKey.trim()) {
      payload['llm.apiKey'] = form.llmApiKey.trim()
    }
    await updateSettings(payload)
    ElMessage.success('已保存，配置已生效')
    form.llmApiKey = ''
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onChooseModel() {
  try {
    const picked = await pickOnnxModelDir(form.embOnnxPath)
    if (picked?.canceled) return
    if (!picked?.ok) {
      ElMessage.warning(picked?.message || '模型文件不完整')
      return
    }
    form.embOnnxPath = picked.dir
    ElMessage.success('已选择模型文件')
  } catch {
    // 拦截器已提示
  }
}

async function onSaveEmb() {
  saving.value = true
  try {
    await updateSettings({
      'embedding.provider': form.embProvider,
      'embedding.onnxModelPath': form.embOnnxPath,
      'embedding.ollamaUrl': form.embOllamaUrl,
      'embedding.ollamaModel': form.embOllamaModel,
      'embedding.dimension': String(form.embDimension)
    })
    ElMessage.success('已保存，配置已生效')
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onSaveMilvus() {
  saving.value = true
  try {
    const res = await updateSettings({
      'milvus.host': form.milvusHost,
      'milvus.port': String(form.milvusPort),
      'milvus.collection': form.milvusCollection
    })
    if (res?.milvusReconnected === false) {
      ElMessage.warning(res?.milvusError || 'Milvus 重连失败，请检查配置')
    } else {
      ElMessage.success('已保存，配置已生效')
    }
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onSaveSql() {
  if (form.sqlMaxStatements > 200) {
    ElMessage.warning('脚本最大条数建议不超过 200，将按 200 保存')
    form.sqlMaxStatements = 200
  }
  saving.value = true
  try {
    await updateSettings({
      'sql.limitEnabled': String(!!form.sqlLimitEnabled),
      'sql.defaultLimit': String(form.sqlDefaultLimit),
      'sql.maxStatements': String(form.sqlMaxStatements),
      'sql.queryTimeoutSeconds': String(form.sqlQueryTimeout)
    })
    ElMessage.success('已保存，配置已生效')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function onTestLlm() {
  testingLlm.value = true
  llmTestMsg.value = ''
  try {
    const res = await testLlm({
      provider: form.llmProvider,
      baseUrl: (form.llmBaseUrl || '').trim(),
      apiKey: form.llmApiKey.trim(),
      model: (form.llmModel || '').trim()
    })
    llmTestOk.value = !!res?.success
    llmTestMsg.value = res?.success
      ? `连接成功（${res.latencyMs ?? 0} ms）`
      : res?.message || '连接失败'
  } catch (e) {
    llmTestOk.value = false
    llmTestMsg.value = e?.message || '连接失败'
  } finally {
    testingLlm.value = false
  }
}

async function onTestEmb() {
  testingEmb.value = true
  embTestMsg.value = ''
  try {
    await updateSettings({
      'embedding.provider': form.embProvider,
      'embedding.onnxModelPath': form.embOnnxPath,
      'embedding.ollamaUrl': form.embOllamaUrl,
      'embedding.ollamaModel': form.embOllamaModel
    })
    const res = await testEmbedding({})
    embTestOk.value = !!res?.success
    embTestMsg.value = res?.success
      ? `成功 dimension=${res.dimension}（${res.latencyMs ?? 0} ms）`
      : res?.message || '失败'
  } catch (e) {
    embTestOk.value = false
    embTestMsg.value = e?.message || '失败'
  } finally {
    testingEmb.value = false
  }
}

async function onTestMilvus() {
  testingMilvus.value = true
  milvusTestMsg.value = ''
  try {
    const res = await milvusHealth()
    milvusTestOk.value = !!res?.reachable
    milvusRowCount.value = res?.rowCount ?? 0
    milvusTestMsg.value = res?.reachable
      ? `连通，rowCount=${res.rowCount ?? 0}`
      : res?.error || '不可达'
  } catch (e) {
    milvusTestOk.value = false
    milvusTestMsg.value = e?.message || '不可达'
  } finally {
    testingMilvus.value = false
  }
}

async function onOpenDir() {
  try {
    await openDataDir()
    ElMessage.success('已打开目录')
  } catch (e) {
    ElMessage.error(e?.message || '打开失败')
  }
}

watch(
  () => appStore.settingsDialog.open,
  async (open) => {
    if (!open) return
    const sec = appStore.settingsDialog.section || 'llm'
    active.value = menuItems.some((m) => m.id === sec) ? sec : 'llm'
    llmTestMsg.value = ''
    embTestMsg.value = ''
    milvusTestMsg.value = ''
    await load()
  }
)

watch(
  () => appStore.settingsDialog.section,
  (sec) => {
    if (!sec || !appStore.settingsDialog.open) return
    if (menuItems.some((m) => m.id === sec)) {
      active.value = sec
    }
  }
)
</script>

<style scoped>
.settings-layout {
  display: flex;
  height: 520px;
  margin: -8px -16px -12px;
  border-top: 1px solid var(--sk-border, #e2e8f0);
}
.settings-nav {
  width: 200px;
  flex-shrink: 0;
  background: var(--sk-bg-sidebar, #f8fafc);
  border-right: 1px solid var(--sk-border, #e2e8f0);
  overflow: hidden;
}
.nav-menu {
  border-right: none;
  background: transparent;
  height: 100%;
  padding: 8px 0;
}
.nav-menu :deep(.el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 2px 8px;
  border-radius: 6px;
  color: var(--sk-text-primary, #334155);
}
.nav-menu :deep(.el-menu-item.is-active) {
  background: var(--sk-primary, #3b82f6) !important;
  color: #fff !important;
}
.nav-menu :deep(.el-menu-item:hover:not(.is-active)) {
  background: var(--sk-bg, #f1f5f9);
}
.settings-body {
  flex: 1;
  min-width: 0;
  overflow: auto;
  padding: 24px;
  background: var(--sk-bg-elevated, #ffffff);
  box-sizing: border-box;
}
.form {
  max-width: 640px;
}
.form :deep(.el-form-item) {
  margin-bottom: 20px;
}
.hint {
  margin-left: 12px;
  font-size: 13px;
}
.hint.ok {
  color: var(--sk-success);
}
.hint.fail {
  color: var(--sk-danger);
}
.field-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--sk-text-tertiary);
  line-height: 1.5;
}
.field-hint.block-hint {
  display: block;
  margin-left: 0;
  margin-top: 6px;
}
.path-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.path-row .el-input {
  flex: 1;
}
</style>

<style>
.settings-dialog-mask {
  background: rgba(0, 0, 0, 0.4) !important;
}
.settings-dialog.el-dialog {
  background: var(--sk-bg-elevated, #ffffff);
  border-radius: 10px;
  overflow: hidden;
}
.settings-dialog .el-dialog__header {
  margin-right: 0;
  padding: 14px 20px;
  border-bottom: none;
}
.settings-dialog .el-dialog__title {
  font-size: 16px;
  font-weight: 600;
  color: var(--sk-text-primary, #0f172a);
}
.settings-dialog .el-dialog__body {
  padding: 0 16px 12px;
  color: var(--sk-text-primary);
}
.settings-dialog .el-dialog__headerbtn {
  top: 14px;
  right: 16px;
}
</style>
