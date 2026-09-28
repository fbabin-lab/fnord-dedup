import { Component, inject, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { Api, SystemInfo, errorMessage } from './api';

@Component({
  selector: 'app-dashboard', imports: [RouterLink, MatButtonModule],
  template: `
    <p class="eyebrow">WORKSPACE OVERVIEW</p><h1>Know what is in your sources.</h1>
    <p class="lede">Build a saved, read-only inventory of your configured files.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button mat-button (click)="load()">Try again</button> }
    @if (info(); as details) {
      <section class="panel"><div class="section-head"><h2>Inventory ready</h2><span class="badge">{{ details.version }}</span></div>
        <p>Scan whole configured sources, follow saved progress, and browse committed observations.</p>
        <p class="notice"><strong>Metadata inventory only.</strong> Duplicate analysis, checksums, signature matching, and text indexing arrive in later milestones.</p>
        <a mat-flat-button routerLink="/scans">Open scans</a>
      </section>
      <div class="columns"><section class="panel"><h2>Source protection</h2><p>Source mounts must be read-only. File access rejects symbolic-link traversal and validates the object opened.</p></section>
      <section class="panel"><h2>Work you can return to</h2><p>Closing this page does not stop a scan. Pause and cancel take effect at safe checkpoints. Interrupted jobs require an explicit resume.</p></section></div>
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
