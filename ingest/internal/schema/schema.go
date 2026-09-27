// Package schema validates event bytes against the JSON Schema files in schemas/.
package schema

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"

	"github.com/santhosh-tekuri/jsonschema/v6"
)

type Validator struct {
	schema *jsonschema.Schema
}

func Load(path string) (*Validator, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	f, err := os.Open(abs)
	if err != nil {
		return nil, fmt.Errorf("schema: %w", err)
	}
	defer f.Close()
	doc, err := jsonschema.UnmarshalJSON(f)
	if err != nil {
		return nil, fmt.Errorf("schema %s: %w", path, err)
	}
	c := jsonschema.NewCompiler()
	c.AssertFormat()
	if err := c.AddResource(abs, doc); err != nil {
		return nil, fmt.Errorf("schema %s: %w", path, err)
	}
	s, err := c.Compile(abs)
	if err != nil {
		return nil, fmt.Errorf("schema %s: %w", path, err)
	}
	return &Validator{schema: s}, nil
}

func (v *Validator) Validate(doc []byte) error {
	inst, err := jsonschema.UnmarshalJSON(bytes.NewReader(doc))
	if err != nil {
		return fmt.Errorf("decode: %w", err)
	}
	return v.schema.Validate(inst)
}
