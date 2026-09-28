import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ReactiveFormsModule, FormControl, FormGroup, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Api, errorMessage } from './api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <a class="skip-link" href="#main">Skip to content</a>
    <header>
      <a class="brand" routerLink="/" aria-label="Fnord Dedup home"><span class="mark" aria-hidden="true">F</span><span>Fnord <strong>Dedup</strong></span></a>
      <span class="policy">Read-only by design</span>
      @if (api.session()?.authenticated) {
        <nav aria-label="Main navigation"><a routerLink="/" routerLinkActive="active" [routerLinkActiveOptions]="{exact:true}">Overview</a><a routerLink="/scans" routerLinkActive="active">Scans</a><a routerLink="/sources" routerLinkActive="active">Sources</a></nav>
        <button mat-button (click)="logout()" [disabled]="busy()">Sign out</button>
      }
    </header>
    <main id="main">
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @if (api.session() === null) {
        <section class="panel login"><h1>Connecting to Fnord Dedup</h1><p role="status">Checking your session…</p>
          @if (error()) { <button mat-flat-button (click)="connect()">Try again</button> }
        </section>
      } @else if (!api.session()?.authenticated) {
        <section class="panel login"><p class="eyebrow">PRIVATE FILE INVESTIGATION</p><h1>Welcome to Fnord Dedup</h1>
          <p class="muted">Sign in with your configured operator account.</p>
          <form [formGroup]="form" (ngSubmit)="login()">
            <mat-form-field appearance="outline"><mat-label>Username</mat-label><input matInput formControlName="username" autocomplete="username" required></mat-form-field>
            <mat-form-field appearance="outline"><mat-label>Password</mat-label><input matInput type="password" formControlName="password" autocomplete="current-password" required></mat-form-field>
            <button mat-flat-button type="submit" [disabled]="form.invalid || busy()">{{ busy() ? 'Signing in…' : 'Sign in' }}</button>
          </form>
          <p class="small muted">Sources are configured on the server. Fnord never modifies or deletes scanned files.</p>
        </section>
      } @else { <router-outlet /> }
    </main>
    <footer>Fnord Dedup · M1 inventory · Identification and review only</footer>
  `
})
export class App implements OnInit {
  readonly api = inject(Api);
  readonly error = signal('');
  readonly busy = signal(false);
  readonly form = new FormGroup({ username: new FormControl('operator', { nonNullable: true, validators: [Validators.required] }), password: new FormControl('', { nonNullable: true, validators: [Validators.required] }) });
  ngOnInit(): void { void this.connect(); }
  async connect(): Promise<void> {
    this.error.set('');
    try { await this.api.refreshSession(); } catch (e) { this.error.set(errorMessage(e)); }
  }
  async login(): Promise<void> {
    if (this.form.invalid || this.busy()) return;
    this.busy.set(true); this.error.set('');
    try { const values = this.form.getRawValue(); await this.api.login(values.username, values.password); }
    catch (e) { this.error.set(errorMessage(e)); }
    finally { this.form.controls.password.reset(); this.busy.set(false); }
  }
  async logout(): Promise<void> {
    this.busy.set(true); this.error.set('');
    try { await this.api.logout(); } catch (e) { this.error.set(errorMessage(e)); }
    finally { this.busy.set(false); }
  }
}
