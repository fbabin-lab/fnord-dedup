import { TestBed } from '@angular/core/testing';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api } from './api';
import { DuplicateGroups } from './duplicate-groups';

afterEach(() => TestBed.resetTestingModule());
describe('duplicate evidence presentation', () => {
  it('keeps stale evidence explicit and unknown object counts unknown', async () => {
    const api = {groups:vi.fn().mockResolvedValue({analysisId:'revision',evidenceRevision:'4',status:'NEEDS_REBUILD',nextCursor:null,items:[{
      id:'group',analysisId:'revision',scanId:'scan',sizeBytes:'9007199254740999',algorithm:'SHA-256',digest:'a'.repeat(64),pathCount:'2',objectCount:null,
      identityStatus:'IDENTITY_UNKNOWN',evidenceLevel:'STALE',sourceIds:['root'],pathLogicalBytes:'18014398509481998',independentObjectLogicalBytes:null,
      maximumDuplicateCopyLogicalBytes:null,physicalSavingsBytes:null
    }]})};
    TestBed.configureTestingModule({imports:[DuplicateGroups],providers:[{provide:Api,useValue:api}]});
    const fixture = TestBed.createComponent(DuplicateGroups); fixture.componentInstance.scanId = 'scan';
    await fixture.componentInstance.load(); fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('STALE'); expect(text).toContain('NEEDS_REBUILD');
    expect(text).toContain('9007199254740999'); expect(text).toContain('2 / Unknown');
    expect(text).toContain('Physical space savings are unknown');
    expect(text).not.toContain('BYTE_VERIFIED');
  });
  it('pins the analysis revision while paging group results', async () => {
    const api = {groups:vi.fn().mockResolvedValue({analysisId:'revision',evidenceRevision:'4',status:'CURRENT',items:[],nextCursor:null})};
    TestBed.configureTestingModule({imports:[DuplicateGroups],providers:[{provide:Api,useValue:api}]});
    const fixture = TestBed.createComponent(DuplicateGroups); fixture.componentInstance.scanId = 'scan';
    await fixture.componentInstance.load('opaque-cursor','revision');
    expect(api.groups).toHaveBeenCalledWith('scan','revision','opaque-cursor');
  });
});
