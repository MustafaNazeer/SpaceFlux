package dedupe

import "testing"

func TestTracker(t *testing.T) {
	type step struct {
		id      int64
		epoch   string
		record  bool
		wantDup bool
	}
	tests := []struct {
		name  string
		steps []step
	}{
		{"unseen object is new", []step{{25544, "2026-09-27T04:10:50.460096", false, false}}},
		{"checking alone does not record", []step{
			{25544, "2026-09-27T04:10:50.460096", false, false},
			{25544, "2026-09-27T04:10:50.460096", false, false},
		}},
		{"same id and epoch after record is duplicate", []step{
			{25544, "2026-09-27T04:10:50.460096", true, false},
			{25544, "2026-09-27T04:10:50.460096", false, true},
		}},
		{"new epoch for same id is new", []step{
			{25544, "2026-09-27T04:10:50.460096", true, false},
			{25544, "2026-09-27T09:00:00.000000", false, false},
		}},
		{"same epoch different id is new", []step{
			{25544, "2026-09-27T04:10:50.460096", true, false},
			{100057, "2026-09-27T04:10:50.460096", false, false},
		}},
		{"only latest epoch per id is remembered", []step{
			{25544, "A", true, false},
			{25544, "B", true, false},
			{25544, "A", false, false},
		}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			tr := NewTracker()
			for i, s := range tc.steps {
				if got := tr.IsDuplicate(s.id, s.epoch); got != s.wantDup {
					t.Fatalf("step %d: IsDuplicate(%d, %q) = %v, want %v", i, s.id, s.epoch, got, s.wantDup)
				}
				if s.record {
					tr.Record(s.id, s.epoch)
				}
			}
		})
	}
}
