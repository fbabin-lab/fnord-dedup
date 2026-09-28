import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { components } from './api-schema';

export type Session = components['schemas']['Session'];
export type SourceList = components['schemas']['SourceList'];
export type SystemInfo = components['schemas']['SystemInfo'];

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  readonly session = signal<Session | null>(null);
  async refreshSession(): Promise<void> {
    this.session.set(await firstValueFrom(this.http.get<Session>('/api/v1/session')));
  }
  async login(username: string, password: string): Promise<void> {
    // Always obtain the current CSRF cookie before a login retry.
    await this.refreshSession();
    const body = new HttpParams().set('username', username).set('password', password);
    await firstValueFrom(this.http.post('/api/v1/session/login', body, { responseType: 'text' }));
    await this.refreshSession();
  }
  async logout(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v1/session/logout', {}, { responseType: 'text' }));
    await this.refreshSession();
  }
  private async get<T>(path: string): Promise<T> {
    try { return await firstValueFrom(this.http.get<T>(path)); }
    catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401)
        this.session.set({ authenticated: false, username: null });
      throw error;
    }
  }
  sources(): Promise<SourceList> { return this.get('/api/v1/sources'); }
  info(): Promise<SystemInfo> { return this.get('/api/v1/system/info'); }
}

export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 429) return 'Too many login attempts. Try again in one minute.';
    if (error.status === 401) return 'The username or password is incorrect, or your session has expired.';
    if (error.status === 403) return 'Your security token expired. Refresh the page and try again.';
  }
  return 'The service is unavailable. Check the server and try again.';
}
