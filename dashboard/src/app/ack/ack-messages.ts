import { Acknowledgement } from '../data/queries';
import { dateHm, hm, parseUtc } from '../format';
import { AckAction, AckResult } from './ack-api';

/** An item's or panel's status line. A failure is shown with its bold lead; anything else is visually hidden. */
export interface AckLine {
  lead: string;
  text: string;
  failure: boolean;
  /** The line says the note is over its limit, which empties silently once it is back within. */
  over?: boolean;
}

export const NO_LINE: AckLine = { lead: '', text: '', failure: false };

export const OVER_LINE: AckLine = {
  lead: '',
  text: 'The note is over the 500 character limit.',
  failure: false,
  over: true,
};

export function progressLine(action: AckAction): AckLine {
  return {
    lead: '',
    text: action === 'acknowledge' ? 'Acknowledging.' : 'Unacknowledging.',
    failure: false,
  };
}

/** "Acknowledged at {HH:MM} UTC." from a row, with the date on the alert page and "with a note" when it has one. */
export function savedLine(ack: Acknowledgement | null, dated: boolean): AckLine {
  if (!ack) {
    return NO_LINE;
  }
  const ms = parseUtc(ack.acted_at);
  const at = Number.isNaN(ms) ? '' : ` at ${dated ? dateHm(ms) : hm(ms)} UTC`;
  const text =
    ack.action === 'acknowledge'
      ? `Acknowledged${at}${ack.note ? ' with a note' : ''}.`
      : `Unacknowledged${at}.`;
  return { lead: '', text, failure: false };
}

const notSaved = (text: string): AckLine => ({ lead: 'Not saved.', text, failure: true });
const notConfirmed = (text: string): AckLine => ({ lead: 'Not confirmed.', text, failure: true });

export const REFUSED_LINE = notSaved('The server refused the request.');

/**
 * The status line for a result, or null for an ended session, which the header reports instead. `page` is the alert
 * page, whose times carry their date and whose lines say to reload where the list says it refreshes.
 */
export function resultLine(result: AckResult, action: AckAction, page: boolean): AckLine | null {
  const state = action === 'acknowledge' ? 'acknowledged' : 'unacknowledged';
  switch (result.kind) {
    case 'saved':
    case 'unknown_done':
      return savedLine(result.ack, page);
    case 'ended':
      return null;
    case 'refused':
      return REFUSED_LINE;
    case 'not_found':
      return notSaved('No alert with this event id is stored.');
    case 'already':
      return notSaved(
        `This alert was already ${state}, for example from an earlier press or another tab. The state above is current.`,
      );
    case 'refused_state':
      return notSaved('The server says this alert cannot be acknowledged.');
    case 'conflict_unknown':
      return notSaved(
        `The server refused the change, and the alert's current state could not be loaded. ${page ? 'Reload the page to see it.' : 'Open the alert to see it.'}`,
      );
    case 'unknown_not':
      return notConfirmed(
        `The server could not be reached or had a problem, and the current state above does not show this alert as ${state}. It was most likely not saved, but a delayed save can still go through later. Trying again never saves it twice.`,
      );
    case 'unknown':
      return notConfirmed(
        `The server could not be reached or had a problem, and the alert's current state could not be loaded, so it is not known whether it was ${state}. Trying again never saves it twice. ${page ? 'Reload the page to see its state.' : 'Open the alert to see its state.'}`,
      );
  }
}

/** Results after which Try again repeats the same action with the same note. */
export function offersTryAgain(result: AckResult): boolean {
  return result.kind === 'refused' || result.kind === 'unknown_not' || result.kind === 'unknown';
}
