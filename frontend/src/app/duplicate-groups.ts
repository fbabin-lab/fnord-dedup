import { Component, inject, Input, OnChanges, Output, EventEmitter, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { Api, GroupPage, GroupDetail, errorMessage } from './api';

@Component({
  selector: 'app-duplicate-groups', imports: [MatButtonModule, DatePipe],
  template: `
    <section class="panel"><div class="section-head"><h2>Duplicate groups</h2><button mat-button (click)="load()">Refresh groups</button></div>
      <p>SHA-256 identifies matching content in this scan. It does not include byte-for-byte verification.</p>
      @if (error()) { <p role="alert" class="error">{{ error() }}</p> }
      @if (page(); as value) {
        @if (value.status === 'NOT_AVAILABLE') { <p class="notice">Analysis has not been published. An unfinished build is never shown as a completed result.</p> }
        @else {
          <p class="small muted">Analysis {{ value.analysisId }} · {{ value.status }}</p>
          @if (value.status === 'NEEDS_REBUILD') { <p class="notice">Evidence changed. A new analysis is pending; stale groups must be reviewed again.</p> }
          <div class="table-scroll"><table><thead><tr><th>SHA-256</th><th>Bytes per file</th><th>Paths / objects</th><th>Evidence</th><th>Theoretical duplicate-copy bytes</th></tr></thead><tbody>
            @for (group of value.items; track group.id) {
              <tr><td class="path"><button mat-button (click)="inspect(group.id)">{{ group.digest.slice(0,16) }}…</button></td><td>{{ group.sizeBytes }}</td>
                <td>{{ group.pathCount }} / {{ group.objectCount ?? 'Unknown' }}</td><td>{{ group.evidenceLevel }}</td><td>{{ group.maximumDuplicateCopyLogicalBytes ?? 'Unknown' }}</td></tr>
            } @empty { <tr><td colspan="5">No duplicate groups in this published revision. Unhashed and failed files provide no content-equality evidence.</td></tr> }
          </tbody></table></div>
          @if (value.nextCursor) { <button mat-button (click)="load(value.nextCursor,value.analysisId)">Next groups</button> }
        }
      }
      <p class="small muted">Physical space savings are unknown. Hard links, shared extents, snapshots, and retention can affect storage.</p>
      @if (detail(); as value) {
        <article><h3>Group detail</h3><p class="path">{{ value.group.digest }}</p>
          <p>{{ value.group.evidenceLevel }} · {{ value.group.identityStatus }} · {{ value.group.sourceIds.length }} participating roots</p>
          <p>{{ value.group.pathLogicalBytes }} path logical bytes; {{ value.group.independentObjectLogicalBytes ?? 'Unknown' }} independent-object logical bytes.</p>
          <div class="table-scroll"><table><thead><tr><th>Path</th><th>Root</th><th>Accepted checksum time (UTC)</th></tr></thead><tbody>
            @for (member of value.members; track member.observation.id) {
              <tr><td class="path"><button mat-button (click)="observation.emit(member.observation.id)">{{ member.observation.path }}</button></td>
                <td class="path">{{ member.observation.sourceId }}</td><td>{{ member.hash.completedAt | date:'medium':'UTC' }}</td></tr>
            }
          </tbody></table></div>
          @if (value.nextCursor) { <button mat-button (click)="inspect(value.group.id,value.nextCursor)">Next members</button> }
          <button mat-button (click)="detail.set(null)">Close group</button>
        </article>
      }
    </section>
  `
})
export class DuplicateGroups implements OnChanges {
  private readonly api = inject(Api);
  @Input({required:true}) scanId = '';
  @Input() analysisId: string | null = null;
  @Input() evidenceRevision = '0';
  @Output() observation = new EventEmitter<string>();
  readonly page = signal<GroupPage | null>(null); readonly detail = signal<GroupDetail | null>(null); readonly error = signal('');
  private generation = 0;
  ngOnChanges(): void { void this.load(); if (this.detail()) void this.inspect(this.detail()!.group.id); }
  async load(cursor?: string | null, revision?: string | null): Promise<void> {
    const generation = ++this.generation;
    try { const value = await this.api.groups(this.scanId,revision,cursor); if (generation === this.generation) { this.page.set(value); this.error.set(''); } }
    catch (e) { if (generation === this.generation) this.error.set(errorMessage(e)); }
  }
  async inspect(id: string, cursor?: string | null): Promise<void> {
    try { this.detail.set(await this.api.group(id,cursor)); this.error.set(''); } catch (e) { this.error.set(errorMessage(e)); }
  }
}
