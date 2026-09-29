import { Component, inject, OnInit, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ReactiveFormsModule, FormControl, FormGroup, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Api, errorMessage, ScanPage, SourceList } from './api';

@Component({
  selector: 'app-scans',
  imports: [RouterLink, ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <p class="eyebrow">READ-ONLY SCANS</p><h1>Scans</h1>
    <p class="lede">Saved observations of whole sources. Files are never modified.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    <section class="panel"><h2>New scan</h2>
      <p>Inventories metadata, then reads regular files whose sizes occur at least twice in this scan to calculate SHA-256. Unique-size files stay unhashed unless you explicitly request a checksum.</p>
      <form [formGroup]="form" (ngSubmit)="create()">
        <mat-form-field appearance="outline"><mat-label>Scan name</mat-label><input matInput [formControl]="name" [readonly]="busy()" maxlength="200" (input)="resetKey()" required></mat-form-field>
        <fieldset><legend>Configured sources</legend>
          @for (source of sources()?.sources ?? []; track source.id) {
            <label class="source-choice"><input type="checkbox" [checked]="selected().has(source.id)" [disabled]="source.status !== 'AVAILABLE' || busy()" (change)="toggle(source.id, $event)">
              <span>{{ source.label }} <span class="small muted">{{ source.status }}</span></span></label>
          } @empty { <p>No sources configured. <a routerLink="/sources">Review source setup</a>.</p> }
        </fieldset>
        <label class="source-choice"><input type="checkbox" [formControl]="includeSignatures" (change)="resetKey()"> Include known-signature size candidates</label><p class="small muted">Off by default. Enabling this reads additional unique-size files matching enabled signature sizes in the catalog captured when the scan starts.</p>
        <button mat-flat-button type="submit" [disabled]="name.invalid || selected().size === 0 || busy()">{{ busy() ? 'Queueing…' : 'Start scan' }}</button>
      </form>
    </section>
    <section class="panel"><div class="section-head"><h2>Saved scans</h2><button mat-button (click)="load()">Refresh list</button></div>
      @if (page(); as scans) {
        <div class="table-scroll"><table><thead><tr><th>Scan</th><th>Scan status</th><th>Entries observed</th><th>Errors</th></tr></thead><tbody>
          @for (scan of scans.items; track scan.id) {
            <tr><td><a [routerLink]="['/scans',scan.id]">{{ scan.name }}</a></td><td>{{ scan.job.state }}</td><td>{{ scan.job.discoveredEntries }}</td><td>{{ scan.job.errorCount }}</td></tr>
          } @empty { <tr><td colspan="4">No inventories yet. Start one from the configured sources above.</td></tr> }
        </tbody></table></div>
        <p class="small muted">Oldest first. Refresh starts a new view of committed history.</p>
        @if (scans.nextCursor) { <button mat-button (click)="load(scans.nextCursor)">Next scans</button> }
      } @else { <p role="status">Loading saved scans…</p> }
    </section>
  `
})
export class Scans implements OnInit {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  readonly name = new FormControl('', {nonNullable:true, validators:[Validators.required, Validators.maxLength(200)]});
  readonly includeSignatures = new FormControl(false,{nonNullable:true});
  readonly form = new FormGroup({name:this.name,includeSignatures:this.includeSignatures});
  readonly selected = signal(new Set<string>());
  readonly sources = signal<SourceList | null>(null);
  readonly page = signal<ScanPage | null>(null);
  readonly error = signal('');
  readonly busy = signal(false);
  private requestKey = crypto.randomUUID();
  ngOnInit(): void { void this.load(); void this.loadSources(); }
  resetKey(): void { this.requestKey = crypto.randomUUID(); }
  toggle(id: string, event: Event): void {
    const selected = new Set(this.selected());
    if ((event.target as HTMLInputElement).checked) selected.add(id); else selected.delete(id);
    this.selected.set(selected); this.resetKey();
  }
  async load(cursor?: string | null): Promise<void> {
    this.error.set('');
    try { this.page.set(await this.api.scans(cursor)); } catch (e) { this.error.set(errorMessage(e)); }
  }
  private async loadSources(): Promise<void> {
    try { this.sources.set(await this.api.sources()); } catch (e) { this.error.set(errorMessage(e)); }
  }
  async create(): Promise<void> {
    if (this.name.invalid || this.busy() || this.selected().size === 0) return;
    this.busy.set(true); this.error.set('');
    try {
      const created = await this.api.createScan({name:this.name.value, sourceIds:[...this.selected()], hashAlgorithm:'SHA-256', includeSignatureCandidates:this.includeSignatures.value, textIndexingEnabled:false},this.requestKey);
      await this.router.navigate(['/scans',created.scanId]);
    } catch (e) { this.error.set(errorMessage(e)); }
    finally { this.busy.set(false); }
  }
}
