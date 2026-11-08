/**
 * The website has no endpoints of its own for splits or workouts; it reads the same change feed the
 * apps sync from. This folds that feed into "the current state of every record" (a delete removes it).
 */

export interface Change {
  seq: number;
  entity: string;
  entityId: string;
  revision: number;
  deleted: boolean;
  data?: Record<string, unknown> | null;
}

export interface PullPage {
  changes: Change[];
  nextCursor: string;
  hasMore: boolean;
}

export type Snapshot = Map<string, Map<string, Record<string, unknown>>>;

export interface Split {
  id: string;
  name: string;
  isActive: boolean;
}

export interface SplitDay {
  id: string;
  splitId: string;
  name: string;
  weekdays: number[];
  sortKey: number;
}

export interface WorkoutSession {
  id: string;
  dayName: string | null;
  startedAt: string;
  endedAt: string | null;
}

export function applyChanges(snapshot: Snapshot, changes: Change[]): Snapshot {
  for (const change of changes) {
    let rows = snapshot.get(change.entity);
    if (!rows) {
      rows = new Map();
      snapshot.set(change.entity, rows);
    }
    if (change.deleted || !change.data) rows.delete(change.entityId);
    else rows.set(change.entityId, change.data);
  }
  return snapshot;
}

/** Reads the whole feed, page by page, and returns the resulting state. */
export async function loadSnapshot(
  pull: (cursor: string) => Promise<PullPage>
): Promise<Snapshot> {
  const snapshot: Snapshot = new Map();
  let cursor = '0';
  for (;;) {
    const page = await pull(cursor);
    applyChanges(snapshot, page.changes);
    if (!page.hasMore) return snapshot;
    cursor = page.nextCursor;
  }
}

function rows<T>(snapshot: Snapshot, entity: string): T[] {
  return [...(snapshot.get(entity)?.values() ?? [])] as T[];
}

export function splitsWithDays(snapshot: Snapshot): { split: Split; days: SplitDay[] }[] {
  const days = rows<SplitDay>(snapshot, 'split_day');
  return rows<Split>(snapshot, 'split')
    .sort((a, b) => Number(b.isActive) - Number(a.isActive) || a.name.localeCompare(b.name))
    .map((split) => ({
      split,
      days: days.filter((d) => d.splitId === split.id).sort((a, b) => a.sortKey - b.sortKey)
    }));
}

export function sessionsNewestFirst(snapshot: Snapshot): WorkoutSession[] {
  return rows<WorkoutSession>(snapshot, 'workout_session').sort(
    (a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt)
  );
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function weekdayNames(days: number[]): string {
  return days
    .filter((d) => d >= 1 && d <= 7)
    .sort((a, b) => a - b)
    .map((d) => WEEKDAYS[d - 1])
    .join(', ');
}

export function durationMinutes(session: WorkoutSession): number | null {
  if (!session.endedAt) return null;
  return Math.max(0, Math.round((Date.parse(session.endedAt) - Date.parse(session.startedAt)) / 60000));
}
