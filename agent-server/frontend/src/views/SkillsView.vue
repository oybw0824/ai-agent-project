<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import UBadge from '@/components/ui/UBadge.vue'
import UButton from '@/components/ui/UButton.vue'
import { ApiError, apiGet, apiPost } from '@/composables/useSSE'
import { useToast } from '@/composables/useToast'
import type { SkillReloadResponse, SkillsResponse, SkillStatus } from '@/types/api'

const toast = useToast()
const data = ref<SkillsResponse | null>(null)
const loading = ref(false)
const reloadingAll = ref(false)
const reloadingSkill = ref<string | null>(null)
const loadError = ref('')

const skills = computed(() => data.value?.skills || [])
const isReloading = computed(() => reloadingAll.value || reloadingSkill.value !== null)

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.code ? `${error.code}: ${error.message}` : error.message
  }
  return error instanceof Error ? error.message : '未知错误'
}

function statusLabel(status: SkillStatus['status']): string {
  return { NOT_LOADED: '未加载', LOADED: '已加载', OUTDATED: '待更新' }[status]
}

function statusVariant(status: SkillStatus['status']): 'default' | 'success' | 'warning' {
  if (status === 'LOADED') return 'success'
  if (status === 'OUTDATED') return 'warning'
  return 'default'
}

async function loadSkills() {
  loading.value = true
  loadError.value = ''
  try {
    data.value = await apiGet<SkillsResponse>('/api/v1/skills')
  } catch (error) {
    loadError.value = errorMessage(error)
  } finally {
    loading.value = false
  }
}

async function reloadAll() {
  if (!data.value || isReloading.value) return
  reloadingAll.value = true
  try {
    const result = await apiPost<SkillReloadResponse>(
      `/api/agents/${encodeURIComponent(data.value.agentName)}/skills/reload`,
    )
    toast.success(`已重新加载 ${result.skillCount || 0} 个 Skill`)
    await loadSkills()
  } catch (error) {
    toast.error(`重新加载失败：${errorMessage(error)}`, 5000)
  } finally {
    reloadingAll.value = false
  }
}

async function reloadOne(skill: SkillStatus) {
  if (!data.value || isReloading.value) return
  reloadingSkill.value = skill.name
  try {
    const result = await apiPost<SkillReloadResponse>(
      `/api/agents/${encodeURIComponent(data.value.agentName)}`
        + `/skills/${encodeURIComponent(skill.name)}/reload`,
    )
    toast.success(`${skill.name} 已加载版本 ${result.version}`)
    await loadSkills()
  } catch (error) {
    toast.error(`${skill.name} 加载失败：${errorMessage(error)}`, 5000)
  } finally {
    reloadingSkill.value = null
  }
}

onMounted(loadSkills)
</script>

<template>
  <div class="skills-page">
    <AppHeader title="🧩 Skill 管理" subtitle="数据库绑定与当前实例内存快照">
      <template #actions>
        <UButton variant="ghost" size="sm" :disabled="isReloading" @click="loadSkills">
          刷新状态
        </UButton>
        <UButton
          variant="primary"
          size="sm"
          :loading="reloadingAll"
          :disabled="loading || isReloading"
          @click="reloadAll"
        >
          全部重新加载
        </UButton>
      </template>
    </AppHeader>

    <div class="skills-content">
      <div v-if="loadError" class="state-card state-card--error">
        <strong>加载 Skill 状态失败</strong>
        <span>{{ loadError }}</span>
        <UButton size="sm" @click="loadSkills">重试</UButton>
      </div>

      <div v-else-if="loading && !data" class="state-card">正在加载 Skill 状态...</div>

      <template v-else>
        <div class="summary-card">
          <div>
            <span class="summary-label">Agent</span>
            <strong>{{ data?.agentName || '-' }}</strong>
          </div>
          <div>
            <span class="summary-label">Skill 数量</span>
            <strong>{{ data?.skillCount || 0 }}</strong>
          </div>
          <div>
            <span class="summary-label">来源</span>
            <strong>{{ data?.source || '-' }}</strong>
          </div>
        </div>

        <div v-if="skills.length" class="skill-list">
          <article v-for="skill in skills" :key="skill.name" class="skill-card">
            <div class="skill-main">
              <div class="skill-title-row">
                <h3>{{ skill.name }}</h3>
                <UBadge :variant="statusVariant(skill.status)">
                  {{ statusLabel(skill.status) }}
                </UBadge>
              </div>
              <p>{{ skill.description || '加载后可查看 Skill 描述' }}</p>
              <div class="version-row">
                <span>目标版本 <code>{{ skill.targetVersion }}</code></span>
                <span>已加载版本 <code>{{ skill.loadedVersion || '-' }}</code></span>
              </div>
            </div>
            <UButton
              size="sm"
              :loading="reloadingSkill === skill.name"
              :disabled="isReloading"
              @click="reloadOne(skill)"
            >
              重新加载
            </UButton>
          </article>
        </div>

        <div v-else class="state-card">
          当前 Agent 没有启用的 Skill 绑定。
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.skills-page{display:flex;flex-direction:column;height:100%;overflow:hidden}
.skills-content{flex:1;overflow-y:auto;padding:24px;display:flex;flex-direction:column;gap:16px}
.summary-card{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;padding:18px 20px;border:1px solid var(--border);border-radius:var(--r-lg);background:var(--bg-surface)}
.summary-card>div{display:flex;flex-direction:column;gap:5px}
.summary-label{font-size:var(--text-xs);color:var(--text-tertiary)}
.summary-card strong{font-size:var(--text-base);font-weight:600}
.skill-list{display:flex;flex-direction:column;gap:10px}
.skill-card{display:flex;align-items:center;justify-content:space-between;gap:24px;padding:18px 20px;border:1px solid var(--border);border-radius:var(--r-lg);background:var(--bg-surface)}
.skill-main{min-width:0;flex:1}
.skill-title-row{display:flex;align-items:center;gap:10px;margin-bottom:6px}
.skill-title-row h3{font-size:var(--text-base);font-weight:600}
.skill-main p{color:var(--text-secondary);font-size:var(--text-sm);margin-bottom:12px}
.version-row{display:flex;flex-wrap:wrap;gap:18px;color:var(--text-tertiary);font-size:var(--text-xs)}
.version-row code{margin-left:4px;padding:2px 6px;border-radius:var(--r-sm);background:var(--bg-inset);color:var(--text-primary)}
.state-card{display:flex;align-items:center;justify-content:center;gap:12px;min-height:120px;padding:24px;border:1px dashed var(--border-strong);border-radius:var(--r-lg);color:var(--text-secondary)}
.state-card--error{flex-direction:column;border-color:var(--danger-border);color:var(--danger)}
@media(max-width:720px){.summary-card{grid-template-columns:1fr}.skill-card{align-items:flex-start;flex-direction:column}.skill-card :deep(.ubtn){align-self:flex-end}}
</style>
