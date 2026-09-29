import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { App } from './app/app';
import { Dashboard } from './app/dashboard';
import { Sources } from './app/sources';
import { Scans } from './app/scans';
import { Explorer } from './app/explorer';
import { ScanDetail } from './app/scan-detail';

bootstrapApplication(App, {
  providers: [provideHttpClient(), provideRouter([
    { path: '', component: Dashboard },
    { path: 'sources', component: Sources },
    { path: 'scans', component: Scans },
    { path: 'scans/:id/files', component: Explorer },
    { path: 'scans/:id', component: ScanDetail },
    { path: '**', redirectTo: '' }
  ])]
}).catch(() => { document.body.textContent = 'Fnord Dedup could not start. Reload the page to try again.'; });
