import { TestBed } from '@angular/core/testing';
import { provideRouter, ActivatedRoute, convertToParamMap } from '@angular/router';
import { vi, afterEach, describe, expect, it } from 'vitest';
import { Api, Job, Scan } from './api';
import { ScanDetail, averageRate } from './scan-detail';

const job: Job = {
  id:'job',scanId:'scan',type:'INVENTORY',phase:'INVENTORY',state:'PAUSED',version:'1',
  createdAt:'2026-09-28T12:00:00Z',startedAt:'2026-09-28T12:00:00Z',updatedAt:'2026-09-28T12:00:02Z',finishedAt:null,
  heartbeatAt:null,checkpointAt:null,currentSourceId:null,currentPath:null,blockCode:null,leaseSeconds:60,heartbeatSeconds:10,
  discoveredEntries:'90071992547409931',discoveredFiles:'90071992547409930',discoveredDirectories:'1',discoveredBytes:'99999999999999999999',
  candidateFiles:'0',candidateBytes:'0',hashedFiles:'0',reusedFiles:'0',physicalBytesRead:'0',usefulBytesHashed:'0',errorCount:'0',skippedEntries:'0',pendingWork:'1',completedWork:'0',totalKnown:false,waitingForIo:false
};
const scan: Scan = {
  id:'scan',name:'<img src=x onerror=alert(1)>',configurationRevision:'a'.repeat(64),createdAt:job.createdAt,inventoryFrozenAt:null,inventoryOnly:true,analysisAvailable:false,
  analysisId:null,evidenceRevision:'0',activeHashJobs:[],latestJob:job,job,sources:[{sourceId:'source',sourceInstanceId:'instance',label:'Fixture',rootLocationId:'root',coverage:'PARTIAL'}]
};

afterEach(() => { TestBed.resetTestingModule(); vi.useRealTimers(); });

describe('durable inventory presentation', () => {
  it('keeps large byte values exact and untrusted names inert', async () => {
    const api = {scan:vi.fn().mockResolvedValue(scan),errors:vi.fn().mockResolvedValue({items:[],nextCursor:null})};
    TestBed.configureTestingModule({imports:[ScanDetail],providers:[provideRouter([]),{provide:Api,useValue:api},{provide:ActivatedRoute,useValue:{snapshot:{paramMap:convertToParamMap({id:'scan'})}}}]});
    const fixture = TestBed.createComponent(ScanDetail);
    await fixture.componentInstance.load(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('99999999999999999999');
    expect(fixture.nativeElement.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Scan incomplete.');
    expect(fixture.nativeElement.querySelector('[role=progressbar]')).toBeNull();
    fixture.destroy();
  });
  it('stops polling when the view closes without cancelling server work', async () => {
    vi.useFakeTimers();
    const api = {scan:vi.fn().mockResolvedValue({...scan,job:{...job,state:'RUNNING'}}),errors:vi.fn().mockResolvedValue({items:[],nextCursor:null}),control:vi.fn()};
    TestBed.configureTestingModule({imports:[ScanDetail],providers:[provideRouter([]),{provide:Api,useValue:api},{provide:ActivatedRoute,useValue:{snapshot:{paramMap:convertToParamMap({id:'scan'})}}}]});
    const fixture = TestBed.createComponent(ScanDetail);
    fixture.detectChanges();
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(2000);
    expect(api.scan).toHaveBeenCalledTimes(2);
    fixture.destroy();
    await vi.advanceTimersByTimeAsync(20000);
    expect(api.scan).toHaveBeenCalledTimes(2);
    expect(api.control).not.toHaveBeenCalled();
  });
  it('calculates rates using exact integers', () => {
    expect(averageRate({...job,finishedAt:'2026-09-28T12:00:10Z'})).toBe('9007199254740993');
    expect(averageRate({...job,startedAt:null})).toBeNull();
  });
});
