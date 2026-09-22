"""Root content editorial/import/build/publish pipeline.

This package implements the Python side of the content pipeline described in
docs/CONTENT.md: source adapters (discover/import), a rights/review registry,
deterministic canonical JSON, media validation, a deterministic pack builder,
and a controlled Supabase publisher. Everything here is standard-library only
except an optional ``requests`` dependency used solely for real network
discovery calls (network access always degrades to a structured, honestly
reported failure rather than fabricated data).
"""

__version__ = "0.1.0"
