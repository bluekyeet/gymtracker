<script lang="ts">
  import { onMount } from 'svelte';
  import { resolve } from '$app/paths';
  import { requireSignIn } from '$lib/guard';

  let ready = $state(false);
  onMount(() => {
    ready = requireSignIn();
  });
</script>

<h1>Dashboard</h1>
{#if ready}
  <p>You are signed in. Log workouts on your phone or watch; this site shows what has synced.</p>
  <ul>
    <li><a href={resolve('/splits')}>Your splits and days</a></li>
    <li><a href={resolve('/history')}>Workout history</a></li>
    <li><a href={resolve('/devices')}>Devices</a>: see and revoke phones and watches</li>
  </ul>
{/if}
