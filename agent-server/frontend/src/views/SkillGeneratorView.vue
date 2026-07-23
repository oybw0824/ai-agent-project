<script setup lang="ts">
import { ref, computed } from 'vue'
import { useSSE } from '@/composables/useSSE'
import { renderSkillMarkdown } from '@/utils/markdown'
import { storage } from '@/utils/storage'
import type { SkillStageData, SkillDoneData, StreamEvent } from '@/types/api'
import type { StepItem } from '@/components/ui/USteps.vue'
import AppHeader from '@/components/layout/AppHeader.vue'
import UButton from '@/components/ui/UButton.vue'
import UBadge from '@/components/ui/UBadge.vue'
import USteps from '@/components/ui/USteps.vue'
import UModal from '@/components/ui/UModal.vue'

const EXAMPLE = '开发一个查询城市天气的技能。功能：1. 用户输入城市名称 2. 调用天气工具获取数据 3. 返回温度、湿度、天气状况。约束：城市名无效时提示；温度用摄氏度。'

const prdInput = ref('')
const templateInput = ref('')
const advancedOpen = ref(false)
const isGenerating = ref(false)
const result = ref<{ markdown?: string; valid?: boolean; errors?: string[]; time?: number } | null>(null)
const stageSteps = ref<StepItem[]>(Array(3).fill({ label: '', status: 'pending' }).map((_, i) => ({
  label: ['拆解与工具映射', '步骤生成', '组装校验'][i],
  status: 'pending' as StepItem['status'],
})))
const elapsed = ref('0.0s')
let genTimer: ReturnType<typeof setInterval> | null = null
let genStart = 0
const modalOpen = ref(false)

const parsed = computed(() => result.value?.markdown ? renderSkillMarkdown(result.value.markdown) : null)
const skillName = computed(() => parsed.value?.frontmatter?.name || 'skill')

interface HistoryItem { name: string; valid: boolean; timeMs: number; ts: number; markdown: string }
const history = ref<HistoryItem[]>(storage.get<HistoryItem[]>('gen_history', []))

function fillExample() { prdInput.value = EXAMPLE }

async function generate() {
  const prd = prdInput.value.trim()
  if (!prd) return
  isGenerating.value = true; result.value = null
  stageSteps.value.forEach(s => { s.status = 'pending'; s.elapsed = undefined })
  genStart = Date.now()
  genTimer = setInterval(() => { elapsed.value = ((Date.now() - genStart) / 1000).toFixed(1) + 's' }, 200)

  const ctrl = new AbortController()
  const stageNameMap: Record<string, string> = { decomposeAndResolve: '拆解与工具映射', generateSteps: '步骤生成', assembleSkill: '组装校验' }
  try {
    await useSSE().start({
      url: '/api/v1/skill/generate-stream', body: { prdContent: prd, template: templateInput.value.trim() || undefined },
      signal: ctrl.signal,
      onEvent(type, data) {
        if (type === 'skill_stage') {
          const event = data as unknown as StreamEvent<SkillStageData>
          const sd = event.data
          if (sd?.stageName) {
            const label = stageNameMap[sd.stageName] || sd.stageName
            const step = stageSteps.value.find(item => item.label === label)
            if (step) {
              step.status = sd.status === 'complete' ? 'completed' : 'running'
              if (sd.elapsedMs > 0) step.elapsed = (sd.elapsedMs / 1000).toFixed(1) + 's'
            }
          }
        } else if (type === 'done') {
          const d = data as unknown as { data?: SkillDoneData }
          if (d.data) {
            result.value = { markdown: d.data.skillMarkdown, valid: d.data.valid, errors: d.data.validationErrors || [], time: d.data.processingTimeMs }
            const name = d.data.skillMarkdown?.match(/name:\s*(\S+)/)?.[1] || 'skill'
            storage.push('gen_history', { name, valid: d.data.valid, timeMs: d.data.processingTimeMs, ts: Date.now(), markdown: d.data.skillMarkdown }, 5)
            history.value = storage.get<HistoryItem[]>('gen_history', [])
          }
        }
      },
      onError(err) { result.value = { errors: [err.message], valid: false } },
    })
  } finally {
    isGenerating.value = false
    if (genTimer) { clearInterval(genTimer); genTimer = null }
    stageSteps.value.forEach(s => { if (s.status !== 'failed') s.status = 'completed' })
  }
}

function copyResult() { if (result.value?.markdown) navigator.clipboard.writeText(result.value.markdown) }
function downloadResult() {
  if (!result.value?.markdown) return
  const b = new Blob([result.value.markdown], { type: 'text/markdown' })
  const u = URL.createObjectURL(b); const a = document.createElement('a'); a.href = u
  a.download = skillName.value + '.md'; a.click(); URL.revokeObjectURL(u)
}
function loadHistory(idx: number) {
  const h = history.value[idx]; if (!h) return
  result.value = { markdown: h.markdown, valid: h.valid, time: h.timeMs, errors: [] }
  stageSteps.value.forEach(s => { s.status = 'completed'; s.elapsed = (h.timeMs / 4 / 1000).toFixed(1) + 's' })
}
</script>

<template>
  <div class="gp">
    <AppHeader title="⚡ Skill Generator" subtitle="PRD → SKILL.md 三阶段流水线">
      <template #actions><UBadge variant="default" size="md">{{ elapsed }}</UBadge></template>
    </AppHeader>

    <div class="gc">
      <div class="gi">
        <div class="gp-hd">📝 PRD 需求</div>
        <div class="gp-bd">
          <textarea v-model="prdInput" placeholder="在此粘贴 PRD 需求文档..." class="gta" :disabled="isGenerating" />
          <div class="gh"><span>{{ prdInput.length }} 字符</span><button class="lb" @click="fillExample">示例</button></div>
          <button class="at" @click="advancedOpen=!advancedOpen">
            <span :style="{transform:advancedOpen?'rotate(90deg)':'',display:'inline-block',transition:'transform .2s'}">▶</span>
            {{ advancedOpen ? '收起' : '展开' }}高级选项
          </button>
          <div v-if="advancedOpen" class="ab"><textarea v-model="templateInput" placeholder="自定义 Skill 模板（可选）..." class="gta gta-sm" :disabled="isGenerating" /></div>
        </div>
        <div class="gp-ft">
          <UButton variant="primary" size="lg" block :loading="isGenerating" @click="isGenerating ? undefined : generate()">
            {{ isGenerating ? '⏹ 停止' : '⚡ 生成 Skill' }}
          </UButton>
        </div>
      </div>

      <div class="gr">
        <div class="gp-hd">📄 结果</div>
        <div class="gr-bd">
          <div v-if="isGenerating || result" class="gpl"><USteps :steps="stageSteps" /></div>
          <div v-if="result?.errors?.length" class="gv gvf"><span>⚠️</span><ul><li v-for="e in result.errors" :key="e">{{ e }}</li></ul></div>
          <div v-else-if="result?.valid" class="gv gvo">✅ 格式校验通过</div>
          <div v-if="parsed" class="gpr">
            <div v-if="parsed.frontmatter" class="gpf"><UBadge variant="success">{{ parsed.frontmatter.name || 'skill' }}</UBadge><span class="gpd">{{ parsed.frontmatter.description }}</span></div>
            <div class="gpm prose" v-html="parsed.html" />
            <div class="gpt"><UButton variant="ghost" size="sm" @click="modalOpen=true">🔍 全屏</UButton><UButton variant="ghost" size="sm" @click="copyResult">📋 复制</UButton><UButton variant="ghost" size="sm" @click="downloadResult">💾 下载</UButton></div>
          </div>
          <div v-if="!isGenerating && !result" class="ge">
            <div class="gei">📋</div><h3>生成 Skill Markdown</h3>
            <p>在左侧输入 PRD，点击「生成 Skill」<br>分三个阶段产出完整技能文件</p>
            <div class="gec"><span><b>拆解</b> · 分解为步骤</span><span><b>映射</b> · 匹配工具</span><span><b>组装</b> · 生成 Skill</span></div>
            <div v-if="history.length" class="ghh"><div class="gp-hd">📜 最近</div><div v-for="(h,i) in [...history].reverse()" :key="i" class="ghi" @click="loadHistory(history.length-1-i)"><span>{{ h.valid?'✅':'⚠️' }} {{ h.name }}</span><span class="ght">{{ (h.timeMs/1000).toFixed(1) }}s</span></div></div>
          </div>
        </div>
      </div>
    </div>
    <UModal v-model="modalOpen" title="SKILL.md 全屏预览" width="90vw"><div v-if="parsed" class="prose" v-html="parsed.html" /></UModal>
  </div>
</template>

<style scoped>
.gp{display:flex;flex-direction:column;height:100%}
.gc{flex:1;display:flex;overflow:hidden}
.gi{width:40%;min-width:300px;max-width:480px;display:flex;flex-direction:column;border-right:1px solid var(--border);background:var(--bg-surface);overflow:hidden}
.gp-hd{padding:12px 16px;border-bottom:1px solid var(--border);font-size:var(--text-sm);font-weight:600}
.gp-bd{flex:1;overflow-y:auto;padding:14px 16px}
.gp-ft{padding:12px 16px;border-top:1px solid var(--border)}
.gta{width:100%;min-height:180px;border:1px solid var(--border-strong);border-radius:var(--r-md);padding:10px 12px;font-size:var(--text-sm);font-family:var(--font-sans);color:var(--text-primary);background:var(--bg-surface);resize:vertical;line-height:1.6;outline:none;transition:border-color var(--dur) var(--ease)}
.gta:focus{border-color:var(--brand);box-shadow:0 0 0 2px var(--brand-glow)}
.gta-sm{min-height:56px}
.gh{display:flex;justify-content:space-between;font-size:11px;color:var(--text-tertiary);margin-top:6px}
.lb{background:none;border:none;color:var(--brand);font-size:11px;cursor:pointer}
.at{display:flex;align-items:center;gap:6px;width:100%;background:none;border:none;font-size:12px;font-weight:600;color:var(--text-secondary);cursor:pointer;padding:8px 0 4px}
.at:hover{color:var(--text-primary)}
.ab{margin-top:8px}
.gr{flex:1;display:flex;flex-direction:column;overflow:hidden;min-width:0}
.gr-bd{flex:1;overflow-y:auto;padding:16px 20px}
.gpl{margin-bottom:14px}
.gv{padding:10px 14px;border-radius:var(--r-md);margin-bottom:12px;font-size:var(--text-sm);display:flex;align-items:flex-start;gap:8px}
.gvo{background:var(--success-soft);color:var(--success);border:1px solid var(--success-border)}
.gvf{background:var(--danger-soft);color:var(--danger);border:1px solid var(--danger-border)}
.gvf ul{margin:3px 0 0 16px;font-size:12px}
.gpr{border:1px solid var(--border);border-radius:var(--r-lg);overflow:hidden;background:var(--bg-surface);animation:fadeIn .3s var(--ease)}
.gpf{padding:10px 16px;border-bottom:1px solid var(--border);display:flex;align-items:center;gap:10px;background:var(--bg-subtle)}
.gpd{font-size:11px;color:var(--text-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.gpm{padding:16px;max-height:400px;overflow-y:auto}
.gpt{padding:10px 16px;border-top:1px solid var(--border);display:flex;gap:6px;background:var(--bg-subtle)}
.ge{text-align:center;padding:48px 20px;color:var(--text-secondary)}
.gei{width:64px;height:64px;margin:0 auto 14px;border-radius:var(--r-xl);background:var(--bg-surface);border:1px solid var(--border);display:flex;align-items:center;justify-content:center;font-size:30px;box-shadow:var(--shadow-xs)}
.ge h3{font-size:var(--text-lg);color:var(--text-primary);margin-bottom:6px}
.ge p{font-size:var(--text-sm);line-height:1.6;margin-bottom:16px}
.gec{display:flex;flex-direction:column;gap:6px;text-align:left;font-size:12px}
.ghh{margin-top:20px;text-align:left}
.ghi{padding:8px 12px;border-radius:var(--r-sm);font-size:12px;cursor:pointer;display:flex;justify-content:space-between;transition:background var(--dur-fast)}
.ghi:hover{background:var(--bg-subtle)}
.ght{font-size:10px;color:var(--text-tertiary)}
@keyframes fadeIn{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}
</style>
