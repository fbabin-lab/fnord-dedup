import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, Signature, SignatureWrite } from './api';
import { Signatures } from './signatures';

const record={id:'signature',revision:'1',catalogRevision:'1',name:'Benign fixture',memo:'Original memo',tags:[],sizeBytes:'5',algorithm:'SHA-256',checksum:'a'.repeat(64),filename:'old.txt',filenameBytesBase64:'b2xkLnR4dA==',filenameMatchMode:'REQUIRED_EXACT',enabled:true,origin:'MANUAL',sourceNote:'',createdAt:'2026-01-01T00:00:00Z',updatedAt:'2026-01-01T00:00:00Z',observationId:null,attemptId:null} as Signature;
function utf8Base64(value:string):string { return btoa(String.fromCharCode(...new TextEncoder().encode(value))); }
function setup(initial:Signature=record) {
  const api={
    saveSignature:vi.fn(async (_id:string|null,body:SignatureWrite)=>({...initial,...body,revision:'2',catalogRevision:'2',tags:[],
      filenameBytesBase64:body.filenameBytesBase64 ?? (body.filename ? utf8Base64(body.filename) : null)} as Signature)),
    signatures:vi.fn().mockResolvedValue({catalogRevision:'2',items:[],nextCursor:null}),
    signature:vi.fn().mockResolvedValue(initial),
    tags:vi.fn().mockResolvedValue({items:[],nextCursor:null})
  };
  TestBed.configureTestingModule({imports:[Signatures],providers:[provideRouter([]),{provide:Api,useValue:api},
    {provide:ActivatedRoute,useValue:{snapshot:{queryParamMap:convertToParamMap({})}}}]});
  const fixture=TestBed.createComponent(Signatures),view=fixture.componentInstance; view.edit(initial);
  return {view,api,fixture};
}
afterEach(()=>TestBed.resetTestingModule());

describe('signature filename and raw-byte override editing',()=>{
  it.each(['new.txt','renamed-é.txt','e\u0301.txt'])('drops stale matching bytes when changing the text filename to %s',async filename=>{
    const {view,api}=setup(); view.form.controls.filename.setValue(filename);
    expect(view.form.controls.filenameBytesBase64.value).toBe('');
    await view.save();
    expect(api.saveSignature).toHaveBeenCalledWith('signature',expect.objectContaining({filename,filenameBytesBase64:null,filenameMatchMode:'REQUIRED_EXACT'}));
    expect(view.record()?.filenameBytesBase64).toBe(utf8Base64(filename));
    expect(view.record()?.filenameBytesBase64).not.toBe(record.filenameBytesBase64);
  });
  it('clears the override when an operator types into the rendered filename input',async()=>{
    const {view,api,fixture}=setup(); vi.spyOn(view,'initialize').mockResolvedValue();
    fixture.detectChanges();
    const input=fixture.nativeElement.querySelector('input[formControlName="filename"]') as HTMLInputElement;
    input.value='typed.txt'; input.dispatchEvent(new Event('input')); fixture.detectChanges();
    expect(view.form.controls.filenameBytesBase64.value).toBe('');
    await view.save();
    expect(api.saveSignature.mock.calls[0][1]).toEqual(expect.objectContaining({filename:'typed.txt',filenameBytesBase64:null}));
  });
  it('preserves non-UTF-8 bytes when loading a record and editing only its memo',async()=>{
    const raw={...record,filename:'[bytes] \\xfb\\xff',filenameBytesBase64:'+/8='};
    const {view,api}=setup(raw); expect(view.form.controls.filenameBytesBase64.value).toBe('+/8=');
    view.form.controls.memo.setValue('New memo'); await view.save();
    expect(api.saveSignature).toHaveBeenCalledWith('signature',expect.objectContaining({filename:raw.filename,filenameBytesBase64:'+/8=',memo:'New memo'}));
  });
  it('preserves exact bytes for an escaped control-character display name',async()=>{
    const raw={...record,filename:'line\\u000abreak.txt',filenameBytesBase64:utf8Base64('line\nbreak.txt')};
    const {view,api}=setup(raw); view.form.controls.enabled.setValue(false); await view.save();
    expect(api.saveSignature.mock.calls[0][1].filenameBytesBase64).toBe(raw.filenameBytesBase64);
  });
  it('allows an explicitly entered raw-byte override after a text edit',async()=>{
    const {view,api}=setup(); view.form.controls.filename.setValue('Raw basename description');
    view.form.controls.filenameBytesBase64.setValue('+/8='); await view.save();
    expect(api.saveSignature.mock.calls[0][1].filenameBytesBase64).toBe('+/8=');
    expect(view.record()?.filenameBytesBase64).toBe('+/8=');
  });
  it('clears a prior raw override again on a later filename edit',async()=>{
    const {view,api}=setup(); view.form.controls.filenameBytesBase64.setValue('+/8=');
    view.form.controls.filename.setValue('new.txt'); await view.save();
    expect(api.saveSignature.mock.calls[0][1].filenameBytesBase64).toBeNull();
  });
  it('does not retain old exact bytes when clearing an advisory filename',async()=>{
    const {view,api}=setup({...record,filenameMatchMode:'ADVISORY'});
    view.form.controls.filename.setValue(''); await view.save();
    expect(api.saveSignature.mock.calls[0][1]).toEqual(expect.objectContaining({filename:null,filenameBytesBase64:null}));
  });
  it('preserves a rejected filename draft without restoring the old override',async()=>{
    const {view,api}=setup(); api.saveSignature.mockRejectedValueOnce(new HttpErrorResponse({status:409,error:{detail:'Signature revision conflict'}}));
    view.form.controls.filename.setValue('new.txt'); await view.save();
    expect(view.form.controls.filename.value).toBe('new.txt');
    expect(view.form.controls.filenameBytesBase64.value).toBe('');
    expect(view.record()?.revision).toBe('1'); expect(view.error()).toBe('Signature revision conflict');
    await view.save(); expect(api.saveSignature.mock.calls[1][1].filenameBytesBase64).toBeNull();
  });
  it('explicit reload restores the saved raw bytes after discarding a text draft',async()=>{
    const raw={...record,filename:'[bytes] \\xff',filenameBytesBase64:'/w=='};
    const {view}=setup(raw); view.form.controls.filename.setValue('draft.txt');
    await view.reload(); expect(view.form.controls.filename.value).toBe(raw.filename);
    expect(view.form.controls.filenameBytesBase64.value).toBe('/w==');
  });
  it('new signatures cannot inherit the previous editor raw override',()=>{
    const {view}=setup(); view.fresh(); expect(view.form.controls.filename.value).toBe('');
    expect(view.form.controls.filenameBytesBase64.value).toBe('');
  });
  it('unsubscribes filename synchronization on destruction',()=>{
    const {view}=setup(); view.ngOnDestroy(); view.form.controls.filename.setValue('ignored.txt');
    expect(view.form.controls.filenameBytesBase64.value).toBe(record.filenameBytesBase64);
  });
});
