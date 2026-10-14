import { Routes } from '@angular/router';

import { ConsolePage } from './console/console-page';
import { AlertPage } from './pages/alert-page';
import { ObjectPage } from './pages/object-page';

/**
 * The console, one alert, and one object's close approaches. An event id holds '/' and ':', so it travels as one
 * percent encoded path segment, which the Router decodes back to the id (app.routes.spec.ts).
 */
export const routes: Routes = [
  { path: '', component: ConsolePage },
  { path: 'alerts/:eventId', component: AlertPage },
  // An empty event id: the page says the address holds no id the API accepts.
  { path: 'alerts', component: AlertPage },
  { path: 'objects/:noradCatId/alerts', component: ObjectPage },
  { path: '**', redirectTo: '' },
];
