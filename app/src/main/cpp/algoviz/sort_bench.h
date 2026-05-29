// Per-cluster driver for the sort benchmark. Thin Android-facing wrapper: it
// pins the thread to the fastest core of each CPU cluster and runs the
// platform-agnostic algoviz::run_all() there, so the JSON shows how identical
// algorithmic work performs on a LITTLE A55 vs a big/prime core. The actual
// measurement logic lives in sort_registry.h (host-unit-tested).
#pragma once

#include "sort_registry.h"

#include "../bench/affinity.h"
#include "../bench/json.h"

#include <vector>

namespace algoviz {

bench::Json run_sort_benchmark_per_cluster(const SortBenchConfig& cfg,
                                           const std::vector<bench::CpuCluster>& clusters);

} // namespace algoviz
