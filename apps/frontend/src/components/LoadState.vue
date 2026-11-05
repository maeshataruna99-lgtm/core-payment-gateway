<script setup lang="ts">
import { Skeleton } from '@/components/ui/skeleton'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'

// Vue turns a boolean prop that is not passed into false, so hasData needs an explicit default.
withDefaults(
  defineProps<{
    loading: boolean
    error: string | null
    forbidden?: boolean
    empty?: boolean
    emptyText?: string
    hasData?: boolean
  }>(),
  { hasData: true },
)
</script>

<template>
  <div v-if="loading" role="status" aria-label="Memuat" class="flex flex-col gap-2">
    <Skeleton class="h-6 w-full" />
    <Skeleton class="h-6 w-5/6" />
    <Skeleton class="h-6 w-2/3" />
  </div>
  <Alert v-else-if="forbidden" variant="destructive">
    <AlertTitle>Tidak punya izin</AlertTitle>
    <AlertDescription>Kunci API ini tidak boleh melihat data ini.</AlertDescription>
  </Alert>
  <template v-else>
    <Alert v-if="error" variant="destructive" class="mb-3">
      <AlertTitle>Gagal memuat data</AlertTitle>
      <AlertDescription>{{ error }}</AlertDescription>
    </Alert>
    <p v-if="empty && !error" class="py-6 text-center text-sm text-muted-foreground">
      {{ emptyText ?? 'Belum ada data.' }}
    </p>
    <slot v-else-if="hasData !== false" />
  </template>
</template>
