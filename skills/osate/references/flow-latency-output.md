# Flow Latency Analysis — Output Description

This describes what the OSATE flow latency analysis
(`org.osate.analysis.flows.FlowLatencyAnalysisSwitch`) produces, and how the
language server's `aadl.analyze.latency` command surfaces it. Use it to interpret
the structured result, generated reports, and command-line output.

## Contents

- [Inputs](#inputs)
- [Three forms of output](#three-forms-of-output)
  - [The AnalysisResult structure](#1-the-analysisresult-structure)
  - [Files written to disk](#2-files-written-to-disk)
  - [LSP command return value](#3-lsp-command-return-value-aadlanalyzelatency)

## Inputs

The analysis runs on an **instance model** (`SystemInstance`, the root of an
`.aaxl2` file) and is parameterized by five booleans:

| Parameter | `true` meaning | `false` meaning | Label |
| --- | --- | --- | --- |
| `asynchronousSystem` | asynchronous system | synchronous system | `AS` / `SS` |
| `majorFrameDelay` | partition output at major frame | output at partition end | `MF` / `PE` |
| `worstCaseDeadline` | worst case = deadline | worst case = max compute execution time | `DL` / `ET` |
| `bestCaseEmptyQueue` | best case = empty queue | best case = full queue | `EQ` / `FQ` |
| `disableQueuingLatency` | queuing latency forced to 0 | queuing latency included | `DQL` / `EQL` |

The analysis iterates over every **end-to-end flow instance** (ETEF) in the
system, and over every **System Operation Mode** (SOM) if no specific mode is
given.

## Three forms of output

A single run produces output in three places:

1. An in-memory `AnalysisResult` object (the structured model).
2. Two files written to disk: a `.result` (serialized `AnalysisResult`) and a
   `.csv` report.
3. The text string returned by the LSP `aadl.analyze.latency` command.

### 1. The `AnalysisResult` structure

`FlowLatencyUtil.recordAsAnalysisResult(...)` builds the tree. The shape is:

```
AnalysisResult
  analysis      = "latency"
  modelElement  = the analyzed root (SystemInstance / ComponentInstance / ETEF)
  message       = parameter labels, e.g. "AS-MF-DL-EQ-EQL"
  parameters[0..4] = the five booleans above (in the table order)
  results       = one Result per end-to-end flow (per SOM)
```

Each **per-flow `Result`** (from `LatencyReportEntry.genResult()`):

```
Result
  modelElement = EndToEndFlowInstance
  message      = "Latency results for <flow name>"
  resultType   = FAILURE if any error diagnostic, else SUCCESS
  values[0] = (String)  SOM name + printable SOM members ("" for the empty SOM)
  values[1] = (Real)    minimum actual latency        (ms)
  values[2] = (Real)    maximum actual latency        (ms)
  values[3] = (Real)    minimum specified latency      (ms)
  values[4] = (Real)    maximum specified latency      (ms)
  values[5] = (Real)    expected minimum latency       (ms)  [from flow's Latency property]
  values[6] = (Real)    expected maximum latency       (ms)
  diagnostics = end-to-end summary issues (see below)
  subResults  = one Result per LatencyContributor (in flow order)
```

Each **contributor `Result`** (from `LatencyContributor.genResult()`):

```
Result
  modelElement = the component/connection NamedElement contributing latency
  values[0] = (Real)   min value        (ms)
  values[1] = (Real)   max value        (ms)
  values[2] = (Real)   expected min     (ms)
  values[3] = (Real)   expected max     (ms)
  values[4] = (String) best-case method  (see LatencyContributorMethod)
  values[5] = (String) worst-case method
  values[6] = (String) flow spec name ("" if none)
  diagnostics = per-contributor info/warning/error messages
  subResults  = sub-contributors (e.g. the bus/protocol latency under a connection)
```

`LatencyContributorMethod` (the method strings) is one of: `UNKNOWN`,
`DEADLINE`, `RESPONSE_TIME`, `PROCESSING_TIME`, `DELAYED`, `SAMPLED`,
`FIRST_PERIODIC`, `SPECIFIED`, `QUEUED`, `TRANSMISSION_TIME`, `PARTITION_FRAME`,
`PARTITION_SCHEDULE`, `PARTITION_OUTPUT`, `SAMPLED_PROTOCOL`.

#### Summary diagnostics

Per-flow diagnostics compare actual/specified totals against the flow's expected
latency and are emitted as `error` / `warning` / `info`. Representative messages:

- error — "Maximum actual latency total Xms exceeds expected maximum end to end latency Yms"
- error — "Minimum specified flow latency total Xms exceeds expected maximum latency Yms"
- warning — "Jitter of actual latency total X..Yms exceeds expected end to end latency jitter A..Bms"
- warning — "Expected end to end latency is not specified"
- info — "Maximum actual latency total Xms is less or equal to expected maximum end to end latency Yms"

Per-contributor diagnostics describe how a value was chosen, e.g. "Using deadline
as execution time was not set", "Assume best case empty queue", "Round up
sampling delay to period Xms", "Best case 0 ms worst case Xms (period) sampling
delay".

### 2. Files written to disk

`invokeAndSaveResult(...)` writes both files under a `reports/latency/`
directory next to the instance model:

```
<instance-dir>/reports/latency/<rootname>__latency_<AS|SS>-<MF|PE>-<DL|ET>-<EQ|FQ>-<DQL|EQL>.result
<instance-dir>/reports/latency/<rootname>__latency_<...same labels...>.csv
```

The parameter labels are encoded into the file name, so different settings
produce different files.

- **`.result`** — the serialized `AnalysisResult` EMF model (the structure in §1).
- **`.csv`** — a human-readable report. Header is the parameters as descriptions,
  e.g. `Latency analysis with preference settings: asynchronous system/major
  partition frame/worst case as deadline/best case as empty queue/queuing latency
  enabled`. Then, per flow:

  ```
  "Latency results for end-to-end flow '<path>' of system '<system>' <mode>"

  Result,Min Specified,Min Actual,Min Method,Max Specified,Max Actual,Max Method,Comments
  <contributor rows; sub-contributors shown in parentheses, with per-row comment cells>
  Latency Total,<minSpec>ms,<minActual>ms,,<maxSpec>ms,<maxActual>ms
  Specified End To End Latency,,<expectedMin>ms,,,<expectedMax>ms
  End to end Latency Summary
  <diagnostic type>,<message>      (one row per summary diagnostic)
  ```

### 3. LSP command return value (`aadl.analyze.latency`)

`AnalyzeLatencyCommand` returns a single newline-separated **string**:

```
Ran latency analysis of <instance-uri>
<absolute path to the .result file>
<absolute path to the .csv file>
<gcc-style diagnostic line>
<gcc-style diagnostic line>
...
```

Diagnostic lines are collected recursively from every `Result` /
sub-`Result` and sorted. Each line is:

```
<instance-file-path>:<element-instance-path>: <severity>: <message>
```

where `<element-instance-path>` is the `getComponentInstancePath()` of the model
element the diagnostic attaches to (e.g. a flow or component instance path), and
`<severity>` is the lowercased diagnostic type (`error`, `warning`, `info`,
`hint`). Note this uses the instance-element path rather than a `line:col` source
span, because results attach to model elements, not text positions.

The `osate-cli` `analyze-latency` subcommand and the VSCode
`aadl2.analyze.latency` command both forward to this command and display/print
the returned string.
