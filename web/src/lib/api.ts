import { env } from '$env/dynamic/public';
import { apiOrigin, createApi } from './api-core';

export { ApiError } from './api-core';

// PUBLIC_API_BASE_URL is the variable compose.yaml already passes to the web container; it may or
// may not end in /v1.
const client = createApi({
  baseUrl: apiOrigin(env.PUBLIC_API_BASE_URL),
  storage: typeof localStorage === 'undefined' ? null : localStorage,
  fetch: (input, init) => fetch(input, init)
});

export const api = client.request;
export const login = client.login;
export const logout = client.logout;
export const isSignedIn = client.isSignedIn;
