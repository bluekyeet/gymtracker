<script lang="ts">
  import { onMount } from 'svelte';
  import { resolve } from '$app/paths';
  import { api } from '$lib/api';
  onMount(async () => {
    try { await api('/v1/devices'); } catch { /* ignore */ }
  });
</script>

<h1>Dashboard</h1>
<p><a href={resolve('/login')}>Login</a> | <a href={resolve('/history')}>History</a> | <a href={resolve('/splits')}>Splits</a> | <a href={resolve('/devices')}>Devices</a></p>
