import { Component, EventEmitter, Input, Output, OnChanges, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Api, Annotation, Observation, HistoryPage, Tag, ReviewState, errorMessage } from './api';
import { TagPicker } from './tag-picker';

export const REVIEW_STATES: ReviewState[]=['UNREVIEWED','REVIEWED','KEEP','REMOVAL_REVIEW'];
@Component({selector:'app-annotation-editor',imports:[ReactiveFormsModule,MatButtonModule,TagPicker],template:`
  <section class="panel" aria-label="Location annotations"><h2>Location annotations</h2>
    <p class="path">{{ observation.path }}</p><p class="small muted">Notes belong to this path in this source instance and remain across scans. Renamed paths start with empty notes.</p>
    @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
    @if (notice()) { <p role="status">{{ notice() }}</p> }
    @if (stored(); as annotation) {
      @if (annotation.needsReview) { <p class="notice"><strong>File changed; review existing annotations.</strong></p> }
      <form [formGroup]="form" (ngSubmit)="save()">
        <label>Location memo <textarea formControlName="memo" rows="5" aria-describedby="memo-limit"></textarea></label>
        <p id="memo-limit" class="small muted">Plain text, up to 20,000 characters. Saving reaffirms these notes for the displayed observation.</p>
        <label>Review state <select formControlName="reviewState" aria-label="Review state">@for (state of states; track state) { <option [value]="state">{{ state }}</option> }</select></label>
        <p class="small muted">KEEP marks a location for protection in future review plans. REMOVAL_REVIEW is a review label; it performs no file action.</p>
        <app-tag-picker [selected]="tags()" (selectedChange)="tags.set($event); form.markAsDirty()" />
        <div class="actions"><button mat-flat-button type="submit" [disabled]="busy()">Save location notes</button>
          <button mat-button type="button" (click)="load()" [disabled]="busy()">Discard draft and reload</button></div>
        <p class="small muted">Version {{ annotation.version }} · {{ annotation.updatedAt ?? 'No saved notes yet' }}</p>
      </form>
    }
    <button mat-button (click)="loadHistory()">View location history</button>
    @if (history(); as page) {
      <p class="small muted">Separate observations of this location; these rows are not separate duplicate copies.</p>
      @for (entry of page.items; track entry.id) {
        <p class="small path">{{ entry.observedAt }} · {{ entry.sizeBytes ?? 'Unknown' }} bytes · {{ entry.hashStatus }} · Scan {{ entry.scanId }}
          @if (entry.annotation.needsReview) { <strong>File changed; review existing annotations.</strong> }</p>
      }
      @if (page.nextCursor) { <button mat-button (click)="loadHistory(page.nextCursor)">Next history</button> }
    }
  </section>`})
export class AnnotationEditor implements OnChanges {
  private readonly api=inject(Api);
  @Input({required:true}) observation!: Observation;
  @Output() saved=new EventEmitter<Annotation>();
  readonly states=REVIEW_STATES;
  readonly stored=signal<Annotation|null>(null); readonly tags=signal<Tag[]>([]);
  readonly history=signal<HistoryPage|null>(null); readonly error=signal(''); readonly notice=signal(''); readonly busy=signal(false);
  readonly form=new FormGroup({memo:new FormControl('',{nonNullable:true}),reviewState:new FormControl<ReviewState>('UNREVIEWED',{nonNullable:true})});
  private generation=0;
  ngOnChanges(): void { this.stored.set(null); this.tags.set([]); this.form.reset({memo:'',reviewState:'UNREVIEWED'}); this.history.set(null); void this.load(); }
  private accept(value:Annotation): void { this.stored.set(value); this.tags.set(value.tags); this.form.reset({memo:value.memo,reviewState:value.reviewState}); }
  async load(): Promise<void> {
    const generation=++this.generation; this.error.set(''); this.notice.set('');
    try { const value=await this.api.annotation(this.observation.locationId,this.observation.id); if(generation===this.generation) this.accept(value); }
    catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); }
  }
  async save(): Promise<void> {
    const annotation=this.stored(); if(!annotation || this.busy()) return;
    const generation=this.generation; this.busy.set(true); this.error.set(''); this.notice.set('');
    try {
      const value=await this.api.saveAnnotation(this.observation.locationId,{...this.form.getRawValue(),observationId:this.observation.id,expectedVersion:annotation.version,tagIds:this.tags().map(t=>t.id)});
      if(generation===this.generation) { this.accept(value); this.notice.set('Location notes saved.'); this.saved.emit(value); }
    } catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
  async loadHistory(cursor?:string|null): Promise<void> {
    const generation=this.generation;
    try { const value=await this.api.history(this.observation.locationId,cursor); if(generation===this.generation) this.history.set(value); }
    catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); }
  }
}
