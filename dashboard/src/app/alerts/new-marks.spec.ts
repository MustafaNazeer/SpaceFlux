import { Alert } from '../data/queries';
import { ALERTS_ANONYMOUS } from '../../testing/fixtures';
import { NEW_FOR_MS, NewMarks } from './new-marks';

const RECORDED = ALERTS_ANONYMOUS.alerts.items as unknown as Alert[];
const alert = (id: string): Alert => ({ ...RECORDED[0], event_id: id });
const list = (...ids: string[]) => ids.map(alert);
const marked = (m: NewMarks) => [...m.ids()].sort();

describe('NewMarks (F23)', () => {
  let marks: NewMarks;

  beforeEach(() => {
    vi.useFakeTimers();
    marks = new NewMarks();
  });

  afterEach(() => {
    marks.destroy();
    vi.useRealTimers();
  });

  it('never marks the first list it sees', () => {
    marks.observe(list('a', 'b'), '');
    expect(marked(marks)).toEqual([]);
  });

  it('marks alerts that join above the previous top, and clears each 10 minutes after it joined', () => {
    marks.observe(list('a', 'b'), '');
    marks.observe(list('n1', 'a', 'b'), '');
    expect(marked(marks)).toEqual(['n1']);
    vi.advanceTimersByTime(4 * 60_000);
    marks.observe(list('n2', 'n1', 'a', 'b'), '');
    expect(marked(marks)).toEqual(['n1', 'n2']);
    vi.advanceTimersByTime(NEW_FOR_MS - 4 * 60_000 - 1);
    expect(marked(marks)).toEqual(['n1', 'n2']);
    vi.advanceTimersByTime(1);
    expect(marked(marks)).toEqual(['n2']);
    vi.advanceTimersByTime(4 * 60_000);
    expect(marked(marks)).toEqual([]);
  });

  it('marks every shown alert the previous list did not hold once all of it has left the list (F41)', () => {
    marks.observe(list('a', 'b'), '');
    marks.observe(list('n1', 'n2', 'n3'), '');
    expect(marked(marks)).toEqual(['n1', 'n2', 'n3']);
  });

  it('marks what joined above the newest alert still listed when the previous top has left', () => {
    marks.observe(list('t', 'a', 'b'), '');
    marks.observe(list('n1', 'a', 'b', 'c'), '');
    expect(marked(marks)).toEqual(['n1']);
  });

  it('never marks an older page added below', () => {
    marks.observe(list('a', 'b'), '');
    marks.observe(list('a', 'b', 'c', 'd'), '');
    expect(marked(marks)).toEqual([]);
  });

  it('starts over unmarked for a list under another filter, and when the list goes away', () => {
    marks.observe(list('a'), '');
    marks.observe(list('n1', 'a'), '');
    expect(marked(marks)).toEqual(['n1']);
    marks.observe(list('c1', 'c2'), 'close_approach');
    expect(marked(marks)).toEqual([]);
    marks.observe(undefined, 'close_approach');
    marks.observe(list('c0', 'c1', 'c2'), 'close_approach');
    expect(marked(marks)).toEqual([]);
  });

  it('marks the first alerts of a list that was empty', () => {
    marks.observe([], '');
    marks.observe(list('n1'), '');
    expect(marked(marks)).toEqual(['n1']);
  });

  it('restarts the 10 minutes when an alert joins again, and the first timer does not clear it early', () => {
    marks.observe(list('a'), '');
    marks.observe(list('n1', 'a'), '');
    vi.advanceTimersByTime(5 * 60_000);
    marks.observe(list('a'), '');
    marks.observe(list('n1', 'a'), '');
    vi.advanceTimersByTime(NEW_FOR_MS - 5 * 60_000 + 1);
    expect(marked(marks)).toEqual(['n1']);
    expect(vi.getTimerCount()).toBe(1);
    vi.advanceTimersByTime(5 * 60_000);
    expect(marked(marks)).toEqual([]);
  });

  it('marks alerts that arrived while the list was not shown for 10 minutes, kept through the first list', () => {
    marks.seed(['away/1', 'away/2']);
    marks.observe(undefined, '');
    marks.observe(list('away/2', 'away/1', 'a'), '');
    expect(marked(marks)).toEqual(['away/1', 'away/2']);
    marks.observe(list('n1', 'away/2', 'away/1', 'a'), '');
    expect(marked(marks)).toEqual(['away/1', 'away/2', 'n1']);
    vi.advanceTimersByTime(NEW_FOR_MS);
    expect(marked(marks)).toEqual([]);
  });

  it('drops seeded marks on a filter change', () => {
    marks.seed(['away/1']);
    marks.observe(list('away/1', 'a'), '');
    marks.observe(undefined, 'close_approach');
    marks.observe(list('c1'), 'close_approach');
    expect(marked(marks)).toEqual([]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('keeps no timer once destroyed', () => {
    marks.observe(list('a'), '');
    marks.observe(list('n1', 'a'), '');
    marks.destroy();
    expect(marked(marks)).toEqual([]);
    expect(vi.getTimerCount()).toBe(0);
  });
});
