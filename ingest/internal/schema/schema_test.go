package schema

import (
	"os"
	"strings"
	"testing"
)

const schemasDir = "../../../schemas"

func TestValidExamplesPass(t *testing.T) {
	tests := []struct{ schema, example string }{
		{"raw.gp/v1.schema.json", "raw.gp/examples/valid-iss.json"},
		{"dlq/v1.schema.json", "dlq/examples/truncated-body.json"},
	}
	for _, tc := range tests {
		t.Run(tc.example, func(t *testing.T) {
			v, err := Load(schemasDir + "/" + tc.schema)
			if err != nil {
				t.Fatalf("Load: %v", err)
			}
			b, err := os.ReadFile(schemasDir + "/" + tc.example)
			if err != nil {
				t.Fatal(err)
			}
			if err := v.Validate(b); err != nil {
				t.Fatalf("Validate: %v", err)
			}
		})
	}
}

func TestInvalidDocumentsFail(t *testing.T) {
	v, err := Load(schemasDir + "/raw.gp/v1.schema.json")
	if err != nil {
		t.Fatal(err)
	}
	valid, err := os.ReadFile(schemasDir + "/raw.gp/examples/valid-iss.json")
	if err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name, from, to, wantInErr string
	}{
		{"string mean motion", `"MEAN_MOTION":15.48664528`, `"MEAN_MOTION":"15.48664528"`, "MEAN_MOTION"},
		{"missing catalog id", `"NORAD_CAT_ID":25544,`, ``, "NORAD_CAT_ID"},
		{"fetched_at without Z", `"fetched_at": "2026-09-27T08:57:39Z"`, `"fetched_at": "2026-09-27T08:57:39"`, "fetched_at"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			doc := strings.Replace(string(valid), tc.from, tc.to, 1)
			if doc == string(valid) {
				t.Fatalf("mutation %q not applied; example layout changed", tc.from)
			}
			err := v.Validate([]byte(doc))
			if err == nil || !strings.Contains(err.Error(), tc.wantInErr) {
				t.Fatalf("Validate err = %v, want mention of %s", err, tc.wantInErr)
			}
		})
	}
}

func TestValidateRejectsMalformedJSON(t *testing.T) {
	v, err := Load(schemasDir + "/raw.gp/v1.schema.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := v.Validate([]byte(`{"schema_version":`)); err == nil {
		t.Fatal("expected error for malformed JSON")
	}
}

func TestLoadMissingFile(t *testing.T) {
	if _, err := Load(schemasDir + "/nope/v1.schema.json"); err == nil {
		t.Fatal("expected error for missing schema file")
	}
}

func TestNameAndDesignatorAreOptional(t *testing.T) {
	v, err := Load(schemasDir + "/raw.gp/v1.schema.json")
	if err != nil {
		t.Fatal(err)
	}
	valid, err := os.ReadFile(schemasDir + "/raw.gp/examples/valid-iss.json")
	if err != nil {
		t.Fatal(err)
	}
	doc := strings.Replace(string(valid), `"OBJECT_NAME":"ISS (ZARYA)","OBJECT_ID":"1998-067A",`, "", 1)
	if doc == string(valid) {
		t.Fatal("mutation not applied")
	}
	if err := v.Validate([]byte(doc)); err != nil {
		t.Fatalf("analyst object without name or designator rejected: %v", err)
	}
}
