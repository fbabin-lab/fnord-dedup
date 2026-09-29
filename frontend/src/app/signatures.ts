import { Component, OnInit, OnDestroy, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Api, Signature, SignatureWrite, SignaturePage, SignatureImport, SignatureExport, SignatureLimits, Observation, Tag, errorMessage } from './api';
import { TagPicker } from './tag-picker';

@Component({selector:'app-signatures',imports:[RouterLink,ReactiveFormsModule,MatButtonModule,TagPicker],template:`
  <p class="eyebrow">USER-MAINTAINED CATALOG</p><h1>Known signatures</h1>
  <p class="lede">Exact size and SHA-256 fingerprints with your labels. Catalog changes rematch stored hashes; extra file reads require explicit authorization.</p>
  @if (error()) { <p class="error" role="alert">{{ error() }}</p> } @if (notice()) { <p class="notice" role="status">{{ notice() }}</p> }
  <section class="panel"><div class="section-head"><h2>Catalog · revision {{ page()?.catalogRevision ?? '…' }}</h2><button mat-button (click)="load()">Refresh catalog</button></div>
    <div class="actions"><label>Find signature name or memo <input [formControl]="query" maxlength="200"></label><button mat-button (click)="load()">Search catalog</button><button mat-stroked-button (click)="fresh()" [disabled]="busy()">New signature</button></div>
    <div class="table-scroll"><table><thead><tr><th>Name</th><th>Bytes</th><th>Revision</th><th>State</th><th>Filename rule</th></tr></thead><tbody>
      @for (record of page()?.items ?? []; track record.id) { <tr><td><button mat-button (click)="edit(record)" [disabled]="busy()">{{ record.name }}</button></td><td>{{ record.sizeBytes }}</td><td>{{ record.revision }}</td><td>{{ record.enabled ? 'Enabled' : 'Disabled' }}</td><td>{{ record.filenameMatchMode }}</td></tr> }
      @empty { <tr><td colspan="5">No matching signatures in this snapshot.</td></tr> }
    </tbody></table></div>
    @if (page()?.nextCursor; as cursor) { <button mat-button (click)="load(cursor)">Next signatures</button> }
  </section>
  @if (editing()) {
    <section class="panel" aria-label="Signature editor"><h2>{{ record() ? 'Edit signature' : 'Create signature' }}</h2>
      @if (record(); as old) { <p class="small">Editing captured record revision {{ old.revision }} · Catalog {{ old.catalogRevision }} · Origin {{ old.origin }} · Created {{ old.createdAt }} · Updated {{ old.updatedAt }}</p>
        <p class="path small">{{ old.id }}</p><div class="actions"><label>Historical record revision <input [formControl]="historyRevision" inputmode="numeric"></label><button mat-button (click)="historical()" [disabled]="busy()">Load signature revision</button><button mat-button (click)="reload()" [disabled]="busy()">Discard draft and reload current</button></div> }
      @if (observation(); as source) {
        <p class="path">From observation: {{ source.path }}</p><p>{{ source.sizeBytes }} bytes · {{ source.hash?.status }}</p>
        @if (source.hash?.status!=='ACCEPTED') {
          <p class="notice">A signature requires a complete accepted SHA-256. Calculate this file explicitly, then reload its evidence and continue. Unique-size files will be read by this action.</p>
          <button mat-stroked-button (click)="hashObservation()" [disabled]="busy() || !!source.hash?.pending">Calculate SHA-256 for this observation</button><button mat-button (click)="refreshObservation()">Reload observation evidence</button>
        } @else { @if (source.hash; as evidence) { @if (evidence.accepted; as accepted) { <p class="path small">{{ accepted.digest }}</p> } }<p class="small">The server supplies the accepted fingerprint and exact raw basename. Continue with a name and labels below.</p> }
        <a [routerLink]="['/scans',source.scanId]">Follow scan and hash job progress</a>
      }
      <form [formGroup]="form" (ngSubmit)="save()"><fieldset class="signature-fields" [disabled]="busy()"><div class="filter-grid">
        <label>Signature name <input formControlName="name" maxlength="200" required></label>
        @if (!observation()) {
          <label>Signature size in bytes <input formControlName="sizeBytes" inputmode="numeric" required></label><label>SHA-256 checksum <input formControlName="checksum" maxlength="64" required></label>
          <label>Advisory filename <input formControlName="filename" maxlength="255"></label><label>Exact filename bytes (base64, optional) <input formControlName="filenameBytesBase64" maxlength="340"></label>
        }
        <label>Filename matching <select formControlName="filenameMatchMode" aria-label="Filename matching"><option value="ADVISORY">Advisory filename (content fingerprint only)</option><option value="REQUIRED_EXACT">Require exact basename bytes</option></select></label>
        <label><input type="checkbox" formControlName="enabled"> Signature enabled</label><label>Source note <input formControlName="sourceNote" maxlength="2000"></label>
      </div>
      @if (form.controls.filenameMatchMode.value==='REQUIRED_EXACT') { <p class="notice">Required-exact excludes renamed copies even when their content is identical. Matching uses case-sensitive raw basename bytes.</p> }
      <label>Signature memo <textarea formControlName="memo" maxlength="20000" rows="5"></textarea></label>
      <app-tag-picker label="Signature tags" [selected]="tags()" (selectedChange)="tags.set($event)" />
      <div class="actions"><button mat-flat-button type="submit" [disabled]="busy() || !form.controls.name.value.trim() || (!!observation() && observation()?.hash?.status!=='ACCEPTED')">Save signature revision</button><button mat-button type="button" (click)="editing.set(false)" [disabled]="busy()">Close editor</button></div>
      <p class="small muted">Saving changes database records only. A revision conflict preserves this draft. Manual file notes and tags remain independent.</p>
      </fieldset></form>
    </section>
  }
  <section class="panel" aria-label="Catalog import"><h2>Import a catalog</h2><p>Stage the entire upload, review errors and proposed rows, then explicitly apply. No active changes occur during the dry run.</p>
    <p class="small">UTF-8 JSON schema version 1 or documented CSV header. Limits: {{ limits()?.importBytes ?? '…' }} bytes / {{ limits()?.importRows ?? '…' }} records. Metadata is inert text.</p>
    <div class="filter-grid"><label>Catalog file <input type="file" accept=".json,.csv,application/json,text/csv" (change)="choose($event)" [disabled]="busy()"></label>
      <label>Import format <select [formControl]="importFormat" aria-label="Import format"><option>JSON</option><option>CSV</option></select></label>
      <label>Existing ID policy <select [formControl]="policy" aria-label="Existing ID policy"><option value="REJECT_EXISTING_ID">Reject existing IDs</option><option value="UPDATE_BY_ID">Update by ID and expected revision</option></select></label></div>
    <button mat-stroked-button (click)="stage()" [disabled]="busy() || !file()">Validate and stage catalog</button>
    @if (staged(); as value) {
      <h3>Dry run · {{ value.state }}</h3><p>{{ value.rowCount }} rows · {{ value.errorCount }} errors · Base catalog {{ value.catalogRevision }} · {{ value.policy }}</p>
      <p class="small">Staging ID {{ value.id }} · Expires {{ value.expiresAt }}</p>
      <div class="table-scroll"><table><thead><tr><th>Row</th><th>Proposed name</th><th>Bytes</th><th>Validation</th></tr></thead><tbody>@for (row of value.items; track row.row) { <tr><td>{{ row.row }}</td><td>{{ row.proposed?.name ?? 'Invalid record' }}</td><td>{{ row.proposed?.sizeBytes ?? '—' }}</td><td>{{ row.error ?? 'Valid' }}</td></tr> }</tbody></table></div>
      @if (value.nextCursor) { <button mat-button (click)="importPage(value.nextCursor)">Next staged rows</button> }
      <button mat-flat-button (click)="apply()" [disabled]="busy() || value.state!=='VALID' || value.errorCount!==0">Apply all {{ value.rowCount }} staged records atomically</button>
      @if (value.state==='INVALID') { <p class="notice">Correct every error and stage a new upload. This upload cannot be applied.</p> }
    }
  </section>
  <section class="panel" aria-label="Catalog export"><h2>Export this catalog revision</h2>
    <p>JSON preserves exact metadata. CSV is spreadsheet-safe display data: formula-like text is prefixed with an apostrophe. Basename base64 preserves exact bytes; use JSON for lossless metadata exchange.</p>
    <label>Export format <select [formControl]="exportFormat" aria-label="Export format"><option>JSON</option><option>CSV</option></select></label><button mat-stroked-button (click)="export()" [disabled]="busy() || !page()">Build catalog export</button>
    @if (artifact(); as value) { <p>{{ value.state }} · Catalog {{ value.catalogRevision }} · {{ value.rowCount }} rows · {{ value.byteCount }} bytes</p><p class="small path">Artifact {{ value.id }}</p>
      @if (value.errorCode) { <p class="error">{{ value.errorCode }}</p> }
      @if (value.state==='READY') { <p class="path small">SHA-256 {{ value.sha256 }}</p><a mat-flat-button [href]="'/api/v1/signature-exports/'+value.id+'/download'">Download {{ value.format }} catalog</a> }
      @else if (value.state==='QUEUED' || value.state==='BUILDING' || value.state==='PAUSED') {
        <button mat-button (click)="control(value.state==='PAUSED' ? 'resume' : 'pause')">{{ value.state==='PAUSED' ? 'Resume export' : 'Pause export' }}</button><button mat-button (click)="control('cancel')">Cancel catalog export</button>
      }
      <button mat-button (click)="refreshArtifact()">Refresh export status</button>
    }
  </section>`})
export class Signatures implements OnInit,OnDestroy {
  private readonly api=inject(Api); private readonly route=inject(ActivatedRoute); private readonly router=inject(Router);
  readonly page=signal<SignaturePage|null>(null); readonly record=signal<Signature|null>(null); readonly observation=signal<Observation|null>(null);
  readonly editing=signal(false); readonly busy=signal(false); readonly error=signal(''); readonly notice=signal(''); readonly tags=signal<Tag[]>([]);
  readonly file=signal<File|null>(null); readonly staged=signal<SignatureImport|null>(null); readonly artifact=signal<SignatureExport|null>(null); readonly limits=signal<SignatureLimits|null>(null);
  readonly query=new FormControl('',{nonNullable:true}); readonly historyRevision=new FormControl('',{nonNullable:true});
  readonly importFormat=new FormControl<'JSON'|'CSV'>('JSON',{nonNullable:true}); readonly exportFormat=new FormControl<'JSON'|'CSV'>('JSON',{nonNullable:true});
  readonly policy=new FormControl<'REJECT_EXISTING_ID'|'UPDATE_BY_ID'>('REJECT_EXISTING_ID',{nonNullable:true});
  readonly form=new FormGroup({name:new FormControl('',{nonNullable:true}),memo:new FormControl('',{nonNullable:true}),sizeBytes:new FormControl('',{nonNullable:true}),checksum:new FormControl('',{nonNullable:true}),filename:new FormControl('',{nonNullable:true}),filenameBytesBase64:new FormControl('',{nonNullable:true}),filenameMatchMode:new FormControl<'ADVISORY'|'REQUIRED_EXACT'>('ADVISORY',{nonNullable:true}),enabled:new FormControl(true,{nonNullable:true}),sourceNote:new FormControl('',{nonNullable:true})});
  private listGeneration=0; private editGeneration=0; private timer?:ReturnType<typeof setInterval>; private applyKey=crypto.randomUUID(); private hashKey=crypto.randomUUID(); private exportRequest?:{payload:string;key:string};
  ngOnInit():void { void this.initialize(); this.timer=setInterval(()=> { if(['QUEUED','BUILDING'].includes(this.artifact()?.state ?? '')) void this.refreshArtifact(); },2000); }
  ngOnDestroy():void { if(this.timer) clearInterval(this.timer); this.editGeneration++; this.listGeneration++; }
  async initialize():Promise<void> { await this.load();
    try { this.limits.set(await this.api.signatureLimits()); const q=this.route.snapshot.queryParamMap;
      if(q.get('observationId')) { this.fresh(); const source=await this.api.observation(q.get('observationId')!); this.observation.set(source); }
      else if(q.get('id')) this.edit(await this.api.signature(q.get('id')!,q.get('revision') ?? undefined));
      if(q.get('importId')) this.staged.set(await this.api.signatureImport(q.get('importId')!));
      if(q.get('exportId')) this.artifact.set(await this.api.signatureExport(q.get('exportId')!));
    } catch(e) { this.error.set(errorMessage(e)); }
  }
  async load(cursor?:string|null):Promise<void> { const generation=++this.listGeneration; this.error.set(''); try { const page=await this.api.signatures(this.query.value,cursor ? this.page()?.catalogRevision : null,cursor); if(generation===this.listGeneration) this.page.set(page); } catch(e) { if(generation===this.listGeneration) this.error.set(errorMessage(e)); } }
  fresh():void { this.editGeneration++; this.record.set(null); this.observation.set(null); this.tags.set([]); this.form.reset({name:'',memo:'',sizeBytes:'',checksum:'',filename:'',filenameBytesBase64:'',filenameMatchMode:'ADVISORY',enabled:true,sourceNote:''}); this.editing.set(true); }
  edit(record:Signature):void { this.editGeneration++; this.record.set(record); this.observation.set(null); this.tags.set(record.tags); this.form.reset({...record,filename:record.filename ?? '',filenameBytesBase64:record.filenameBytesBase64 ?? ''}); this.historyRevision.setValue(record.revision); this.editing.set(true); }
  async historical():Promise<void> { const old=this.record(),generation=this.editGeneration; if(!old) return; try { const record=await this.api.signature(old.id,this.historyRevision.value); if(generation===this.editGeneration) this.edit(record); } catch(e) { this.error.set(errorMessage(e)); } }
  async reload():Promise<void> { const old=this.record(),generation=this.editGeneration; if(!old) return; try { const record=await this.api.signature(old.id); if(generation===this.editGeneration) this.edit(record); } catch(e) { this.error.set(errorMessage(e)); } }
  async save():Promise<void> { if(this.busy()) return; const generation=this.editGeneration,old=this.record(),source=this.observation(),v=this.form.getRawValue();
    const metadata={name:v.name,memo:v.memo,tagIds:this.tags().map(t=>t.id),filenameMatchMode:v.filenameMatchMode,enabled:v.enabled,sourceNote:v.sourceNote};
    const body:SignatureWrite=source ? {...metadata,observationId:source.id} : {...metadata,sizeBytes:v.sizeBytes,algorithm:'SHA-256',checksum:v.checksum,filename:v.filename || null,filenameBytesBase64:v.filenameBytesBase64 || null,...(old ? {expectedRevision:old.revision} : {})};
    this.busy.set(true); this.error.set(''); try { const result=await this.api.saveSignature(old?.id ?? null,body); if(generation===this.editGeneration) this.edit(result); this.notice.set('Signature revision saved. Existing hashes will be rematched without source reads.'); await this.load(); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async hashObservation():Promise<void> { const source=this.observation(); if(!source || this.busy()) return; this.busy.set(true); this.error.set(''); try { const result=await this.api.hash(source.scanId,[source.id],false,this.hashKey); this.notice.set('Hash job '+result.jobId+' queued. Reload observation evidence after it finishes, then continue creating the signature.'); await this.refreshObservation(); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); } }
  async refreshObservation():Promise<void> { const source=this.observation(),generation=this.editGeneration; if(!source) return; try { const result=await this.api.observation(source.id); if(generation===this.editGeneration) this.observation.set(result); } catch(e) { this.error.set(errorMessage(e)); } }
  choose(event:Event):void { const file=(event.target as HTMLInputElement).files?.[0] ?? null; this.file.set(file); this.staged.set(null); if(file?.name.toLowerCase().endsWith('.csv')) this.importFormat.setValue('CSV'); else this.importFormat.setValue('JSON'); }
  async stage():Promise<void> { const file=this.file(); if(!file || this.busy()) return; if(this.limits() && file.size>this.limits()!.importBytes) { this.error.set('Catalog file exceeds the configured upload byte limit.'); return; } this.busy.set(true); this.error.set('');
    try { const value=await this.api.stageSignatures(file,this.importFormat.value,this.policy.value); this.staged.set(value); this.applyKey=crypto.randomUUID(); await this.router.navigate([],{queryParams:{importId:value.id},queryParamsHandling:'merge',replaceUrl:true}); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async importPage(cursor:string):Promise<void> { const value=this.staged(); if(!value) return; try { const result=await this.api.signatureImport(value.id,cursor); if(this.staged()?.id===value.id) this.staged.set(result); } catch(e) { this.error.set(errorMessage(e)); } }
  async apply():Promise<void> { const value=this.staged(); if(!value || this.busy()) return; this.busy.set(true); this.error.set(''); try { const result=await this.api.applySignatures(value,this.applyKey); this.staged.set(await this.api.signatureImport(value.id)); this.notice.set('Applied all '+result.rowCount+' records at catalog revision '+result.catalogRevision+'.'); await this.load(); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); } }
  async export():Promise<void> { const page=this.page(); if(!page || this.busy()) return; this.busy.set(true); this.error.set(''); const payload=JSON.stringify([this.exportFormat.value,page.catalogRevision]); if(this.exportRequest?.payload!==payload) this.exportRequest={payload,key:crypto.randomUUID()};
    try { const result=await this.api.exportSignatures(this.exportFormat.value,page.catalogRevision,this.exportRequest.key); this.artifact.set(result); this.exportRequest=undefined; await this.router.navigate([],{queryParams:{exportId:result.id},queryParamsHandling:'merge',replaceUrl:true}); } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async refreshArtifact():Promise<void> { const id=this.artifact()?.id; if(!id) return; try { const value=await this.api.signatureExport(id); if(this.artifact()?.id===id) this.artifact.set(value); } catch(e) { this.error.set(errorMessage(e)); } }
  async control(action:'pause'|'resume'|'cancel'):Promise<void> { const id=this.artifact()?.id; if(!id) return; try { this.artifact.set(await this.api.controlSignatureExport(id,action)); } catch(e) { this.error.set(errorMessage(e)); } }
}
