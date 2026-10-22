import {
  codePoints,
  isAcknowledged,
  noteCounter,
  rowOf,
  supersedes,
  withoutOperator,
} from './note';

describe('note helpers', () => {
  it('counts code points, so a character outside the Basic Multilingual Plane counts once', () => {
    expect(codePoints('')).toBe(0);
    expect(codePoints('abc')).toBe(3);
    // U+1F6F0 SATELLITE is two UTF-16 units.
    const astral = String.fromCodePoint(0x1f6f0);
    expect(astral.length).toBe(2);
    expect(codePoints(astral)).toBe(1);
    expect(codePoints(`a${astral}b`)).toBe(3);
    // U+00E9 and e followed by U+0301 are one and two code points, as the API counts them.
    expect(codePoints('\u00e9')).toBe(1);
    expect(codePoints('e\u0301')).toBe(2);
  });

  it('counts a lone surrogate once, as Java does', () => {
    expect(codePoints('\ud83d')).toBe(1);
    expect(codePoints('a\udc00b')).toBe(3);
  });

  it('says how far over the limit a note is', () => {
    expect(noteCounter(0)).toBe('0 of 500 characters');
    expect(noteCounter(500)).toBe('500 of 500 characters');
    expect(noteCounter(501)).toBe('501 of 500 characters, 1 over the limit');
    expect(noteCounter(512)).toBe('512 of 500 characters, 12 over the limit');
  });

  it('drops the principal and the note for the anonymous view', () => {
    const row = {
      action: 'acknowledge',
      acted_at: '2026-10-22T10:00:00.000000Z',
      principal: 'operator',
      note: 'n',
    };
    expect(withoutOperator(row)).toEqual({ ...row, principal: null, note: null });
    expect(withoutOperator(null)).toBeNull();
    const bare = { ...row, principal: null, note: null };
    expect(withoutOperator(bare)).toBe(bare);
  });

  it('reads only a newest acknowledge row as acknowledged', () => {
    expect(isAcknowledged(null)).toBe(false);
    expect(
      isAcknowledged({ action: 'unacknowledge', acted_at: '', principal: null, note: null }),
    ).toBe(false);
    expect(
      isAcknowledged({ action: 'acknowledge', acted_at: '', principal: null, note: null }),
    ).toBe(true);
  });

  it('reads absent REST fields as null', () => {
    expect(rowOf({ action: 'acknowledge', acted_at: 't' })).toEqual({
      action: 'acknowledge',
      acted_at: 't',
      principal: null,
      note: null,
    });
  });

  it('lets an answer replace a row this page holds only when it is the same row or a newer one', () => {
    const at = (t: string, action = 'acknowledge') => ({
      action,
      acted_at: `2026-10-22T10:${t}.000000Z`,
      principal: null,
      note: null,
    });
    expect(supersedes(at('15:00'), at('15:00'))).toBe(true);
    expect(supersedes(at('16:00', 'unacknowledge'), at('15:00'))).toBe(true);
    expect(supersedes(at('14:59'), at('15:00', 'unacknowledge'))).toBe(false);
    expect(supersedes(null, at('15:00'))).toBe(false);
    expect(supersedes(null, null)).toBe(true);
    expect(supersedes(at('01:00'), null)).toBe(true);
  });
});
