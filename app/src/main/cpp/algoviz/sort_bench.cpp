#include "sort_bench.h"

#include <cstddef>
#include <utility>

namespace algoviz {

bench::Json run_sort_benchmark_per_cluster(const SortBenchConfig& cfg,
                                           const std::vector<bench::CpuCluster>& clusters) {
    std::vector<bench::Json> rows;
    rows.reserve(clusters.size());
    for (std::size_t i = 0; i < clusters.size(); ++i) {
        const auto& c = clusters[i];
        const bool pinned = bench::pin_to_cpu(c.max_id);
        bench::Json sort_result = run_all(cfg);

        bench::Json wrap;
        wrap.kv("cluster_idx", static_cast<std::int64_t>(i))
            .kv("cpu_pinned", c.max_id)
            .kv("max_freq_khz", c.max_freq_khz)
            .kv("pinned", pinned)
            .kv("sort", std::move(sort_result));
        rows.push_back(std::move(wrap));
    }
    bench::Json out;
    out.kv("per_cluster", rows);
    return out;
}

} // namespace algoviz
