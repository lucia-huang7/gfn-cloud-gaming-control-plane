package com.gfn.controlplane.node;

import com.gfn.controlplane.session.GpuProfile;
import com.gfn.controlplane.session.Region;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RegisterNodeRequest(
        @NotBlank String nodeId,
        @NotNull Region region,
        @NotNull GpuProfile gpuProfile,
        @Min(1) @Max(64) int totalSlots,
        @Min(1) @Max(250) int avgLatencyMs
) {
}

