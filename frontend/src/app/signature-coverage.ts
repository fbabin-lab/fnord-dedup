import { Component, Input, OnChanges, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { Api, SignatureFindings, SignatureRunPage, errorMessage } from './api';

@Component({selector:'app-signature-coverage',imports:[RouterLink,MatButtonModule],template:`
  <section class="panel" aria-label="Signature findings"><div class="section-head"><h2>Signature findings</h2><button mat-button (click)="load()">Refresh current findings</button></div>
    <p class="small muted">Derived names, memos, and tags are separate from your manual notes. Exact content fingerprints identify catalog entries; no match is a statement about the checked catalog only.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (value(); as result) {
      <p><strong>{{ result.matchStatus }}</strong> · {{ result.checkStatus }}</p>
      <p class="small">Catalog revision {{ result.catalogRevision ?? 'Not checked' }} · Checked {{ result.checkedAt ?? 'Not yet' }} · {{ result.current ? 'Current published run' : 'Historical or unavailable run' }}</p>
      @if (!result.catalogCurrent) { <p class="notice">Catalog changes are pending a database rematch. No additional file reads are authorized by catalog edits.</p> }
      @if (result.checkStatus==='HASH_REQUIRED') { <p class="notice">The file has a catalog candidate size but has no accepted checksum. Use “Check known signatures” in scan progress to preview and authorize those reads.</p> }
      @for (finding of result.items; track finding.signature.id) {
        <article class="finding"><h3>{{ finding.signature.name }}</h3><p>{{ finding.active ? 'Active derived finding' : 'Historical / inactive finding' }} · Signature revision {{ finding.signature.revision }} · Matched {{ finding.matchedAt }}</p>
          <p class="memo">{{ finding.signature.memo }}</p><p>@for (tag of finding.signature.tags; track tag.id) { <span class="tag-label">{{ tag.label }}</span> }</p>
          <a routerLink="/signatures" [queryParams]="{id:finding.signature.id,revision:finding.signature.revision}">View captured signature revision</a></article>
      } @empty { <p>No confirmed findings in this page.</p> }
      @if (result.nextCursor) { <button mat-button (click)="load(result.runId,result.nextCursor)">Next findings</button> }
    }
    <button mat-button (click)="history()">View finding history</button>
    @if (runs(); as page) { @for (run of page.items; track run.id) { <p><button mat-button (click)="load(run.id)">Catalog {{ run.catalogRevision }} · {{ run.publishedAt }} · {{ run.current ? 'Current' : 'Historical' }}</button></p> } @if (page.nextCursor) { <button mat-button (click)="history(page.nextCursor)">Next finding runs</button> } }
    <p><a routerLink="/signatures" [queryParams]="{observationId:observationId}">Create signature from this observation</a></p>
  </section>`})
export class SignatureCoverage implements OnChanges {
  private readonly api=inject(Api); @Input({required:true}) observationId=''; @Input({required:true}) scanId='';
  readonly value=signal<SignatureFindings|null>(null); readonly runs=signal<SignatureRunPage|null>(null); readonly error=signal(''); private generation=0;
  ngOnChanges():void { this.value.set(null); this.runs.set(null); void this.load(); }
  async load(run?:string|null,cursor?:string|null):Promise<void> {
    const generation=++this.generation; this.error.set('');
    try { const value=await this.api.observationSignatures(this.observationId,run,cursor); if(generation===this.generation) this.value.set(value); }
    catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); }
  }
  async history(cursor?:string|null):Promise<void> { const id=this.observationId; try { const runs=await this.api.signatureRuns(this.scanId,cursor); if(id===this.observationId) this.runs.set(runs); } catch(e) { this.error.set(errorMessage(e)); } }
}
