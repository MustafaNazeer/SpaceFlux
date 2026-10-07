import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideApollo } from 'apollo-angular';

import { apolloOptions } from './data/graphql';
import { SessionService } from './session/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // The API's CSRF cookie and header names, which are also Angular's defaults (docs/api/rest.md, section 7).
    provideHttpClient(
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    provideApollo(apolloOptions),
    // The session call sets the XSRF-TOKEN cookie, so it finishes before any GraphQL POST is sent.
    provideAppInitializer(() => inject(SessionService).load()),
  ],
};
