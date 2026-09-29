import { TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, Annotation, Observation } from './api';
import { AnnotationEditor } from './annotation-editor';
import { Explorer, exactUnit } from './explorer';

const note:Annotation={locationId:'location',observationId:'entry',version:'1',memo:'<img src=x onerror=alert(1)>',reviewState:'KEEP',tags:[{id:'tag',label:'<script>inert</script>',version:'1'}],needsReview:true,baselineObservationId:'entry',updatedAt:null,updatedBy:'operator'};
const observation={id:'entry',locationId:'location',path:'<img src=x onerror=alert(1)>',sizeBytes:'9223372036854775807'} as Observation;
afterEach(()=>TestBed.resetTestingModule());
describe('location notes and explorer',()=>{
  it('preserves a conflicting draft and reloads only at an explicit request',async()=>{
    const api={annotation:vi.fn().mockResolvedValue(note),saveAnnotation:vi.fn().mockRejectedValue(new HttpErrorResponse({status:409,error:{detail:'These notes changed in another view.'}})),tags:vi.fn().mockResolvedValue({items:[],nextCursor:null})};
    TestBed.configureTestingModule({imports:[AnnotationEditor],providers:[{provide:Api,useValue:api}]});
    const fixture=TestBed.createComponent(AnnotationEditor); const editor=fixture.componentInstance; editor.observation=observation;
    await editor.load(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBeNull(); expect(fixture.nativeElement.querySelector('script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('File changed; review existing annotations.');
    editor.form.controls.memo.setValue('unsaved second tab'); await editor.save(); fixture.detectChanges();
    expect(editor.form.controls.memo.value).toBe('unsaved second tab'); expect(editor.stored()?.version).toBe('1');
    expect(api.saveAnnotation).toHaveBeenCalledWith('location',expect.objectContaining({expectedVersion:'1',memo:'unsaved second tab',tagIds:['tag']}));
    expect(fixture.nativeElement.textContent).toContain('These notes changed in another view.');
    await editor.load(); expect(editor.form.controls.memo.value).toBe(note.memo);
  });
  it('does not let a late response overwrite a newly selected observation',async()=>{
    let resolve!:(value:Annotation)=>void;
    const api={annotation:vi.fn().mockImplementationOnce(()=>new Promise<Annotation>(r=>resolve=r)).mockResolvedValue({...note,version:'2',memo:'new location'})};
    TestBed.configureTestingModule({imports:[AnnotationEditor],providers:[{provide:Api,useValue:api}]});
    const editor=TestBed.createComponent(AnnotationEditor).componentInstance; editor.observation=observation;
    const old=editor.load(); editor.stored.set(note); editor.observation={...observation,id:'next'}; editor.ngOnChanges(); expect(editor.stored()).toBeNull(); await Promise.resolve(); resolve(note); await old;
    expect(editor.form.controls.memo.value).toBe('new location');
  });
  it('retains exact decimal inputs when searching and binds subsequent pages to that query',async()=>{
    const api={search:vi.fn().mockResolvedValue({items:[],nextCursor:'next',viewToken:'view',evidenceRevision:'1',annotationRevision:'0',analysisId:null})};
    TestBed.configureTestingModule({imports:[Explorer],providers:[provideRouter([]),{provide:Api,useValue:api},{provide:ActivatedRoute,useValue:{snapshot:{paramMap:convertToParamMap({id:'scan'})}}}]});
    const explorer=TestBed.createComponent(Explorer).componentInstance;
    explorer.filters.controls['minBytes'].setValue('9007199254740993'); explorer.filters.controls['nameContains'].setValue('%_');
    await explorer.refresh(); await explorer.next('next');
    expect(api.search).toHaveBeenLastCalledWith('scan',expect.objectContaining({filters:{minBytes:'9007199254740993',nameContains:'%_'},cursor:'next'}));
  });
  it('formats binary units with integer arithmetic above both JS and signed 64-bit bounds',()=>{
    expect(exactUnit('18446744073709551614')).toBe('15.9 EiB'); expect(exactUnit('9007199254740993')).toBe('8.0 PiB'); expect(exactUnit(null)).toBe('Unknown'); expect(exactUnit('0')).toBe('0 B');
  });
});
