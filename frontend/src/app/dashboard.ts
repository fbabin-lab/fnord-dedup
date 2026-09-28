import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { Api, SystemInfo, errorMessage } from './api';

@Component({
  selector: 'app-dashboard', imports: [RouterLink, MatButtonModule],
  template: `
    <p class="eyebrow">WORKSPACE OVERVIEW</p><h1>A safe starting point.</h1>
    <p class="lede">Your file investigation workspace is taking shape.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button mat-button (click)="load()">Try again</button> }
    @if (info(); as details) {
      <section class="panel"><div class="section-head"><h2>Foundation connected</h2><span class="badge">{{ details.version }}</span></div>
        <p>Authentication, database storage, and the source safety adapter are available.</p>
        <p class="notice"><strong>Scanning is not available in this milestone.</strong> No inventory, duplicate counts, or signature results have been generated.</p>
        <a mat-flat-button routerLink="/sources">Review configured sources</a>
      </section>
      <div class="columns"><section class="panel"><h2>Source protection</h2><p>Source mounts must be read-only. File access rejects symbolic-link traversal and validates the object opened.</p></section>
      <section class="panel"><h2>Next: durable inventory</h2><p>M1 adds recursive inventory with saved progress, pause, resume, cancellation, and restart recovery.</p></section></div>
    } @else if (!error()) { <p role="status">Loading server information…</p> }
  `
})
export class Dashboard implements OnInit {
  private readonly api = inject(Api);
  readonly info = signal<SystemInfo | null>(null);
  readonly error = signal('');
  ngOnInit(): void { void this.load(); }
  async load(): Promise<void> { this.error.set(''); try { this.info.set(await this.api.info()); } catch (e) { this.error.set(errorMessage(e)); } }
}
