<script lang="ts">
  import { onMount } from 'svelte';
  import { api } from '$lib/api';
  import { describeFailure, requireSignIn } from '$lib/guard';
  import { durationMinutes, loadSnapshot, sessionsNewestFirst, type PullPage, type WorkoutSession } from '$lib/snapshot';

  let sessions = $state<WorkoutSession[]>([]);
  let loading = $state(true);
  let error = $state('');

  const dateFormat = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });

  async function load(): Promise<void> {
    try {
      const snapshot = await loadSnapshot((cursor) =>
        api<PullPage>(`/v1/sync/pull?cursor=${encodeURIComponent(cursor)}&limit=500`)
      );
      sessions = sessionsNewestFirst(snapshot);
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

<h1>History</h1>
{#if error}<p role="alert" style="color: crimson">{error}</p>{/if}
{#if loading}
  <p>Loading…</p>
{:else if sessions.length === 0 && !error}
  <p>No workouts yet. Start one from the app and it will show up here after it syncs.</p>
{:else}
  <ul>
    {#each sessions as s (s.id)}
      {@const minutes = durationMinutes(s)}
      <li>
        <strong>{s.dayName ?? 'Workout'}</strong>, {dateFormat.format(new Date(s.startedAt))}
        {minutes === null ? ' · in progress' : ` · ${minutes} min`}
      </li>
    {/each}
  </ul>
{/if}
