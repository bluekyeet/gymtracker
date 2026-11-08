<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '$lib/api';
  import { describeFailure, requireSignIn } from '$lib/guard';
  import { loadSnapshot, splitsWithDays, weekdayNames, type PullPage } from '$lib/snapshot';

  let splits = $state<ReturnType<typeof splitsWithDays>>([]);
  let loading = $state(true);
  let error = $state('');

  async function load(): Promise<void> {
    try {
      const snapshot = await loadSnapshot((cursor) =>
        api<PullPage>(`/v1/sync/pull?cursor=${encodeURIComponent(cursor)}&limit=500`)
      );
      splits = splitsWithDays(snapshot);
    } catch (e) {
      error = describeFailure(e);
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    if (requireSignIn()) void load();
  });
</script>

<h1>Splits</h1>
{#if error}<p role="alert" style="color: crimson">{error}</p>{/if}
{#if loading}
  <p>Loading…</p>
{:else if splits.length === 0 && !error}
  <p>No splits yet. Create one in the app and it will show up here after it syncs.</p>
{:else}
  {#each splits as { split, days } (split.id)}
    <section>
      <h2>{split.name}{split.isActive ? ' · main split' : ''}</h2>
      {#if days.length === 0}
        <p>No days yet.</p>
      {:else}
        <ul>
          {#each days as day (day.id)}
            <li>{day.name}{day.weekdays.length ? ` — ${weekdayNames(day.weekdays)}` : ''}</li>
          {/each}
        </ul>
      {/if}
    </section>
  {/each}
{/if}
