import { inject, Injectable, signal } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type { components } from './api-schema';

export type Signature = components['schemas']['Signature'];
export type SignatureWrite = components['schemas']['SignatureWrite'];
export type SignaturePage = components['schemas']['SignaturePage'];
export type SignaturePreview = components['schemas']['SignaturePreview'];
export type SignatureFindings = components['schemas']['SignatureFindings'];
export type SignatureRunPage = components['schemas']['SignatureRunPage'];
export type SignatureImport = components['schemas']['SignatureImport'];
export type SignatureExport = components['schemas']['SignatureExport'];
export type SignatureLimits = components['schemas']['SignatureLimits'];

export type SearchQuery = components['schemas']['SearchQuery'];
export type SearchFilters = components['schemas']['SearchFilters'];
export type SearchEntry = components['schemas']['SearchEntry'];
export type SearchPage = components['schemas']['SearchPage'];
export type Annotation = components['schemas']['Annotation'];
export type AnnotationUpdate = components['schemas']['AnnotationUpdate'];
export type Tag = components['schemas']['Tag'];
export type TagPage = components['schemas']['TagPage'];
export type ReviewState = components['schemas']['ReviewState'];
export type Directory = components['schemas']['Directory'];
export type HistoryPage = components['schemas']['HistoryPage'];
export type Selection = components['schemas']['Selection'];
export type SelectionDetail = components['schemas']['SelectionDetail'];
export type SelectionRequest = components['schemas']['SelectionRequest'];
export type AnnotationBatch = components['schemas']['AnnotationBatch'];
export type BatchResult = components['schemas']['BatchResult'];

export type Session = components['schemas']['Session'];
export type SourceList = components['schemas']['SourceList'];
export type SystemInfo = components['schemas']['SystemInfo'];
export type Scan = components['schemas']['Scan'];
export type Job = components['schemas']['Job'];
export type Observation = components['schemas']['Observation'];
export type ChildrenPage = components['schemas']['ChildrenPage'];
export type ErrorPage = components['schemas']['ErrorPage'];
export type ScanPage = components['schemas']['ScanPage'];
export type HashAttemptPage = components['schemas']['HashAttemptPage'];
export type GroupPage = components['schemas']['GroupPage'];
export type GroupDetail = components['schemas']['GroupDetail'];
export type ScanRequest = components['schemas']['ScanRequest'];

@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  readonly session = signal<Session | null>(null);
  async refreshSession(): Promise<void> {
    this.session.set(await firstValueFrom(this.http.get<Session>('/api/v1/session')));
  }
  async login(username: string, password: string): Promise<void> {
    // Always obtain the current CSRF cookie before a login retry.
    await this.refreshSession();
    const body = new HttpParams().set('username', username).set('password', password);
    await firstValueFrom(this.http.post('/api/v1/session/login', body, { responseType: 'text' }));
    await this.refreshSession();
  }
  async logout(): Promise<void> {
    await firstValueFrom(this.http.post('/api/v1/session/logout', {}, { responseType: 'text' }));
    await this.refreshSession();
  }
  private async get<T>(path: string): Promise<T> {
    try { return await firstValueFrom(this.http.get<T>(path)); }
    catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 401)
        this.session.set({ authenticated: false, username: null });
      throw error;
    }
  }
  async hash(scanId: string, observationIds: string[], forceRehash: boolean, key: string): Promise<components['schemas']['HashCreated']> {
    return firstValueFrom(this.http.post<components['schemas']['HashCreated']>('/api/v1/hash-jobs', {scanId,observationIds,forceRehash}, {headers:{'Idempotency-Key':key}}));
  }
  hashSelection(scanId: string, selectionId: string, key: string): Promise<components['schemas']['HashCreated']> { return firstValueFrom(this.http.post<components['schemas']['HashCreated']>('/api/v1/hash-jobs',{scanId,selectionId,forceRehash:false},{headers:{'Idempotency-Key':key}})); }
  search(scanId: string, body: SearchQuery): Promise<SearchPage> { return firstValueFrom(this.http.post<SearchPage>(`/api/v1/scans/${encodeURIComponent(scanId)}/files/search`,body)); }
  directory(scanId: string, locationId: string): Promise<Directory> { return this.get(`/api/v1/scans/${encodeURIComponent(scanId)}/directories/${encodeURIComponent(locationId)}`); }
  annotation(locationId: string, observationId: string): Promise<Annotation> { return this.get(`/api/v1/locations/${encodeURIComponent(locationId)}/annotation?observationId=${encodeURIComponent(observationId)}`); }
  saveAnnotation(locationId: string, body: AnnotationUpdate): Promise<Annotation> { return firstValueFrom(this.http.put<Annotation>(`/api/v1/locations/${encodeURIComponent(locationId)}/annotation`,body)); }
  history(locationId: string, cursor?: string | null): Promise<HistoryPage> { return this.get(`/api/v1/locations/${encodeURIComponent(locationId)}/history?limit=20`+cursorQuery(cursor)); }
  tags(query = '', cursor?: string | null): Promise<TagPage> { return this.get('/api/v1/tags?limit=50&query='+encodeURIComponent(query)+cursorQuery(cursor)); }
  createTag(label: string): Promise<Tag> { return firstValueFrom(this.http.post<Tag>('/api/v1/tags',{label})); }
  renameTag(tag: Tag, label: string): Promise<Tag> { return firstValueFrom(this.http.patch<Tag>(`/api/v1/tags/${encodeURIComponent(tag.id)}`,{label,expectedVersion:tag.version})); }
  freeze(scanId: string, body: SelectionRequest): Promise<Selection> { return firstValueFrom(this.http.post<Selection>(`/api/v1/scans/${encodeURIComponent(scanId)}/selections`,body)); }
  selection(id: string, cursor?: string | null): Promise<SelectionDetail> { return this.get(`/api/v1/selections/${encodeURIComponent(id)}?limit=100`+cursorQuery(cursor)); }
  annotationBatch(body: AnnotationBatch, key: string): Promise<BatchResult> { return firstValueFrom(this.http.post<BatchResult>('/api/v1/annotation-batches',body,{headers:{'Idempotency-Key':key}})); }
  signatures(query='', revision?:string|null, cursor?:string|null):Promise<SignaturePage> { return this.get('/api/v1/signatures?limit=50&query='+encodeURIComponent(query)+(revision ? '&catalogRevision='+encodeURIComponent(revision) : '')+cursorQuery(cursor)); }
  signature(id:string, revision?:string):Promise<Signature> { return this.get('/api/v1/signatures/'+encodeURIComponent(id)+(revision ? '?revision='+encodeURIComponent(revision) : '')); }
  saveSignature(id:string|null, body:SignatureWrite):Promise<Signature> { return firstValueFrom(id ? this.http.patch<Signature>('/api/v1/signatures/'+encodeURIComponent(id),body) : this.http.post<Signature>('/api/v1/signatures',body)); }
  signatureLimits():Promise<SignatureLimits> { return this.get('/api/v1/signature-limits'); }
  stageSignatures(file:File, format:'JSON'|'CSV', policy:'REJECT_EXISTING_ID'|'UPDATE_BY_ID'):Promise<SignatureImport> { return firstValueFrom(this.http.post<SignatureImport>('/api/v1/signature-imports?format='+format+'&policy='+policy,file,{headers:{'Content-Type':'application/octet-stream'}})); }
  signatureImport(id:string,cursor?:string|null):Promise<SignatureImport> { return this.get('/api/v1/signature-imports/'+encodeURIComponent(id)+'?limit=100'+cursorQuery(cursor)); }
  applySignatures(staged:SignatureImport,key:string):Promise<components['schemas']['SignatureImportApplied']> { return firstValueFrom(this.http.post<components['schemas']['SignatureImportApplied']>('/api/v1/signature-imports/'+encodeURIComponent(staged.id)+'/apply',{expectedCatalogRevision:staged.catalogRevision},{headers:{'Idempotency-Key':key}})); }
  signaturePreview(scan:string):Promise<SignaturePreview> { return firstValueFrom(this.http.post<SignaturePreview>('/api/v1/scans/'+encodeURIComponent(scan)+'/signature-check-preview',{})); }
  checkSignatures(preview:SignaturePreview,key:string):Promise<components['schemas']['HashCreated']> { return firstValueFrom(this.http.post<components['schemas']['HashCreated']>('/api/v1/signature-check-jobs',{previewId:preview.id,allowBodyReads:true},{headers:{'Idempotency-Key':key}})); }
  observationSignatures(id:string,run?:string|null,cursor?:string|null):Promise<SignatureFindings> { return this.get('/api/v1/observations/'+encodeURIComponent(id)+'/signatures?limit=50'+(run ? '&runId='+encodeURIComponent(run) : '')+cursorQuery(cursor)); }
  signatureRuns(scan:string,cursor?:string|null):Promise<SignatureRunPage> { return this.get('/api/v1/scans/'+encodeURIComponent(scan)+'/signature-runs?limit=50'+cursorQuery(cursor)); }
  exportSignatures(format:'JSON'|'CSV',catalogRevision:string,key:string):Promise<SignatureExport> { return firstValueFrom(this.http.post<SignatureExport>('/api/v1/signature-exports',{format,catalogRevision},{headers:{'Idempotency-Key':key}})); }
  signatureExport(id:string):Promise<SignatureExport> { return this.get('/api/v1/signature-exports/'+encodeURIComponent(id)); }
  controlSignatureExport(id:string,action:'pause'|'resume'|'cancel'):Promise<SignatureExport> { return firstValueFrom(this.http.post<SignatureExport>('/api/v1/signature-exports/'+encodeURIComponent(id)+'/'+action,{})); }
  job(id: string): Promise<Job> { return this.get(`/api/v1/jobs/${encodeURIComponent(id)}`); }
  attempts(id: string, cursor?: string | null): Promise<HashAttemptPage> { return this.get(`/api/v1/observations/${encodeURIComponent(id)}/hash-attempts?limit=20` + cursorQuery(cursor)); }
  groups(scanId: string, revision?: string | null, cursor?: string | null): Promise<GroupPage> {
    return this.get(`/api/v1/scans/${encodeURIComponent(scanId)}/duplicate-groups?limit=50` + (revision ? '&analysisId='+encodeURIComponent(revision) : '') + cursorQuery(cursor));
  }
  group(id: string, cursor?: string | null): Promise<GroupDetail> { return this.get(`/api/v1/duplicate-groups/${encodeURIComponent(id)}?limit=100` + cursorQuery(cursor)); }
  sources(): Promise<SourceList> { return this.get('/api/v1/sources'); }
  info(): Promise<SystemInfo> { return this.get('/api/v1/system/info'); }
  scans(cursor?: string | null): Promise<ScanPage> { return this.get('/api/v1/scans?limit=20' + cursorQuery(cursor)); }
  scan(id: string): Promise<Scan> { return this.get(`/api/v1/scans/${encodeURIComponent(id)}`); }
  async createScan(body: ScanRequest, key: string): Promise<components['schemas']['ScanCreated']> {
    return firstValueFrom(this.http.post<components['schemas']['ScanCreated']>('/api/v1/scans', body, {headers: {'Idempotency-Key': key}}));
  }
  async control(id: string, action: 'pause' | 'resume' | 'cancel'): Promise<Job> {
    return firstValueFrom(this.http.post<Job>(`/api/v1/jobs/${encodeURIComponent(id)}/${action}`, {}));
  }
  children(scanId: string, locationId: string, cursor?: string | null): Promise<ChildrenPage> {
    return this.get(`/api/v1/scans/${encodeURIComponent(scanId)}/directories/${encodeURIComponent(locationId)}/children?limit=100` + cursorQuery(cursor));
  }
  errors(jobId: string, cursor?: string | null): Promise<ErrorPage> { return this.get(`/api/v1/jobs/${encodeURIComponent(jobId)}/errors?limit=20` + cursorQuery(cursor)); }
  observation(id: string): Promise<Observation> { return this.get(`/api/v1/observations/${encodeURIComponent(id)}`); }
}

function cursorQuery(cursor?: string | null): string { return cursor ? '&cursor=' + encodeURIComponent(cursor) : ''; }

export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.error && typeof error.error.detail === 'string' && error.status !== 401 && error.status !== 403)
      return error.error.detail;
    if (error.status === 429) return 'Too many login attempts. Try again in one minute.';
    if (error.status === 401) return 'The username or password is incorrect, or your session has expired.';
    if (error.status === 403) return 'Your security token expired. Refresh the page and try again.';
  }
  return 'The service is unavailable. Check the server and try again.';
}
