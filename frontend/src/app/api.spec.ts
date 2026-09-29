import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { Api } from './api';
import { describe, it, expect } from 'vitest';

describe('Session integration', () => {
  it('refreshes the CSRF session before and after login', async () => {
    TestBed.configureTestingModule({providers:[provideHttpClient(),provideHttpClientTesting()]});
    const api=TestBed.inject(Api), http=TestBed.inject(HttpTestingController);
    const pending=api.login('operator','not-a-real-credential');
    http.expectOne('/api/v1/session').flush({authenticated:false,username:null});
    await new Promise(resolve => setTimeout(resolve, 0));
    const login=http.expectOne('/api/v1/session/login');
    expect(login.request.body.get('username')).toBe('operator');
    login.flush(null,{status:204,statusText:'No Content'});
    await new Promise(resolve => setTimeout(resolve, 0));
    http.expectOne('/api/v1/session').flush({authenticated:true,username:'operator'});
    await pending;
    expect(api.session()?.authenticated).toBe(true);
    http.verify();
  });
});
