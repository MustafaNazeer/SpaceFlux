import { Acknowledgement } from '../data/queries';
import { parseUtc } from '../format';

/** The API's note limit in Unicode code points, the count MySQL applies to the note column (docs/api/rest.md). */
export const NOTE_MAX = 500;

/**
 * The length of a note as the API counts it: Unicode code points, not UTF-16 units, so a character outside the Basic
 * Multilingual Plane counts once. A lone surrogate counts once, as Java's codePointCount counts it.
 */
export function codePoints(text: string): number {
  let n = 0;
  for (const _ of text) {
    n++;
  }
  return n;
}

/** "{n} of 500 characters", with how far over the limit a note is. */
export function noteCounter(count: number): string {
  const over = count - NOTE_MAX;
  return over > 0
    ? `${count} of ${NOTE_MAX} characters, ${over} over the limit`
    : `${count} of ${NOTE_MAX} characters`;
}

/** An acknowledgement as an anonymous viewer gets it: the action and its time, no principal and no note. */
export function withoutOperator(ack: Acknowledgement | null): Acknowledgement | null {
  return ack && (ack.principal !== null || ack.note !== null)
    ? { ...ack, principal: null, note: null }
    : ack;
}

/** True when the newest row says acknowledged; no row, or a newest unacknowledge row, is not acknowledged. */
export function isAcknowledged(ack: Acknowledgement | null | undefined): boolean {
  return ack?.action === 'acknowledge';
}

/** A row as the REST API writes it, with absent fields read as null. */
export function rowOf(body: {
  action: string;
  acted_at: string;
  principal?: string | null;
  note?: string | null;
}): Acknowledgement {
  return {
    action: body.action,
    acted_at: body.acted_at,
    principal: body.principal ?? null,
    note: body.note ?? null,
  };
}

/**
 * True when `incoming`, read from the API, may replace `local`, a row this page wrote or read again: it is the same
 * row or a newer one. No row is never newer than a row, since rows are never deleted.
 */
export function supersedes(
  incoming: Acknowledgement | null,
  local: Acknowledgement | null,
): boolean {
  if (local === null) {
    return true;
  }
  if (incoming === null) {
    return false;
  }
  return parseUtc(incoming.acted_at) >= parseUtc(local.acted_at);
}

/** Alerts as an anonymous viewer gets them, with no principal or note on any acknowledgement. */
export function alertsWithoutOperator<T extends { acknowledgement: Acknowledgement | null }>(
  alerts: T[],
): T[] {
  return alerts.map((a) => {
    const ack = withoutOperator(a.acknowledgement);
    return ack === a.acknowledgement ? a : { ...a, acknowledgement: ack };
  });
}

/** The height a status line has now, which it keeps while Try again's request is out, so the button stays put. */
export function heightOf(root: HTMLElement, selector: string): number | null {
  const line = root.querySelector<HTMLElement>(selector);
  return line ? line.getBoundingClientRect().height : null;
}
