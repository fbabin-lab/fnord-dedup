import { Component, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Api, SourceList, errorMessage } from './api';

@Component({
  selector: 'app-sources', imports: [MatButtonModule],
  template: `
    <p class="eyebrow">SERVER CONFIGURATION</p><h1>Sources</h1>
    <p class="lede">Registered directories and their last startup validation.</p>
    <p class="notice">Source status reflects startup checks. Future scan and resume operations will revalidate the mount and source identity.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button mat-button (click)="load()">Try again</button> }
    @if (data(); as listing) {
      @for (source of listing.sources; track source.id) {
        <section class="panel source"><div class="section-head"><h2>{{ source.label }}</h2><span class="badge" [class.blocked]="source.status !== 'AVAILABLE'">{{ source.status }}</span></div>
          <p class="path">{{ source.containerPath }}</p><p>{{ source.detail }}</p>
          <dl><dt>Source key</dt><dd>{{ source.key }}</dd><dt>Nested mount policy</dt><dd>{{ source.crossMounts ? 'Included only when read-only' : 'Excluded' }}</dd><dt>Excluded mount boundaries</dt><dd>{{ source.excludedMountCount }}</dd></dl>
        </section>
      } @empty {
        <section class="panel empty"><h2>No sources configured</h2><p>Add an existing directory as a read-only bind mount and add its registry entry in the server configuration. Restart the backend to validate it.</p><p class="small muted">See docs/OPERATIONS.md in your checkout for the source configuration example.</p></section>
      }
      <p class="small muted">Mounts and source identities are managed on the server. This page does not open or scan source files.</p>
    } @else if (!error()) { <p role="status">Loading sources…</p> }
  `
})
export class Sources implements OnInit {
  private readonly api = inject(Api);
  readonly data = signal<SourceList | null>(null);
  readonly error = signal('');
  ngOnInit(): void { void this.load(); }
  async load(): Promise<void> { this.error.set(''); try { this.data.set(await this.api.sources()); } catch (e) { this.error.set(errorMessage(e)); } }
}
