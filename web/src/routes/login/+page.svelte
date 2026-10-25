<script lang="ts">
  import { goto } from '$app/navigation';
  import { resolve } from '$app/paths';
  import { setToken, api } from '$lib/api';

  let email = '';
  let password = '';
  let error = '';

  async function submit(): Promise<void> {
    try {
      const r = await api('/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password, deviceName: 'web', deviceKind: 'web' })
      });
      setToken(r.accessToken);
      goto(resolve('/'));
    } catch {
      error = 'Login failed';
    }
  }
</script>

<form on:submit|preventDefault={submit}>
  <h1>Login</h1>
  <input bind:value={email} placeholder="email" />
  <input bind:value={password} type="password" placeholder="password" />
  <button type="submit">Sign in</button>
  {#if error}<p>{error}</p>{/if}
</form>
