<script lang="ts">
  import { onMount } from 'svelte';
  import { goto } from '$app/navigation';
  import { resolve } from '$app/paths';
  import { ApiError, isSignedIn, login } from '$lib/api';

  let email = $state('');
  let password = $state('');
  let error = $state('');
  let busy = $state(false);

  onMount(() => {
    if (isSignedIn()) void goto(resolve('/'));
  });

  async function submit(event: SubmitEvent): Promise<void> {
    event.preventDefault();
    error = '';
    busy = true;
    try {
      await login(email.trim(), password);
      await goto(resolve('/'));
    } catch (e) {
      error = e instanceof ApiError ? e.message : 'Sign in failed. Try again.';
    } finally {
      busy = false;
    }
  }
</script>

<h1>Sign in</h1>
<form onsubmit={submit}>
  <p>
    <label>Email <input type="email" autocomplete="username" required bind:value={email} /></label>
  </p>
  <p>
    <label>Password <input type="password" autocomplete="current-password" required bind:value={password} /></label>
  </p>
  <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
  {#if error}<p role="alert" style="color: crimson">{error}</p>{/if}
</form>
