import { Component, Input, OnChanges, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Api, SignaturePreview, errorMessage } from './api';
@Component({selector:'app-signature-check',imports:[MatButtonModule],template:`
  <section class="panel" aria-label="Check known signatures"><h2>Check known signatures</h2>
    <p>Catalog edits rematch accepted checksums using the database. Additional source reads require your authorization below.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> } @if (notice()) { <p role="status">{{ notice() }}</p> }
    <button mat-stroked-button (click)="estimate()" [disabled]="busy() || !frozen">Preview signature candidate reads</button>
    @if (preview(); as value) {
      <p class="notice"><strong>{{ value.candidateFiles }} previously unhashed files · {{ value.candidateBytes }} bytes</strong> in catalog revision {{ value.catalogRevision }}. Includes unique-size files. The worker uses existing accepted checksums and reads only eligible candidate sizes.</p>
      <p class="small">Preview expires {{ value.expiresAt }}. Changed evidence or catalog requires a new preview.</p>
      <button mat-flat-button (click)="confirm()" [disabled]="busy()">Authorize reads and check signatures</button>
      <button mat-button (click)="preview.set(null)">Dismiss estimate</button>
    }
  </section>`})
export class SignatureCheck implements OnChanges {
  private readonly api=inject(Api); @Input({required:true}) scanId=''; @Input() frozen=false;
  readonly preview=signal<SignaturePreview|null>(null); readonly error=signal(''); readonly notice=signal(''); readonly busy=signal(false);
  private key=crypto.randomUUID(); private generation=0;
  ngOnChanges():void { this.generation++; this.preview.set(null); }
  async estimate():Promise<void> { if(this.busy()) return; const generation=++this.generation; this.busy.set(true); this.error.set(''); this.preview.set(null);
    try { const value=await this.api.signaturePreview(this.scanId); if(generation===this.generation) { this.preview.set(value); this.key=crypto.randomUUID(); } } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async confirm():Promise<void> { const value=this.preview(); if(!value || this.busy()) return; this.busy.set(true); this.error.set('');
    try { const job=await this.api.checkSignatures(value,this.key); this.notice.set('Signature check queued: '+job.jobId+'. Follow the job controls above.'); this.preview.set(null); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
}
