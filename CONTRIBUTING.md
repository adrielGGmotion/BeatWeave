# Contributing

Use [docs/build.md](docs/build.md) to set up the toolchain and run the relevant
module tests. Keep changes focused and explain the problem, the resulting
behavior and how you checked it.

For analysis or timing changes, add a regression that exposes the original
failure. Retain original event timestamps in diagnostics. A test that reproduces
the implementation's own clock cannot establish musical accuracy; comparisons
against annotated recordings need an independent reference and a stated tolerance.

When reporting a bad transition, include the library and model versions, requested
bar count, selected source times and planning diagnostics. Identify the exact
recording/version and decoder settings where possible. Do not commit copyrighted
recordings; use a redistributable fixture or document how to reproduce the issue.

Keep model weights, generated native libraries, Maven publications, caches and
test output out of source control. Model hashes and provenance belong with any
model update. Changes to a native dependency must retain its source and notices.

Update the usage docs when an API contract changes. Distinguish build checks,
synthetic rendering tests and annotated-music results in a pull request; do not
treat one as evidence for the others.
