import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { ApiError, isSignedIn } from './api';

/** Call from onMount on every page that needs an account. Returns false after sending the user to sign in. */
export function requireSignIn(): boolean {
  if (isSignedIn()) return true;
  void goto(resolve('/login'));
  return false;
}

/** Shows a readable message for a failed call, and sends the user to sign in if the session is gone. */
export function describeFailure(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) void goto(resolve('/login'));
    return error.message;
  }
  return 'Something went wrong. Try again.';
}
