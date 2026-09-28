import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient } from '@angular/common/http';
import { provideRouter } from '@angular/router';
import { App } from './app/app';
import { Dashboard } from './app/dashboard';
import { Sources } from './app/sources';

bootstrapApplication(App, {
  providers: [provideHttpClient(), provideRouter([
    { path: '', component: Dashboard },
    { path: 'sources', component: Sources },
    { path: '**', redirectTo: '' }
  ])]
}).catch(() => { document.body.textContent = 'Fnord Dedup could not start. Reload the page to try again.'; });
