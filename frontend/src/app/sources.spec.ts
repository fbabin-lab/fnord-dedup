import { TestBed } from '@angular/core/testing';
import { Sources } from './sources';
import { Api, SourceList } from './api';
import { describe, it, expect } from 'vitest';

describe('Sources', () => {
  it('renders labels as inert text and explains unavailable sources', async () => {
    const source = { id:'1', sourceInstanceId:'2', key:'fixture', label:'<img src=x onerror=alert(1)>',
      containerPath:'/sources/archive', enabled:true, crossMounts:false, status:'WRITABLE_SOURCE',
      detail:'The effective source mount is writable.', excludedMountCount:0 };
    const data: SourceList = { configurationRevision:'abc', sources:[source] };
    await TestBed.configureTestingModule({ imports:[Sources], providers:[{provide:Api,useValue:{sources:async () => data}}] }).compileComponents();
    const fixture = TestBed.createComponent(Sources);
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain(source.label);
    expect(fixture.nativeElement.textContent).toContain('WRITABLE_SOURCE');
    expect(fixture.nativeElement.textContent).toContain('startup');
  });
  it('shows an honest empty state', async () => {
    await TestBed.configureTestingModule({ imports:[Sources], providers:[{provide:Api,useValue:{sources:async () => ({configurationRevision:'x',sources:[]})}}] }).compileComponents();
    const fixture=TestBed.createComponent(Sources);
    fixture.detectChanges(); await fixture.whenStable(); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No sources configured');
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });
});
