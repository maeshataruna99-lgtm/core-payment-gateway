<script setup lang="ts">
import { computed, type Component } from 'vue'
import { Ban, Calendar, Check, CircleHelp, Clock, X } from '@lucide/vue'

const props = defineProps<{ status: string; label?: string }>()

const STYLES: Record<string, { classes: string; icon: Component }> = {
  good: { classes: 'bg-green-50 text-green-800 border-green-200', icon: Check },
  waiting: { classes: 'bg-amber-50 text-amber-900 border-amber-200', icon: Clock },
  bad: { classes: 'bg-red-50 text-red-800 border-red-200', icon: X },
  unknown: { classes: 'bg-white text-foreground border-neutral-500 border-dashed', icon: CircleHelp },
  closed: { classes: 'bg-neutral-100 text-neutral-700 border-neutral-200', icon: Calendar },
  off: { classes: 'bg-neutral-100 text-neutral-700 border-neutral-200', icon: Ban },
}

const KIND: Record<string, string> = {
  SUCCEEDED: 'good',
  DELIVERED: 'good',
  PAID: 'good',
  OPEN: 'waiting',
  CREATED: 'waiting',
  PENDING: 'waiting',
  FAILED: 'bad',
  UNKNOWN: 'unknown',
  EXPIRED: 'closed',
  CANCELLED: 'off',
}

const style = computed(() => STYLES[KIND[props.status] ?? 'off'])
</script>

<template>
  <span
    class="inline-flex items-center gap-1 whitespace-nowrap rounded-full border px-2 py-0.5 text-xs font-medium"
    :class="style.classes"
  >
    <component :is="style.icon" class="size-3" aria-hidden="true" />
    {{ label ?? status }}
  </span>
</template>
