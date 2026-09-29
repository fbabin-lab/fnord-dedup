import { Component, EventEmitter, Input, Output, inject, signal, OnInit } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Api, Tag, TagPage, errorMessage } from './api';

@Component({selector:'app-tag-picker',imports:[ReactiveFormsModule,MatButtonModule],template:`
  <fieldset class="tag-picker"><legend>{{ label }}</legend>
    <div class="actions">@for (tag of selected; track tag.id) { <button type="button" mat-stroked-button (click)="toggle(tag)">Remove {{ tag.label }}</button> }
      @empty { <span class="small muted">No tags selected.</span> }</div>
    <details><summary>Choose or manage reusable tags</summary>
      <div class="actions"><label>Find tags <input [formControl]="query" maxlength="64"></label><button type="button" mat-button (click)="load()">Find tags</button></div>
      @if (error()) { <p class="error" role="alert">{{ error() }}</p> }
      @for (tag of page()?.items ?? []; track tag.id) {
        <div class="actions"><label><input type="checkbox" [checked]="has(tag.id)" (change)="toggle(tag)"> {{ tag.label }}</label>
          <button type="button" mat-button (click)="rename.set(tag); name.setValue(tag.label)">Rename {{ tag.label }}</button></div>
      } @empty { <p class="small">No matching tags.</p> }
      @if (page()?.nextCursor; as cursor) { <button type="button" mat-button (click)="load(cursor)">Next tags</button> }
      <div class="actions"><label>{{ rename() ? 'New tag label' : 'Create tag label' }} <input [formControl]="name" maxlength="128"></label>
        <button type="button" mat-stroked-button (click)="save()" [disabled]="busy() || !name.value.trim()">{{ rename() ? 'Save tag label' : 'Create tag' }}</button>
        @if (rename()) { <button type="button" mat-button (click)="rename.set(null); name.setValue('')">Cancel rename</button> }</div>
      <p class="small muted">Tags are shared across locations. Labels compare without case using Unicode NFKC; filenames retain their original bytes.</p>
    </details>
  </fieldset>`})
export class TagPicker implements OnInit {
  private readonly api=inject(Api);
  @Input() label='Manual tags'; @Input() selected: Tag[]=[];
  @Output() selectedChange=new EventEmitter<Tag[]>();
  readonly query=new FormControl('',{nonNullable:true}); readonly name=new FormControl('',{nonNullable:true});
  readonly page=signal<TagPage|null>(null); readonly error=signal(''); readonly busy=signal(false); readonly rename=signal<Tag|null>(null);
  private generation=0;
  ngOnInit(): void { void this.load(); }
  has(id: string): boolean { return this.selected.some(tag=>tag.id===id); }
  toggle(tag: Tag): void { this.selectedChange.emit(this.has(tag.id) ? this.selected.filter(t=>t.id!==tag.id) : [...this.selected,tag]); }
  async load(cursor?:string|null): Promise<void> {
    const generation=++this.generation; this.error.set('');
    try { const page=await this.api.tags(this.query.value,cursor); if(generation===this.generation) this.page.set(page); }
    catch(e) { if(generation===this.generation) this.error.set(errorMessage(e)); }
  }
  async save(): Promise<void> {
    if(this.busy()) return; this.busy.set(true); this.error.set('');
    try {
      const old=this.rename(); const tag=old ? await this.api.renameTag(old,this.name.value) : await this.api.createTag(this.name.value);
      this.selectedChange.emit(old ? this.selected.map(t=>t.id===tag.id ? tag : t) : [...this.selected,tag]);
      this.rename.set(null); this.name.setValue(''); this.query.setValue(''); await this.load();
    } catch(e) { this.error.set(errorMessage(e)); } finally { this.busy.set(false); }
  }
}
