import { createRouter, createWebHistory } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import { restoreSession } from '@/lib/auth'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true },
    },
    {
      // A customer holds only the token in the URL, so this page needs no sign-in.
      path: '/checkout/:id',
      component: () => import('@/views/CheckoutView.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: AppShell,
      children: [
        { path: '', component: () => import('@/views/OverviewView.vue'), meta: { title: 'Ringkasan' } },
        {
          path: 'monitor',
          component: () => import('@/views/MonitorView.vue'),
          meta: { title: 'Monitor transaksi' },
        },
        {
          path: 'webhooks',
          component: () => import('@/views/WebhooksView.vue'),
          meta: { title: 'Webhook Center' },
        },
        {
          path: 'settlement',
          component: () => import('@/views/SettlementView.vue'),
          meta: { title: 'Settlement' },
        },
        {
          path: 'checkout-demo',
          component: () => import('@/views/CheckoutDemoView.vue'),
          meta: { title: 'Checkout demo' },
        },
        {
          path: 'settings',
          component: () => import('@/views/SettingsView.vue'),
          meta: { title: 'Pengaturan sandbox' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  return (await restoreSession()) ? true : '/login'
})
