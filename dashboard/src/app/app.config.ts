import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideApollo } from 'apollo-angular';

import { routes } from './app.routes';

import { apolloOptions } from './data/graphql';
import { SessionService } from './session/session.service';
import { provideApiHttpClient } from './session/xsrf-refresh';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideApiHttpClient(),
    provideRouter(routes),
    provideApollo(apolloOptions),
    // The session call sets the XSRF-TOKEN cookie, so it finishes before any GraphQL POST is sent.
    provideAppInitializer(() => inject(SessionService).load()),
  ],
};
