<script setup lang="ts">
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { SCENARIOS } from '@/lib/demo'
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold tracking-tight">Pengaturan sandbox</h1>
    <p class="text-sm text-muted-foreground">Cara kerja simulator. Secret tidak pernah ditampilkan di sini.</p>
  </div>

  <Card class="max-w-3xl">
    <CardHeader>
      <CardTitle><h2 class="text-sm font-semibold">Mode</h2></CardTitle>
    </CardHeader>
    <CardContent class="text-sm">
      Sandbox aktif. Mode ini tidak bisa dimatikan, karena aplikasi tidak punya koneksi ke provider nyata.
    </CardContent>
  </Card>

  <Card class="max-w-3xl">
    <CardHeader>
      <CardTitle><h2 class="text-sm font-semibold">Skenario provider simulasi</h2></CardTitle>
      <CardDescription>
        Dua digit terakhir jumlah menentukan perilakunya, jadi demo selalu bisa diulang. Berlaku juga untuk refund,
        kecuali skenario normal yang langsung berhasil.
      </CardDescription>
    </CardHeader>
    <CardContent class="overflow-x-auto">
      <Table class="min-w-[28rem]">
        <TableHeader>
          <TableRow>
            <TableHead>Jumlah berakhir</TableHead>
            <TableHead>Skenario</TableHead>
            <TableHead>Perilaku</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow v-for="item in SCENARIOS" :key="item.suffix">
            <TableCell class="font-mono text-xs">{{ item.suffix === 0 ? 'Lainnya' : `0${item.suffix}` }}</TableCell>
            <TableCell>{{ item.name }}</TableCell>
            <TableCell>{{ item.note }}</TableCell>
          </TableRow>
        </TableBody>
      </Table>
    </CardContent>
  </Card>
</template>
