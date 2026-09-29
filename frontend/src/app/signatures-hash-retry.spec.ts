import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Api, Job, Observation } from './api';
import { Signatures } from './signatures';

function setup() {
  const observation={id:'observation',scanId:'scan',hash:{status:'NOT_REQUESTED',pending:false}} as Observation;
  const api={
    hash:vi.fn().mockResolvedValue({scanId:'scan',jobId:'job-1'}),
    job:vi.fn().mockResolvedValue({id:'job-1',state:'RUNNING'} as Job),
    observation:vi.fn().mockResolvedValue(observation)
  };
  TestBed.configureTestingModule({imports:[Signatures],providers:[
    provideRouter([]),{provide:Api,useValue:api},
    {provide:ActivatedRoute,useValue:{snapshot:{queryParamMap:convertToParamMap({})}}}
  ]});
  const view=TestBed.createComponent(Signatures).componentInstance;
  view.fresh(); view.observation.set(observation);
  return {view,api,observation};
}

function deferred<T>() {
  let resolve!:(value:T)=>void;
  const promise=new Promise<T>(done=>{resolve=done;});
  return {promise,resolve};
}

const unavailable=()=>new HttpErrorResponse({status:503});
afterEach(()=>TestBed.resetTestingModule());

describe('observation hash submission intents',()=>{
  it('retains the same key and payload after an ambiguous submission failure',async()=>{
    const {view,api}=setup(); api.hash.mockRejectedValueOnce(unavailable());
    await view.hashObservation();
    const first=api.hash.mock.calls[0]; expect(view.error()).not.toBe('');
    await view.hashObservation();
    expect(api.hash.mock.calls[1]).toEqual(first);
    expect(api.job).not.toHaveBeenCalled(); expect(view.busy()).toBe(false);
  });

  it.each(['CANCELLED','FAILED','COMPLETED_WITH_ERRORS','COMPLETED'] as const)(
    'uses a new key and job for an explicit retry after %s without accepted evidence',async state=>{
      const {view,api}=setup(); await view.hashObservation();
      const first=api.hash.mock.calls[0];
      api.job.mockResolvedValue({id:'job-1',state} as Job);
      api.hash.mockResolvedValue({scanId:'scan',jobId:'job-2'});
      await view.refreshObservation(); await view.hashObservation();
      expect(api.job).toHaveBeenCalledWith('job-1');
      expect(api.hash).toHaveBeenCalledTimes(2);
      expect(api.hash.mock.calls[1].slice(0,3)).toEqual(first.slice(0,3));
      expect(api.hash.mock.calls[1][3]).not.toBe(first[3]);
      expect(view.notice()).toContain('job-2');
      // Subsequent status checks follow the replacement job, not cancelled history.
      api.job.mockResolvedValue({id:'job-2',state:'RUNNING'} as Job);
      await view.hashObservation();
      expect(api.job).toHaveBeenLastCalledWith('job-2');
      expect(api.hash).toHaveBeenCalledTimes(2);
    }
  );

  it.each(['QUEUED','RUNNING','PAUSE_REQUESTED','PAUSED','CANCEL_REQUESTED','INTERRUPTED'] as const)(
    'does not create a second job while the acknowledged job is %s',async state=>{
      const {view,api}=setup(); await view.hashObservation();
      api.job.mockResolvedValue({id:'job-1',state} as Job);
      await view.hashObservation();
      expect(api.hash).toHaveBeenCalledTimes(1);
      expect(view.notice()).toContain(state);
      expect(view.notice()).toContain('no new job was submitted');
    }
  );

  it('does not submit another job when the previous job status cannot be read',async()=>{
    const {view,api}=setup(); await view.hashObservation();
    api.job.mockRejectedValueOnce(unavailable());
    await view.hashObservation();
    expect(api.hash).toHaveBeenCalledTimes(1); expect(view.notice()).toBe('');
    expect(view.error()).not.toBe(''); expect(view.busy()).toBe(false);
    await view.hashObservation();
    expect(api.job).toHaveBeenLastCalledWith('job-1'); expect(api.hash).toHaveBeenCalledTimes(1);
  });

  it('requires a successful evidence refresh before creating a post-terminal retry',async()=>{
    const {view,api}=setup(); await view.hashObservation();
    api.job.mockResolvedValue({id:'job-1',state:'CANCELLED'} as Job);
    api.observation.mockRejectedValueOnce(unavailable());
    await view.hashObservation(); expect(api.hash).toHaveBeenCalledTimes(1);
    await view.hashObservation(); expect(api.hash).toHaveBeenCalledTimes(2);
    expect(api.hash.mock.calls[1][3]).not.toBe(api.hash.mock.calls[0][3]);
  });

  it('remembers an acknowledged job when the following evidence refresh fails',async()=>{
    const {view,api}=setup(); api.observation.mockRejectedValueOnce(unavailable());
    await view.hashObservation(); expect(view.error()).not.toBe('');
    await view.hashObservation();
    expect(api.job).toHaveBeenCalledWith('job-1'); expect(api.hash).toHaveBeenCalledTimes(1);
  });

  it('does not request another hash when completion has already supplied accepted evidence',async()=>{
    const {view,api,observation}=setup(); await view.hashObservation();
    api.job.mockResolvedValue({id:'job-1',state:'COMPLETED'} as Job);
    api.observation.mockResolvedValue({...observation,hash:{status:'ACCEPTED',pending:false}} as Observation);
    await view.hashObservation();
    expect(api.hash).toHaveBeenCalledTimes(1); expect(view.observation()?.hash?.status).toBe('ACCEPTED');
    expect(view.notice()).toContain('already has an accepted SHA-256');
  });

  it('does not duplicate a hash job queued by another tab after the previous job ended',async()=>{
    const {view,api,observation}=setup(); await view.hashObservation();
    api.job.mockResolvedValue({id:'job-1',state:'CANCELLED'} as Job);
    api.observation.mockResolvedValue({...observation,hash:{status:'PENDING',pending:true}} as Observation);
    await view.hashObservation();
    expect(api.hash).toHaveBeenCalledTimes(1); expect(view.notice()).toContain('Another hash request is pending');
  });

  it('keeps the replacement key if the new post-cancellation submission has an uncertain outcome',async()=>{
    const {view,api}=setup(); await view.hashObservation();
    api.job.mockResolvedValue({id:'job-1',state:'CANCELLED'} as Job);
    api.hash.mockRejectedValueOnce(unavailable()).mockResolvedValue({scanId:'scan',jobId:'job-2'});
    await view.hashObservation(); await view.hashObservation();
    expect(api.hash.mock.calls[1][3]).not.toBe(api.hash.mock.calls[0][3]);
    expect(api.hash.mock.calls[2]).toEqual(api.hash.mock.calls[1]);
    expect(api.job).toHaveBeenCalledTimes(1);
  });

  it('recovers an ambiguous old submission before allowing a separate post-cancellation intent',async()=>{
    const {view,api}=setup(); api.hash.mockRejectedValueOnce(unavailable());
    await view.hashObservation(); await view.hashObservation();
    expect(api.hash.mock.calls[1]).toEqual(api.hash.mock.calls[0]);
    expect(view.notice()).not.toContain('queued');
    api.job.mockResolvedValue({id:'job-1',state:'CANCELLED'} as Job);
    api.hash.mockResolvedValue({scanId:'scan',jobId:'job-2'});
    await view.hashObservation();
    expect(api.hash.mock.calls[2][3]).not.toBe(api.hash.mock.calls[0][3]);
    expect(view.notice()).toContain('job-2');
  });

  it('ignores a double click while the submission is in flight',async()=>{
    const {view,api}=setup(); const pending=deferred<{scanId:string;jobId:string}>();
    api.hash.mockReturnValueOnce(pending.promise);
    const first=view.hashObservation(); await view.hashObservation();
    expect(api.hash).toHaveBeenCalledTimes(1); expect(view.busy()).toBe(true);
    pending.resolve({scanId:'scan',jobId:'job-1'}); await first;
    expect(view.busy()).toBe(false);
  });

  it('binds the key to the observation and scan instead of the lifetime of the editor',async()=>{
    const {view,api,observation}=setup(); api.hash.mockRejectedValueOnce(unavailable());
    await view.hashObservation();
    view.fresh(); view.observation.set({...observation,id:'other',scanId:'other-scan'} as Observation);
    api.observation.mockResolvedValue(view.observation()!);
    await view.hashObservation();
    expect(api.hash.mock.calls[1].slice(0,3)).toEqual(['other-scan',['other'],false]);
    expect(api.hash.mock.calls[1][3]).not.toBe(api.hash.mock.calls[0][3]);
  });

  it('does not submit a terminal-job retry after the editor changes during the status request',async()=>{
    const {view,api}=setup(); await view.hashObservation(); const pending=deferred<Job>();
    api.job.mockReturnValueOnce(pending.promise); const retry=view.hashObservation();
    view.fresh(); view.notice.set('New editor');
    pending.resolve({id:'job-1',state:'CANCELLED'} as Job); await retry;
    expect(api.hash).toHaveBeenCalledTimes(1); expect(view.notice()).toBe('New editor');
    expect(view.observation()).toBeNull(); expect(view.busy()).toBe(false);
  });

  it('ignores late submission feedback after the component is destroyed',async()=>{
    const {view,api}=setup(); const pending=deferred<{scanId:string;jobId:string}>();
    api.hash.mockReturnValueOnce(pending.promise); const request=view.hashObservation();
    view.ngOnDestroy(); pending.resolve({scanId:'scan',jobId:'job-1'}); await request;
    expect(api.observation).not.toHaveBeenCalled(); expect(view.notice()).toBe('');
  });

  it.each(['ACCEPTED','PENDING'] as const)('does not submit when the displayed evidence is %s',async status=>{
    const {view,api,observation}=setup();
    view.observation.set({...observation,hash:{status,pending:status==='PENDING'}} as Observation);
    await view.hashObservation(); expect(api.hash).not.toHaveBeenCalled();
  });
});
