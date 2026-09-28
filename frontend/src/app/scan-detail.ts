import { Component, inject, OnDestroy, OnInit, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { Api, ChildrenPage, ErrorPage, Job, Observation, Scan, errorMessage } from './api';

export function active(job: Job): boolean { return ['QUEUED','RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED'].includes(job.state); }
export function averageRate(job: Job): string | null {
  if (!job.startedAt) return null;
  const elapsed = Math.floor(((job.finishedAt ? Date.parse(job.finishedAt) : Date.now()) - Date.parse(job.startedAt))/1000);
  return elapsed < 2 ? null : (BigInt(job.discoveredEntries)/BigInt(elapsed)).toString();
}

@Component({
  selector: 'app-scan-detail', imports: [RouterLink, DatePipe, MatButtonModule],
  template: `
    <a routerLink="/scans">← Scans</a>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p><button mat-button (click)="load()">Retry</button> }
    @if (scan(); as value) {
      <p class="eyebrow">METADATA INVENTORY</p><h1>{{ value.name }}</h1>
      <p class="small muted">Created {{ value.createdAt | date:'medium':'UTC' }} UTC · {{ value.id }}</p>
      <section class="panel" aria-label="Inventory progress"><div class="section-head"><h2>Inventory progress</h2><span class="badge" [class.blocked]="value.job.errorCount !== '0'">{{ value.job.state }}</span></div>
        <p class="notice"><strong>{{ value.inventoryFrozenAt ? 'Inventory finished.' : 'Scan incomplete.' }}</strong>
          These are observations made over time. Duplicate analysis and content indexing are unavailable in M1.</p>
        <div class="metrics">
          <div><strong>{{ value.job.discoveredFiles }}</strong><span>Regular files</span></div>
          <div><strong>{{ value.job.discoveredDirectories }}</strong><span>Directories, including roots</span></div>
          <div><strong>{{ value.job.discoveredBytes }}</strong><span>Logical bytes observed</span></div>
          <div><strong>{{ value.job.errorCount }}</strong><span>Errors</span></div>
        </div>
        <p>Total entries are unknown during discovery. {{ value.job.discoveredEntries }} entries committed;
          {{ value.job.pendingWork }} directory tasks unfinished; {{ value.job.skippedEntries }} mount boundaries excluded.</p>
        @if (rate(); as speed) { <p class="small muted">Average {{ speed }} entries per elapsed second, including time paused.</p> }
        @if (value.job.currentPath) { <p class="path">Current directory: {{ value.job.currentPath }}</p> }
        @if (value.job.waitingForIo) { <p class="notice">Waiting for I/O or a checkpoint. A blocked filesystem call may delay pause or cancellation.</p> }
        @if (value.job.state === 'PAUSE_REQUESTED' || value.job.state === 'CANCEL_REQUESTED') { <p role="status">Request saved. Waiting for the worker to checkpoint and close its handles.</p> }
        @if (value.job.state === 'INTERRUPTED') { <p class="notice">The server lost its work lease or restarted. Review source status, then explicitly resume.</p> }
        @if (value.job.blockCode) { <p class="notice">Job notice: {{ value.job.blockCode }}. Review source status before resuming.</p> }
        <p class="small muted">Last heartbeat: {{ (value.job.heartbeatAt | date:'medium':'UTC') || 'Not started' }} UTC.
          Last checkpoint: {{ (value.job.checkpointAt | date:'medium':'UTC') || 'None' }} UTC.</p>
        <div class="actions">
          @if (value.job.state === 'QUEUED' || value.job.state === 'RUNNING') { <button mat-stroked-button (click)="control('pause')" [disabled]="busy()">Pause</button> }
          @if (value.job.state === 'PAUSED' || value.job.state === 'INTERRUPTED') { <button mat-flat-button (click)="control('resume')" [disabled]="busy()">Resume</button> }
          @if (canCancel(value.job)) { <button mat-stroked-button (click)="control('cancel')" [disabled]="busy()">Cancel inventory</button> }
          <button mat-button (click)="load()">Refresh progress</button>
        </div>
        <p class="small muted">Progress refreshes every two seconds while active. Closing this view does not stop server work.</p>
      </section>
      <section class="panel"><h2>Source coverage</h2>
        @for (source of value.sources; track source.sourceId) {
          <div class="source-row"><button mat-button (click)="browse(source.rootLocationId)">{{ source.label }}</button><span class="badge" [class.blocked]="source.coverage !== 'COMPLETE'">{{ source.coverage }}</span></div>
        }
        <p class="small muted">Partial or unavailable coverage never means missing historical files were deleted.</p>
      </section>
      <section class="panel"><div class="section-head"><h2>Committed observations</h2><div class="actions">
        @if (children()?.parentLocationId; as parent) { <button mat-button (click)="browse(parent)">Parent directory</button> }
        @if (directory()) { <button mat-button (click)="browse(directory()!)">Refresh entries</button> }
      </div></div>
        <p class="small muted">Browse a source above. This view reads saved metadata only. Refresh to include newly committed entries.</p>
        @if (browseError()) { <p class="notice" role="status">{{ browseError() }}</p> }
        @if (children(); as page) {
          <p class="path">{{ page.displayPath }}</p>
          <div class="table-scroll"><table><thead><tr><th>Name</th><th>Type</th><th>Bytes</th><th>Observation</th></tr></thead><tbody>
            @for (entry of page.items; track entry.id) {
              <tr><td class="path">@if (entry.entryType === 'DIRECTORY') { <button mat-button (click)="browse(entry.locationId)">{{ entry.name }}</button> }
                @else { <button mat-button (click)="inspect(entry.id)">{{ entry.name }}</button> }</td>
                <td>{{ entry.entryType }}</td><td>{{ entry.sizeBytes ?? '—' }}</td><td>{{ entry.unstable ? 'UNSTABLE' : entry.directoryCoverage ?? entry.discoveryStatus }}</td></tr>
            } @empty { <tr><td colspan="4">No committed children in this page. An unfinished directory may still contain unobserved entries.</td></tr> }
          </tbody></table></div>
          @if (page.nextCursor) { <button mat-button (click)="browse(directory()!,page.nextCursor)">Next entries</button> }
        }
      </section>
      @if (detail(); as entry) {
        <section class="panel"><div class="section-head"><h2>Observation detail</h2><button mat-button (click)="detail.set(null)">Close detail</button></div>
          <p class="path">{{ entry.path }}</p><dl><dt>Observed type</dt><dd>{{ entry.entryType }}</dd><dt>Bytes</dt><dd>{{ entry.sizeBytes ?? 'Unavailable' }}</dd>
            <dt>Modified (UTC)</dt><dd>{{ entry.mtime ?? 'Unavailable' }}</dd><dt>Exact mtime</dt><dd>{{ entry.mtimeSeconds }} seconds + {{ entry.mtimeNanos }} nanoseconds</dd>
            <dt>Metadata changed (ctime)</dt><dd>{{ entry.ctime ?? 'Unavailable' }} (not creation time)</dd><dt>Exact ctime</dt><dd>{{ entry.ctimeSeconds }} seconds + {{ entry.ctimeNanos }} nanoseconds</dd>
            <dt>Inode / mount</dt><dd>{{ entry.inode ?? 'Unknown' }} / {{ entry.mountId ?? 'Unknown' }}</dd>
            <dt>Discovery outcome</dt><dd>{{ entry.discoveryStatus }}{{ entry.unstable ? ' · Conflicting replay; original metadata retained' : '' }}</dd>
            <dt>Exact relative path (base64)</dt><dd class="path">{{ entry.relativePathBytesBase64 }}</dd>
            @if (entry.symlinkTargetBytesBase64) { <dt>Link target (base64)</dt><dd class="path">{{ entry.symlinkTargetBytesBase64 }}</dd> }
          </dl></section>
      }
      <section class="panel"><div class="section-head"><h2>Inventory errors</h2><button mat-button (click)="loadErrors()">Refresh errors</button></div>
        @for (item of errors()?.items ?? []; track item.id) { <article class="inventory-error"><strong>{{ item.code }}</strong><p class="path">{{ item.displayPath }}</p><p>{{ item.detail }}</p></article> }
        @if (value.job.errorCount === '0') { <p>No committed errors so far.</p> }
        @if (errors()?.nextCursor; as cursor) { <button mat-button (click)="loadErrors(cursor)">Next errors</button> }
      </section>
    } @else if (!error()) { <p role="status">Loading inventory…</p> }
  `
})
export class ScanDetail implements OnInit, OnDestroy {
  private readonly api = inject(Api);
  private readonly route = inject(ActivatedRoute);
  readonly scan = signal<Scan | null>(null);
  readonly error = signal(''); readonly browseError = signal(''); readonly busy = signal(false);
  readonly children = signal<ChildrenPage | null>(null); readonly errors = signal<ErrorPage | null>(null);
  readonly directory = signal<string | null>(null); readonly detail = signal<Observation | null>(null);
  readonly rate = signal<string | null>(null);
  private timer?: ReturnType<typeof setTimeout>; private destroyed = false; private loading = false;
  private browseGeneration = 0;
  private get id(): string { return this.route.snapshot.paramMap.get('id') ?? ''; }
  ngOnInit(): void { void this.load(); }
  ngOnDestroy(): void { this.destroyed = true; clearTimeout(this.timer); }
  canCancel(job: Job): boolean { return ['QUEUED','RUNNING','PAUSE_REQUESTED','PAUSED','INTERRUPTED'].includes(job.state); }
  async load(): Promise<void> {
    if (this.loading || this.destroyed) return;
    this.loading = true; clearTimeout(this.timer);
    try {
      const previousErrors = this.scan()?.job.errorCount;
      const value = await this.api.scan(this.id);
      if (this.destroyed) return;
      this.scan.set(value); this.rate.set(averageRate(value.job)); this.error.set('');
      if (!this.errors() || previousErrors !== value.job.errorCount) await this.loadErrors();
    } catch (e) { if (!this.destroyed) this.error.set(errorMessage(e)); }
    finally {
      this.loading = false;
      if (!this.destroyed) this.timer = setTimeout(() => void this.load(), this.scan() && active(this.scan()!.job) ? 2000 : 10000);
    }
  }
  async control(action: 'pause' | 'resume' | 'cancel'): Promise<void> {
    const value = this.scan(); if (!value || this.busy()) return;
    this.busy.set(true); this.error.set('');
    try { const job = await this.api.control(value.job.id,action); this.scan.update(v => v ? {...v,job} : v); await this.load(); }
    catch (e) { this.error.set(errorMessage(e)); }
    finally { this.busy.set(false); }
  }
  async browse(location: string, cursor?: string | null): Promise<void> {
    const generation = ++this.browseGeneration;
    this.directory.set(location); this.browseError.set(''); this.detail.set(null);
    try { const result = await this.api.children(this.id,location,cursor); if (generation === this.browseGeneration) this.children.set(result); }
    catch (e) { if (generation === this.browseGeneration) { this.children.set(null); this.browseError.set(errorMessage(e)); } }
  }
  async inspect(id: string): Promise<void> { try { this.detail.set(await this.api.observation(id)); } catch (e) { this.browseError.set(errorMessage(e)); } }
  async loadErrors(cursor?: string | null): Promise<void> {
    const value = this.scan(); if (!value) return;
    try { this.errors.set(await this.api.errors(value.job.id,cursor)); } catch (e) { this.error.set(errorMessage(e)); }
  }
}
