import { Alert } from '../data/queries';

/**
 * The list as drawn while it is held (focus inside it or a form open): the alerts shown when the hold began, in
 * their order and with their newest data, never trimmed or reordered, then any alert the data lists below the last
 * of them, such as an older page. Any other alert the data lists waits in `held` until the hold ends.
 */
export function holdList(
  frozen: readonly Alert[],
  next: readonly Alert[],
): { items: Alert[]; held: Alert[] } {
  const latest = new Map(next.map((a) => [a.event_id, a]));
  const frozenIds = new Set(frozen.map((a) => a.event_id));
  const items = frozen.map((a) => latest.get(a.event_id) ?? a);
  let lastAt = -1;
  next.forEach((a, i) => {
    if (frozenIds.has(a.event_id)) {
      lastAt = i;
    }
  });
  if (lastAt < 0) {
    return { items, held: next.filter((a) => !frozenIds.has(a.event_id)) };
  }
  // Anything new above the last held alert would move the ones below it, so it waits too.
  const held = next.slice(0, lastAt + 1).filter((a) => !frozenIds.has(a.event_id));
  return { items: [...items, ...next.slice(lastAt + 1)], held };
}
