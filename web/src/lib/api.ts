/* eslint-disable no-undef */
const API_BASE = (import.meta as any).env?.VITE_API_BASE ?? 'http://localhost:8080';

export function getToken(): string | null {
  return typeof localStorage !== 'undefined' ? localStorage.getItem('token') : null;
}

export function setToken(t: string): void {
  if (typeof localStorage !== 'undefined') localStorage.setItem('token', t);
}

export async function api(path: string, init?: any): Promise<any> {
  const token = getToken();
  const headers = new Headers(init && init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (!headers.has('Content-Type') && init && init.body) headers.set('Content-Type', 'application/json');
  const res = await fetch(`${API_BASE}${path}`, { ...init, headers });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}
