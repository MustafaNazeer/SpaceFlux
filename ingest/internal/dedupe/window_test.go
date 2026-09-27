package dedupe

import "testing"

func TestWindowPublishesOnlyRecordsAbsentFromPreviousResponse(t *testing.T) {
	w := NewWindow[string]()
	for _, k := range []string{"a", "b"} {
		if !w.IsNew(k) {
			t.Fatalf("%s: first response should be all new", k)
		}
	}
	w.Commit([]string{"a", "b"})

	got := map[string]bool{}
	for _, k := range []string{"b", "c"} {
		got[k] = w.IsNew(k)
	}
	if got["b"] || !got["c"] {
		t.Fatalf("second response IsNew = %v, want b old and c new", got)
	}
	w.Commit([]string{"b", "c"})

	if !w.IsNew("a") {
		t.Fatal("a record absent from the previous response counts as new again; memory must stay one response deep")
	}
}

func TestWindowUncommittedResponseChangesNothing(t *testing.T) {
	w := NewWindow[string]()
	w.Commit([]string{"a"})
	w.IsNew("b")
	if w.IsNew("a") || !w.IsNew("b") {
		t.Fatal("IsNew must not change the window; only Commit does")
	}
}
