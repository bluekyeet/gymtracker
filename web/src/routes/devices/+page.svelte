<script lang="ts">
  import { onMount } from 'svelte';
  import { resolve } from '$app/paths';
  import { api } from '$lib/api';
  let devices: any[] = [];
  async function revoke(id: string): Promise<void> {
    await api(`/v1/devices/${id}/revoke`, { method: 'POST' });
    devices = devices.filter((d: any) => d.id !== id);
  }
  onMount(async () => {
    try { devices = await api('/v1/devices'); } catch { devices = []; }
  });
</script>

<h1>Devices</h1>
<ul>
  {#each devices as d (d.id)}
    <li>{d.name} ({d.kind}) <button on:click={() => revoke(d.id)}>Revoke</button></li>
  {/each}
</ul>
<p><a href={resolve('/')}>Back</a></p>
