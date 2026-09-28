# Bus Load Analysis — Output Description

This describes what the OSATE bus load analysis
(`org.osate.analysis.resource.budgets.busload.NewBusLoadAnalysis`) produces. It
is the bus-load counterpart to [flow-latency-output.md](flow-latency-output.md).
The `aadl.analyze.busload` language-server command and the `osate-cli`
`analyze-bus-load` subcommand expose this analysis.

## Contents

- [Inputs](#inputs)
- [Three forms of output](#three-forms-of-output)
  - [The AnalysisResult structure](#1-the-analysisresult-structure)
  - [File written to disk](#2-file-written-to-disk)
  - [LSP command return value](#3-lsp-command-return-value-aadlanalyzebusload)

## Inputs

The analysis runs on an **instance model** (`SystemInstance`, the root of an
`.aaxl2` file). Unlike latency, it takes **no parameters / preference settings** —
`invoke(monitor, systemInstance)` (optionally `invoke(..., createCSV)`) simply
runs over **every System Operation Mode** (SOM) in the model.

For each SOM, `BusLoadModelBuilder` builds a tree of the buses and virtual buses
in the system and the connections / broadcasts bound to them, then a two-pass
traversal computes capacity, budget, required budget, and actual usage.

### Properties consulted

All bandwidth values are in **KB/s** (`DataRateUnits.KBYTESPS`):

- `SEI::BandwidthCapacity` — physical capacity of a (virtual) bus.
- `SEI::BandwidthBudget` — declared budget of a (virtual) bus or connection.
- `SEI::DataRate` / `SEI::MessageRate`, or `Communication_Properties::Output_Rate`,
  or the source's `Timing_Properties::Period` — used to derive a connection's
  message rate.
- `Memory_Properties::Data_Size` / `Source_Data_Size` — connection payload size,
  plus per-(virtual-)bus data overhead (`Data_Size`).
- `SEI::BroadcastProtocol` — when true, connections sharing a source feature are
  grouped into a single **Broadcast** (one message sent once to all receivers).

"Actual" usage of a connection = payload size (plus accumulated bus/protocol data
overhead) × message rate. A bus's actual usage and required budget are the sums
over everything bound to it.

## Three forms of output

A single command run produces output in three forms:

1. An in-memory `AnalysisResult` object (the structured model).
2. One file written to disk: a `.csv` report (only when `createCSV` is true).
3. The text string returned by the LSP `aadl.analyze.busload` command.

There is **no `.result` serialization** in `NewBusLoadAnalysis` — only the CSV is
written.

### 1. The `AnalysisResult` structure

Built directly in `analyzeBody`. The shape is:

```
AnalysisResult
  analysis      = "Bus Load"
  modelElement  = the analyzed SystemInstance
  resultType    = SUCCESS
  message       = "Bus load analysis of " + <root full name>
  parameters    = (empty — analysis has no parameters)
  diagnostics   = (empty)
  results       = one Result per System Operation Mode
```

Each **per-SOM `Result`**:

```
Result
  modelElement = SystemOperationMode
  resultType   = SUCCESS
  message      = "" for the null/empty SOM, else the printable SOM members "(xxx, ..., yyy)"
  values       = (empty)
  diagnostics  = (empty — no diagnostics at the SOM level)
  subResults   = one Result per root Bus ComponentInstance (category = bus)
```

Each **per-bus `Result`** (a `Bus`; **virtual buses use the identical layout**):

```
Result
  modelElement = ComponentInstance (the bus or virtual bus)
  resultType   = SUCCESS
  message      = the component's name
  values[0] = (Real)    capacity            (KB/s)  [SEI::BandwidthCapacity]
  values[1] = (Real)    budget              (KB/s)  [SEI::BandwidthBudget]
  values[2] = (Real)    required budget     (KB/s)  [sum of bound budgets]
  values[3] = (Real)    actual usage        (KB/s)  [sum of bound actuals]
  values[4] = (Integer) # virtual buses bound to this bus
  values[5] = (Integer) # connections bound to this bus
  values[6] = (Integer) # broadcast sources bound to this bus
  values[7] = (Integer) data overhead of this bus (bytes)
  diagnostics = capacity/budget findings for this bus (see below)
  subResults  = ordered by kind, using the counts above:
                [0 .. values[4]-1]                          -> virtual bus Results (same layout as a bus)
                [values[4] .. values[4]+values[5]-1]        -> connection Results
                [values[4]+values[5] .. end]                -> broadcast Results
```

Each **per-connection `Result`**:

```
Result
  modelElement = ConnectionInstance
  resultType   = SUCCESS
  message      = the connection's name
  values[0] = (Real) budget        (KB/s)  [SEI::BandwidthBudget on the connection]
  values[1] = (Real) actual usage  (KB/s)  [data size × message rate, incl. bus overhead]
  diagnostics = connection findings (see below)
  subResults  = (empty)
```

Each **per-broadcast `Result`** (a group of connections sharing one source
feature, when the bus is `BroadcastProtocol`):

```
Result
  modelElement = ConnectionInstanceEnd (the shared source feature)
  resultType   = SUCCESS
  message      = "Broadcast from " + <source feature instance path>
  values[0] = (Real) budget        (KB/s)  [max budget across the grouped connections]
  values[1] = (Real) actual usage  (KB/s)  [one message for the whole group]
  diagnostics = broadcast findings (see below)
  subResults  = one connection Result per connection in the broadcast (layout above)
```

#### Diagnostics

Diagnostics are attached to the bus / connection / broadcast `Result` they
concern. Representative messages:

- **Connection**
  - error — "Connection X -- Actual bandwidth > budget: A KB/s > B KB/s"
  - warning — "Connection X has no bandwidth budget"
- **Broadcast** (when grouped connections disagree on budget)
  - warning — "Connection X sharing broadcast source \<path> has budget B KB/s; using maximum"
- **Bus / virtual bus** (label is "Bus " or "Virtual bus ")
  - warning — "Bus X has no capacity"
  - error — "Bus X -- Actual bandwidth > capacity: A KB/s > C KB/s"
  - warning — "Bus X has no bandwidth budget"
  - error — "Bus X -- budget > capacity: B KB/s > C KB/s"
  - error — "Bus X -- Required budget > budget: R KB/s > B KB/s"

### 2. File written to disk

When `createCSV` is true, `saveResults` writes one CSV next to the instance
model:

```
<instance-dir>/reports/BusLoad/<rootname>__BusLoad.csv
```

(`REPORTS_DIR = "reports"`, `ANALYSIS_DIR = "BusLoad"`,
`REPORT_NAME_TAIL = "__BusLoad.csv"`.) Unlike latency, the file name encodes no
parameters (there are none), so a re-run overwrites the same file.

The CSV (written by the inner `ResultWriter`, an `AbstractCSVResultWriter`)
contains the analysis message, then per SOM:

- a per-SOM summary table:
  `Physical Bus, Capacity (KB/s), Budget (KB/s), Required Budget (KB/s), Actual (KB/s)`
  — one row per root bus.
- then, recursively per bus, a detail block headed by the bus's data overhead,
  with a
  `Bound Virtual Bus/Connection, Capacity (KB/s), Budget (KB/s), Required Budget (KB/s), Actual (KB/s)`
  table (bound virtual buses show all four numeric columns; connections show only
  Budget and Actual), followed by that bus's diagnostics, then recursion into
  bound virtual buses, connections, and broadcasts.
- broadcast blocks list their included connections under
  `Included Connection, Budget (KB/s), Actual (KB/s)`.
- connection blocks emit a row only when the connection has diagnostics.

### 3. LSP command return value (`aadl.analyze.busload`)

The command returns a single newline-separated string:

```
Ran bus load analysis of <instance-uri>
<absolute path to the .csv file>
<gcc-style diagnostic line>
<gcc-style diagnostic line>
...
```

with each diagnostic line collected recursively from every `Result` /
sub-`Result` and sorted, in the same form latency uses:

```
<instance-file-path>:<element-instance-path>: <severity>: <message>
```

Because bus load writes no `.result` file, the command lists only the CSV path
(latency lists both `.result` and `.csv`).

The `osate-cli` `analyze-bus-load` subcommand forwards to this command and
prints the returned string.
