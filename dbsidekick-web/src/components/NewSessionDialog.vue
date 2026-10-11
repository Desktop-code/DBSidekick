<template>
  <el-dialog
    v-model="visible"
    title="新建会话"
    width="440px"
    append-to-body
  >
    <el-form label-width="88px" @submit.prevent>
      <el-form-item label="数据源">
        <el-select
          v-model="form.datasourceId"
          placeholder="选择数据源"
          filterable
          style="width: 100%"
          @change="onDatasourceChange"
        >
          <el-option v-for="d in appStore.datasources" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="数据库">
        <el-select
          v-model="form.dbName"
          placeholder="选择数据库"
          filterable
          :loading="loadingDb"
          :disabled="!form.datasourceId"
          style="width: 100%"
        >
          <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
        </el-select>
      </el-form-item>
      <el-form-item label="会话名称">
        <el-input v-model="form.title" placeholder="新会话" maxlength="40" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!canCreate" @click="onCreate">创建</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listInstanceDatabases } from '../api/datasource'
import { useAppStore } from '../stores/app'
import { useTabsStore } from '../stores/tabs'

const appStore = useAppStore()
const tabsStore = useTabsStore()

const visible = computed({
  get: () => appStore.sessionDialog.open,
  set: (open) => {
    if (!open) appStore.closeSessionDialog()
  }
})

const form = reactive({
  datasourceId: '',
  dbName: '',
  title: '新会话'
})
const databases = ref([])
const loadingDb = ref(false)

const canCreate = computed(() => !!form.datasourceId && !!form.dbName)

watch(
  () => appStore.sessionDialog.token,
  () => {
    if (appStore.sessionDialog.open) onOpen()
  }
)

async function onOpen() {
  if (!appStore.datasources.length) {
    try {
      await appStore.loadDatasources()
    } catch {
      // 下拉为空时仍允许关掉弹窗
    }
  }
  form.datasourceId = appStore.sessionDialog.datasourceId || ''
  form.dbName = appStore.sessionDialog.dbName || ''
  form.title = '新会话'
  await loadDatabases(true)
}

async function onDatasourceChange() {
  form.dbName = ''
  await loadDatabases(false)
}

async function loadDatabases(keepSelection) {
  if (!form.datasourceId) {
    databases.value = []
    form.dbName = ''
    return
  }
  loadingDb.value = true
  try {
    const names = await listInstanceDatabases(form.datasourceId)
    databases.value = Array.isArray(names) ? names : []
    if (!keepSelection || !databases.value.includes(form.dbName)) {
      form.dbName = ''
    }
  } catch (e) {
    databases.value = []
    form.dbName = ''
    ElMessage.error(e?.response?.data?.message || e?.message || '加载数据库失败')
  } finally {
    loadingDb.value = false
  }
}

function onCreate() {
  if (!canCreate.value) return
  const title = (form.title || '').trim() || '新会话'
  appStore.setCurrentDatasource(form.datasourceId)
  appStore.setCurrentDatabase(form.dbName)
  appStore.setCurrentSessionId(null)
  tabsStore.openAiChat({
    datasourceId: form.datasourceId,
    dbName: form.dbName,
    title: `AI会话: ${title}`
  })
  visible.value = false
}
</script>
