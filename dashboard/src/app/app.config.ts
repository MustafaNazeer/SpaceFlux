import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
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
import { sessionActivityInterceptor } from './session/session-activity';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // The API's CSRF cookie and header names, which are also Angular's defaults (docs/api/rest.md, section 7).
    // The session's idle clock counts every request answered below 500.
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
      withInterceptors([sessionActivityInterceptor]),
    ),
    provideRouter(routes),
    provideApollo(apolloOptions),
    // The session call sets the XSRF-TOKEN cookie, so it finishes before any GraphQL POST is sent.
    provideAppInitializer(() => inject(SessionService).load()),
  ],
};
