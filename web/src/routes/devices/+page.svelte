<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '$lib/api';
  import { describeFailure, requireSignIn } from '$lib/guard';

  interface Device {
    id: string;
    name: string;
    kind: string;
    revoked: boolean;
  }

  let devices = $state<Device[]>([]);
  let loading = $state(true);
  let error = $state('');

  async function load(): Promise<void> {
    try {
      devices = await api<Device[]>('/v1/devices');
    } catch (e) {
      error = describeFailure(e);
    } finally {
      loading = false;
    }
  }

  async function revoke(device: Device): Promise<void> {
    if (!confirm(`Revoke "${device.name}"? It will be signed out and stop syncing.`)) return;
    error = '';
    try {
      await api(`/v1/devices/${device.id}/revoke`, { method: 'POST' });
      devices = devices.map((d) => (d.id === device.id ? { ...d, revoked: true } : d));
    } catch (e) {
      error = describeFailure(e);
    }
  }

  onMount(() => {
    if (requireSignIn()) void load();
  });
</script>

<h1>Devices</h1>
{#if error}<p role="alert" style="color: crimson">{error}</p>{/if}
{#if loading}
  <p>Loading…</p>
{:else if devices.length === 0 && !error}
  <p>No devices yet. Enroll a phone or watch from its Settings screen.</p>
{:else}
  <ul>
    {#each devices as d (d.id)}
      <li>
        <strong>{d.name}</strong> ({d.kind}){d.revoked ? ' · revoked' : ''}
        {#if !d.revoked}<button type="button" onclick={() => revoke(d)}>Revoke</button>{/if}
      </li>
    {/each}
  </ul>
{/if}
