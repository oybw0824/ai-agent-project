import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/chat',
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '对话', icon: '💬' },
  },
  {
    path: '/skill-generator',
    name: 'skill-generator',
    component: () => import('@/views/SkillGeneratorView.vue'),
    meta: { title: 'Skill Generator', icon: '⚡' },
  },
  {
    path: '/monitor',
    name: 'monitor',
    component: () => import('@/views/MonitorView.vue'),
    meta: { title: '监控面板', icon: '📊' },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

export default router
