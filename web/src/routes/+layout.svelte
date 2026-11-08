<script lang="ts">
  import type { Snippet } from 'svelte';
  import { goto } from '$app/navigation';
  import { resolve } from '$app/paths';
  import { logout } from '$lib/api';

  let { children }: { children: Snippet } = $props();

  function signOut(): void {
    logout();
    void goto(resolve('/login'));
  }
</script>

<svelte:head>
  <title>PowerTrack</title>
</svelte:head>

<header>
  <strong>PowerTrack</strong>
  <nav>
    <a href={resolve('/')}>Today</a>
    <a href={resolve('/splits')}>Splits</a>
    <a href={resolve('/history')}>History</a>
    <a href={resolve('/devices')}>Devices</a>
  </nav>
  <button type="button" onclick={signOut}>Sign out</button>
</header>

<main>
  {@render children()}
</main>

<style>
  :global(:root) {
    color-scheme: light dark;
  }
  :global(body) {
    margin: 0;
    font-family: system-ui, sans-serif;
    line-height: 1.5;
  }
  header {
    display: flex;
    align-items: center;
    gap: 1rem;
    padding: 0.75rem 1rem;
    border-bottom: 1px solid color-mix(in srgb, currentColor 20%, transparent);
  }
  nav {
    display: flex;
    gap: 1rem;
    flex: 1;
  }
  main {
    max-width: 44rem;
    margin: 0 auto;
    padding: 1rem;
  }
</style>
