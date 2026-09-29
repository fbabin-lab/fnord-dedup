import { TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, Signature, SignaturePreview, SignatureFindings, Observation } from './api';
import { Signatures } from './signatures';
import { SignatureCheck } from './signature-check';
import { SignatureCoverage } from './signature-coverage';
const record={id:'signature',revision:'2',catalogRevision:'3',name:'<img src=x onerror=alert(1)>',memo:'=inert',tags:[{id:'tag',label:'Original label',version:'1'}],sizeBytes:'9007199254740993',algorithm:'SHA-256',checksum:'a'.repeat(64),filename:null,filenameBytesBase64:null,filenameMatchMode:'ADVISORY',enabled:true,origin:'MANUAL',sourceNote:'',createdAt:'2026-01-01T00:00:00Z',updatedAt:'2026-01-01T00:00:00Z',observationId:null,attemptId:null} as Signature;
const preview={id:'preview',scanId:'scan',catalogRevision:'3',candidateFiles:'1',candidateBytes:'9007199254740993',expiresAt:'2026-01-01T00:00:00Z'} as SignaturePreview;
afterEach(()=>TestBed.resetTestingModule());
describe('signature catalog and explicit coverage',()=>{
  it('requires separate confirmation after an exact read estimate and retries with the same idempotency key',async()=>{
    const api={signaturePreview:vi.fn().mockResolvedValue(preview),checkSignatures:vi.fn().mockRejectedValueOnce(new HttpErrorResponse({status:503})).mockResolvedValue({jobId:'job'})};
    TestBed.configureTestingModule({imports:[SignatureCheck],providers:[{provide:Api,useValue:api}]});
    const fixture=TestBed.createComponent(SignatureCheck),view=fixture.componentInstance; view.scanId='scan'; view.frozen=true;
    await view.estimate(); fixture.detectChanges();
    expect(api.checkSignatures).not.toHaveBeenCalled(); expect(fixture.nativeElement.textContent).toContain('9007199254740993 bytes');
    await view.confirm(); const first=api.checkSignatures.mock.calls[0]; expect(view.preview()).toEqual(preview);
    await view.confirm(); expect(api.checkSignatures.mock.calls[1]).toEqual(first); expect(view.preview()).toBeNull();
  });
  it('keeps a conflicting signature draft and stable tag IDs with exact decimal size',async()=>{
    const api={saveSignature:vi.fn().mockRejectedValue(new HttpErrorResponse({status:409,error:{detail:'Signature revision conflict'}}))};
    TestBed.configureTestingModule({imports:[Signatures],providers:[provideRouter([]),{provide:Api,useValue:api},{provide:ActivatedRoute,useValue:{snapshot:{queryParamMap:convertToParamMap({})}}}]});
    const view=TestBed.createComponent(Signatures).componentInstance; view.edit(record); view.form.controls.memo.setValue('Unsaved memo'); await view.save();
    expect(view.form.controls.memo.value).toBe('Unsaved memo'); expect(view.record()?.revision).toBe('2'); expect(view.error()).toBe('Signature revision conflict');
    expect(api.saveSignature).toHaveBeenCalledWith('signature',expect.objectContaining({expectedRevision:'2',sizeBytes:'9007199254740993',memo:'Unsaved memo',tagIds:['tag']}));
  });
  it('does not silently hash or fabricate a fingerprint when creating from an observation',async()=>{
    const api={saveSignature:vi.fn().mockRejectedValue(new HttpErrorResponse({status:409,error:{detail:'Hash required'}})),hash:vi.fn()};
    TestBed.configureTestingModule({imports:[Signatures],providers:[provideRouter([]),{provide:Api,useValue:api},{provide:ActivatedRoute,useValue:{snapshot:{queryParamMap:convertToParamMap({})}}}]});
    const view=TestBed.createComponent(Signatures).componentInstance; view.fresh(); view.observation.set({id:'observation',hash:{status:'NOT_REQUESTED'}} as Observation); view.form.controls.name.setValue('New'); await view.save();
    expect(api.hash).not.toHaveBeenCalled(); const body=api.saveSignature.mock.calls[0][1]; expect(body.observationId).toBe('observation'); expect(body.checksum).toBeUndefined(); expect(body.sizeBytes).toBeUndefined();
  });
  it('renders derived labels as text and distinguishes missing coverage from a confirmed finding',async()=>{
    const result={runId:'run',catalogRevision:'3',catalogCurrent:true,matchStatus:'UNDETERMINED',checkStatus:'HASH_REQUIRED',checkedAt:null,attemptId:null,observationId:'obs',current:true,items:[{signature:record,matchedAt:'2026-01-01T00:00:00Z',active:false}],nextCursor:null} as SignatureFindings;
    const api={observationSignatures:vi.fn().mockResolvedValue(result)};
    TestBed.configureTestingModule({imports:[SignatureCoverage],providers:[provideRouter([]),{provide:Api,useValue:api}]});
    const fixture=TestBed.createComponent(SignatureCoverage),view=fixture.componentInstance; view.observationId='obs'; view.scanId='scan'; await view.load(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBeNull(); expect(fixture.nativeElement.textContent).toContain('UNDETERMINED'); expect(fixture.nativeElement.textContent).toContain('Historical / inactive finding'); expect(fixture.nativeElement.textContent).toContain('no accepted checksum');
  });
});
