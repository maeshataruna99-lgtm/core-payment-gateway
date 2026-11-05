<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import {
  Activity,
  CreditCard,
  Landmark,
  LayoutDashboard,
  LogOut,
  Send,
  SlidersHorizontal,
} from '@lucide/vue'
import { Button } from '@/components/ui/button'
import SandboxBanner from '@/components/SandboxBanner.vue'
import { isStaff, me, signOut } from '@/lib/auth'
import { isDemo } from '@/lib/demo'

const route = useRoute()
const router = useRouter()

const operations = computed(() => [
  { to: '/', name: 'Ringkasan', icon: LayoutDashboard },
  { to: '/monitor', name: 'Monitor transaksi', icon: Activity },
  { to: '/webhooks', name: 'Webhook Center', icon: Send },
  { to: '/settlement', name: isStaff.value ? 'Settlement dan rekonsiliasi' : 'Settlement', icon: Landmark },
])

// The blueprint keeps demo controls separate from normal flows.
const demo = computed(() => [
  ...(isDemo ? [{ to: '/checkout-demo', name: 'Checkout demo', icon: CreditCard }] : []),
  { to: '/settings', name: 'Pengaturan sandbox', icon: SlidersHorizontal },
])

const title = computed(() => String(route.meta.title ?? ''))
const roleName = computed(() =>
  me.value?.role === 'MERCHANT' ? 'Merchant' : me.value?.role === 'ADMIN' ? 'Admin' : 'Operator',
)

function leave(): void {
  signOut()
  void router.push('/login')
}
</script>

<template>
  <div class="min-h-screen bg-background">
    <SandboxBanner />
    <div class="flex flex-wrap">
      <nav
        aria-label="Menu utama"
        class="flex min-w-56 flex-[1_1_14rem] flex-col gap-0.5 border-r bg-sidebar p-3 md:min-h-[calc(100vh-37px)]"
      >
        <div class="mb-2 flex items-center gap-2 p-2">
          <span class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <CreditCard class="size-4" aria-hidden="true" />
          </span>
          <span class="leading-tight">
            <b class="font-semibold">PayGate</b><br />
            <span class="text-xs text-muted-foreground">Sandbox</span>
          </span>
        </div>
        <div class="p-2 text-xs font-medium text-muted-foreground">Operasi</div>
        <RouterLink
          v-for="item in operations"
          :key="item.to"
          :to="item.to"
          class="flex min-h-11 items-center gap-2 rounded-lg px-2 text-sm text-neutral-700 transition-colors hover:bg-sidebar-accent focus-visible:outline-2 focus-visible:outline-ring"
          exact-active-class="bg-neutral-100 font-medium text-foreground"
        >
          <component :is="item.icon" class="size-4" aria-hidden="true" />
          {{ item.name }}
        </RouterLink>
        <div class="px-2 pb-2 pt-4 text-xs font-medium text-muted-foreground">Demo</div>
        <RouterLink
          v-for="item in demo"
          :key="item.to"
          :to="item.to"
          class="flex min-h-11 items-center gap-2 rounded-lg px-2 text-sm text-neutral-700 transition-colors hover:bg-sidebar-accent focus-visible:outline-2 focus-visible:outline-ring"
          exact-active-class="bg-neutral-100 font-medium text-foreground"
        >
          <component :is="item.icon" class="size-4" aria-hidden="true" />
          {{ item.name }}
        </RouterLink>
      </nav>
      <div class="min-w-0 flex-[999_1_35rem]">
        <header class="flex min-h-14 flex-wrap items-center justify-between gap-2 border-b px-6 py-2">
          <div class="text-sm text-muted-foreground">
            PayGate / <span class="font-medium text-foreground">{{ title }}</span>
          </div>
          <div class="flex items-center gap-3 text-sm">
            <span class="rounded-full border px-2 py-0.5 text-xs font-medium">{{ roleName }}</span>
            <Button variant="outline" class="h-11" @click="leave">
              <LogOut class="size-4" aria-hidden="true" />
              Keluar
            </Button>
          </div>
        </header>
        <main class="flex flex-col gap-4 p-6">
          <RouterView />
        </main>
      </div>
    </div>
  </div>
</template>
