import { Routes } from '@angular/router';

import { ConsolePage } from './console/console-page';

const alertPage = () => import('./pages/alert-page').then((m) => m.AlertPage);
const objectPage = () => import('./pages/object-page').then((m) => m.ObjectPage);

/**
 * The console, one alert, and one object's close approaches. An event id holds '/' and ':', so it travels as one
 * percent encoded path segment, which the Router decodes back to the id (app.routes.spec.ts). The alert and object
 * pages load on first use, so the console's first load carries only what it shows.
 */
export const routes: Routes = [
  { path: '', component: ConsolePage },
  { path: 'alerts/:eventId', loadComponent: alertPage },
  // An empty event id: the page says the address holds no id the API accepts.
  { path: 'alerts', loadComponent: alertPage },
  { path: 'objects/:noradCatId/alerts', loadComponent: objectPage },
  { path: '**', redirectTo: '' },
];
