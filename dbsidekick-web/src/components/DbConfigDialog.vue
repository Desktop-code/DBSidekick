<template>
  <el-dialog
    v-model="visible"
    :title="mode === 'edit' ? '编辑连接' : '新建连接'"
    width="560px"
    destroy-on-close
    @closed="onClosed"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="150px" size="default">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" placeholder="例如 local-mysql" />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-select v-model="form.type" style="width: 100%" @change="onTypeChange">
          <el-option label="MYSQL" value="MYSQL" />
          <el-option label="POSTGRESQL" value="POSTGRESQL" />
        </el-select>
      </el-form-item>
      <el-form-item label="主机" prop="host">
        <el-input v-model="form.host" placeholder="localhost" />
      </el-form-item>
      <el-form-item label="端口" prop="port">
        <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
      </el-form-item>
      <el-form-item label="数据库名" prop="database">
        <el-input v-model="form.database" placeholder="留空则连接整个实例" />
      </el-form-item>
      <el-form-item v-if="form.type === 'POSTGRESQL'" label="Schema" prop="schema">
        <el-input v-model="form.schema" placeholder="可选，留空则同步该库全部 schema" />
      </el-form-item>
      <el-form-item label="用户名" prop="username">
        <el-input v-model="form.username" />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          :placeholder="mode === 'edit' ? '不修改则留空' : ''"
          autocomplete="new-password"
        />
      </el-form-item>
    </el-form>

    <div v-if="testMsg" class="test-msg" :class="testOk ? 'ok' : 'fail'">{{ testMsg }}</div>

    <template #footer>
      <el-button :loading="testing" @click="onTest">测试连接</el-button>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { addDatasource, testDatasource, updateDatasource } from '../api/datasource'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  mode: { type: String, default: 'create' }, // create | edit
  initial: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const formRef = ref(null)
const testing = ref(false)
const saving = ref(false)
const testMsg = ref('')
const testOk = ref(false)

const form = reactive({
  id: '',
  name: '',
  type: 'MYSQL',
  host: 'localhost',
  port: 3306,
  database: '',
  schema: '',
  username: 'root',
  password: ''
})

const rules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  host: [{ required: true, message: '请输入主机', trigger: 'blur' }],
  port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }]
}

function resetForm() {
  Object.assign(form, {
    id: '',
    name: '',
    type: 'MYSQL',
    host: 'localhost',
    port: 3306,
    database: '',
    schema: '',
    username: 'root',
    password: ''
  })
  testMsg.value = ''
  testOk.value = false
}

function fillFromInitial() {
  resetForm()
  if (props.mode === 'edit' && props.initial) {
    const d = props.initial
    form.id = d.id || ''
    form.name = d.name || ''
    form.type = (d.type || 'MYSQL').toString().toUpperCase()
    form.host = d.host || 'localhost'
    form.port = d.port || (form.type === 'POSTGRESQL' ? 5432 : 3306)
    form.database = d.database || ''
    form.schema = d.schema || ''
    form.username = d.username || ''
    form.password = ''
  }
}

watch(
  () => [props.modelValue, props.mode, props.initial],
  ([open]) => {
    if (open) fillFromInitial()
  }
)

function onTypeChange(t) {
  form.port = t === 'POSTGRESQL' ? 5432 : 3306
  if (t !== 'POSTGRESQL') form.schema = ''
}

function buildPayload() {
  const payload = {
    id: form.id || undefined,
    name: form.name.trim(),
    type: form.type,
    host: form.host.trim(),
    port: form.port,
    database: form.database.trim(),
    username: form.username.trim(),
    password: form.password,
    enabled: true
  }
  if (form.type === 'POSTGRESQL' && (form.schema || '').trim()) {
    payload.schema = form.schema.trim()
  }
  return payload
}

async function onTest() {
  await formRef.value?.validate?.().catch(() => Promise.reject())
  testing.value = true
  testMsg.value = ''
  try {
    const res = await testDatasource(buildPayload())
    if (res?.success) {
      testOk.value = true
      testMsg.value = `连接成功（${res.latencyMs ?? 0} ms）`
    } else {
      testOk.value = false
      testMsg.value = res?.message || '连接失败'
    }
  } catch (e) {
    testOk.value = false
    testMsg.value = e?.response?.data?.message || e?.message || '连接失败'
  } finally {
    testing.value = false
  }
}

async function onSave() {
  await formRef.value?.validate?.().catch(() => Promise.reject())
  saving.value = true
  try {
    const payload = buildPayload()
    if (props.mode === 'edit') {
      await updateDatasource(payload)
      ElMessage.success('已更新连接')
    } else {
      delete payload.id
      const res = await addDatasource(payload)
      payload.id = res?.id
      ElMessage.success('已新建连接')
    }
    emit('saved', payload)
    visible.value = false
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function onClosed() {
  testMsg.value = ''
}
</script>

<style scoped>
.test-msg {
  margin: 0 16px 8px;
  font-size: 13px;
}
.test-msg.ok {
  color: #67c23a;
}
.test-msg.fail {
  color: #f56c6c;
}
</style>
