<script lang="ts">
  import { onMount } from 'svelte';
  import { resolve } from '$app/paths';
  import { api } from '$lib/api';
  let splits: any[] = [];
  onMount(async () => {
    try { splits = await api('/v1/splits'); } catch { splits = []; }
  });
</script>

<h1>Splits</h1>
<ul>{#each splits as s (s.id ?? s.name)}<li>{s.name}</li>{/each}</ul>
<p><a href={resolve('/')}>Back</a></p>
