<script lang="ts">
  import { onMount } from 'svelte';
  import { resolve } from '$app/paths';
  import { api } from '$lib/api';
  let sessions: any[] = [];
  onMount(async () => {
    try { sessions = await api('/v1/sync/pull?cursor=0&limit=50').then((r: any) => r.changes); } catch { sessions = []; }
  });
</script>

<h1>History</h1>
<ul>
  {#each sessions as s (s.entityId)}
    <li>{s.entity} {s.entityId}</li>
  {/each}
</ul>
<p><a href={resolve('/')}>Back</a></p>
