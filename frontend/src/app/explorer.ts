import { Component, inject, OnInit, OnDestroy, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Api, Scan, SourceList, SearchQuery, SearchFilters, SearchPage, SearchEntry, Observation, Directory, SelectionDetail, Tag, AnnotationBatch, ReviewState, errorMessage } from './api';
import { AnnotationEditor, REVIEW_STATES } from './annotation-editor';
import { SignatureCoverage } from './signature-coverage';
import { TagPicker } from './tag-picker';

type TreeNode={id:string;label:string;depth:number;expanded:boolean;next:string|null};
export function exactUnit(value:string|null|undefined): string {
  if(value==null) return 'Unknown'; const bytes=BigInt(value); const units=['B','KiB','MiB','GiB','TiB','PiB','EiB'];
  let scale=1n,index=0; while(index<units.length-1 && bytes>=scale*1024n) { scale*=1024n; index++; }
  return index===0 ? `${bytes} B` : `${bytes/scale}.${(bytes%scale)*10n/scale} ${units[index]}`;
}
const textFields=[
  ['nameContains','Filename contains'],['pathContains','Path contains'],['nameExact','Exact filename'],['pathExact','Exact relative path'],['extension','Extension'],
  ['minBytes','Minimum bytes (inclusive)'],['maxBytes','Maximum bytes (inclusive)'],['mtimeFrom','Modified from (UTC, inclusive)'],['mtimeTo','Modified until (UTC, exclusive)'],
  ['signatureId','Signature ID'],['signatureTagId','Signature tag ID'],['checksum','Exact SHA-256'],['checksumPrefix','SHA-256 prefix'],['memoContains','Memo contains'],['nameBytesBase64','Exact name bytes (base64)'],['pathBytesBase64','Exact path bytes (base64)']
] as const;
@Component({selector:'app-explorer',imports:[RouterLink,ReactiveFormsModule,MatButtonModule,AnnotationEditor,TagPicker,SignatureCoverage],template:`
  <a [routerLink]="['/scans',id]">← Scan progress and duplicate groups</a>
  <p class="eyebrow">STORED FILE EXPLORER</p><h1>{{ scan()?.name ?? 'Loading scan…' }}</h1>
  <p class="lede">Search observations, record location notes, and mark files for review.</p>
  @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
  @if (notice()) { <p class="notice" role="status">{{ notice() }}</p> }
  @if (scan(); as value) {
    <p class="notice"><strong>{{ value.inventoryFrozenAt ? 'Inventory finished' : 'Partial inventory' }}</strong> · {{ value.job.state }} · {{ value.job.discoveredEntries }} entries committed.
      Saved metadata only. Refresh results to include new observations or annotation changes.</p>
    <div class="explorer-layout">
      <aside class="panel directory-tree" aria-label="Saved directories"><h2>Directories</h2>
        <button mat-button (click)="allSources()">All selected sources</button>
        <p class="small muted">Status reflects the last startup/job validation; browsing does not probe source availability.</p>
        @for (source of value.sources; track source.sourceId) {
          <p class="small">{{ source.label }} · Coverage {{ source.coverage }} · {{ sourceStatus(source.sourceId) }}</p>
        }
        @for (node of tree(); track node.id) {
          <div class="tree-row" [style.padding-left.px]="node.depth*12">
            <button type="button" class="tree-toggle" [attr.aria-label]="(node.expanded ? 'Collapse ' : 'Expand ')+node.label" [attr.aria-expanded]="node.expanded" (click)="expand(node)">{{ node.expanded ? '−' : '+' }}</button>
            <button type="button" mat-button class="path" (click)="openDirectory(node.id)">{{ node.label }}</button>
          </div>
          @if (node.expanded && node.next) { <button mat-button (click)="expand(node,true)">More folders in {{ node.label }}</button> }
        }
      </aside>
      <div class="explorer-results">
        <section class="panel"><div class="section-head"><h2>Search metadata</h2><button mat-button (click)="refresh()" [disabled]="busy()">Refresh results</button></div>
          @if (directory(); as dir) {
            <nav aria-label="Directory breadcrumbs" class="breadcrumbs">@for (crumb of dir.breadcrumbs; track crumb.locationId) {
              <button mat-button (click)="openDirectory(crumb.locationId)">{{ crumb.name || 'Source root' }}</button>
            }</nav><p class="small">Directory coverage: {{ dir.coverage ?? 'Unknown' }}</p>
          }
          <form [formGroup]="filters" (ngSubmit)="refresh()" class="filter-form">
            <div class="filter-grid"><label>Directory scope <select formControlName="scope" aria-label="Directory scope"><option value="all">All selected sources</option><option value="children" [disabled]="!directory()">This directory</option><option value="subtree" [disabled]="!directory()">This directory and descendants</option></select></label>
              <label>Source <select formControlName="sourceId" aria-label="Source"><option value="">Any source</option>@for (source of value.sources; track source.sourceId) { <option [value]="source.sourceId">{{ source.label }}</option> }</select></label>
              <label>Filename contains <input formControlName="nameContains"></label><label>Path contains <input formControlName="pathContains"></label>
              <label>Entry type <select formControlName="entryType" aria-label="Entry type"><option value="">Any type</option>@for (type of entryTypes; track type) { <option [value]="type">{{ type }}</option> }</select></label>
              <label>Sort by <select formControlName="sort" aria-label="Sort by"><option value="name">Filename bytes</option><option value="path">Path bytes</option><option value="size">Logical bytes</option><option value="mtime">Exact modified time</option><option value="checksumTime">Checksum completion time</option></select></label>
              <label>Direction <select formControlName="direction" aria-label="Direction"><option value="ASC">Ascending</option><option value="DESC">Descending</option></select></label>
            </div>
            <details><summary>More filters</summary><div class="filter-grid">
              @for (field of extraFields; track field[0]) { <label>{{ field[1] }} <input [formControlName]="field[0]" [placeholder]="field[0].startsWith('mtime') ? '2026-01-01T00:00:00Z' : ''"></label> }
              <label>Checksum status <select formControlName="hashStatus" aria-label="Checksum status"><option value="">Any checksum status</option>@for (status of hashStates; track status) { <option [value]="status">{{ status }}</option> }</select></label>
              <label>Duplicate state <select formControlName="duplicateState" aria-label="Duplicate state"><option value="">Any duplicate state</option><option>DUPLICATE</option><option>STALE</option><option>NOT_GROUPED</option></select></label>
              <label>Review-state filter <select formControlName="reviewState" aria-label="Review-state filter"><option value="">Any review state</option>@for (state of states; track state) { <option [value]="state">{{ state }}</option> }</select></label>
              @for (flag of flags; track flag[0]) { <label>{{ flag[1] }} <select [formControlName]="flag[0]" [attr.aria-label]="flag[1]"><option value="">Either</option><option value="true">Yes</option><option value="false">No</option></select></label> }
              <label>Signature match status <select formControlName="signatureStatus" aria-label="Signature match status"><option value="">Any status</option><option>MATCHED</option><option>NO_MATCH_IN_CHECKED_CATALOG</option><option>UNDETERMINED</option></select></label>
              <label>Signature check status <select formControlName="signatureCheckStatus" aria-label="Signature check status"><option value="">Any coverage</option>@for (status of signatureChecks; track status) { <option>{{ status }}</option> }</select></label>
              <label>Tag scope <select formControlName="tagScope" aria-label="Tag scope"><option value="">Manual tags</option><option value="EFFECTIVE">Manual and active derived tags</option></select></label>
              <label>Tag matching <select formControlName="tagMode" aria-label="Tag matching"><option value="ANY">Any selected tag</option><option value="ALL">All selected tags</option></select></label>
            </div><app-tag-picker label="Filter tags" [selected]="filterTags()" (selectedChange)="filterTags.set($event); invalidate()" /></details>
            <p class="small muted">Filters combine with AND. Text is literal and case-sensitive; % and _ are ordinary characters. UTC dates use a half-open interval. Empty fields are ignored.</p>
            <div class="actions"><button mat-flat-button type="submit" [disabled]="busy()">Search stored files</button><button mat-button type="button" (click)="reset()">Reset filters</button></div>
          </form>
          @if (page(); as result) {
            <p class="small">{{ result.items.length }} rows on this page · Evidence revision {{ result.evidenceRevision }}. {{ result.nextCursor ? 'More results available.' : 'End of this captured view.' }}</p>
            <div class="actions"><button mat-button (click)="selectPage()">Select this page</button><button mat-button (click)="checked.set([])">Clear selection</button>
              <button mat-stroked-button (click)="freeze(false)" [disabled]="busy() || checked().length===0">Review {{ checked().length }} selected rows</button>
              <button mat-stroked-button (click)="freeze(true)" [disabled]="busy()">Freeze all matching results</button></div>
            <p class="small muted">Bulk actions require a frozen selection of 1–500 observations. Refine filters for larger results.</p>
            <div class="table-scroll"><table><thead><tr><th>Select</th><th>Name / path</th><th>Type</th><th>Logical bytes</th><th>Modified (UTC)</th><th>Evidence / notes</th></tr></thead><tbody>
              @for (entry of result.items; track entry.id) {
                <tr><td><input type="checkbox" [attr.aria-label]="'Select '+entry.path" [checked]="checked().includes(entry.id)" (change)="toggle(entry.id)"></td>
                  <td class="path">@if (entry.entryType==='DIRECTORY') { <button mat-button (click)="openDirectory(entry.locationId)">▸ {{ entry.name || 'Source root' }}</button> }
                    @else { <button mat-button (click)="inspect(entry)">{{ entry.name }}</button> }
                    <small>{{ entry.path }}</small><button mat-button (click)="inspect(entry)">Details</button></td>
                  <td>{{ entry.entryType }}</td><td class="exact-number">{{ entry.sizeBytes ?? 'Unknown' }}<small>{{ units(entry.sizeBytes) }}</small></td><td class="small">{{ entry.mtime ?? 'Unknown' }}</td>
                  <td><span class="badge" [class.blocked]="entry.stale">{{ entry.hashStatus }}</span><p class="small">{{ entry.signatureStatus ?? 'UNDETERMINED' }} · {{ entry.signatureCheckStatus ?? 'CATALOG_NOT_CHECKED' }}</p><p class="small">{{ entry.duplicateState }} · {{ entry.annotation.reviewState }}</p>
                    @for (tag of entry.annotation.tags; track tag.id) { <span class="tag-label">{{ tag.label }}</span> }
                    @if (entry.annotation.needsReview) { <p class="small">File changed; review existing annotations.</p> }
                    @if (entry.hasError) { <p class="small">Observation has errors.</p> }</td></tr>
              } @empty { <tr><td colspan="6">No observations match this captured view.</td></tr> }
            </tbody></table></div>
            @if (result.nextCursor) { <button mat-button (click)="next(result.nextCursor)" [disabled]="busy()">Next results</button> }
          }
        </section>
      </div>
    </div>
    @if (selection(); as selected) {
      <section class="panel" aria-label="Frozen bulk review"><h2>Review frozen selection</h2><p><strong>{{ selected.count }} observations · {{ selected.totalBytes }} known logical bytes</strong> ({{ units(selected.totalBytes) }})</p>
        <p class="small">{{ selected.unknownSizes }} observations have an unknown size.</p><p class="small">Captured {{ selected.createdAt }} · Expires {{ selected.expiresAt }}. Targets stay fixed when inventory grows.</p>
        @if (selected.needsReview || selected.expired || selected.appliedAt) { <p class="notice">This selection needs a fresh review or has already been applied. Freeze a new selection before editing.</p> }
        <details><summary>Preview captured members</summary>@for (member of selected.items; track member.id) { <p class="path small">{{ member.path }} · {{ member.sizeBytes ?? 'Unknown' }} bytes · Captured annotation version {{ member.capturedAnnotation.version }}</p> }
          @if (selected.nextCursor) { <button mat-button (click)="preview(selected.nextCursor)">Next captured members</button> }</details>
        <app-tag-picker label="Add tags to selection" [selected]="addTags()" (selectedChange)="addTags.set($event)" />
        <app-tag-picker label="Remove tags from selection" [selected]="removeTags()" (selectedChange)="removeTags.set($event)" />
        <label>Bulk review state <select [formControl]="bulkState" aria-label="Bulk review state"><option value="">Leave review state unchanged</option>@for (state of states; track state) { <option [value]="state">{{ state }}</option> }</select></label>
        <p class="small muted">Calculate checksums explicitly permits source reads for the frozen regular-file selection, including unique-size files. Existing accepted evidence is reused.</p>
        <button mat-stroked-button (click)="calculateSelection()" [disabled]="busy() || selected.needsReview || selected.expired">Calculate frozen checksums</button>
        <p class="small muted">Changes affect application notes only. A conflict leaves the whole batch unchanged.</p>
        <div class="actions"><button mat-flat-button (click)="apply()" [disabled]="busy() || selected.needsReview || selected.expired || !!selected.appliedAt">Apply to {{ selected.count }} observations</button><button mat-button (click)="selection.set(null)">Close bulk review</button></div>
      </section>
    }
    @if (detail(); as entry) {
      <section class="panel"><div class="section-head"><h2>Stored observation</h2><button mat-button (click)="detail.set(null)">Close file details</button></div><p class="path">{{ entry.path }}</p>
        <dl><dt>Bytes</dt><dd>{{ entry.sizeBytes ?? 'Unknown' }} ({{ units(entry.sizeBytes) }})</dd><dt>Exact modified time</dt><dd>{{ entry.mtimeSeconds }} seconds + {{ entry.mtimeNanos }} nanoseconds UTC</dd>
          <dt>Metadata changed (ctime)</dt><dd>{{ entry.ctimeSeconds }} seconds + {{ entry.ctimeNanos }} nanoseconds UTC; not creation time</dd><dt>Inode / mount</dt><dd>{{ entry.inode ?? 'Unknown' }} / {{ entry.mountId ?? 'Unknown' }}</dd>
          <dt>Exact relative path (base64)</dt><dd class="path">{{ entry.relativePathBytesBase64 }}</dd><dt>Checksum status</dt><dd>{{ entry.hash?.status }}</dd>
          <dt>SHA-256</dt><dd class="path">{{ entry.hash?.accepted?.digest ?? 'No accepted checksum' }}</dd><dt>Checksum completed</dt><dd>{{ entry.hash?.accepted?.completedAt ?? 'Not calculated' }}</dd></dl>
        @if (entry.hash?.status==='STALE') { <p class="notice">Historical checksum invalidated. Use a new scan to establish fresh observations.</p> }
        <a [routerLink]="['/scans',id]">Open scan hashing and duplicate analysis</a>
      </section>
      <app-signature-coverage [observationId]="entry.id" [scanId]="id" />
      <app-annotation-editor [observation]="entry" (saved)="annotationSaved()" />
    }
  }
`})
export class Explorer implements OnInit,OnDestroy {
  private readonly api=inject(Api); private readonly route=inject(ActivatedRoute);
  readonly id=this.route.snapshot.paramMap.get('id') ?? '';
  readonly scan=signal<Scan|null>(null); readonly sources=signal<SourceList|null>(null); readonly directory=signal<Directory|null>(null);
  readonly tree=signal<TreeNode[]>([]); readonly page=signal<SearchPage|null>(null); readonly detail=signal<Observation|null>(null); readonly selection=signal<SelectionDetail|null>(null);
  readonly error=signal(''); readonly notice=signal(''); readonly busy=signal(false); readonly checked=signal<string[]>([]);
  readonly filterTags=signal<Tag[]>([]); readonly addTags=signal<Tag[]>([]); readonly removeTags=signal<Tag[]>([]);
  readonly bulkState=new FormControl<ReviewState|''>('',{nonNullable:true}); readonly states=REVIEW_STATES; readonly units=exactUnit;
  readonly extraFields=textFields.slice(2); readonly entryTypes=['REGULAR','DIRECTORY','SYMLINK','SPECIAL','UNKNOWN'];
  readonly signatureChecks=['CHECKED_HASH','EXCLUDED_BY_SIZE','HASH_REQUIRED','STALE','READ_ERROR','CATALOG_NOT_CHECKED'];
  readonly hashStates=['ACCEPTED','PENDING','FAILED','STALE','INELIGIBLE','NOT_REQUESTED','NOT_REQUESTED_UNIQUE_SIZE'];
  readonly flags=[['hasError','Has errors'],['stale','Stale evidence'],['annotationsNeedReview','Notes need review']];
  readonly filters=new FormGroup(Object.fromEntries([...textFields.map(([key])=>key),'sourceId','entryType','signatureStatus','signatureCheckStatus','tagScope','hashStatus','duplicateState','reviewState','hasError','stale','annotationsNeedReview','tagMode','sort','direction','scope'].map(key=>[key,new FormControl(({sort:'path',direction:'ASC',tagMode:'ANY',scope:'all'} as Record<string,string>)[key] ?? '',{nonNullable:true})])));
  private activeQuery:SearchQuery={}; private generation=0; private detailGeneration=0; private timer?:ReturnType<typeof setTimeout>; private destroyed=false;
  private hashRequest?:{id:string;key:string};
  private batch?:{payload:string;key:string}; private expanding=new Set<string>();
  ngOnInit(): void { this.filters.valueChanges.subscribe(()=>this.invalidate()); void this.load(); void this.refresh(); }
  ngOnDestroy(): void { this.destroyed=true; clearTimeout(this.timer); }
  async load():Promise<void> {
    try { const [scan,sources]=await Promise.all([this.api.scan(this.id),this.api.sources()]); if(this.destroyed) return;
      this.scan.set(scan); this.sources.set(sources); if(!this.tree().length) this.tree.set(scan.sources.map(s=>({id:s.rootLocationId,label:s.label,depth:0,expanded:false,next:null})));
    } catch(e) { if(!this.destroyed) this.error.set(errorMessage(e)); }
    finally { if(!this.destroyed) this.timer=setTimeout(()=>void this.load(),10000); }
  }
  sourceStatus(id:string):string { return this.sources()?.sources.find(s=>s.id===id)?.status ?? 'Not currently configured'; }
  invalidate():void { this.selection.set(null); this.checked.set([]); this.page.set(null); ++this.generation; }
  private query():SearchQuery {
    const values=this.filters.getRawValue(); const filters:Record<string,unknown>={};
    for(const [key,value] of Object.entries(values)) if(value && !['scope','sort','direction','tagMode'].includes(key)) filters[key]=['hasError','stale','annotationsNeedReview'].includes(key) ? value==='true' : value;
    if(this.directory() && values['scope']!=='all') filters[values['scope']==='subtree' ? 'subtreeLocationId' : 'parentLocationId']=this.directory()!.locationId;
    if(this.filterTags().length) { filters['tagIds']=this.filterTags().map(t=>t.id); filters['tagMode']=values['tagMode']; }
    return {filters:filters as SearchFilters,sort:values['sort'] as SearchQuery['sort'],direction:values['direction'] as SearchQuery['direction'],limit:100};
  }
  async refresh():Promise<void> { this.selection.set(null); this.checked.set([]); this.activeQuery=this.query(); await this.fetch(); }
  async next(cursor:string):Promise<void> { this.checked.set([]); await this.fetch(cursor); }
  private async fetch(cursor?:string):Promise<void> {
    const generation=++this.generation; this.busy.set(true); this.error.set(''); this.page.set(null);
    try { const page=await this.api.search(this.id,{...this.activeQuery,cursor}); if(generation===this.generation) this.page.set(page); }
    catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  reset():void { this.filters.reset(Object.fromEntries(Object.keys(this.filters.controls).map(key=>[key,({sort:'path',direction:'ASC',tagMode:'ANY',scope:'all'} as Record<string,string>)[key] ?? '']))); this.filterTags.set([]); void this.refresh(); }
  allSources():void { this.directory.set(null); this.filters.controls['scope'].setValue('all'); void this.refresh(); }
  async openDirectory(id:string):Promise<void> {
    try { this.directory.set(await this.api.directory(this.id,id)); this.filters.controls['scope'].setValue('children'); await this.refresh(); }
    catch(e) { this.error.set(errorMessage(e)); }
  }
  async expand(node:TreeNode,more=false):Promise<void> {
    if(this.expanding.has(node.id)) return;
    const current=this.tree(),index=current.findIndex(n=>n.id===node.id); if(index<0) return;
    if(node.expanded && !more) { let end=index+1; while(end<current.length && current[end].depth>node.depth) end++; this.tree.set([...current.slice(0,index),{...node,expanded:false,next:null},...current.slice(end)]); return; }
    if(current.length>=1000) { this.error.set('Collapse some folders before expanding more. The tree displays up to 1,000 directories.'); return; }
    this.expanding.add(node.id);
    try {
      const page=await this.api.search(this.id,{filters:{parentLocationId:node.id,entryType:'DIRECTORY'},sort:'name',limit:50,cursor:more ? node.next : null});
      const nodes=this.tree(); if(nodes.length+page.items.length>1000) { this.error.set('Collapse some folders before expanding more; the tree is limited to 1,000 visible directories.'); return; } const at=nodes.findIndex(n=>n.id===node.id); if(at<0) return;
      let end=at+1; while(end<nodes.length && nodes[end].depth>node.depth) end++;
      this.tree.set([...nodes.slice(0,at),{...nodes[at],expanded:true,next:page.nextCursor},...nodes.slice(at+1,end),...page.items.map(e=>({id:e.locationId,label:e.name,depth:node.depth+1,expanded:false,next:null})),...nodes.slice(end)]);
    } catch(e) { this.error.set(errorMessage(e)); } finally { this.expanding.delete(node.id); }
  }
  toggle(id:string):void { this.checked.update(ids=>ids.includes(id) ? ids.filter(x=>x!==id) : [...ids,id]); }
  selectPage():void { this.checked.set(this.page()?.items.map(e=>e.id) ?? []); }
  async inspect(entry:SearchEntry):Promise<void> {
    const generation=++this.detailGeneration;
    try { const detail=await this.api.observation(entry.id); if(generation===this.detailGeneration) this.detail.set(detail); }
    catch(e) { this.error.set(errorMessage(e)); }
  }
  annotationSaved():void { this.notice.set('Notes saved. Refresh results to use updated annotations in filters or bulk selections.'); this.selection.set(null); }
  async freeze(all:boolean):Promise<void> {
    const page=this.page(); if(!page || this.busy()) return; const generation=this.generation; this.busy.set(true); this.error.set('');
    try { const selection=await this.api.freeze(this.id,{query:this.activeQuery,viewToken:page.viewToken,...(!all ? {observationIds:this.checked()} : {})}); const preview=await this.api.selection(selection.id); if(generation!==this.generation) return; this.selection.set(preview); this.addTags.set([]); this.removeTags.set([]); this.bulkState.setValue(''); this.batch=undefined; }
    catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async preview(cursor:string):Promise<void> { const selected=this.selection(); if(!selected) return; try { const preview=await this.api.selection(selected.id,cursor); if(this.selection()?.id===selected.id) this.selection.set(preview); } catch(e) { this.error.set(errorMessage(e)); } }
  async calculateSelection():Promise<void> {
    const selected=this.selection(); if(!selected || this.busy()) return;
    if(this.hashRequest?.id!==selected.id) this.hashRequest={id:selected.id,key:crypto.randomUUID()};
    this.busy.set(true); this.error.set('');
    try { const job=await this.api.hashSelection(this.id,selected.id,this.hashRequest.key); this.hashRequest=undefined; this.selection.set(null); this.notice.set(`Checksum job ${job.jobId} queued. Follow pause/resume/cancel controls in Scan progress and duplicate groups.`); }
    catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async apply():Promise<void> {
    const selected=this.selection(); if(!selected || this.busy()) return;
    const state=this.bulkState.value;
    const body:AnnotationBatch={selectionId:selected.id,addTagIds:this.addTags().map(t=>t.id),removeTagIds:this.removeTags().map(t=>t.id),...(state ? {reviewState:state} : {})};
    const payload=JSON.stringify(body); if(this.batch?.payload!==payload) this.batch={payload,key:crypto.randomUUID()};
    this.busy.set(true); this.error.set('');
    try { const result=await this.api.annotationBatch(body,this.batch.key); this.batch=undefined; this.selection.set(null); this.notice.set(`Updated ${result.updatedCount} frozen observations.`); await this.refresh(); }
    catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
}
