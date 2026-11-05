<script setup lang="ts">
import { TriangleAlert } from '@lucide/vue'

defineProps<{ text?: string }>()
</script>

<template>
  <div
    role="note"
    class="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-4 py-2 text-sm font-medium text-amber-900"
  >
    <TriangleAlert class="size-4 shrink-0" aria-hidden="true" />
    <span>{{ text ?? 'SANDBOX: semua transaksi disimulasikan. Tidak ada uang nyata yang berpindah.' }}</span>
  </div>
</template>
