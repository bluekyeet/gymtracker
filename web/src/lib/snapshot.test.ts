import { describe, expect, it } from 'vitest';
import {
  applyChanges,
  durationMinutes,
  loadSnapshot,
  sessionsNewestFirst,
  splitsWithDays,
  weekdayNames,
  type Change,
  type PullPage
} from './snapshot';

const up = (seq: number, entity: string, entityId: string, data: Record<string, unknown>): Change => ({
  seq,
  entity,
  entityId,
  revision: 1,
  deleted: false,
  data: { id: entityId, ...data }
});
const del = (seq: number, entity: string, entityId: string): Change => ({ seq, entity, entityId, revision: 2, deleted: true, data: null });

describe('snapshot', () => {
  it('a delete removes the record and later pages win over earlier ones', () => {
    const s = applyChanges(new Map(), [up(1, 'split', 'a', { name: 'Old', isActive: false, }), up(2, 'split', 'a', { name: 'New', isActive: true })]);
    expect(splitsWithDays(s)[0].split.name).toBe('New');
    applyChanges(s, [del(3, 'split', 'a')]);
    expect(splitsWithDays(s)).toEqual([]);
  });

  it('reads every page of the feed and stops when there is no more', async () => {
    const pages: Record<string, PullPage> = {
      '0': { changes: [up(1, 'split', 'a', { name: 'PPL', isActive: true })], nextCursor: '1', hasMore: true },
      '1': { changes: [up(2, 'split', 'b', { name: 'Full body', isActive: false })], nextCursor: '2', hasMore: false }
    };
    const seen: string[] = [];
    const s = await loadSnapshot(async (cursor) => {
      seen.push(cursor);
      return pages[cursor];
    });
    expect(seen).toEqual(['0', '1']);
    expect(splitsWithDays(s).map((x) => x.split.name)).toEqual(['PPL', 'Full body']); // active first
  });

  it('lists days under their split, in order, and ignores days of other splits', () => {
    const s = applyChanges(new Map(), [
      up(1, 'split', 's1', { name: 'PPL', isActive: true }),
      up(2, 'split_day', 'd2', { splitId: 's1', name: 'Pull', sortKey: 1, weekdays: [3] }),
      up(3, 'split_day', 'd1', { splitId: 's1', name: 'Push', sortKey: 0, weekdays: [1, 4] }),
      up(4, 'split_day', 'dx', { splitId: 'other', name: 'Stray', sortKey: 0, weekdays: [] })
    ]);
    expect(splitsWithDays(s)[0].days.map((d) => d.name)).toEqual(['Push', 'Pull']);
  });

  it('sorts workouts newest first and measures their length', () => {
    const s = applyChanges(new Map(), [
      up(1, 'workout_session', 'w1', { dayName: 'Push', startedAt: '2026-01-01T10:00:00Z', endedAt: '2026-01-01T11:05:00Z' }),
      up(2, 'workout_session', 'w2', { dayName: 'Legs', startedAt: '2026-02-01T10:00:00Z', endedAt: null })
    ]);
    const sessions = sessionsNewestFirst(s);
    expect(sessions.map((x) => x.dayName)).toEqual(['Legs', 'Push']);
    expect(durationMinutes(sessions[0])).toBeNull();
    expect(durationMinutes(sessions[1])).toBe(65);
  });

  it('names weekdays in calendar order', () => {
    expect(weekdayNames([4, 1])).toBe('Mon, Thu');
    expect(weekdayNames([])).toBe('');
    expect(weekdayNames([9, 7])).toBe('Sun');
  });
});
